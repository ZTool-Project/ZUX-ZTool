package com.qimian233.ztool.data.gametool

import com.qimian233.ztool.hook.modules.gametool.ThermalZoneSource
import java.io.File
import java.util.Locale

enum class ThermalZoneHint {
    SOC_OVERALL,
    CPU_CLUSTER,
    GPU,
    SUBSYSTEM_AOSS,
    SKIN_THERMISTOR,
    BATTERY,
    MEMORY_STORAGE,
    PMIC,
    CHARGER,
    DISPLAY,
    MODEM_RF,
    OTHER
}

data class ThermalZoneInfo(
    val index: Int,
    val type: String,
    val tempMilliC: Int,
    val hint: ThermalZoneHint
) {
    val tempCelsius: Float get() = tempMilliC / 1000.0f
}

/** Probes readable Android thermal sysfs zones and classifies sensor types by Android/SoC convention. */
object ThermalZoneCatalog {
    const val DEFAULT_THERMAL_ROOT = "/sys/class/thermal"
    const val MIN_PLAUSIBLE_TEMP_MILLI_C = 5_000
    const val MAX_PLAUSIBLE_TEMP_MILLI_C = 150_000

    private val ZONE_DIR_REGEX = Regex("^thermal_zone(\\d+)$")

    private val PREFERRED_RANK: Map<String, Int> = ThermalZoneSource.PREFERRED_NAMES
        .withIndex()
        .associate { it.value.lowercase(Locale.ROOT) to it.index }

    fun load(thermalRoot: File = File(DEFAULT_THERMAL_ROOT)): List<ThermalZoneInfo> {
        val dirs = thermalRoot.listFiles() ?: return emptyList()
        val zones = ArrayList<ThermalZoneInfo>()

        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val index = ZONE_DIR_REGEX.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val typeFile = File(dir, "type")
            val tempFile = File(dir, "temp")
            val type = readTrimmed(typeFile) ?: continue
            val tempMilliC = readTrimmed(tempFile)?.toIntOrNull() ?: continue
            if (tempMilliC !in MIN_PLAUSIBLE_TEMP_MILLI_C..MAX_PLAUSIBLE_TEMP_MILLI_C) continue

            zones += ThermalZoneInfo(
                index = index,
                type = type,
                tempMilliC = tempMilliC,
                hint = classifyHint(type)
            )
        }

        return zones.sortedWith(
            compareBy<ThermalZoneInfo>(
                { PREFERRED_RANK[it.type.lowercase(Locale.ROOT)] ?: Int.MAX_VALUE },
                { it.hint.ordinal },
                { it.index }
            )
        )
    }

    fun classifyHint(type: String): ThermalZoneHint {
        val normalized = type.trim().lowercase(Locale.ROOT)
        return when {
            normalized == "soc_max" ||
                normalized == "soc" ||
                normalized == "mtktscpu" ||
                normalized.startsWith("soc-therm") ||
                normalized.startsWith("soc_therm") ||
                normalized.startsWith("tsens_tz_sensor") -> ThermalZoneHint.SOC_OVERALL

            normalized.startsWith("cpu") ||
                normalized.startsWith("apc") -> ThermalZoneHint.CPU_CLUSTER

            normalized.startsWith("gpu") ||
                normalized.contains("adreno") ||
                normalized.contains("mali") ||
                normalized.startsWith("mtktsgpu") -> ThermalZoneHint.GPU

            normalized.startsWith("aoss") ||
                normalized.startsWith("nsp") ||
                normalized.startsWith("cdsp") ||
                normalized.startsWith("adsp") ||
                normalized.startsWith("video") ||
                normalized.startsWith("camera") -> ThermalZoneHint.SUBSYSTEM_AOSS

            normalized == "battery" ||
                normalized == "bat" ||
                normalized == "bms" ||
                normalized == "mtktsbattery" ||
                normalized.startsWith("batt") -> ThermalZoneHint.BATTERY

            normalized.startsWith("skin") ||
                normalized.startsWith("shell") ||
                normalized.startsWith("xo-therm") ||
                normalized.startsWith("xo_therm") ||
                normalized.startsWith("quiet-therm") ||
                normalized.startsWith("quiet_therm") ||
                normalized.startsWith("sys-therm") ||
                normalized.startsWith("sys_therm") ||
                normalized.startsWith("ap-therm") ||
                normalized.startsWith("board") ||
                normalized.startsWith("rear") ||
                normalized.startsWith("front") -> ThermalZoneHint.SKIN_THERMISTOR

            normalized.startsWith("ddr") ||
                normalized.startsWith("dram") ||
                normalized.startsWith("lpddr") ||
                normalized.startsWith("mem") ||
                normalized.startsWith("ufs") ||
                normalized.startsWith("emmc") -> ThermalZoneHint.MEMORY_STORAGE

            normalized.startsWith("pmic") ||
                normalized.startsWith("pmk") ||
                normalized.startsWith("pmr") ||
                normalized.startsWith("pm8") ||
                normalized.startsWith("pm7") ||
                normalized.startsWith("pm6") ||
                normalized.startsWith("mt63") -> ThermalZoneHint.PMIC

            normalized.startsWith("chg") ||
                normalized.startsWith("charger") ||
                normalized.startsWith("smb") ||
                normalized.startsWith("qnovo") ||
                normalized.startsWith("usb") ||
                normalized.startsWith("wls") -> ThermalZoneHint.CHARGER

            normalized.startsWith("disp") ||
                normalized.startsWith("panel") ||
                normalized.startsWith("lcm") ||
                normalized.startsWith("lcd") ||
                normalized.startsWith("oled") ||
                normalized.startsWith("mdss") -> ThermalZoneHint.DISPLAY

            normalized.startsWith("modem") ||
                normalized.startsWith("mdm") ||
                normalized.startsWith("pa-therm") ||
                normalized.startsWith("pa_therm") ||
                normalized.startsWith("pa0") ||
                normalized.startsWith("pa1") ||
                normalized.startsWith("wlan") ||
                normalized.startsWith("wifi") ||
                normalized.startsWith("sdr") ||
                normalized.startsWith("mtktspa") -> ThermalZoneHint.MODEM_RF

            else -> ThermalZoneHint.OTHER
        }
    }

    fun formatTempC(tempMilliC: Int): String =
        String.format(Locale.US, "%.1f °C", tempMilliC / 1000.0f)

    private fun readTrimmed(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
