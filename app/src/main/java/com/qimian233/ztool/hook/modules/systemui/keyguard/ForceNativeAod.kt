package com.qimian233.ztool.hook.modules.systemui.keyguard

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.PreferenceKeys
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Force-enable native AOSP AOD (Always-On Display), ignoring battery saver restrictions.
 */
@SuppressLint("PrivateApi")
class ForceNativeAod : ForcedAodHookModule() {
    override fun getModuleName(): String = PreferenceKeys.FORCE_NATIVE_AOD.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        logger.info("Loading module ForceNativeAOD.")
        hookMinimumDozeBrightness(classLoader)
        hookGetAlwaysOn(classLoader)
        hookAlwaysOnEnabled()
    }

    private fun hookGetAlwaysOn(classLoader: ClassLoader) {
        try {
            val dozeParamsClass = classLoader.loadClass(DOZE_PARAMETERS_CLASS)
            val getAlwaysOnMethod = dozeParamsClass.getDeclaredMethod("getAlwaysOn")
            hookWithId(getAlwaysOnMethod, "get_always_on") {
                logger.debug("ForceNativeAOD: getAlwaysOn() -> true")
                true
            }
            logger.info("Hooked DozeParameters.getAlwaysOn() [OK]")
        } catch (t: Throwable) {
            logger.error("Failed to hook DozeParameters.getAlwaysOn()", t)
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun hookAlwaysOnEnabled() {
        try {
            val configClass = Class.forName(AMBIENT_DISPLAY_CONFIG_CLASS)
            val alwaysOnEnabledMethod = configClass.getDeclaredMethod(
                "alwaysOnEnabled", Int::class.javaPrimitiveType
            )
            hookWithId(alwaysOnEnabledMethod, "always_on_enabled") {
                logger.debug("ForceNativeAOD: alwaysOnEnabled() -> true")
                true
            }
            logger.info("Hooked AmbientDisplayConfiguration.alwaysOnEnabled() [OK]")
        } catch (t: Throwable) {
            logger.error("Failed to hook AmbientDisplayConfiguration.alwaysOnEnabled()", t)
        }
    }

    companion object {
        private const val DOZE_PARAMETERS_CLASS =
            "com.android.systemui.statusbar.phone.DozeParameters"
        private const val AMBIENT_DISPLAY_CONFIG_CLASS =
            "android.hardware.display.AmbientDisplayConfiguration"
    }
}
