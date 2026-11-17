package com.qimian233.ztool.hook.modules.systemui.statusbar

// See docs/research/systemui_battery_indicator.md

/** Visual style of the status bar battery indicator. */
enum class BatteryIndicatorStyle(val prefValue: String) {
    DEFAULT_FRAMED("default_framed"),
    FRAMELESS("frameless"),
    IOS_STYLE("ios_style");

    val isFilledContainer: Boolean
        get() = this == FRAMELESS || this == IOS_STYLE

    companion object {
        /** Parses a persisted [prefValue], falling back to [DEFAULT_FRAMED]. */
        fun fromPref(value: String?): BatteryIndicatorStyle =
            value?.let { stored -> entries.firstOrNull { it.prefValue == stored } } ?: DEFAULT_FRAMED
    }
}

/** User-configured options for the status bar battery indicator hook. */
data class BatteryIndicatorOptions(
    val enabled: Boolean = false,
    val externalPercent: Boolean = false,
    val chargingColor: Boolean = false,
    val percentBold: Boolean = false,
    val style: BatteryIndicatorStyle = BatteryIndicatorStyle.DEFAULT_FRAMED
)

/** Snapshot of host battery state used to render the battery indicator. */
data class BatteryIndicatorState(
    val level: Int = 100,
    val isCharging: Boolean = false,
    val chargeRate: Int = 0,
    val isPowerSave: Boolean = false,
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

/** Pure policy rules for status bar battery indicator colors and fallback conditions. */
object BatteryIndicatorPolicy {
    // ZuiBatteryManager chargerRate >= 3 indicates fast/turbo charging across SystemUI.
    const val FAST_CHARGE_RATE_THRESHOLD = 3
    const val LOW_BATTERY_MAX_LEVEL = 15

    const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    const val COLOR_BLACK = 0xFF000000.toInt()
    const val COLOR_HEALTH_FRAME = 0xFF18AD74.toInt()
    const val COLOR_POWER_SAVE_FILL = 0xFFCC8E47.toInt()
    const val COLOR_LOW_BATTERY_FILL = 0xFFFF0000.toInt()
    const val COLOR_NORMAL_CHARGING_FILL = 0xFF34C759.toInt()
    const val COLOR_FAST_CHARGING_FILL = 0xFFBF5AF2.toInt()

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

    /** Resolves frame and level-fill colors and alphas for [state] and [options]. */
    fun resolveColors(
        state: BatteryIndicatorState,
        options: BatteryIndicatorOptions
    ): BatteryResolvedColors {
        val baseMonochrome = if (state.isBlackTheme) COLOR_BLACK else COLOR_WHITE
        val hasInnerPercent = state.systemShowPercent && !options.externalPercent
        val isFilledContainer = options.style.isFilledContainer

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
            options.chargingColor && state.isCharging -> {
                fillColor = if (state.isFastCharging) {
                    COLOR_FAST_CHARGING_FILL
                } else {
                    COLOR_NORMAL_CHARGING_FILL
                }
                fillAlpha = if (hasInnerPercent && !(isFilledContainer && state.isBlackTheme)) {
                    ALPHA_FILL_CHARGING_INNER_PERCENT
                } else {
                    ALPHA_OPAQUE
                }
            }
            state.isPowerSave -> {
                fillColor = COLOR_POWER_SAVE_FILL
                fillAlpha = ALPHA_OPAQUE
            }
            !state.isCharging && state.clampedLevel in 1..LOW_BATTERY_MAX_LEVEL -> {
                fillColor = COLOR_LOW_BATTERY_FILL
                fillAlpha = ALPHA_OPAQUE
            }
            else -> {
                fillColor = baseMonochrome
                fillAlpha = when {
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

    /**
     * Resolves the master toggle state, honoring [explicitEnabled] when persisted and
     * falling back to active legacy sub-options for unmigrated configurations.
     */
    fun resolveMasterEnabled(
        explicitEnabled: Boolean?,
        externalPercent: Boolean,
        chargingColor: Boolean,
        percentBold: Boolean,
        style: BatteryIndicatorStyle
    ): Boolean = explicitEnabled ?: (
        externalPercent ||
            chargingColor ||
            percentBold ||
            style != BatteryIndicatorStyle.DEFAULT_FRAMED
    )

    /** Returns the override text color for inner percentage digits, or null to keep stock color. */
    fun resolveInnerPercentTextColor(options: BatteryIndicatorOptions): Int? {
        if (!options.enabled || options.externalPercent || !options.style.isFilledContainer) return null
        return COLOR_WHITE
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
