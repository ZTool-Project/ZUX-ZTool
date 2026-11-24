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

    const val OPEN_ANIM_DURATION_MS = 380L
    const val OPEN_ALPHA_DURATION_MS = 180L
    const val CLOSE_SCALE_DURATION_MS = 260L
    const val CLOSE_ALPHA_DURATION_MS = 210L
    const val ICON_EXPAND_DURATION_MS = 350L
    const val EDGE_DOCK_DURATION_MS = 240L
    const val SNAP_BACK_DURATION_MS = 260L
    const val RESTORE_DURATION_MS = 380L

    data class TransformFrame(
        val posX: Float,
        val posY: Float,
        val scaleX: Float,
        val scaleY: Float
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

    fun openSpringInterpolator(): Interpolator = SpringInterpolator(0.78f, 15.5f)

    fun restoreSpringInterpolator(): Interpolator = SpringInterpolator(0.78f, 15.0f)

    fun snapBackSpringInterpolator(): Interpolator = SpringInterpolator(0.88f, 17.5f)

    fun closeScaleInterpolator(): Interpolator = PathInterpolator(0.35f, 0.0f, 0.22f, 1.0f)

    fun closeAlphaInterpolator(): Interpolator = PathInterpolator(0.40f, 0.0f, 1.0f, 1.0f)

    fun openAlphaInterpolator(): Interpolator = PathInterpolator(0.0f, 0.0f, 0.20f, 1.0f)

    fun iconExpandInterpolator(): Interpolator = PathInterpolator(0.18f, 0.90f, 0.16f, 1.0f)

    fun edgeDockInterpolator(): Interpolator = PathInterpolator(0.32f, 0.0f, 0.20f, 1.0f)

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
}
