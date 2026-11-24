package com.qimian233.ztool.hook.modules.systemui.statusbar

// See docs/research/systemui_battery_indicator.md

/** Visual style of the status bar battery indicator. */
enum class BatteryIndicatorStyle(val prefValue: String) {
    DEFAULT_FRAMED("default_framed"),
    FRAMELESS("frameless"),
    IOS_STYLE("ios_style"),
    VERTICAL("vertical"),
    RING("ring"),
    PIE("pie"),
    SEGMENTED("segmented");

    val isFilledContainer: Boolean
        get() = this == FRAMELESS || this == IOS_STYLE || this == VERTICAL || this == PIE

    val supportsInnerPercent: Boolean
        get() = this != SEGMENTED

    companion object {
        /** Parses a persisted [prefValue], falling back to [DEFAULT_FRAMED]. */
        fun fromPref(value: String?): BatteryIndicatorStyle =
            value?.let { stored -> entries.firstOrNull { it.prefValue == stored } } ?: DEFAULT_FRAMED
    }
}

/** Charging animation style for [BatteryIndicatorStyle.SEGMENTED]. */
enum class SegmentedChargingStyle(val prefValue: String) {
    FILL_FROM_EMPTY("fill_from_empty"),
    BLINK_CURRENT("blink_current");

    companion object {
        /** Parses a persisted [prefValue], falling back to [FILL_FROM_EMPTY]. */
        fun fromPref(value: String?): SegmentedChargingStyle =
            value?.let { stored -> entries.firstOrNull { it.prefValue == stored } }
                ?: FILL_FROM_EMPTY
    }
}

/** User-configured options for the status bar battery indicator hook. */
data class BatteryIndicatorOptions(
    val enabled: Boolean = false,
    val externalPercent: Boolean = false,
    val chargingColor: Boolean = false,
    val performanceModeColor: Boolean = false,
    val percentBold: Boolean = false,
    val style: BatteryIndicatorStyle = BatteryIndicatorStyle.DEFAULT_FRAMED,
    val segmentedClassicColor: Boolean = true,
    val segmentedChargingStyle: SegmentedChargingStyle = SegmentedChargingStyle.FILL_FROM_EMPTY
)

/** Snapshot of host battery state used to render the battery indicator. */
data class BatteryIndicatorState(
    val level: Int = 100,
    val isCharging: Boolean = false,
    val chargeRate: Int = 0,
    val isPowerSave: Boolean = false,
    val isHighPerformance: Boolean = false,
    val isBlackTheme: Boolean = false,
    val isHealthFrameActive: Boolean = false,
    val systemShowPercent: Boolean = false
) {
    val clampedLevel: Int
        get() = level.coerceIn(0, 100)

    val isFastCharging: Boolean
        get() = chargeRate >= BatteryIndicatorPolicy.FAST_CHARGE_RATE_THRESHOLD
}

/** Resolved ARGB base colors (opaque) and 0..255 alphas for battery frame and level fill. */
data class BatteryResolvedColors(
    val frameColor: Int,
    val frameAlpha: Int,
    val fillColor: Int,
    val fillAlpha: Int
)

/** Inner percentage TextView sizing and alignment contract for a battery style. */
data class InnerPercentLayoutSpec(
    val symmetricPadding: Boolean,
    val includeFontPadding: Boolean,
    val baseSizeScale: Float,
    val maxWidthDp: Float?,
    val centerOffsetYDp: Float
)

/** Pure policy rules for status bar battery indicator colors and fallback conditions. */
object BatteryIndicatorPolicy {
    // ZuiBatteryManager chargerRate >= 3 indicates fast/turbo charging across SystemUI.
    const val FAST_CHARGE_RATE_THRESHOLD = 3
    const val LOW_BATTERY_MAX_LEVEL = 15

    // Game Assistant Item$Mode values in Settings.Global["game_helper_game_mode"].
    const val GAME_MODE_RAMPAGE = 2
    const val GAME_MODE_PERFORMANCE = 8
    const val GAME_MODE_SUPER_GOD = 16

    const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    const val COLOR_BLACK = 0xFF000000.toInt()
    const val COLOR_HEALTH_FRAME = 0xFF18AD74.toInt()
    const val COLOR_POWER_SAVE_FILL = 0xFFCC8E47.toInt()
    const val COLOR_LOW_BATTERY_FILL = 0xFFFF0000.toInt()
    const val COLOR_NORMAL_CHARGING_FILL = 0xFF34C759.toInt()
    const val COLOR_FAST_CHARGING_FILL = 0xFFBF5AF2.toInt()
    const val COLOR_HIGH_PERFORMANCE_FILL = 0xFF0A84FF.toInt()
    const val COLOR_SEGMENTED_CLASSIC_FILL = COLOR_NORMAL_CHARGING_FILL

    const val SEGMENTED_CELL_COUNT = 4
    private const val SEGMENT_1_MAX_LEVEL = 25
    private const val SEGMENT_2_MAX_LEVEL = 50
    private const val SEGMENT_3_MAX_LEVEL = 75

    const val ALPHA_OPAQUE = 255
    const val ALPHA_FRAME_LIGHT_BAR = 179 // 0.7f on light status bar (mIsBlackTheme = true)
    const val ALPHA_FRAME_DARK_BAR = 204 // 0.8f on dark status bar (mIsBlackTheme = false)
    const val ALPHA_FRAMELESS_CONTAINER_LIGHT_BAR = 64 // 0.25f dim container on light bar
    const val ALPHA_FRAMELESS_CONTAINER_DARK_BAR = 77 // 0.30f dim container on dark bar
    const val ALPHA_FRAMELESS_CONTAINER_INNER_PERCENT = 38 // 0.15f container behind inner text
    const val ALPHA_FRAMELESS_CONTAINER_LIGHT_INNER_PERCENT = 115 // 0.45f container on light bar for white text
    const val ALPHA_FILL_INNER_PERCENT = 51 // 0.2f when percentage text overlays the icon
    const val ALPHA_FRAMELESS_FILL_INNER_PERCENT = 64 // 0.25f fill over dim container behind text
    const val ALPHA_FRAMELESS_FILL_LIGHT_INNER_PERCENT = 217 // 0.85f dark fill on light bar for white text
    const val ALPHA_FILL_PURE_LIGHT_BAR = 204 // 0.8f pure fill on light status bar
    const val ALPHA_FILL_PURE_DARK_BAR = 230 // 0.9f pure fill on dark status bar
    const val ALPHA_FILL_CHARGING_INNER_PERCENT = 166 // 0.65f charging fill behind inner text

    val STOCK_INNER_PERCENT_LAYOUT = InnerPercentLayoutSpec(
        symmetricPadding = false,
        includeFontPadding = true,
        baseSizeScale = 1f,
        maxWidthDp = null,
        centerOffsetYDp = 0f
    )

    // Vertical body spans x=5.5..28.5 (9.2dp wide) and y=19.5..52 (center y=35.75 vs viewport 33.5 -> +0.9dp).
    private val VERTICAL_INNER_PERCENT_LAYOUT = InnerPercentLayoutSpec(
        symmetricPadding = true,
        includeFontPadding = false,
        baseSizeScale = 0.68f,
        maxWidthDp = 7.2f,
        centerOffsetYDp = 0.9f
    )

    // Ring diameter is 36u and hollow inner diameter is 24.92u (9.97dp); cap width inside inscribed box.
    private val RING_INNER_PERCENT_LAYOUT = InnerPercentLayoutSpec(
        symmetricPadding = true,
        includeFontPadding = false,
        baseSizeScale = 0.68f,
        maxWidthDp = 7.2f,
        centerOffsetYDp = 0f
    )

    private val PIE_INNER_PERCENT_LAYOUT = InnerPercentLayoutSpec(
        symmetricPadding = true,
        includeFontPadding = false,
        baseSizeScale = 0.84f,
        maxWidthDp = 10.4f,
        centerOffsetYDp = 0f
    )

    /** Resolves frame and level-fill colors and alphas for [state] and [options]. */
    fun resolveColors(
        state: BatteryIndicatorState,
        options: BatteryIndicatorOptions
    ): BatteryResolvedColors {
        val baseMonochrome = if (state.isBlackTheme) COLOR_BLACK else COLOR_WHITE
        val hasInnerPercent =
            state.systemShowPercent && !options.externalPercent && options.style.supportsInnerPercent
        val isFilledContainer = options.style.isFilledContainer
        val isHollowRing = options.style == BatteryIndicatorStyle.RING

        val frameColor: Int
        val frameAlpha: Int
        if (isFilledContainer) {
            frameColor = baseMonochrome
            frameAlpha = when {
                hasInnerPercent && state.isBlackTheme -> ALPHA_FRAMELESS_CONTAINER_LIGHT_INNER_PERCENT
                hasInnerPercent -> ALPHA_FRAMELESS_CONTAINER_INNER_PERCENT
                state.isBlackTheme -> ALPHA_FRAMELESS_CONTAINER_LIGHT_BAR
                else -> ALPHA_FRAMELESS_CONTAINER_DARK_BAR
            }
        } else if (isHollowRing) {
            frameColor = baseMonochrome
            frameAlpha = if (state.isBlackTheme) {
                ALPHA_FRAMELESS_CONTAINER_LIGHT_BAR
            } else {
                ALPHA_FRAMELESS_CONTAINER_DARK_BAR
            }
        } else if (state.isHealthFrameActive) {
            frameColor = COLOR_HEALTH_FRAME
            frameAlpha = ALPHA_OPAQUE
        } else {
            frameColor = baseMonochrome
            frameAlpha = if (state.isBlackTheme) ALPHA_FRAME_LIGHT_BAR else ALPHA_FRAME_DARK_BAR
        }

        val fillColor: Int
        val fillAlpha: Int
        when {
            options.performanceModeColor && state.isHighPerformance -> {
                fillColor = COLOR_HIGH_PERFORMANCE_FILL
                fillAlpha = resolveAccentFillAlpha(
                    hasInnerPercent = hasInnerPercent,
                    isHollowRing = isHollowRing,
                    isFilledContainer = isFilledContainer,
                    isBlackTheme = state.isBlackTheme
                )
            }
            options.chargingColor && state.isCharging -> {
                fillColor = if (state.isFastCharging) {
                    COLOR_FAST_CHARGING_FILL
                } else {
                    COLOR_NORMAL_CHARGING_FILL
                }
                fillAlpha = resolveAccentFillAlpha(
                    hasInnerPercent = hasInnerPercent,
                    isHollowRing = isHollowRing,
                    isFilledContainer = isFilledContainer,
                    isBlackTheme = state.isBlackTheme
                )
            }
            state.isPowerSave -> {
                fillColor = COLOR_POWER_SAVE_FILL
                fillAlpha = ALPHA_OPAQUE
            }
            !state.isCharging && state.clampedLevel in 1..LOW_BATTERY_MAX_LEVEL -> {
                fillColor = COLOR_LOW_BATTERY_FILL
                fillAlpha = ALPHA_OPAQUE
            }
            else -> if (options.style == BatteryIndicatorStyle.SEGMENTED && options.segmentedClassicColor) {
                fillColor = COLOR_SEGMENTED_CLASSIC_FILL
                fillAlpha = ALPHA_OPAQUE
            } else {
                fillColor = baseMonochrome
                fillAlpha = when {
                    isHollowRing && state.isBlackTheme -> ALPHA_FILL_PURE_LIGHT_BAR
                    isHollowRing -> ALPHA_FILL_PURE_DARK_BAR
                    hasInnerPercent && isFilledContainer && state.isBlackTheme ->
                        ALPHA_FRAMELESS_FILL_LIGHT_INNER_PERCENT
                    hasInnerPercent && isFilledContainer -> ALPHA_FRAMELESS_FILL_INNER_PERCENT
                    hasInnerPercent -> ALPHA_FILL_INNER_PERCENT
                    state.isBlackTheme -> ALPHA_FILL_PURE_LIGHT_BAR
                    else -> ALPHA_FILL_PURE_DARK_BAR
                }
            }
        }

        return BatteryResolvedColors(
            frameColor = frameColor,
            frameAlpha = frameAlpha,
            fillColor = fillColor,
            fillAlpha = fillAlpha
        )
    }

    private fun resolveAccentFillAlpha(
        hasInnerPercent: Boolean,
        isHollowRing: Boolean,
        isFilledContainer: Boolean,
        isBlackTheme: Boolean
    ): Int = if (hasInnerPercent && !isHollowRing && !(isFilledContainer && isBlackTheme)) {
        ALPHA_FILL_CHARGING_INNER_PERCENT
    } else {
        ALPHA_OPAQUE
    }

    /** Returns true when Game Assistant's [gameMode] represents a high-performance state. */
    fun isHighPerformanceGameMode(gameMode: Int): Boolean =
        gameMode == GAME_MODE_RAMPAGE ||
            gameMode == GAME_MODE_PERFORMANCE ||
            gameMode == GAME_MODE_SUPER_GOD

    /**
     * Resolves the master toggle state, honoring [explicitEnabled] when persisted and
     * falling back to active legacy sub-options for unmigrated configurations.
     */
    fun resolveMasterEnabled(
        explicitEnabled: Boolean?,
        externalPercent: Boolean,
        chargingColor: Boolean,
        percentBold: Boolean,
        style: BatteryIndicatorStyle,
        performanceModeColor: Boolean = false
    ): Boolean = explicitEnabled ?: (
        externalPercent ||
            chargingColor ||
            performanceModeColor ||
            percentBold ||
            style != BatteryIndicatorStyle.DEFAULT_FRAMED
    )

    /** Returns the override text color for inner percentage digits, or null to keep stock color. */
    fun resolveInnerPercentTextColor(options: BatteryIndicatorOptions): Int? {
        if (!options.enabled || options.externalPercent || !options.style.isFilledContainer) return null
        return COLOR_WHITE
    }

    /** Resolves inner percentage text sizing and centering rules for [options]. */
    fun resolveInnerPercentLayoutSpec(options: BatteryIndicatorOptions): InnerPercentLayoutSpec {
        if (!options.enabled || options.externalPercent) return STOCK_INNER_PERCENT_LAYOUT
        return when (options.style) {
            BatteryIndicatorStyle.DEFAULT_FRAMED,
            BatteryIndicatorStyle.FRAMELESS,
            BatteryIndicatorStyle.IOS_STYLE,
            BatteryIndicatorStyle.SEGMENTED -> STOCK_INNER_PERCENT_LAYOUT
            BatteryIndicatorStyle.VERTICAL -> VERTICAL_INNER_PERCENT_LAYOUT
            BatteryIndicatorStyle.RING -> RING_INNER_PERCENT_LAYOUT
            BatteryIndicatorStyle.PIE -> PIE_INNER_PERCENT_LAYOUT
        }
    }

    /** Returns the target lit segment count (0..4) for [level] and [isCharging]. */
    fun resolveSegmentedTargetCount(level: Int, isCharging: Boolean): Int {
        val clamped = level.coerceIn(0, 100)
        if (clamped == 0 && !isCharging) return 0
        return when {
            clamped <= SEGMENT_1_MAX_LEVEL -> 1
            clamped <= SEGMENT_2_MAX_LEVEL -> 2
            clamped <= SEGMENT_3_MAX_LEVEL -> 3
            else -> SEGMENTED_CELL_COUNT
        }
    }

    /** Returns the number of segments currently lit (0..4) for the given animation [step]. */
    fun resolveSegmentedLitCount(
        level: Int,
        isCharging: Boolean,
        chargingStyle: SegmentedChargingStyle,
        step: Int
    ): Int {
        val target = resolveSegmentedTargetCount(level, isCharging)
        if (!isCharging || target == 0) return target
        val normalizedStep = if (step >= 0) step else 0
        return when (chargingStyle) {
            SegmentedChargingStyle.FILL_FROM_EMPTY -> normalizedStep % (target + 1)
            SegmentedChargingStyle.BLINK_CURRENT ->
                if (normalizedStep % 2 == 0) target else (target - 1).coerceAtLeast(0)
        }
    }

    /** Returns true when stock VectorDrawable should be kept instead of custom rendering. */
    fun shouldUseStockDrawable(
        level: Int,
        isCharging: Boolean,
        isStateUnknown: Boolean,
        isVirtualBattery: Boolean
    ): Boolean {
        if (isStateUnknown || isVirtualBattery) return true
        return level <= 0 && !isCharging
    }
}
