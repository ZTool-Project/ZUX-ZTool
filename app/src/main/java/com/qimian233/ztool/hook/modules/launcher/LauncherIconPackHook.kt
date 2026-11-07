package com.qimian233.ztool.hook.modules.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageItemInfo
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.function.Supplier

/**
 * Serves app icons from the selected third-party icon pack and keeps the host
 * icon cache honest.
 *
 * Icon entry points are resolved by capability: the unified `getIcon(PackageItemInfo,
 * ApplicationInfo, int, boolean)` / `getIconWithOverrides(...)` pair of newer hosts and
 * the per-overload `getIcon` family of older hosts are both attempted, whichever exists.
 * Taking effect requires a launcher restart.
 */
class LauncherIconPackHook : AppHookModule() {

    /** Cached appender for the host's freshness-state object, resolved lazily. */
    @Volatile
    private var appendMethod: Method? = null

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
        val providerClass = try {
            param.defaultClassLoader.loadClass(ICON_PROVIDER_CLASS)
        } catch (t: Throwable) {
            logger.warn("Icon pack: IconProvider not found: ${t.message}")
            return
        }
        val contextField = try {
            findField(providerClass, "mContext").apply { isAccessible = true }
        } catch (t: Throwable) {
            logger.warn("Icon pack: mContext not found: ${t.message}")
            return
        }
        val resolver = IconPackResolver(packPackage)
        val contextOf: (Any?) -> Context? = { host ->
            if (host == null) null else try {
                contextField.get(host) as? Context
            } catch (_: Throwable) {
                null
            }
        }
        try {
            installIconHooks(param.defaultClassLoader, resolver, contextOf)
            installCacheStateHook(param.defaultClassLoader, resolver, contextOf)
        } catch (t: Throwable) {
            logger.error("Icon pack: hook install failed", t)
        }
    }

    private fun installIconHooks(
        classLoader: ClassLoader,
        resolver: IconPackResolver,
        contextOf: (Any?) -> Context?
    ) {
        var installed = 0
        for (target in ICON_TARGETS) {
            val method = findMostDerivedMethod(
                classLoader, target.name, target.parameterTypes
            )
            if (method == null) {
                logger.debug("Icon pack: ${target.signature} absent")
                continue
            }
            hookWithId(method, target.id) { chain ->
                val context = contextOf(chain.thisObject)
                val density = chain.args.getOrNull(target.densityIndex) as? Int ?: 0
                val icon = try {
                    val component = target.componentOf(chain.args.getOrNull(0))
                    if (component != null) {
                        resolver.resolve(context, component, density)
                    } else {
                        resolver.resolveByPackage(
                            context, target.packageOf(chain.args.getOrNull(0)), density
                        )
                    }
                } catch (_: Throwable) {
                    null
                }
                icon ?: chain.proceed()
            }
            installed++
            logger.debug("Icon pack: ${target.signature} hooked on ${method.declaringClass.simpleName}")
        }
        logger.info("Icon pack: installed $installed/${ICON_TARGETS.size} icon entry hooks")
    }

    private fun installCacheStateHook(
        classLoader: ClassLoader,
        resolver: IconPackResolver,
        contextOf: (Any?) -> Context?
    ) {
        val method = findMostDerivedMethod(
            classLoader,
            "getStateForApp",
            arrayOf(ApplicationInfo::class.java)
        )
        if (method == null) {
            logger.warn("Icon pack: getStateForApp absent, icons will not refresh")
            return
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
            if (result is String) result + suffix else appendSuffix(result, suffix) ?: result
        }
        logger.info("Icon pack: getStateForApp hooked on ${method.declaringClass.simpleName}")
    }

    /**
     * Finds the most derived declaration among the known provider classes, so a
     * subclass override is hooked instead of the shadowed base implementation.
     */
    private fun findMostDerivedMethod(
        classLoader: ClassLoader,
        name: String,
        parameterTypes: Array<Class<*>>
    ): Method? {
        for (className in PROVIDER_CLASS_CANDIDATES) {
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

    /**
     * Appends [suffix] to the host freshness object. Hosts since 18.6 return an
     * immutable `PersistedItemState` whose `withAdditionalValues(String[])` feeds
     * `toString()`; older builds return a plain string and never reach here.
     */
    private fun appendSuffix(state: Any, suffix: String): Any? {
        val cached = appendMethod
        val method = if (cached != null && cached.declaringClass == state.javaClass) {
            cached
        } else {
            state.javaClass.methods.firstOrNull {
                it.name == "withAdditionalValues" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Array<String>::class.java
            }?.also { appendMethod = it } ?: return null
        }
        return try {
            method.invoke(state, arrayOf(suffix))
        } catch (_: Throwable) {
            null
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

    /**
     * One icon entry point. [componentOf] extracts a component-bearing argument;
     * [packageOf] covers hosts whose entry only receives the package name.
     */
    private class IconTarget(
        val id: String,
        val name: String,
        val parameterTypes: Array<Class<*>>,
        val densityIndex: Int,
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
                packageOf = { it as? String }
            ),
            // 18.6.0.0927 and older: per-overload entries.
            IconTarget(
                id = "launcher_icon_pack_get_icon_component",
                name = "getIcon",
                parameterTypes = arrayOf(ComponentInfo::class.java, INT_TYPE),
                densityIndex = 1,
                componentOf = { it as? ComponentInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_component_anim",
                name = "getIcon",
                parameterTypes = arrayOf(ComponentInfo::class.java, INT_TYPE, BOOLEAN_TYPE),
                densityIndex = 1,
                componentOf = { it as? ComponentInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_application",
                name = "getIcon",
                parameterTypes = arrayOf(ApplicationInfo::class.java, INT_TYPE),
                densityIndex = 1,
                componentOf = { it as? ApplicationInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_application_anim",
                name = "getIcon",
                parameterTypes = arrayOf(ApplicationInfo::class.java, INT_TYPE, BOOLEAN_TYPE),
                densityIndex = 1,
                componentOf = { it as? ApplicationInfo }
            ),
            IconTarget(
                id = "launcher_icon_pack_get_icon_launcher_activity",
                name = "getIcon",
                parameterTypes = arrayOf(LauncherActivityInfo::class.java, INT_TYPE, BOOLEAN_TYPE),
                densityIndex = 1,
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
                componentOf = { it as? ActivityInfo }
            )
        )
    }
}
