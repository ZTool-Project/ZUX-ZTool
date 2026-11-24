package com.qimian233.ztool.data.freeform

/** Shared constants and FULL-state screen layout geometry for freeform edge bubbles. */
object EdgeBubbleGeometry {

    const val SIDE_LEFT = 0
    const val SIDE_RIGHT = 1

    const val ICON_SIZE_DP = 48f
    const val INNER_MARGIN_DP = 8f
    const val SCREEN_MARGIN_DP = 30f
    const val FULL_HANG_DP = 24f
    const val CONTAINER_RADIUS_DP = 18f
    const val COLLAPSE_DP = 16f
    const val SQUIRCLE_WEIGHT = 3f
    const val ELEVATION_DP = 3.33f
    const val VERTICAL_CLAMP_MARGIN_DP = 8f

    const val HALF_ALPHA = 0.45f
    const val SHADOW_COLOR_INT = 0x38000000
    const val BG_LIGHT_INT = 0xFFF0F0F0.toInt()
    const val BG_DARK_INT = 0xFF444444.toInt()

    /** Pure-Kotlin integer screen bounds compatible with JVM unit tests. */
    data class IntRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val exactCenterX: Float get() = (left + right) / 2f
        val exactCenterY: Float get() = (top + bottom) / 2f
    }

    /** Screen-space layout metrics for an expanded (FULL) bubble on one display edge. */
    data class FullBubbleLayout(
        val side: Int,
        val iconPx: Int,
        val innerPx: Int,
        val screenMarginPx: Int,
        val fullHangPx: Int,
        val collapsePx: Int,
        val modeShiftPx: Int,
        val containerRect: IntRect,
        val iconRect: IntRect
    )

    /** Computes the FULL-state container and icon screen rects matching EdgeBubbleView. */
    fun computeFullLayout(
        side: Int,
        centerYRatio: Float,
        displayWidth: Int,
        displayHeight: Int,
        density: Float
    ): FullBubbleLayout {
        val safeSide = if (side == SIDE_RIGHT) SIDE_RIGHT else SIDE_LEFT
        val iconPx = (ICON_SIZE_DP * density).toInt().coerceAtLeast(1)
        val innerPx = (INNER_MARGIN_DP * density).toInt()
        val screenMarginPx = (SCREEN_MARGIN_DP * density).toInt()
        val fullHangPx = (FULL_HANG_DP * density).toInt()
        val containerWpx = iconPx + innerPx * 2 + screenMarginPx
        val containerHpx = iconPx + innerPx * 2
        val collapsePx = (COLLAPSE_DP * density).toInt()
        val modeShiftPx = containerWpx - collapsePx - fullHangPx

        val yLimit = (VERTICAL_CLAMP_MARGIN_DP * density).toInt()
        val rawCenterY = (displayHeight * centerYRatio).toInt()
        val maxTop = (displayHeight - containerHpx - yLimit).coerceAtLeast(yLimit)
        val containerTop = (rawCenterY - containerHpx / 2).coerceIn(yLimit, maxTop)
        val containerLeft = if (safeSide == SIDE_LEFT) {
            -fullHangPx
        } else {
            displayWidth - (containerWpx - fullHangPx)
        }
        val iconLeft = containerLeft + if (safeSide == SIDE_LEFT) {
            screenMarginPx + innerPx
        } else {
            innerPx
        }
        val iconTop = containerTop + innerPx

        return FullBubbleLayout(
            side = safeSide,
            iconPx = iconPx,
            innerPx = innerPx,
            screenMarginPx = screenMarginPx,
            fullHangPx = fullHangPx,
            collapsePx = collapsePx,
            modeShiftPx = modeShiftPx,
            containerRect = IntRect(
                left = containerLeft,
                top = containerTop,
                right = containerLeft + containerWpx,
                bottom = containerTop + containerHpx
            ),
            iconRect = IntRect(
                left = iconLeft,
                top = iconTop,
                right = iconLeft + iconPx,
                bottom = iconTop + iconPx
            )
        )
    }
}
