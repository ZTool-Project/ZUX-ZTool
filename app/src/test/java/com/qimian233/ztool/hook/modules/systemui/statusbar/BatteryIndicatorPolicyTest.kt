package com.qimian233.ztool.hook.modules.systemui.statusbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryIndicatorPolicyTest {

    @Test
    fun `master enabled flag controls renderer activation independently of sub-options`() {
        assertFalse(BatteryIndicatorOptions().enabled)
        assertTrue(BatteryIndicatorOptions(enabled = true).enabled)
        assertFalse(
            BatteryIndicatorOptions(
                enabled = false,
                externalPercent = true,
                chargingColor = true,
                percentBold = true,
                style = BatteryIndicatorStyle.IOS_STYLE
            ).enabled
        )
    }

    @Test
    fun `resolveMasterEnabled honors explicit flag and falls back to legacy sub-options`() {
        assertFalse(
            BatteryIndicatorPolicy.resolveMasterEnabled(
                explicitEnabled = false,
                externalPercent = true,
                chargingColor = true,
                percentBold = true,
                style = BatteryIndicatorStyle.IOS_STYLE
            )
        )
        assertTrue(
            BatteryIndicatorPolicy.resolveMasterEnabled(
                explicitEnabled = true,
                externalPercent = false,
                chargingColor = false,
                percentBold = false,
                style = BatteryIndicatorStyle.DEFAULT_FRAMED
            )
        )
        assertFalse(
            BatteryIndicatorPolicy.resolveMasterEnabled(
                explicitEnabled = null,
                externalPercent = false,
                chargingColor = false,
                percentBold = false,
                style = BatteryIndicatorStyle.DEFAULT_FRAMED
            )
        )
        assertTrue(
            BatteryIndicatorPolicy.resolveMasterEnabled(
                explicitEnabled = null,
                externalPercent = true,
                chargingColor = false,
                percentBold = false,
                style = BatteryIndicatorStyle.DEFAULT_FRAMED
            )
        )
        assertTrue(
            BatteryIndicatorPolicy.resolveMasterEnabled(
                explicitEnabled = null,
                externalPercent = false,
                chargingColor = false,
                percentBold = false,
                style = BatteryIndicatorStyle.FRAMELESS
            )
        )
    }

    @Test
    fun `style fromPref parses valid values and degrades unknown values to default`() {
        assertEquals(BatteryIndicatorStyle.DEFAULT_FRAMED, BatteryIndicatorStyle.fromPref("default_framed"))
        assertEquals(BatteryIndicatorStyle.FRAMELESS, BatteryIndicatorStyle.fromPref("frameless"))
        assertEquals(BatteryIndicatorStyle.IOS_STYLE, BatteryIndicatorStyle.fromPref("ios_style"))
        assertEquals(BatteryIndicatorStyle.VERTICAL, BatteryIndicatorStyle.fromPref("vertical"))
        assertEquals(BatteryIndicatorStyle.RING, BatteryIndicatorStyle.fromPref("ring"))
        assertEquals(BatteryIndicatorStyle.PIE, BatteryIndicatorStyle.fromPref("pie"))
        assertEquals(BatteryIndicatorStyle.SEGMENTED, BatteryIndicatorStyle.fromPref("segmented"))
        assertEquals(BatteryIndicatorStyle.DEFAULT_FRAMED, BatteryIndicatorStyle.fromPref("unknown"))
        assertEquals(BatteryIndicatorStyle.DEFAULT_FRAMED, BatteryIndicatorStyle.fromPref(null))
    }

    @Test
    fun `segmented charging style fromPref parses valid values and falls back to fill from empty`() {
        assertEquals(
            SegmentedChargingStyle.FILL_FROM_EMPTY,
            SegmentedChargingStyle.fromPref("fill_from_empty")
        )
        assertEquals(
            SegmentedChargingStyle.BLINK_CURRENT,
            SegmentedChargingStyle.fromPref("blink_current")
        )
        assertEquals(
            SegmentedChargingStyle.FILL_FROM_EMPTY,
            SegmentedChargingStyle.fromPref("unknown")
        )
        assertEquals(
            SegmentedChargingStyle.FILL_FROM_EMPTY,
            SegmentedChargingStyle.fromPref(null)
        )
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
    fun `filled container styles use translucent container on dark and light bars and ignore opaque health frame`() {
        for (style in listOf(
            BatteryIndicatorStyle.FRAMELESS,
            BatteryIndicatorStyle.IOS_STYLE,
            BatteryIndicatorStyle.VERTICAL,
            BatteryIndicatorStyle.PIE
        )) {
            val options = BatteryIndicatorOptions(style = style)

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
    }

    @Test
    fun `filled container styles adjust container and fill for light inner percentage text`() {
        for (style in listOf(
            BatteryIndicatorStyle.FRAMELESS,
            BatteryIndicatorStyle.IOS_STYLE,
            BatteryIndicatorStyle.VERTICAL,
            BatteryIndicatorStyle.PIE
        )) {
            val darkBar = BatteryIndicatorPolicy.resolveColors(
                BatteryIndicatorState(level = 60, isBlackTheme = false, systemShowPercent = true),
                BatteryIndicatorOptions(externalPercent = false, style = style)
            )
            assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_CONTAINER_INNER_PERCENT, darkBar.frameAlpha)
            assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_FILL_INNER_PERCENT, darkBar.fillAlpha)

            val lightBar = BatteryIndicatorPolicy.resolveColors(
                BatteryIndicatorState(level = 60, isBlackTheme = true, systemShowPercent = true),
                BatteryIndicatorOptions(externalPercent = false, style = style)
            )
            assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_CONTAINER_LIGHT_INNER_PERCENT, lightBar.frameAlpha)
            assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_FILL_LIGHT_INNER_PERCENT, lightBar.fillAlpha)

            val lightBarCharging = BatteryIndicatorPolicy.resolveColors(
                BatteryIndicatorState(
                    level = 60,
                    isCharging = true,
                    chargeRate = 3,
                    isBlackTheme = true,
                    systemShowPercent = true
                ),
                BatteryIndicatorOptions(externalPercent = false, chargingColor = true, style = style)
            )
            assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, lightBarCharging.fillAlpha)
        }
    }

    @Test
    fun `ring style uses translucent track and keeps full arc brightness with inner percent`() {
        val options = BatteryIndicatorOptions(externalPercent = false, style = BatteryIndicatorStyle.RING)

        val darkBar = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 65, isBlackTheme = false, systemShowPercent = true, isHealthFrameActive = true),
            options
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_WHITE, darkBar.frameColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_CONTAINER_DARK_BAR, darkBar.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.COLOR_WHITE, darkBar.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FILL_PURE_DARK_BAR, darkBar.fillAlpha)

        val lightBar = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 65, isBlackTheme = true, systemShowPercent = true),
            options
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, lightBar.frameColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAMELESS_CONTAINER_LIGHT_BAR, lightBar.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, lightBar.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FILL_PURE_LIGHT_BAR, lightBar.fillAlpha)

        val chargingWithInnerPercent = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 65, isCharging = true, chargeRate = 3, systemShowPercent = true),
            BatteryIndicatorOptions(externalPercent = false, chargingColor = true, style = BatteryIndicatorStyle.RING)
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_FAST_CHARGING_FILL, chargingWithInnerPercent.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, chargingWithInnerPercent.fillAlpha)
    }

    @Test
    fun `inner percentage text uses light color only when enabled with internal filled container styles`() {
        assertNull(
            BatteryIndicatorPolicy.resolveInnerPercentTextColor(
                BatteryIndicatorOptions(
                    enabled = false,
                    externalPercent = false,
                    style = BatteryIndicatorStyle.IOS_STYLE
                )
            )
        )
        assertNull(
            BatteryIndicatorPolicy.resolveInnerPercentTextColor(
                BatteryIndicatorOptions(
                    enabled = true,
                    externalPercent = false,
                    style = BatteryIndicatorStyle.DEFAULT_FRAMED
                )
            )
        )
        assertNull(
            BatteryIndicatorPolicy.resolveInnerPercentTextColor(
                BatteryIndicatorOptions(
                    enabled = true,
                    externalPercent = false,
                    style = BatteryIndicatorStyle.RING
                )
            )
        )
        assertNull(
            BatteryIndicatorPolicy.resolveInnerPercentTextColor(
                BatteryIndicatorOptions(
                    enabled = true,
                    externalPercent = true,
                    style = BatteryIndicatorStyle.IOS_STYLE
                )
            )
        )
        for (style in listOf(
            BatteryIndicatorStyle.FRAMELESS,
            BatteryIndicatorStyle.IOS_STYLE,
            BatteryIndicatorStyle.VERTICAL,
            BatteryIndicatorStyle.PIE
        )) {
            assertEquals(
                BatteryIndicatorPolicy.COLOR_WHITE,
                BatteryIndicatorPolicy.resolveInnerPercentTextColor(
                    BatteryIndicatorOptions(
                        enabled = true,
                        externalPercent = false,
                        style = style
                    )
                )
            )
        }
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

    @Test
    fun `inner percent layout keeps stock spec when disabled external or horizontal`() {
        val stock = BatteryIndicatorPolicy.STOCK_INNER_PERCENT_LAYOUT
        assertEquals(
            stock,
            BatteryIndicatorPolicy.resolveInnerPercentLayoutSpec(
                BatteryIndicatorOptions(enabled = false, style = BatteryIndicatorStyle.RING)
            )
        )
        assertEquals(
            stock,
            BatteryIndicatorPolicy.resolveInnerPercentLayoutSpec(
                BatteryIndicatorOptions(
                    enabled = true,
                    externalPercent = true,
                    style = BatteryIndicatorStyle.VERTICAL
                )
            )
        )
        for (style in listOf(
            BatteryIndicatorStyle.DEFAULT_FRAMED,
            BatteryIndicatorStyle.FRAMELESS,
            BatteryIndicatorStyle.IOS_STYLE
        )) {
            assertEquals(
                stock,
                BatteryIndicatorPolicy.resolveInnerPercentLayoutSpec(
                    BatteryIndicatorOptions(enabled = true, externalPercent = false, style = style)
                )
            )
        }
    }

    @Test
    fun `inner percent layout uses symmetric centering and bounded widths for vertical ring and pie`() {
        val vertical = BatteryIndicatorPolicy.resolveInnerPercentLayoutSpec(
            BatteryIndicatorOptions(enabled = true, style = BatteryIndicatorStyle.VERTICAL)
        )
        assertTrue(vertical.symmetricPadding)
        assertFalse(vertical.includeFontPadding)
        assertTrue(vertical.baseSizeScale < 1f)
        assertTrue((vertical.maxWidthDp ?: 0f) in 7f..9f)
        assertTrue(vertical.centerOffsetYDp > 0f)

        val ring = BatteryIndicatorPolicy.resolveInnerPercentLayoutSpec(
            BatteryIndicatorOptions(enabled = true, style = BatteryIndicatorStyle.RING)
        )
        assertTrue(ring.symmetricPadding)
        assertFalse(ring.includeFontPadding)
        assertTrue(ring.baseSizeScale < 1f)
        assertTrue((ring.maxWidthDp ?: 0f) in 7f..9f)
        assertEquals(0f, ring.centerOffsetYDp)

        val pie = BatteryIndicatorPolicy.resolveInnerPercentLayoutSpec(
            BatteryIndicatorOptions(enabled = true, style = BatteryIndicatorStyle.PIE)
        )
        assertTrue(pie.symmetricPadding)
        assertFalse(pie.includeFontPadding)
        assertTrue((pie.maxWidthDp ?: 0f) > (ring.maxWidthDp ?: 0f))
        assertEquals(0f, pie.centerOffsetYDp)
    }

    @Test
    fun `segmented style disallows inner percent and toggles classic green vs monochrome fill`() {
        assertFalse(BatteryIndicatorStyle.SEGMENTED.supportsInnerPercent)
        assertTrue(BatteryIndicatorStyle.DEFAULT_FRAMED.supportsInnerPercent)

        val classicDark = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 65, isBlackTheme = false, systemShowPercent = true),
            BatteryIndicatorOptions(
                enabled = true,
                externalPercent = false,
                style = BatteryIndicatorStyle.SEGMENTED,
                segmentedClassicColor = true
            )
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_WHITE, classicDark.frameColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FRAME_DARK_BAR, classicDark.frameAlpha)
        assertEquals(BatteryIndicatorPolicy.COLOR_SEGMENTED_CLASSIC_FILL, classicDark.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_OPAQUE, classicDark.fillAlpha)

        val monoLight = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 65, isBlackTheme = true, systemShowPercent = true),
            BatteryIndicatorOptions(
                enabled = true,
                externalPercent = false,
                style = BatteryIndicatorStyle.SEGMENTED,
                segmentedClassicColor = false
            )
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, monoLight.frameColor)
        assertEquals(BatteryIndicatorPolicy.COLOR_BLACK, monoLight.fillColor)
        assertEquals(BatteryIndicatorPolicy.ALPHA_FILL_PURE_LIGHT_BAR, monoLight.fillAlpha)

        val lowBattery = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 10, isCharging = false),
            BatteryIndicatorOptions(
                style = BatteryIndicatorStyle.SEGMENTED,
                segmentedClassicColor = true
            )
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_LOW_BATTERY_FILL, lowBattery.fillColor)

        val powerSave = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 60, isCharging = false, isPowerSave = true),
            BatteryIndicatorOptions(
                style = BatteryIndicatorStyle.SEGMENTED,
                segmentedClassicColor = true
            )
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_POWER_SAVE_FILL, powerSave.fillColor)

        val fastCharge = BatteryIndicatorPolicy.resolveColors(
            BatteryIndicatorState(level = 60, isCharging = true, chargeRate = 3),
            BatteryIndicatorOptions(
                chargingColor = true,
                style = BatteryIndicatorStyle.SEGMENTED,
                segmentedClassicColor = true
            )
        )
        assertEquals(BatteryIndicatorPolicy.COLOR_FAST_CHARGING_FILL, fastCharge.fillColor)

        assertNull(
            BatteryIndicatorPolicy.resolveInnerPercentTextColor(
                BatteryIndicatorOptions(
                    enabled = true,
                    externalPercent = false,
                    style = BatteryIndicatorStyle.SEGMENTED
                )
            )
        )
    }

    @Test
    fun `segmented level thresholds and charging animations resolve expected lit counts`() {
        assertEquals(0, BatteryIndicatorPolicy.resolveSegmentedTargetCount(0, isCharging = false))
        assertEquals(1, BatteryIndicatorPolicy.resolveSegmentedTargetCount(0, isCharging = true))
        assertEquals(1, BatteryIndicatorPolicy.resolveSegmentedTargetCount(1, isCharging = false))
        assertEquals(1, BatteryIndicatorPolicy.resolveSegmentedTargetCount(25, isCharging = false))
        assertEquals(2, BatteryIndicatorPolicy.resolveSegmentedTargetCount(26, isCharging = false))
        assertEquals(2, BatteryIndicatorPolicy.resolveSegmentedTargetCount(50, isCharging = false))
        assertEquals(3, BatteryIndicatorPolicy.resolveSegmentedTargetCount(51, isCharging = false))
        assertEquals(3, BatteryIndicatorPolicy.resolveSegmentedTargetCount(75, isCharging = false))
        assertEquals(4, BatteryIndicatorPolicy.resolveSegmentedTargetCount(76, isCharging = false))
        assertEquals(4, BatteryIndicatorPolicy.resolveSegmentedTargetCount(100, isCharging = false))

        assertEquals(
            listOf(0, 1, 2, 3, 0, 1),
            (0..5).map { step ->
                BatteryIndicatorPolicy.resolveSegmentedLitCount(
                    level = 60,
                    isCharging = true,
                    chargingStyle = SegmentedChargingStyle.FILL_FROM_EMPTY,
                    step = step
                )
            }
        )

        assertEquals(
            listOf(3, 2, 3, 2),
            (0..3).map { step ->
                BatteryIndicatorPolicy.resolveSegmentedLitCount(
                    level = 60,
                    isCharging = true,
                    chargingStyle = SegmentedChargingStyle.BLINK_CURRENT,
                    step = step
                )
            }
        )
        assertEquals(
            listOf(1, 0, 1, 0),
            (0..3).map { step ->
                BatteryIndicatorPolicy.resolveSegmentedLitCount(
                    level = 10,
                    isCharging = true,
                    chargingStyle = SegmentedChargingStyle.BLINK_CURRENT,
                    step = step
                )
            }
        )
    }
}
