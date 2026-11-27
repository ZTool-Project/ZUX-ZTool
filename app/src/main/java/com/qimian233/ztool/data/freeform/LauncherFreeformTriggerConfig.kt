package com.qimian233.ztool.data.freeform

import com.qimian233.ztool.data.keys.PreferenceKeys

/** Shared bounds and coupled normalization for QuickStep freeform swipe trigger area. */
object LauncherFreeformTriggerConfig {
    const val TOP_PERCENT_MIN = 5
    const val TOP_PERCENT_MAX = 55
    val TOP_PERCENT_DEFAULT =
        PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_TOP_PERCENT.default

    const val SIDE_MARGIN_MIN = 0
    const val SIDE_MARGIN_MAX = 80
    const val MIN_HORIZONTAL_WIDTH_PERCENT = 20
    val LEFT_PERCENT_DEFAULT =
        PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_LEFT_PERCENT.default
    val RIGHT_PERCENT_DEFAULT =
        PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_RIGHT_PERCENT.default

    fun normalizeTopPercent(percent: Int): Int =
        percent.coerceIn(TOP_PERCENT_MIN, TOP_PERCENT_MAX)

    fun normalizeLeftPercent(left: Int, right: Int): Pair<Int, Int> {
        val clampedLeft = left.coerceIn(SIDE_MARGIN_MIN, SIDE_MARGIN_MAX)
        val maxRight = (100 - MIN_HORIZONTAL_WIDTH_PERCENT - clampedLeft).coerceAtLeast(SIDE_MARGIN_MIN)
        val clampedRight = right.coerceIn(SIDE_MARGIN_MIN, maxRight)
        return clampedLeft to clampedRight
    }

    fun normalizeRightPercent(left: Int, right: Int): Pair<Int, Int> {
        val clampedRight = right.coerceIn(SIDE_MARGIN_MIN, SIDE_MARGIN_MAX)
        val maxLeft = (100 - MIN_HORIZONTAL_WIDTH_PERCENT - clampedRight).coerceAtLeast(SIDE_MARGIN_MIN)
        val clampedLeft = left.coerceIn(SIDE_MARGIN_MIN, maxLeft)
        return clampedLeft to clampedRight
    }

    fun normalizeMargins(left: Int, right: Int): Pair<Int, Int> =
        normalizeLeftPercent(left, right)
}
