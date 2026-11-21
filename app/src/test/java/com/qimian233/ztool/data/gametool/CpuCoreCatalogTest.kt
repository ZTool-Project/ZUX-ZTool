package com.qimian233.ztool.data.gametool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CpuCoreCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `load probes cpufreq nodes assigns clusters and parses cpuinfo names`() {
        val cpuRoot = tempFolder.newFolder("cpu")
        createCore(
            cpuRoot,
            index = 0,
            nodes = mapOf(
                "scaling_cur_freq" to "1800000",
                "cpuinfo_max_freq" to "2419200"
            ),
            relatedCpus = "0 1 2"
        )
        createCore(
            cpuRoot,
            index = 3,
            nodes = mapOf(
                "scaling_cur_freq" to "2800000",
                "scaling_max_freq" to "3321600",
                "cpuinfo_max_freq" to "3321600"
            ),
            relatedCpus = "3 4"
        )
        createCore(
            cpuRoot,
            index = 10,
            nodes = mapOf(
                "scaling_cur_freq" to "3800000",
                "cpuinfo_cur_freq" to "3810000",
                "cpuinfo_max_freq" to "4320000"
            ),
            relatedCpus = "10"
        )
        // Noise directory and core without readable frequency values should be ignored
        File(cpuRoot, "cpuidle").mkdirs()
        createCore(
            cpuRoot,
            index = 9,
            nodes = mapOf("scaling_cur_freq" to "0"),
            relatedCpus = "9"
        )

        val cpuInfo = tempFolder.newFile("cpuinfo").apply {
            writeText(
                """
                processor	: 0
                CPU implementer	: 0x41
                CPU part	: 0xd80

                processor	: 3
                CPU implementer	: 0x41
                CPU part	: 0xd81

                processor	: 10
                CPU implementer	: 0x51
                CPU part	: 0x001
                """.trimIndent()
            )
        }

        val cores = CpuCoreCatalog.load(cpuRoot = cpuRoot, cpuInfoFile = cpuInfo)
        assertEquals(listOf(0, 3, 10), cores.map { it.index })

        assertEquals("Cortex-A520", cores[0].coreName)
        assertEquals(0, cores[0].clusterId)
        assertEquals(2419200L, cores[0].maxFreqKhz)
        assertEquals(listOf("scaling_cur_freq", "cpuinfo_max_freq"), cores[0].readableNodes)

        assertEquals("Cortex-A720", cores[1].coreName)
        assertEquals(1, cores[1].clusterId)
        assertEquals(listOf("scaling_cur_freq", "scaling_max_freq", "cpuinfo_max_freq"), cores[1].readableNodes)

        assertEquals("Oryon", cores[2].coreName)
        assertEquals(2, cores[2].clusterId)
        assertEquals(listOf("scaling_cur_freq", "cpuinfo_cur_freq", "cpuinfo_max_freq"), cores[2].readableNodes)
    }

    @Test
    fun `availableNodesForSelection picks auto highest core or explicit core`() {
        val cores = listOf(
            CpuCoreInfo(0, "Cortex-A55", 1800000L, 0, listOf("scaling_cur_freq", "scaling_max_freq")),
            CpuCoreInfo(7, "Cortex-X4", 3400000L, 1, listOf("scaling_cur_freq", "cpuinfo_cur_freq", "cpuinfo_max_freq"))
        )

        assertEquals(
            listOf("scaling_cur_freq", "cpuinfo_cur_freq", "cpuinfo_max_freq"),
            CpuCoreCatalog.availableNodesForSelection(cores, selectedCoreIndex = -1)
        )
        assertEquals(
            listOf("scaling_cur_freq", "scaling_max_freq"),
            CpuCoreCatalog.availableNodesForSelection(cores, selectedCoreIndex = 0)
        )
        assertEquals(
            listOf(CpuCoreCatalog.DEFAULT_FREQ_NODE),
            CpuCoreCatalog.availableNodesForSelection(emptyList(), selectedCoreIndex = -1)
        )
    }

    @Test
    fun `parseCpuInfoText handles uppercase hex and unknown parts safely`() {
        val names = CpuCoreCatalog.parseCpuInfoText(
            """
            processor : 0
            CPU implementer : 0x41
            CPU part : 0xD82

            processor : 1
            CPU implementer : 0x51
            CPU part : 0x9999
            """.trimIndent()
        )
        assertEquals("Cortex-X4", names[0])
        assertNull(names[1])
    }

    @Test
    fun `formatMaxFreqGhz formats khz to two decimal GHz`() {
        assertEquals("4.32 GHz", CpuCoreCatalog.formatMaxFreqGhz(4_320_000L))
        assertEquals("2.42 GHz", CpuCoreCatalog.formatMaxFreqGhz(2_419_200L))
    }

    private fun createCore(
        cpuRoot: File,
        index: Int,
        nodes: Map<String, String>,
        relatedCpus: String
    ) {
        val cpufreq = File(cpuRoot, "cpu$index/cpufreq").apply { mkdirs() }
        File(cpufreq, "related_cpus").writeText(relatedCpus)
        nodes.forEach { (name, value) -> File(cpufreq, name).writeText(value) }
    }
}
