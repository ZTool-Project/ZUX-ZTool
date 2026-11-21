package com.qimian233.ztool.hook.modules.gametool

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File

/**
 * Hooks HWDataInterface CPU frequency getters to read user-selected sysfs nodes.
 * See docs/research/gametool_hardware_telemetry.md
 */
class CpuFrequencyFix : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.FIX_CPU_CLOCK.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.GAME_SERVICE.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val prefs = remotePreferences
        val configuredCore = prefs.getInt(
            PreferenceKeys.FIX_CPU_CLOCK_CORE.name,
            PreferenceKeys.FIX_CPU_CLOCK_CORE.default
        )
        val configuredNode = prefs.getString(
            PreferenceKeys.FIX_CPU_CLOCK_NODE.name,
            PreferenceKeys.FIX_CPU_CLOCK_NODE.default
        )?.takeIf { it in ALLOWED_NODES } ?: PreferenceKeys.FIX_CPU_CLOCK_NODE.default

        val coreIndex = resolveTargetCoreIndex(configuredCore)
        if (coreIndex < 0) {
            logger.warn("No readable CPU cpufreq directory found (configured=$configuredCore)")
            return
        }

        val curFreqFile = File("$CPU_ROOT/cpu$coreIndex/cpufreq/$configuredNode")
        val hwMaxFile = File("$CPU_ROOT/cpu$coreIndex/cpufreq/$NODE_CPUINFO_MAX")
        val policyMaxFile = File("$CPU_ROOT/cpu$coreIndex/cpufreq/$NODE_SCALING_MAX")

        try {
            val hwDataClass = param.defaultClassLoader.loadClass(HW_DATA_INTERFACE_CLASS)

            val getCpuCurFreqMethod = hwDataClass.getDeclaredMethod("getCpuCurFreq")
            hookWithId(getCpuCurFreqMethod, "get_cpu_cur_freq") { chain ->
                readSysfsInt(curFreqFile) ?: (chain.proceed() as Int)
            }

            val getCpuMaxFreqMethod = hwDataClass.getDeclaredMethod("getCpuMaxFreq")
            hookWithId(getCpuMaxFreqMethod, "get_cpu_max_freq") { chain ->
                (readSysfsInt(hwMaxFile) ?: readSysfsInt(policyMaxFile)) ?: (chain.proceed() as Int)
            }

            logger.info("Hooked CPU frequency for cpu$coreIndex/$configuredNode")
        } catch (t: Throwable) {
            logger.error("Failed to hook HWDataInterface CPU frequency methods", t)
        }
    }

    private fun resolveTargetCoreIndex(configuredCore: Int): Int {
        if (configuredCore >= 0) return configuredCore
        val dirs = File(CPU_ROOT).listFiles() ?: return -1
        return dirs.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            val idx = CPU_DIR_REGEX.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (File(dir, "cpufreq").isDirectory) idx else null
        }.maxOrNull() ?: -1
    }

    private fun readSysfsInt(file: File): Int? = try {
        if (!file.canRead()) {
            null
        } else {
            file.bufferedReader().use { it.readLine() }?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val HW_DATA_INTERFACE_CLASS = "com.zui.game.service.util.HWDataInterface"
        const val CPU_ROOT = "/sys/devices/system/cpu"
        const val NODE_CPUINFO_MAX = "cpuinfo_max_freq"
        const val NODE_SCALING_MAX = "scaling_max_freq"

        val CPU_DIR_REGEX = Regex("^cpu(\\d+)$")
        val ALLOWED_NODES = setOf(
            "scaling_cur_freq",
            "cpuinfo_cur_freq",
            NODE_SCALING_MAX,
            NODE_CPUINFO_MAX
        )
    }
}
