package com.qimian233.ztool.hook.modules.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageItemInfo
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.function.Supplier

/**
 * Serves app icons from the selected third-party icon pack and keeps the host icon cache honest.
 *
 * Resolves unified and legacy icon entry points by capability, using offline DexIndex hints when available.
 */
class LauncherIconPackHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_ICON_PACK.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    @SuppressLint("PrivateApi")
    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val packPackage = remotePreferences
            .getString(PreferenceKeys.LAUNCHER_ICON_PACK_PACKAGE.name, "")
            ?.trim()
            .orEmpty()
        if (packPackage.isEmpty()) {
            logger.info("Icon pack: no pack selected, hooks stay off")
            return
        }
        logger.info(
            "Icon pack: host ${param.packageName} versionCode=${hostVersionCode(param.applicationInfo)}"
        )

        val classLoader = param.defaultClassLoader
        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_ICON_PACK)

        val providerClassName = readString(module, DexIndexConstants.Keys.ICON_PACK_PROVIDER_CLASS)
            ?: ICON_PROVIDER_CLASS
        val providerClass = try {
            classLoader.loadClass(providerClassName)
        } catch (t: Throwable) {
            logger.warn("Icon pack: IconProvider not found: ${t.message}")
            return
        }
        val contextFieldName = readString(module, DexIndexConstants.Keys.ICON_PACK_CONTEXT_FIELD)
            ?: "mContext"
        val contextField = try {
            findField(providerClass, contextFieldName)
        } catch (t: Throwable) {
            logger.warn("Icon pack: $contextFieldName not found: ${t.message}")
            return
        }

        val subclassName = readString(module, DexIndexConstants.Keys.ICON_PACK_SUBCLASS)
        val providerCandidates = (listOfNotNull(subclassName) + PROVIDER_CLASS_CANDIDATES + providerClassName)
            .distinct()

        val resolver = IconPackResolver(packPackage)
        val contextOf: (Any?) -> Context? = { host ->
            if (host == null) null else try {
                contextField.get(host) as? Context
            } catch (_: Throwable) {
                null
            }
        }
        try {
            installIconHooks(classLoader, providerCandidates, module, resolver, contextOf)
            installCacheStateHook(classLoader, providerCandidates, module, resolver, contextOf)
        } catch (t: Throwable) {
            logger.error("Icon pack: hook install failed", t)
        }
    }

    private fun installIconHooks(
        classLoader: ClassLoader,
        providerCandidates: List<String>,
        module: JsonObject?,
        resolver: IconPackResolver,
        contextOf: (Any?) -> Context?
    ) {
        val targets = buildIconTargets(classLoader, module)
        var installed = 0
        for (target in targets) {
            val method = findMostDerivedMethod(
                providerCandidates, classLoader, target.name, target.parameterTypes
            )
            if (method == null) {
                logger.debug("Icon pack: ${target.signature} absent")
                continue
            }
            hookWithId(method, target.id) { chain ->
                val context = contextOf(chain.thisObject)
                val density = chain.args.getOrNull(target.densityIndex) as? Int ?: 0
                val icon = try {
                    val component = target.componentIndex?.let { idx ->
                        target.componentOf(chain.args.getOrNull(idx))
                    }
                    if (component != null) {
                        resolver.resolve(context, component, density)
                    } else {
                        val pkg = target.packageIndex?.let { idx ->
                            target.packageOf(chain.args.getOrNull(idx))
                        }
                        resolver.resolveByPackage(context, pkg, density)
                    }
                } catch (_: Throwable) {
                    null
                }
                icon ?: chain.proceed()
            }
            installed++
            logger.debug("Icon pack: ${target.signature} hooked on ${method.declaringClass.simpleName}")
        }
        logger.info("Icon pack: installed $installed/${targets.size} icon entry hooks")
    }

    private fun buildIconTargets(classLoader: ClassLoader, module: JsonObject?): List<IconTarget> {
        val unifiedName = readString(module, DexIndexConstants.Keys.ICON_PACK_UNIFIED_METHOD)
        val unifiedParams = readParams(classLoader, module, DexIndexConstants.Keys.ICON_PACK_UNIFIED_PARAMS)
        val overridesName = readString(module, DexIndexConstants.Keys.ICON_PACK_OVERRIDES_METHOD)
        val overridesParams = readParams(classLoader, module, DexIndexConstants.Keys.ICON_PACK_OVERRIDES_PARAMS)

        return ICON_TARGETS.map { base ->
            when {
                base.id == "launcher_icon_pack_unified" && (unifiedName != null || unifiedParams != null) -> {
                    val params = unifiedParams ?: base.parameterTypes
                    val densityIdx = params.indexOfFirst { it == INT_TYPE }.takeIf { it >= 0 }
                        ?: base.densityIndex
                    val compIdx = params.indexOfFirst { PackageItemInfo::class.java.isAssignableFrom(it) }
                        .takeIf { it >= 0 } ?: 0
                    IconTarget(
                        id = base.id,
                        name = unifiedName ?: base.name,
                        parameterTypes = params,
                        densityIndex = densityIdx,
                        componentIndex = compIdx,
                        componentOf = base.componentOf
                    )
                }
                base.id == "launcher_icon_pack_overrides" && (overridesName != null || overridesParams != null) -> {
                    val params = overridesParams ?: base.parameterTypes
                    val densityIdx = params.indexOfFirst { it == INT_TYPE }.takeIf { it >= 0 }
                        ?: base.densityIndex
                    val pkgIdx = params.indexOfFirst { it == String::class.java }
                        .takeIf { it >= 0 } ?: 0
                    IconTarget(
                        id = base.id,
                        name = overridesName ?: base.name,
                        parameterTypes = params,
                        densityIndex = densityIdx,
                        packageIndex = pkgIdx,
                        packageOf = base.packageOf
                    )
                }
                else -> base
            }
        }
    }

    private fun installCacheStateHook(
        classLoader: ClassLoader,
        providerCandidates: List<String>,
        module: JsonObject?,
        resolver: IconPackResolver,
        contextOf: (Any?) -> Context?
    ) {
        val stateMethodName = readString(module, DexIndexConstants.Keys.ICON_PACK_STATE_METHOD)
            ?: "getStateForApp"
        val appendMethodName = readString(module, DexIndexConstants.Keys.ICON_PACK_APPEND_METHOD)
            ?: "withAdditionalValues"
        val method = findMostDerivedMethod(
            providerCandidates,
            classLoader,
            stateMethodName,
            arrayOf(ApplicationInfo::class.java)
        )
        if (method == null) {
            logger.warn("Icon pack: $stateMethodName absent, icons will not refresh")
            return
        }
        val stateReturnType = method.returnType
        val appendMethod = if (stateReturnType != String::class.java) {
            stateReturnType.methods.firstOrNull {
                it.name == appendMethodName &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Array<String>::class.java
            }?.apply { isAccessible = true }
        } else {
            null
        }
        hookWithId(method, "launcher_icon_pack_cache_state") { chain ->
            val result = chain.proceed() ?: return@hookWithId null
            val suffix = try {
                resolver.stateSuffix(contextOf(chain.thisObject))
            } catch (_: Throwable) {
                ""
            }
            if (suffix.isEmpty()) {
                return@hookWithId result
            }
            if (result is String) {
                result + suffix
            } else if (appendMethod != null) {
                try {
                    appendMethod.invoke(result, arrayOf(suffix)) ?: result
                } catch (_: Throwable) {
                    result
                }
            } else {
                result
            }
        }
        logger.info("Icon pack: $stateMethodName hooked on ${method.declaringClass.simpleName}")
    }

    private fun findMostDerivedMethod(
        candidates: List<String>,
        classLoader: ClassLoader,
        name: String,
        parameterTypes: Array<Class<*>>
    ): Method? {
        for (className in candidates) {
            val clazz = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                continue
            }
            try {
                return clazz.getDeclaredMethod(name, *parameterTypes)
            } catch (_: Throwable) {
                // not declared here; try the next class in the hierarchy
            }
        }
        return null
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun readParams(
        classLoader: ClassLoader,
        module: JsonObject?,
        key: String
    ): Array<Class<*>>? {
        val raw = readString(module, key) ?: return null
        return runCatching {
            raw.split(",").map { resolveType(classLoader, it.trim()) }.toTypedArray()
        }.getOrNull()
    }

    private fun resolveType(classLoader: ClassLoader, name: String): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> if (name.endsWith("[]")) {
            val element = resolveType(classLoader, name.removeSuffix("[]"))
            java.lang.reflect.Array.newInstance(element, 0).javaClass
        } else {
            classLoader.loadClass(name)
        }
    }

    /** Best-effort host version code; the fields are hidden API, so 0 means unknown. */
    private fun hostVersionCode(info: ApplicationInfo): Long {
        try {
            return ApplicationInfo::class.java.getField("longVersionCode").getLong(info)
        } catch (_: Throwable) {
            // fall through to the legacy int field
        }
        return try {
            ApplicationInfo::class.java.getField("versionCode").getInt(info).toLong()
        } catch (_: Throwable) {
            0L
        }
    }

    private class IconTarget(
        val id: String,
        val name: String,
        val parameterTypes: Array<Class<*>>,
        val densityIndex: Int,
        val componentIndex: Int? = null,
        val packageIndex: Int? = null,
        val componentOf: (Any?) -> PackageItemInfo? = { null },
        val packageOf: (Any?) -> String? = { null }
    ) {
        val signature: String
            get() = name + parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
    }

    private companion object {
        const val ICON_PROVIDER_CLASS = "com.android.launcher3.icons.IconProvider"

        /** Most derived first; a subclass override shadows the base implementation. */
        val PROVIDER_CLASS_CANDIDATES = listOf(
            "com.android.launcher3.icons.LauncherIconProviderImpl",
            "com.android.launcher3.icons.LauncherIconProvider",
            ICON_PROVIDER_CLASS
        )

        val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!
        val BOOLEAN_TYPE: Class<*> = Boolean::class.javaPrimitiveType!!

        val ICON_TARGETS: List<IconTarget> = listOf(
            // 18.6.0.0957+: unified entry points, one component-aware and one package-only.
            IconTarget(
                id = "launcher_icon_pack_unified",
                name = "getIcon",
                parameterTypes = arrayOf(
                    PackageItemInfo::class.java,
                    ApplicationInfo::class.java,
                    INT_TYPE,
                    BOOLEAN_TYPE
                ),
                densityIndex = 2,
                componentIndex = 0,
                componentOf = { it as? PackageItemInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_overrides",
                name = "getIconWithOverrides",
                parameterTypes = arrayOf(
                    String::class.java,
                    INT_TYPE,
                    Supplier::class.java,
                    BOOLEAN_TYPE
                ),
                densityIndex = 1,
                packageIndex = 0,
                packageOf = { it as? String }
            ),
            // 18.6.0.0927 and older: per-overload entries.
            IconTarget(
                id = "launcher_icon_pack_get_icon_component",
                name = "getIcon",
                parameterTypes = arrayOf(ComponentInfo::class.java, INT_TYPE),
                densityIndex = 1,
                componentIndex = 0,
                componentOf = { it as? ComponentInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_component_anim",
                name = "getIcon",
                parameterTypes = arrayOf(ComponentInfo::class.java, INT_TYPE, BOOLEAN_TYPE),
                densityIndex = 1,
                componentIndex = 0,
                componentOf = { it as? ComponentInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_application",
                name = "getIcon",
                parameterTypes = arrayOf(ApplicationInfo::class.java, INT_TYPE),
                densityIndex = 1,
                componentIndex = 0,
                componentOf = { it as? ApplicationInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_application_anim",
                name = "getIcon",
                parameterTypes = arrayOf(ApplicationInfo::class.java, INT_TYPE, BOOLEAN_TYPE),
                densityIndex = 1,
                componentIndex = 0,
                componentOf = { it as? ApplicationInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_launcher_activity",
                name = "getIcon",
                parameterTypes = arrayOf(LauncherActivityInfo::class.java, INT_TYPE, BOOLEAN_TYPE),
                densityIndex = 1,
                componentIndex = 0,
                componentOf = { arg ->
                    val info = arg as? LauncherActivityInfo
                    info?.activityInfo ?: info?.applicationInfo
                }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_zui_icon",
                name = "getZuiIcon",
                parameterTypes = arrayOf(ActivityInfo::class.java, INT_TYPE),
                densityIndex = 1,
                componentIndex = 0,
                componentOf = { it as? ActivityInfo }
            )
        )
    }
}
