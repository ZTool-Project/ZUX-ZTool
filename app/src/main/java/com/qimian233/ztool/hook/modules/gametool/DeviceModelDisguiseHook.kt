package com.qimian233.ztool.hook.modules.gametool

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Makes the game service believe the device is the model picked in module settings.
 *
 * The host funnels every `isXxx()` check through the private `isModel(String)`
 * comparison, so one hook covers all of them; the selected code is read once at
 * package load and captured. Falls back to [FALLBACK_MODEL] when unset.
 */
class DeviceModelDisguiseHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.DISGUISE_TB322FC.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.GAME_SERVICE.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val target = remotePreferences
            .getString(PreferenceKeys.DEVICE_DISGUISE_MODEL.name, PreferenceKeys.DEVICE_DISGUISE_MODEL.default)
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_MODEL
        hookDeviceUtils(param.defaultClassLoader, target)
    }

    private fun hookDeviceUtils(classLoader: ClassLoader, target: String) {
        try {
            val deviceUtilsClass = classLoader.loadClass(DEVICE_UTILS_CLASS)
            val isModelMethod = deviceUtilsClass.getDeclaredMethod("isModel", String::class.java)
            hookWithId(isModelMethod, "is_model") { chain ->
                if (chain.args.getOrNull(0) as? String == target) true else chain.proceed() as Boolean
            }
            logger.info("Disguising device model as $target")
        } catch (e: Exception) {
            logger.error("Failed to hook DeviceUtils.isModel", e)
        }
    }

    private companion object {
        const val DEVICE_UTILS_CLASS = "com.zui.util.DeviceUtils"
        const val FALLBACK_MODEL = "TB322FC"
    }
}
