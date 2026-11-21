package com.qimian233.ztool.hook.modules.gametool

import com.qimian233.ztool.hook.base.ModuleLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThermalZoneSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `explicit type resolves even when zone index shifted across reboot`() {
        val root = tempFolder.newFolder("thermal_shifted")
        createZone(root, 4, "battery", "30000")
        createZone(root, 12, "aoss-0", "43200")

        val source = ThermalZoneSource(
            logger = ModuleLog("Test"),
            preferredType = "aoss-0",
            preferredIndexHint = 9,
            thermalRoot = root
        )

        assertEquals(43200, source.read())
    }

    @Test
    fun `explicit type uses matching indexHint and picks up updated readings from cache`() {
        val root = tempFolder.newFolder("thermal_hint")
        val targetDir = createZone(root, 9, "cpuss-0", "46100")

        val source = ThermalZoneSource(
            logger = ModuleLog("Test"),
            preferredType = "cpuss-0",
            preferredIndexHint = 9,
            thermalRoot = root
        )

        assertEquals(46100, source.read())
        File(targetDir, "temp").writeText("48900")
        assertEquals(48900, source.read())
    }

    @Test
    fun `explicit missing type returns null without fallback guessing`() {
        val root = tempFolder.newFolder("thermal_missing")
        createZone(root, 0, "soc_max", "45000")
        createZone(root, 1, "cpu-0-0", "44000")

        val source = ThermalZoneSource(
            logger = ModuleLog("Test"),
            preferredType = "nonexistent-sensor",
            preferredIndexHint = 0,
            thermalRoot = root
        )

        assertNull(source.read())
    }

    @Test
    fun `auto mode matches PREFERRED_NAMES order and ignores unpreferred sensors`() {
        val root = tempFolder.newFolder("thermal_auto")
        createZone(root, 0, "custom-board-therm", "39000")
        createZone(root, 1, "skin-msm-therm", "35500")
        createZone(root, 2, "soc_max", "51200")

        val autoSource = ThermalZoneSource(
            logger = ModuleLog("Test"),
            preferredType = "",
            thermalRoot = root
        )
        assertEquals(51200, autoSource.read())

        val unmatchedRoot = tempFolder.newFolder("thermal_unmatched")
        createZone(unmatchedRoot, 0, "custom-board-therm", "39000")
        val unmatchedSource = ThermalZoneSource(
            logger = ModuleLog("Test"),
            preferredType = "",
            thermalRoot = unmatchedRoot
        )
        assertNull(unmatchedSource.read())
    }

    private fun createZone(root: File, index: Int, type: String, temp: String): File {
        val dir = File(root, "thermal_zone$index").apply { mkdirs() }
        File(dir, "type").writeText(type)
        File(dir, "temp").writeText(temp)
        return dir
    }
}
