package com.qimian233.ztool.hook.modules.gametool

import com.qimian233.ztool.hook.base.ModuleLog
import java.io.File

/**
 * Resolves and caches a readable thermal zone sysfs temperature file in milli-Celsius.
 * Matches by explicit zone type (with optional index hint) or by preferred SoC defaults.
 */
internal class ThermalZoneSource(
    private val logger: ModuleLog,
    private val preferredType: String = "",
    private val preferredIndexHint: Int = -1,
    private val thermalRoot: File = File(THERMAL_ROOT)
) {

    @Volatile
    private var cachedTempFile: File? = null

    @Volatile
    private var lastFailureAt = 0L

    /** Returns temperature in milli-Celsius, or null if no matching readable zone is available. */
    fun read(): Int? {
        cachedTempFile?.let { file ->
            readMilliCelsius(file)?.let { return it }
            cachedTempFile = null
        }
        val now = System.currentTimeMillis()
        if (now - lastFailureAt < RESOLVE_COOLDOWN_MS) return null

        val resolved = resolve()
        if (resolved == null) {
            lastFailureAt = now
            return null
        }
        cachedTempFile = resolved.tempFile
        logger.info("Thermal zone: ${resolved.type} -> ${resolved.value} mC")
        return resolved.value
    }

    private fun resolve(): ResolvedZone? {
        val targetType = preferredType.trim()
        if (targetType.isNotEmpty()) {
            if (preferredIndexHint >= 0) {
                readIfMatches(File(thermalRoot, "$ZONE_PREFIX$preferredIndexHint"), targetType)?.let {
                    return it
                }
            }
            val matched = scanZoneDirs()
                .firstNotNullOfOrNull { dir -> readIfMatches(dir, targetType) }
            if (matched == null) {
                logger.warn("Preferred thermal zone '$targetType' unavailable under ${thermalRoot.path}")
            }
            return matched
        }

        val candidates = scanZoneDirs().mapNotNull { dir ->
            val type = readText(File(dir, TYPE_FILE)) ?: return@mapNotNull null
            val tempFile = File(dir, TEMP_FILE)
            val value = readMilliCelsius(tempFile) ?: return@mapNotNull null
            ResolvedZone(type, tempFile, value)
        }
        for (preferred in PREFERRED_NAMES) {
            candidates.firstOrNull { it.type.equals(preferred, ignoreCase = true) }?.let { return it }
        }
        logger.warn("No preferred thermal zone available under ${thermalRoot.path}")
        return null
    }

    private fun scanZoneDirs(): List<File> =
        thermalRoot.listFiles()
            ?.mapNotNull { file ->
                if (!file.isDirectory) return@mapNotNull null
                val index = ZONE_DIR_REGEX.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                index to file
            }
            ?.sortedBy { it.first }
            ?.map { it.second }
            ?: emptyList()

    private fun readIfMatches(zoneDir: File, expectedType: String): ResolvedZone? {
        if (!zoneDir.isDirectory) return null
        val type = readText(File(zoneDir, TYPE_FILE)) ?: return null
        if (!type.equals(expectedType, ignoreCase = true)) return null
        val tempFile = File(zoneDir, TEMP_FILE)
        val value = readMilliCelsius(tempFile) ?: return null
        return ResolvedZone(type, tempFile, value)
    }

    private fun readMilliCelsius(file: File): Int? =
        readText(file)?.toIntOrNull()?.takeIf { it in PLAUSIBLE_RANGE }

    private fun readText(file: File): String? = try {
        if (!file.canRead()) null else file.bufferedReader().use { it.readLine() }?.trim()
    } catch (_: Exception) {
        null
    }

    private data class ResolvedZone(val type: String, val tempFile: File, val value: Int)

    companion object {
        private const val THERMAL_ROOT = "/sys/class/thermal"
        private const val ZONE_PREFIX = "thermal_zone"
        private const val TYPE_FILE = "type"
        private const val TEMP_FILE = "temp"
        private const val RESOLVE_COOLDOWN_MS = 30_000L

        private val ZONE_DIR_REGEX = Regex("^thermal_zone(\\d+)$")

        /** 5-150 °C; filters placeholder sentinels and non-temperature sensors. */
        private val PLAUSIBLE_RANGE = 5_000..150_000

        /** Ordered auto-selection zone names matched when no explicit zone is configured. */
        internal val PREFERRED_NAMES = listOf(
            "soc_max", "user_temp", "skin-msm-therm-usr", "skin-msm-therm",
            "back_temp", "user_back_temp", "front_temp", "user_front_temp",
            "soc_therm", "mtktscpu", "ap_therm"
        )
    }
}
