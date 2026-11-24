package com.qimian233.ztool.hook.modules.systemui

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Nonlinear curves and surface transform geometry for freeform window animations. */
object FreeformAnimCurves {

    const val OPEN_START_SCALE = 0.72f
    const val CLOSE_END_SCALE = 0.72f
    const val EDGE_DOCK_END_SCALE = 0.16f

    const val OPEN_ANIM_DURATION_MS = 680L
    const val OPEN_ALPHA_DURATION_MS = 480L
    const val CLOSE_SCALE_DURATION_MS = 560L
    const val CLOSE_ALPHA_DURATION_MS = 510L
    const val ICON_EXPAND_DURATION_MS = 650L
    const val EDGE_DOCK_DURATION_MS = 540L
    const val SNAP_BACK_DURATION_MS = 560L
    const val RESTORE_DURATION_MS = 680L

    const val OPEN_SPRING_DAMPING = 0.78f
    const val OPEN_SPRING_OMEGA = 15.5f
    const val RESTORE_SPRING_DAMPING = 0.78f
    const val RESTORE_SPRING_OMEGA = 15.0f
    const val SNAP_BACK_SPRING_DAMPING = 0.88f
    const val SNAP_BACK_SPRING_OMEGA = 17.5f

    const val CLOSE_SCALE_CTRL_X1 = 0.35f
    const val CLOSE_SCALE_CTRL_Y1 = 0.0f
    const val CLOSE_SCALE_CTRL_X2 = 0.22f
    const val CLOSE_SCALE_CTRL_Y2 = 1.0f

    const val CLOSE_ALPHA_CTRL_X1 = 0.40f
    const val CLOSE_ALPHA_CTRL_Y1 = 0.0f
    const val CLOSE_ALPHA_CTRL_X2 = 1.0f
    const val CLOSE_ALPHA_CTRL_Y2 = 1.0f

    const val OPEN_ALPHA_CTRL_X1 = 0.0f
    const val OPEN_ALPHA_CTRL_Y1 = 0.0f
    const val OPEN_ALPHA_CTRL_X2 = 0.20f
    const val OPEN_ALPHA_CTRL_Y2 = 1.0f

    const val ICON_EXPAND_CTRL_X1 = 0.18f
    const val ICON_EXPAND_CTRL_Y1 = 0.90f
    const val ICON_EXPAND_CTRL_X2 = 0.16f
    const val ICON_EXPAND_CTRL_Y2 = 1.0f

    const val EDGE_DOCK_CTRL_X1 = 0.32f
    const val EDGE_DOCK_CTRL_Y1 = 0.0f
    const val EDGE_DOCK_CTRL_X2 = 0.20f
    const val EDGE_DOCK_CTRL_Y2 = 1.0f

    const val EDGE_TOUCH_ZONE_DP = 32f
    const val EDGE_ZONE_MIN_OVERHANG_RATIO = 0.25f
    const val EDGE_FLING_VELOCITY_DP = 550f
    const val EDGE_FLING_MIN_OVERHANG_RATIO = 0.15f
    const val RESTORE_BUBBLE_HIDE_PROGRESS = 0.30f

    const val EDGE_DOCK_WINDOW_FADE_START = 0.25f
    const val EDGE_DOCK_WINDOW_FADE_END = 0.72f
    const val EDGE_DOCK_OVERLAY_FADE_START = 0.20f
    const val EDGE_DOCK_OVERLAY_FADE_END = 0.68f
    const val EDGE_DOCK_OVERLAY_START_SCALE = 1.85f
    const val EDGE_DOCK_PAD_EXTEND_START = 0.55f
    const val EDGE_DOCK_PAD_EXTEND_END = 1.00f

    data class TransformFrame(
        val posX: Float,
        val posY: Float,
        val scaleX: Float,
        val scaleY: Float
    )

    data class DockMorphFrame(
        val windowAlpha: Float,
        val overlayAlpha: Float,
        val overlayScale: Float,
        val padExtension: Float
    )

    class SpringInterpolator(
        private val dampingRatio: Float,
        private val omega: Float
    ) : Interpolator {
        override fun getInterpolation(input: Float): Float =
            evaluateSpring(input, dampingRatio, omega)
    }

    /** Normalized damped spring step response mapped onto [0, 1] time. */
    fun evaluateSpring(t: Float, dampingRatio: Float, omega: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val zeta = dampingRatio.coerceAtLeast(0.01f).toDouble()
        val w = omega.coerceAtLeast(0.01f).toDouble()

        fun raw(x: Double): Double {
            return if (zeta < 1.0) {
                val under = sqrt(1.0 - zeta * zeta)
                val wd = w * under
                1.0 - exp(-zeta * w * x) * (cos(wd * x) + (zeta / under) * sin(wd * x))
            } else {
                1.0 - exp(-w * x) * (1.0 + w * x)
            }
        }

        val endValue = raw(1.0)
        if (endValue == 0.0) return t
        return (raw(t.toDouble()) / endValue).toFloat()
    }

    fun openSpringInterpolator(): Interpolator =
        SpringInterpolator(OPEN_SPRING_DAMPING, OPEN_SPRING_OMEGA)

    fun restoreSpringInterpolator(): Interpolator =
        SpringInterpolator(RESTORE_SPRING_DAMPING, RESTORE_SPRING_OMEGA)

    fun snapBackSpringInterpolator(): Interpolator =
        SpringInterpolator(SNAP_BACK_SPRING_DAMPING, SNAP_BACK_SPRING_OMEGA)

    fun closeScaleInterpolator(): Interpolator = PathInterpolator(
        CLOSE_SCALE_CTRL_X1,
        CLOSE_SCALE_CTRL_Y1,
        CLOSE_SCALE_CTRL_X2,
        CLOSE_SCALE_CTRL_Y2
    )

    fun closeAlphaInterpolator(): Interpolator = PathInterpolator(
        CLOSE_ALPHA_CTRL_X1,
        CLOSE_ALPHA_CTRL_Y1,
        CLOSE_ALPHA_CTRL_X2,
        CLOSE_ALPHA_CTRL_Y2
    )

    fun openAlphaInterpolator(): Interpolator = PathInterpolator(
        OPEN_ALPHA_CTRL_X1,
        OPEN_ALPHA_CTRL_Y1,
        OPEN_ALPHA_CTRL_X2,
        OPEN_ALPHA_CTRL_Y2
    )

    fun iconExpandInterpolator(): Interpolator = PathInterpolator(
        ICON_EXPAND_CTRL_X1,
        ICON_EXPAND_CTRL_Y1,
        ICON_EXPAND_CTRL_X2,
        ICON_EXPAND_CTRL_Y2
    )

    fun edgeDockInterpolator(): Interpolator = PathInterpolator(
        EDGE_DOCK_CTRL_X1,
        EDGE_DOCK_CTRL_Y1,
        EDGE_DOCK_CTRL_X2,
        EDGE_DOCK_CTRL_Y2
    )

    /**
     * Interpolates a surface transform shrinking from its visual bounds toward a dock center.
     * [startWidth] and [startHeight] are visual dimensions (logical size * baseScale).
     */
    fun computeShrinkFrame(
        startLeft: Float,
        startTop: Float,
        startWidth: Float,
        startHeight: Float,
        baseScaleX: Float,
        baseScaleY: Float,
        targetCenterX: Float,
        targetCenterY: Float,
        endScaleRatio: Float,
        progress: Float
    ): TransformFrame {
        val ratio = 1f + (endScaleRatio - 1f) * progress
        val sx = (baseScaleX * ratio).coerceAtLeast(0.001f)
        val sy = (baseScaleY * ratio).coerceAtLeast(0.001f)
        val startCx = startLeft + startWidth / 2f
        val startCy = startTop + startHeight / 2f
        val cx = startCx + (targetCenterX - startCx) * progress
        val cy = startCy + (targetCenterY - startCy) * progress
        val curW = startWidth * ratio
        val curH = startHeight * ratio
        return TransformFrame(
            posX = cx - curW / 2f,
            posY = cy - curH / 2f,
            scaleX = sx,
            scaleY = sy
        )
    }

    /**
     * Interpolates a surface transform shrinking from its visual bounds onto a fixed screen rect.
     * [startWidth] and [startHeight] are visual dimensions (logical size * baseScale).
     */
    fun computeShrinkToRectFrame(
        startLeft: Float,
        startTop: Float,
        startWidth: Float,
        startHeight: Float,
        baseScaleX: Float,
        baseScaleY: Float,
        targetLeft: Float,
        targetTop: Float,
        targetWidth: Float,
        targetHeight: Float,
        progress: Float
    ): TransformFrame {
        val safeStartW = startWidth.coerceAtLeast(1f)
        val safeStartH = startHeight.coerceAtLeast(1f)
        val endRatioX = (targetWidth / safeStartW).coerceAtLeast(0.001f)
        val endRatioY = (targetHeight / safeStartH).coerceAtLeast(0.001f)
        val ratioX = 1f + (endRatioX - 1f) * progress
        val ratioY = 1f + (endRatioY - 1f) * progress
        val sx = (baseScaleX * ratioX).coerceAtLeast(0.001f)
        val sy = (baseScaleY * ratioY).coerceAtLeast(0.001f)
        val startCx = startLeft + safeStartW / 2f
        val startCy = startTop + safeStartH / 2f
        val targetCx = targetLeft + targetWidth / 2f
        val targetCy = targetTop + targetHeight / 2f
        val cx = startCx + (targetCx - startCx) * progress
        val cy = startCy + (targetCy - startCy) * progress
        val curW = safeStartW * ratioX
        val curH = safeStartH * ratioY
        return TransformFrame(
            posX = cx - curW / 2f,
            posY = cy - curH / 2f,
            scaleX = sx,
            scaleY = sy
        )
    }

    /** Computes window fade-out, icon capsule fade-in/scale, and edge pad extension at [progress]. */
    fun computeDockMorphFrame(progress: Float): DockMorphFrame {
        val p = progress.coerceIn(0f, 1f)
        val windowAlpha = 1f - smoothstep(EDGE_DOCK_WINDOW_FADE_START, EDGE_DOCK_WINDOW_FADE_END, p)
        val overlayAlpha = smoothstep(EDGE_DOCK_OVERLAY_FADE_START, EDGE_DOCK_OVERLAY_FADE_END, p)
        val scaleT = ((p - EDGE_DOCK_OVERLAY_FADE_START) / (1f - EDGE_DOCK_OVERLAY_FADE_START))
            .coerceIn(0f, 1f)
        val overlayScale = EDGE_DOCK_OVERLAY_START_SCALE + (1f - EDGE_DOCK_OVERLAY_START_SCALE) * scaleT
        val padExtension = smoothstep(EDGE_DOCK_PAD_EXTEND_START, EDGE_DOCK_PAD_EXTEND_END, p)
        return DockMorphFrame(
            windowAlpha = windowAlpha,
            overlayAlpha = overlayAlpha,
            overlayScale = overlayScale,
            padExtension = padExtension
        )
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Interpolates a surface transform expanding from a bubble rect to target freeform bounds.
     * [logicalWidth] and [logicalHeight] are unscaled task bounds; [baseScaleX]/[baseScaleY] map
     * them to the final visual bounds at [targetLeft], [targetTop].
     */
    fun computeExpandFrame(
        bubbleCenterX: Float,
        bubbleCenterY: Float,
        bubbleWidth: Float,
        bubbleHeight: Float,
        targetLeft: Float,
        targetTop: Float,
        logicalWidth: Float,
        logicalHeight: Float,
        baseScaleX: Float,
        baseScaleY: Float,
        progress: Float
    ): TransformFrame {
        val safeW = logicalWidth.coerceAtLeast(1f)
        val safeH = logicalHeight.coerceAtLeast(1f)
        val startScaleX = (bubbleWidth / safeW).coerceAtLeast(0.001f)
        val startScaleY = (bubbleHeight / safeH).coerceAtLeast(0.001f)
        val sx = (startScaleX + (baseScaleX - startScaleX) * progress).coerceAtLeast(0.001f)
        val sy = (startScaleY + (baseScaleY - startScaleY) * progress).coerceAtLeast(0.001f)
        val targetVisualW = safeW * baseScaleX
        val targetVisualH = safeH * baseScaleY
        val targetCx = targetLeft + targetVisualW / 2f
        val targetCy = targetTop + targetVisualH / 2f
        val cx = bubbleCenterX + (targetCx - bubbleCenterX) * progress
        val cy = bubbleCenterY + (targetCy - bubbleCenterY) * progress
        val curW = safeW * sx
        val curH = safeH * sy
        return TransformFrame(
            posX = cx - curW / 2f,
            posY = cy - curH / 2f,
            scaleX = sx,
            scaleY = sy
        )
    }

    /** Computes horizontal offset needed to pull an overhanging window back inside the display. */
    fun computeOnscreenSnapOffset(
        left: Int,
        right: Int,
        displayLeft: Int,
        displayRight: Int
    ): Int {
        val windowWidth = right - left
        val displayWidth = displayRight - displayLeft
        if (windowWidth >= displayWidth) {
            return when {
                left > displayLeft -> displayLeft - left
                right < displayRight -> displayRight - right
                else -> 0
            }
        }
        return when {
            left < displayLeft -> displayLeft - left
            right > displayRight -> displayRight - right
            else -> 0
        }
    }

    /** Returns dock side (0 left, 1 right) or -1 when the window should snap back onscreen. */
    fun resolveEdgeDockSide(
        offLeft: Int,
        offRight: Int,
        windowWidth: Int,
        triggerPercent: Int,
        pointerRawX: Float,
        displayWidth: Int,
        velocityXPxPerSec: Float,
        density: Float
    ): Int {
        if (offLeft <= 0 && offRight <= 0) return -1
        val safeWidth = windowWidth.coerceAtLeast(1).toFloat()
        val triggerWidth = safeWidth * triggerPercent / 100f
        if (offLeft >= triggerWidth) return 0
        if (offRight >= triggerWidth) return 1

        val safeDensity = density.coerceAtLeast(0.5f)
        val edgeZonePx = EDGE_TOUCH_ZONE_DP * safeDensity
        val minZoneOverhang = minOf(triggerWidth, safeWidth * EDGE_ZONE_MIN_OVERHANG_RATIO)
        if (offLeft >= minZoneOverhang && pointerRawX <= edgeZonePx) return 0
        if (offRight >= minZoneOverhang && pointerRawX >= displayWidth - edgeZonePx) return 1

        val minFlingSpeed = EDGE_FLING_VELOCITY_DP * safeDensity
        val minFlingOverhang = minOf(triggerWidth, safeWidth * EDGE_FLING_MIN_OVERHANG_RATIO)
        if (offLeft >= minFlingOverhang && velocityXPxPerSec <= -minFlingSpeed) return 0
        if (offRight >= minFlingOverhang && velocityXPxPerSec >= minFlingSpeed) return 1

        return -1
    }
}
