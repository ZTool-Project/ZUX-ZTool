package com.qimian233.ztool.data.gametool

import java.io.File
import java.util.Locale

data class CpuCoreInfo(
    val index: Int,
    val coreName: String?,
    val maxFreqKhz: Long?,
    val clusterId: Int?,
    val readableNodes: List<String>
)

/** Probes readable CPU core frequency sysfs nodes and hardware metadata off the main thread. */
object CpuCoreCatalog {
    const val DEFAULT_CPU_ROOT = "/sys/devices/system/cpu"
    const val DEFAULT_CPU_INFO_PATH = "/proc/cpuinfo"
    const val DEFAULT_FREQ_NODE = "scaling_cur_freq"

    val ALLOWED_FREQ_NODES = listOf(
        "scaling_cur_freq",
        "cpuinfo_cur_freq",
        "scaling_max_freq",
        "cpuinfo_max_freq"
    )

    private val CPU_DIR_REGEX = Regex("^cpu(\\d+)$")
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val ARM_PARTS = mapOf(
        0xd03 to "Cortex-A53",
        0xd05 to "Cortex-A55",
        0xd07 to "Cortex-A57",
        0xd08 to "Cortex-A72",
        0xd09 to "Cortex-A73",
        0xd0a to "Cortex-A75",
        0xd0b to "Cortex-A76",
        0xd0d to "Cortex-A77",
        0xd40 to "Neoverse V1",
        0xd41 to "Cortex-A78",
        0xd44 to "Cortex-X1",
        0xd46 to "Cortex-A510",
        0xd47 to "Cortex-A710",
        0xd48 to "Cortex-X2",
        0xd4d to "Cortex-A715",
        0xd4e to "Cortex-X3",
        0xd80 to "Cortex-A520",
        0xd81 to "Cortex-A720",
        0xd82 to "Cortex-X4",
        0xd85 to "Cortex-X925",
        0xd87 to "Cortex-A725"
    )

    private val QCOM_PARTS = mapOf(
        0x001 to "Oryon",
        0x002 to "Oryon",
        0x802 to "Kryo 385",
        0x803 to "Kryo 385",
        0x804 to "Kryo 485",
        0x805 to "Kryo 485"
    )

    fun load(
        cpuRoot: File = File(DEFAULT_CPU_ROOT),
        cpuInfoFile: File = File(DEFAULT_CPU_INFO_PATH)
    ): List<CpuCoreInfo> {
        val coreDirs = cpuRoot.listFiles()
            ?.mapNotNull { dir ->
                if (!dir.isDirectory) return@mapNotNull null
                val index = CPU_DIR_REGEX.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                index to dir
            }
            ?.sortedBy { it.first }
            ?: return emptyList()

        val coreNames = parseCoreNames(cpuInfoFile)
        val clusterKeys = LinkedHashMap<String, Int>()
        val result = ArrayList<CpuCoreInfo>(coreDirs.size)

        for ((index, dir) in coreDirs) {
            val cpufreqDir = File(dir, "cpufreq")
            if (!cpufreqDir.isDirectory) continue

            val readableNodes = ALLOWED_FREQ_NODES.filter { node ->
                readPositiveLong(File(cpufreqDir, node)) != null
            }
            if (readableNodes.isEmpty()) continue

            val maxFreqKhz = readPositiveLong(File(cpufreqDir, "cpuinfo_max_freq"))
                ?: readPositiveLong(File(cpufreqDir, "scaling_max_freq"))
            val clusterId = readClusterKey(File(cpufreqDir, "related_cpus"))?.let { key ->
                clusterKeys.getOrPut(key) { clusterKeys.size }
            }

            result += CpuCoreInfo(
                index = index,
                coreName = coreNames[index],
                maxFreqKhz = maxFreqKhz,
                clusterId = clusterId,
                readableNodes = readableNodes
            )
        }
        return result
    }

    fun availableNodesForSelection(
        cores: List<CpuCoreInfo>,
        selectedCoreIndex: Int
    ): List<String> {
        if (cores.isEmpty()) return listOf(DEFAULT_FREQ_NODE)
        val targetCore = if (selectedCoreIndex < 0) {
            cores.maxByOrNull { it.index }
        } else {
            cores.firstOrNull { it.index == selectedCoreIndex }
        }
        if (targetCore != null && targetCore.readableNodes.isNotEmpty()) {
            return targetCore.readableNodes
        }
        val union = cores.flatMapTo(LinkedHashSet()) { it.readableNodes }
        return ALLOWED_FREQ_NODES.filter { it in union }.ifEmpty { listOf(DEFAULT_FREQ_NODE) }
    }

    fun formatMaxFreqGhz(maxFreqKhz: Long): String =
        String.format(Locale.US, "%.2f GHz", maxFreqKhz / 1_000_000.0)

    internal fun parseCoreNames(cpuInfoFile: File): Map<Int, String> {
        val text = runCatching {
            if (cpuInfoFile.isFile && cpuInfoFile.canRead()) cpuInfoFile.readText() else null
        }.getOrNull() ?: return emptyMap()
        return parseCpuInfoText(text)
    }

    internal fun parseCpuInfoText(text: String): Map<Int, String> {
        val names = HashMap<Int, String>()
        var currentProcessor: Int? = null
        var implementer: Int? = null
        var part: Int? = null

        fun flushBlock() {
            val proc = currentProcessor
            val impl = implementer
            val prt = part
            if (proc != null && impl != null && prt != null) {
                resolveMidrName(impl, prt)?.let { names[proc] = it }
            }
            currentProcessor = null
            implementer = null
            part = null
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                flushBlock()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            when (key) {
                "processor" -> {
                    val parsed = value.toIntOrNull()
                    if (currentProcessor != null && parsed != null) {
                        flushBlock()
                    }
                    currentProcessor = parsed
                }
                "cpu implementer" -> implementer = parseHexOrInt(value)
                "cpu part" -> part = parseHexOrInt(value)
            }
        }
        flushBlock()
        return names
    }

    private fun resolveMidrName(implementer: Int, part: Int): String? = when (implementer) {
        0x41 -> ARM_PARTS[part]
        0x51 -> QCOM_PARTS[part]
        else -> null
    }

    private fun parseHexOrInt(value: String): Int? {
        val trimmed = value.trim().lowercase(Locale.ROOT)
        return if (trimmed.startsWith("0x")) {
            trimmed.removePrefix("0x").toIntOrNull(16)
        } else {
            trimmed.toIntOrNull()
        }
    }

    private fun readPositiveLong(file: File): Long? {
        if (!file.isFile || !file.canRead()) return null
        val value = runCatching { file.readText().trim().toLongOrNull() }.getOrNull() ?: return null
        return value.takeIf { it > 0L }
    }

    private fun readClusterKey(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        val cpus = runCatching { file.readText().trim() }.getOrNull()
            ?.split(WHITESPACE_REGEX)
            ?.mapNotNull { it.toIntOrNull() }
            ?.sorted()
            ?.distinct()
            ?: return null
        if (cpus.isEmpty()) return null
        return cpus.joinToString(",")
    }
}
