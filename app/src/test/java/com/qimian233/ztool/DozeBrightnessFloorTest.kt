package com.qimian233.ztool

import com.qimian233.ztool.hook.modules.systemui.keyguard.DozeBrightnessFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DozeBrightnessFloorTest {
    @Test
    fun defaultAndSensorValuesShareTheFloor() {
        for (brightness in listOf(0f, 1f / 254f, 4f / 254f, 0.099f, 0.1f)) {
            assertEquals(0.1f, DozeBrightnessFloor.clamp(brightness), 0f)
        }
        for (brightness in listOf(0, 2, 5, 26, 27)) {
            assertEquals(27, DozeBrightnessFloor.clamp(brightness))
        }
    }

    @Test
    fun higherBrightnessIsPreserved() {
        for (brightness in listOf(0.11f, 0.5f, 1f)) {
            assertEquals(brightness, DozeBrightnessFloor.clamp(brightness), 0f)
        }
        for (brightness in listOf(28, 128, 255)) {
            assertEquals(brightness, DozeBrightnessFloor.clamp(brightness))
        }
    }

    @Test
    fun platformSentinelsAreNotConvertedToVisibleBrightness() {
        assertEquals(-1, DozeBrightnessFloor.clamp(-1))
        assertEquals(-1f, DozeBrightnessFloor.clamp(-1f), 0f)
        assertTrue(DozeBrightnessFloor.clamp(Float.NaN).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, DozeBrightnessFloor.clamp(Float.POSITIVE_INFINITY), 0f)
        assertEquals(Float.NEGATIVE_INFINITY, DozeBrightnessFloor.clamp(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun bothAodHooksApplyingTheFloorDoesNotCompoundBrightness() {
        for (brightness in listOf(0f, 0.05f, 0.2f, 1f)) {
            val once = DozeBrightnessFloor.clamp(brightness)
            assertEquals(once, DozeBrightnessFloor.clamp(once), 0f)
        }
        for (brightness in listOf(0, 2, 28, 255)) {
            val once = DozeBrightnessFloor.clamp(brightness)
            assertEquals(once, DozeBrightnessFloor.clamp(once))
        }
    }
}
