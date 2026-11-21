package com.qimian233.ztool.data.gametool

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThermalZoneCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `load filters sentinel temperatures classifies hints and sorts recommended first`() {
        val thermalRoot = tempFolder.newFolder("thermal")
        createZone(thermalRoot, 0, "battery", "31200")
        createZone(thermalRoot, 3, "cpuss-0", "45500")
        createZone(thermalRoot, 5, "aoss-0", "41000")
        createZone(thermalRoot, 7, "skin-msm-therm", "36800")
        createZone(thermalRoot, 9, "soc_max", "52000")
        // Implausible/placeholder readings that must be ignored
        createZone(thermalRoot, 1, "tsens_tz_sensor0", "0")
        createZone(thermalRoot, 2, "pm8550-temp", "-273000")
        createZone(thermalRoot, 4, "invalid_low", "4999")
        createZone(thermalRoot, 6, "invalid_high", "150001")
        File(thermalRoot, "thermal_zone8").mkdirs() // missing type & temp
        File(thermalRoot, "cooling_device0").mkdirs()

        val zones = ThermalZoneCatalog.load(thermalRoot)
        assertEquals(
            listOf("soc_max", "skin-msm-therm", "cpuss-0", "aoss-0", "battery"),
            zones.map { it.type }
        )

        val hintsByType = zones.associate { it.type to it.hint }
        assertEquals(ThermalZoneHint.SOC_OVERALL, hintsByType["soc_max"])
        assertEquals(ThermalZoneHint.SKIN_THERMISTOR, hintsByType["skin-msm-therm"])
        assertEquals(ThermalZoneHint.CPU_CLUSTER, hintsByType["cpuss-0"])
        assertEquals(ThermalZoneHint.SUBSYSTEM_AOSS, hintsByType["aoss-0"])
        assertEquals(ThermalZoneHint.BATTERY, hintsByType["battery"])
        assertEquals(9, zones.first().index)
        assertEquals(52000, zones.first().tempMilliC)
    }

    @Test
    fun `classifyHint recognizes Android Qualcomm and MediaTek zone names`() {
        assertEquals(ThermalZoneHint.SOC_OVERALL, ThermalZoneCatalog.classifyHint("mtktscpu"))
        assertEquals(ThermalZoneHint.SOC_OVERALL, ThermalZoneCatalog.classifyHint("tsens_tz_sensor5"))
        assertEquals(ThermalZoneHint.GPU, ThermalZoneCatalog.classifyHint("gpuss-0"))
        assertEquals(ThermalZoneHint.MEMORY_STORAGE, ThermalZoneCatalog.classifyHint("ddr-therm"))
        assertEquals(ThermalZoneHint.PMIC, ThermalZoneCatalog.classifyHint("pm8550-temp"))
        assertEquals(ThermalZoneHint.CHARGER, ThermalZoneCatalog.classifyHint("chg-therm"))
        assertEquals(ThermalZoneHint.DISPLAY, ThermalZoneCatalog.classifyHint("panel-therm"))
        assertEquals(ThermalZoneHint.MODEM_RF, ThermalZoneCatalog.classifyHint("pa-therm0"))
        assertEquals(ThermalZoneHint.OTHER, ThermalZoneCatalog.classifyHint("vendor_custom_xyz"))
    }

    @Test
    fun `formatTempC renders one decimal Celsius`() {
        assertEquals("38.5 °C", ThermalZoneCatalog.formatTempC(38_500))
    }

    private fun createZone(root: File, index: Int, type: String, temp: String) {
        val dir = File(root, "thermal_zone$index").apply { mkdirs() }
        File(dir, "type").writeText(type)
        File(dir, "temp").writeText(temp)
    }
}
