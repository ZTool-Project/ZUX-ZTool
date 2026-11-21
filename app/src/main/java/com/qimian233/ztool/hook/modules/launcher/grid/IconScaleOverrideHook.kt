package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor

/**
 * Overrides the launcher's custom icon scale without the 0.6..1.3 host clamp.
 *
 * Hooks Utilities#getCustomedIconScale on pre-18.6 builds; on 18.6+ where that
 * helper was inlined, writes customIconScale before DeviceProfile.<init> and
 * after InvariantDeviceProfile#initGrid so all derived profiles see it.
 */
@SuppressLint("PrivateApi")
class IconScaleOverrideHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_ICON_SCALE_OVERRIDE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_ICON_SCALE_OVERRIDE)

        val legacyMethodName = readString(module, DexIndexConstants.Keys.ICON_SCALE_LEGACY_UTILS_METHOD)
        val legacyMethod = if (legacyMethodName != null || module == null) {
            runCatching {
                val utilitiesClass = classLoader.loadClass("com.android.launcher3.Utilities")
                findMethod(utilitiesClass, legacyMethodName ?: "getCustomedIconScale", Context::class.java)
            }.getOrNull()
        } else {
            null
        }
        if (legacyMethod != null) {
            hookWithId(legacyMethod, "launcher_icon_scale_override") { chain ->
                val scale = readOverrideScale() ?: return@hookWithId chain.proceed()
                logger.debug("IconScale: overriding customIconScale -> $scale")
                scale
            }
            logger.info("IconScaleOverrideHook installed (legacy Utilities#getCustomedIconScale)")
            return
        }

        val idpClassName = readString(module, DexIndexConstants.Keys.ICON_SCALE_IDP_CLASS)
            ?: "com.android.launcher3.InvariantDeviceProfile"
        val initGridName = readString(module, DexIndexConstants.Keys.ICON_SCALE_IDP_INIT_GRID_METHOD)
            ?: "initGrid"
        val customScaleFieldName = readString(module, DexIndexConstants.Keys.ICON_SCALE_IDP_CUSTOM_SCALE_FIELD)
            ?: "customIconScale"
        val dpClassName = readString(module, DexIndexConstants.Keys.ICON_SCALE_DP_CLASS)
            ?: "com.android.launcher3.DeviceProfile"
        val dpInitParamsRaw = readString(module, DexIndexConstants.Keys.ICON_SCALE_DP_INIT_PARAMS)

        try {
            val idpClass = classLoader.loadClass(idpClassName)
            val initGridMethod = findMethod(idpClass, initGridName, String::class.java)
            val customScaleField = findField(idpClass, customScaleFieldName)
            val dpClass = classLoader.loadClass(dpClassName)
            val dpInitCtor = resolveDpInitConstructor(dpClass, classLoader, dpInitParamsRaw, idpClass)

            hookWithId(dpInitCtor, "launcher_icon_scale_override_dp_init") { chain ->
                val scale = readOverrideScale()
                if (scale != null) {
                    val inv = chain.args.firstOrNull()
                    if (inv != null && idpClass.isInstance(inv)) {
                        customScaleField.setFloat(inv, scale)
                    }
                }
                chain.proceed()
            }

            hookWithId(initGridMethod, "launcher_icon_scale_override_init_grid") { chain ->
                val result = chain.proceed()
                val scale = readOverrideScale()
                val idp = chain.thisObject
                if (scale != null && idp != null) {
                    customScaleField.setFloat(idp, scale)
                    logger.debug("IconScale: overriding customIconScale -> $scale")
                }
                result
            }
            logger.info("IconScaleOverrideHook installed ($idpClassName#$initGridName + $dpClassName.<init>)")
        } catch (th: Throwable) {
            logger.error("IconScale: failed to resolve 18.6+ scale targets, aborting", th)
        }
    }

    private fun readOverrideScale(): Float? {
        val prefs = remotePreferences
        val enabled = prefs.getBoolean(
            PreferenceKeys.LAUNCHER_ICON_SCALE_OVERRIDE.name,
            PreferenceKeys.LAUNCHER_ICON_SCALE_OVERRIDE.default
        )
        if (!enabled) return null
        val scale = prefs.getFloat(
            PreferenceKeys.LAUNCHER_ICON_SCALE_VALUE.name,
            PreferenceKeys.LAUNCHER_ICON_SCALE_VALUE.default
        )
        return if (scale > 0f) scale else null
    }

    private fun resolveDpInitConstructor(
        dpClass: Class<*>,
        classLoader: ClassLoader,
        paramsRaw: String?,
        idpClass: Class<*>
    ): Constructor<*> {
        if (paramsRaw != null) {
            val paramTypes = paramsRaw.split(",")
                .map { resolveType(classLoader, it.trim()) }
                .toTypedArray()
            return dpClass.getDeclaredConstructor(*paramTypes)
        }
        return dpClass.declaredConstructors.singleOrNull {
            it.parameterCount > 0 && it.parameterTypes.firstOrNull() == idpClass
        } ?: throw NoSuchMethodException("Unique DeviceProfile(IDP, ...) constructor not found")
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

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
}
