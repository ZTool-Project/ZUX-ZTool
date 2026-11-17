package com.qimian233.ztool.hook.modules.systemui.statusbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryIndicatorPolicyTest {

    @Test
    fun `options enabled reflects external percent, charging color, or non-default style`() {
        assertFalse(BatteryIndicatorOptions().enabled)
        assertTrue(BatteryIndicatorOptions(externalPercent = true).enabled)
        assertTrue(BatteryIndicatorOptions(chargingColor = true).enabled)
        assertTrue(BatteryIndicatorOptions(style = BatteryIndicatorStyle.FRAMELESS).enabled)
    }

    @Test
    fun `style fromPref parses valid values and degrades unknown values to default`() {
        assertEquals(BatteryIndicatorStyle.DEFAULT_FRAMED, BatteryIndicatorStyle.fromPref("default_framed"))
        assertEquals(BatteryIndicatorStyle.FRAMELESS, BatteryIndicatorStyle.fromPref("frameless"))
        assertEquals(BatteryIndicatorStyle.DEFAULT_FRAMED, BatteryIndicatorStyle.fromPref("unknown"))
        assertEquals(BatteryIndicatorStyle.DEFAULT_FRAMED, BatteryIndicatorStyle.fromPref(null))
    }

    @Test
    fun `normal charging uses green fill color`() {
        val state = BatteryIndicatorState(
            level = 65,
            isCharging = true,
            chargeRate = 1,
            systemShowPercent = false
        )
        val options = BatteryIndicatorOptions(chargingColor = true)
        val resolved = BatteryIndicatorPolicy.resolveColors(state, options)

        assertEquals(BatteryIndicatorPolicy.COLOR_NORMAL_CHARGING_FILL, resolved.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, resolved.fillAlpha)
    }

    @Test
    fun `fast charging uses bright purple fill color`() {
        val state = BatteryIndicatorState(
            level = 65,
            isCharging = true,
            chargeRate = 3,
            systemShowPercent = false
        )
        val options = BatteryIndicatorOptions(chargingColor = true)
        val resolved = BatteryIndicatorPolicy.resolveColors(state, options)

        assertEquals(BatteryIndicatorPolicy.COLOR_FAST_CHARGING_FILL, resolved.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, resolved.fillAlpha)
    }

    @Test
    fun `charging color reduces fill alpha only when inner percentage is shown`() {
        val stateWithInnerPercent = BatteryIndicatorState(
            level = 50,
            isCharging = true,
            chargeRate = 3,
            systemShowPercent = true
        )
        val innerResolved = BatteryIndicatorPolicy.resolveColors(
            stateWithInnerPercent,
            BatteryIndicatorOptions(externalPercent = false, chargingColor = true)
        )
        assertEquals(BatteryIndicatorPolicy.ALPHA_FILL_CHARGING_INNER_PERCENT, innerResolved.fillAlpha)

        val externalResolved = BatteryIndicatorPolicy.resolveColors(
            stateWithInnerPercent,
            BatteryIndicatorOptions(externalPercent = true, chargingColor = true)
        )
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, externalResolved.fillAlpha)
    }

    @Test
    fun `non-charging states match stock power save, low battery, and theme alphas`() {
        val options = BatteryIndicatorOptions(externalPercent = true, chargingColor = true)

        val lowBattery = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 12, isCharging = false),
            options
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_LOW_BATTERY_FILL, lowBattery.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, lowBattery.fillAlpha)

        val powerSave = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 50, isCharging = false, isPowerSave = true),
            options
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_POWER_SAVE_FILL, powerSave.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, powerSave.fillAlpha)

        val lightBarNormal = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 80, isCharging = false, isBlackTheme = true),
            options
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, lightBarNormal.frameColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAME_LIGHT_BAR, lightBarNormal.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, lightBarNormal.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FILL_PURE_LIGHT_BAR, lightBarNormal.fillAlpha)
    }

    @Test
    fun `health frame overrides frame color while preserving charging fill color`() {
        val state = BatteryIndicatorState(
            level = 80,
            isCharging = true,
            chargeRate = 2,
            isHealthFrameActive = true
        )
        val options = BatteryIndicatorOptions(chargingColor = true)
        val resolved = BatteryIndicatorPolicy.resolveColors(state, options)

        assertEquals(BatteryIndicatorPolicy.COLOR_HEALTH_FRAME, resolved.frameColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, resolved.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.COLOR_NORMAL_CHARGING_FILL, resolved.fillColor)
    }

    @Test
    fun `frameless style uses translucent container on dark and light bars and ignores opaque health frame`() {
        val options = BatteryIndicatorOptions(style = BatteryIndicatorStyle.FRAMELESS)

        val darkBar = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 75, isBlackTheme = false, isHealthFrameActive = true),
            options
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_WHITE, darkBar.frameColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_CONTAINER_DARK_BAR, darkBar.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.COLOR_WHITE, darkBar.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FILL_PURE_DARK_BAR, darkBar.fillAlpha)

        val lightBar = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 75, isBlackTheme = true),
            options
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, lightBar.frameColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_CONTAINER_LIGHT_BAR, lightBar.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, lightBar.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FILL_PURE_LIGHT_BAR, lightBar.fillAlpha)
    }

    @Test
    fun `frameless style dims container and fill when inner percentage text is shown`() {
        val resolved = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 60, isBlackTheme = false, systemShowPercent = true),
            BatteryIndicatorOptions(externalPercent = false, style = BatteryIndicatorStyle.FRAMELESS)
        )
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_CONTAINER_INNER_PERCENT, resolved.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_FILL_INNER_PERCENT, resolved.fillAlpha)
    }

    @Test
    fun `stock drawable fallback triggers on zero non-charging or unknown states`() {
        assertTrue(
            BatteryIndicatorPolicy.shouldUseStockDrawable(
                level = 0,
                isCharging = false,
                isStateUnknown = false,
                isVirtualBattery = false
            )
        )
        assertFalse(
            BatteryIndicatorPolicy.shouldUseStockDrawable(
                level = 0,
                isCharging = true,
                isStateUnknown = false,
                isVirtualBattery = false
            )
        )
        assertTrue(
            BatteryIndicatorPolicy.shouldUseStockDrawable(
                level = 50,
                isCharging = true,
                isStateUnknown = true,
                isVirtualBattery = false
            )
        )
    }
}
