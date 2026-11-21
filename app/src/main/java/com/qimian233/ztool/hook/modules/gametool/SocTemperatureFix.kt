package com.qimian233.ztool.hook.modules.gametool

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hooks HWDataInterface.getTemp to return sysfs readings from the selected thermal zone.
 * See docs/research/gametool_hardware_telemetry.md
 */
class SocTemperatureFix : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.FIX_SOC_TEMP.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.GAME_SERVICE.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val prefs = remotePreferences
        val preferredType = prefs.getString(
            PreferenceKeys.FIX_SOC_TEMP_ZONE_TYPE.name,
            PreferenceKeys.FIX_SOC_TEMP_ZONE_TYPE.default
        ).orEmpty()
        val preferredIndexHint = prefs.getInt(
            PreferenceKeys.FIX_SOC_TEMP_ZONE_INDEX_HINT.name,
            PreferenceKeys.FIX_SOC_TEMP_ZONE_INDEX_HINT.default
        )

        try {
            val hwDataInterfaceClass = param.defaultClassLoader.loadClass(HW_DATA_INTERFACE_CLASS)
            val thermalSource = ThermalZoneSource(logger, preferredType, preferredIndexHint)

            val getTempMethod = hwDataInterfaceClass.getDeclaredMethod("getTemp")
            hookWithId(getTempMethod, "get_temp") { chain ->
                thermalSource.read() ?: (chain.proceed() as Int)
            }

            logger.info("Hooked HWDataInterface.getTemp (preferredType='$preferredType')")
        } catch (t: Throwable) {
            logger.error("Failed to hook HWDataInterface.getTemp", t)
        }
    }

    companion object {
        private const val HW_DATA_INTERFACE_CLASS = "com.zui.game.service.util.HWDataInterface"
    }
}
