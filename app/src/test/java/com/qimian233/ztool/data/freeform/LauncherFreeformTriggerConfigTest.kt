package com.qimian233.ztool.data.freeform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherFreeformTriggerConfigTest {

    @Test
    fun `normalizeTopPercent clamps out of range values`() {
        assertEquals(
            LauncherFreeformTriggerConfig.TOP_PERCENT_MIN,
            LauncherFreeformTriggerConfig.normalizeTopPercent(-10)
        )
        assertEquals(
            LauncherFreeformTriggerConfig.TOP_PERCENT_MIN,
            LauncherFreeformTriggerConfig.normalizeTopPercent(0)
        )
        assertEquals(20, LauncherFreeformTriggerConfig.normalizeTopPercent(20))
        assertEquals(
            LauncherFreeformTriggerConfig.TOP_PERCENT_MAX,
            LauncherFreeformTriggerConfig.normalizeTopPercent(55)
        )
        assertEquals(
            LauncherFreeformTriggerConfig.TOP_PERCENT_MAX,
            LauncherFreeformTriggerConfig.normalizeTopPercent(90)
        )
    }

    @Test
    fun `normalizeLeftPercent clamps and pushes right margin when width drops below minimum`() {
        assertEquals(0 to 0, LauncherFreeformTriggerConfig.normalizeLeftPercent(-5, -5))
        assertEquals(30 to 50, LauncherFreeformTriggerConfig.normalizeLeftPercent(30, 50))
        assertEquals(60 to 20, LauncherFreeformTriggerConfig.normalizeLeftPercent(60, 40))
        assertEquals(80 to 0, LauncherFreeformTriggerConfig.normalizeLeftPercent(95, 50))

        val (left, right) = LauncherFreeformTriggerConfig.normalizeLeftPercent(73, 40)
        assertTrue(100 - left - right >= LauncherFreeformTriggerConfig.MIN_HORIZONTAL_WIDTH_PERCENT)
    }

    @Test
    fun `normalizeRightPercent clamps and pushes left margin when width drops below minimum`() {
        assertEquals(0 to 0, LauncherFreeformTriggerConfig.normalizeRightPercent(-5, -5))
        assertEquals(40 to 40, LauncherFreeformTriggerConfig.normalizeRightPercent(40, 40))
        assertEquals(20 to 60, LauncherFreeformTriggerConfig.normalizeRightPercent(40, 60))
        assertEquals(0 to 80, LauncherFreeformTriggerConfig.normalizeRightPercent(50, 95))

        val (left, right) = LauncherFreeformTriggerConfig.normalizeRightPercent(40, 73)
        assertTrue(100 - left - right >= LauncherFreeformTriggerConfig.MIN_HORIZONTAL_WIDTH_PERCENT)
    }

    @Test
    fun `normalizeMargins preserves minimum horizontal width`() {
        assertEquals(80 to 0, LauncherFreeformTriggerConfig.normalizeMargins(90, 90))
        assertEquals(25 to 35, LauncherFreeformTriggerConfig.normalizeMargins(25, 35))
    }

    @Test
    fun `normalizeCornerRadiusDp clamps out of range values`() {
        assertEquals(
            LauncherFreeformTriggerConfig.CORNER_RADIUS_DP_MIN,
            LauncherFreeformTriggerConfig.normalizeCornerRadiusDp(-5)
        )
        assertEquals(24, LauncherFreeformTriggerConfig.normalizeCornerRadiusDp(24))
        assertEquals(
            LauncherFreeformTriggerConfig.CORNER_RADIUS_DP_MAX,
            LauncherFreeformTriggerConfig.normalizeCornerRadiusDp(100)
        )
    }
}
