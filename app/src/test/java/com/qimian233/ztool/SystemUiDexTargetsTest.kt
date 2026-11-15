package com.qimian233.ztool

import com.qimian233.ztool.dexindex.base.SystemUiDexTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SystemUiDexTargetsTest {
    private class FirmwareLoader(private val available: Map<String, Class<*>>) : ClassLoader(null) {
        val requested = mutableListOf<String>()

        override fun loadClass(name: String): Class<*> {
            requested += name
            return available[name] ?: throw ClassNotFoundException(name)
        }
    }

    private val paths = listOf(
        SystemUiDexTargets.CHARGING_CONTROLLERS,
        SystemUiDexTargets.CHARGING_ACTIVITIES,
        SystemUiDexTargets.CHARGING_VIDEO_VIEWS,
        SystemUiDexTargets.NETWORK_SPEED_VIEWS,
    )

    @Test
    fun mixedFirmwarePrefersCurrentClassesWithoutLoadingLegacyClasses() {
        for (names in paths) {
            val loader = FirmwareLoader(mapOf(names[0] to String::class.java, names[1] to Int::class.java))
            assertSame(String::class.java, SystemUiDexTargets.loadFirstClass(loader, names))
            assertEquals(listOf(names[0]), loader.requested)
        }
    }

    @Test
    fun olderFirmwareStillResolvesLegacyClasses() {
        for (names in paths) {
            val loader = FirmwareLoader(mapOf(names[1] to String::class.java))
            assertSame(String::class.java, SystemUiDexTargets.loadFirstClass(loader, names))
            assertEquals(names, loader.requested)
        }
    }

    @Test(expected = ClassNotFoundException::class)
    fun missingBothPathsReportsFailure() {
        SystemUiDexTargets.loadFirstClass(FirmwareLoader(emptyMap()), SystemUiDexTargets.CHARGING_VIDEO_VIEWS)
    }
}
