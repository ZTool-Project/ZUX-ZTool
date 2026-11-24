package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

// See docs/research/systemui_battery_indicator.md

/** Strategy interface for drawing a status bar battery indicator shape. */
interface BatteryShapeRenderer {
    val viewportWidth: Float
    val viewportHeight: Float
    val previewGlyphHeightFraction: Float
        get() = 1f

    fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors,
        options: BatteryIndicatorOptions = BatteryIndicatorOptions(),
        animationStep: Int = 0
    )
}

/** Custom status bar battery drawable backed by a pluggable [BatteryShapeRenderer]. */
class ZToolBatteryDrawable(
    private val density: Float,
    state: BatteryIndicatorState = BatteryIndicatorState(),
    options: BatteryIndicatorOptions = BatteryIndicatorOptions()
) : Drawable() {

    private var currentState: BatteryIndicatorState = state
    private var currentOptions: BatteryIndicatorOptions = options
    private var drawableAlpha: Int = 255
    private var renderer: BatteryShapeRenderer = rendererForStyle(options.style)
    private var animationStep: Int = 0
    private var isAnimationScheduled: Boolean = false
    private val animationTickRunnable = object : Runnable {
        override fun run() {
            isAnimationScheduled = false
            if (!shouldAnimate()) {
                animationStep = 0
                return
            }
            animationStep = (animationStep + 1) % SEGMENTED_ANIMATION_STEP_MODULUS
            invalidateSelf()
            scheduleNextTick()
        }
    }

    val viewportWidth: Float
        get() = renderer.viewportWidth

    val viewportHeight: Float
        get() = renderer.viewportHeight

    val previewGlyphHeightFraction: Float
        get() = renderer.previewGlyphHeightFraction

    /** Updates battery state and options, invalidating only when values change. */
    fun update(state: BatteryIndicatorState, options: BatteryIndicatorOptions) {
        if (currentState == state && currentOptions == options) return
        val styleChanged = currentOptions.style != options.style
        val chargingChanged = currentState.isCharging != state.isCharging
        val chargingStyleChanged =
            currentOptions.segmentedChargingStyle != options.segmentedChargingStyle
        currentState = state
        currentOptions = options
        if (styleChanged) {
            renderer = rendererForStyle(options.style)
        }
        syncAnimationState(resetStep = styleChanged || chargingChanged || chargingStyleChanged)
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int =
        (renderer.viewportWidth * DP_PER_VIEWPORT_UNIT * density).roundToInt()

    override fun getIntrinsicHeight(): Int =
        (renderer.viewportHeight * DP_PER_VIEWPORT_UNIT * density).roundToInt()

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            stopAnimation(resetStep = true)
        } else if (restart || changed) {
            syncAnimationState(resetStep = restart)
        }
        return changed
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (shouldAnimate() && !isAnimationScheduled) {
            scheduleNextTick()
        }
        val baseColors = BatteryIndicatorPolicy.resolveColors(currentState, currentOptions)
        val scaledColors = if (drawableAlpha == 255) {
            baseColors
        } else {
            baseColors.copy(
                frameAlpha = (baseColors.frameAlpha * drawableAlpha) / 255,
                fillAlpha = (baseColors.fillAlpha * drawableAlpha) / 255
            )
        }
        renderer.draw(canvas, b, currentState, scaledColors, currentOptions, animationStep)
    }

    private fun shouldAnimate(): Boolean {
        if (currentOptions.style != BatteryIndicatorStyle.SEGMENTED || !currentState.isCharging) {
            return false
        }
        if (!isVisible) return false
        val cb = callback ?: return false
        return cb !is View || cb.isAttachedToWindow
    }

    private fun syncAnimationState(resetStep: Boolean) {
        if (resetStep) {
            animationStep = 0
        }
        if (shouldAnimate()) {
            if (resetStep) {
                unscheduleSelf(animationTickRunnable)
                isAnimationScheduled = false
            }
            if (!isAnimationScheduled) {
                scheduleNextTick()
            }
        } else {
            stopAnimation(resetStep = true)
        }
    }

    private fun scheduleNextTick() {
        isAnimationScheduled = true
        scheduleSelf(animationTickRunnable, SystemClock.uptimeMillis() + SEGMENTED_ANIMATION_TICK_MS)
    }

    private fun stopAnimation(resetStep: Boolean) {
        if (isAnimationScheduled) {
            unscheduleSelf(animationTickRunnable)
            isAnimationScheduled = false
        }
        if (resetStep) {
            animationStep = 0
        }
    }

    override fun setAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 255)
        if (drawableAlpha != clamped) {
            drawableAlpha = clamped
            invalidateSelf()
        }
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Multi-layer colors are resolved explicitly by BatteryIndicatorPolicy.
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun rendererForStyle(style: BatteryIndicatorStyle): BatteryShapeRenderer = when (style) {
        BatteryIndicatorStyle.DEFAULT_FRAMED -> DefaultFramedBatteryRenderer()
        BatteryIndicatorStyle.FRAMELESS -> FramelessBatteryRenderer()
        BatteryIndicatorStyle.IOS_STYLE -> IosBatteryRenderer()
        BatteryIndicatorStyle.VERTICAL -> VerticalBatteryRenderer()
        BatteryIndicatorStyle.RING -> RingBatteryRenderer()
        BatteryIndicatorStyle.PIE -> PieBatteryRenderer()
        BatteryIndicatorStyle.SEGMENTED -> SegmentedBatteryRenderer()
    }

    companion object {
        // 0.4dp per viewport unit matches stock stat_sys_battery_*.xml (66x67 -> 26.4dp x 26.8dp).
        private const val DP_PER_VIEWPORT_UNIT = 0.4f
        private const val SEGMENTED_ANIMATION_TICK_MS = 500L
        // Divisible by 2, 3, 4, and 5 so every charging cycle wraps seamlessly.
        private const val SEGMENTED_ANIMATION_STEP_MODULUS = 60
    }
}

private inline fun drawInCenteredViewport(
    canvas: Canvas,
    bounds: Rect,
    viewportWidth: Float,
    viewportHeight: Float,
    block: () -> Unit
) {
    if (bounds.isEmpty || viewportWidth <= 0f || viewportHeight <= 0f) return
    val scale = min(bounds.width() / viewportWidth, bounds.height() / viewportHeight)
    val dx = bounds.left + (bounds.width() - viewportWidth * scale) / 2f
    val dy = bounds.top + (bounds.height() - viewportHeight * scale) / 2f
    val saveCount = canvas.save()
    canvas.translate(dx, dy)
    canvas.scale(scale, scale)
    try {
        block()
    } finally {
        canvas.restoreToCount(saveCount)
    }
}

private object HorizontalBatteryGeometry {
    // Exact coordinate system from SystemUI res/drawable/stat_sys_battery_*.xml.
    const val VIEWPORT_WIDTH = 66f
    const val VIEWPORT_HEIGHT = 67f

    val CAP_PATH: Path = Path().apply {
        moveTo(62f, 28f)
        lineTo(64.073f, 31.015f)
        cubicTo(64.677f, 31.893f, 65f, 32.934f, 65f, 34f)
        cubicTo(65f, 35.066f, 64.677f, 36.107f, 64.073f, 36.985f)
        lineTo(62f, 40f)
        close()
    }
}

/** Renders the stock ZUI 66x67 framed battery outline, terminal cap, and continuous level bar. */
class DefaultFramedBatteryRenderer : BatteryShapeRenderer {
    override val viewportWidth: Float = HorizontalBatteryGeometry.VIEWPORT_WIDTH
    override val viewportHeight: Float = HorizontalBatteryGeometry.VIEWPORT_HEIGHT
    override val previewGlyphHeightFraction: Float = GLYPH_HEIGHT / viewportHeight

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FRAME_STROKE_WIDTH
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillRect = RectF()

    override fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors,
        options: BatteryIndicatorOptions,
        animationStep: Int
    ) {
        drawInCenteredViewport(canvas, bounds, viewportWidth, viewportHeight) {
            framePaint.color = colors.frameColor
            framePaint.alpha = colors.frameAlpha
            canvas.drawPath(FRAME_PATH, framePaint)

            capPaint.color = colors.frameColor
            capPaint.alpha = colors.frameAlpha
            canvas.drawPath(HorizontalBatteryGeometry.CAP_PATH, capPaint)

            val level = state.clampedLevel
            if (level > 0) {
                val fillWidth = FILL_MAX_WIDTH * (level / 100f)
                fillRect.set(FILL_LEFT, FILL_TOP, FILL_LEFT + fillWidth, FILL_BOTTOM)
                val cornerRadius = min(FILL_MAX_CORNER_RADIUS, fillWidth / 2f)
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, fillPaint)
            }
        }
    }

    companion object {
        private const val FRAME_STROKE_WIDTH = 4f
        private const val GLYPH_HEIGHT = 36f
        private const val FILL_LEFT = 8f
        private const val FILL_TOP = 23f
        private const val FILL_BOTTOM = 45f
        private const val FILL_MAX_WIDTH = 47f
        private const val FILL_MAX_CORNER_RADIUS = 3.85f

        private val FRAME_PATH: Path = Path().apply {
            moveTo(11.256f, 18f)
            lineTo(51.744f, 18f)
            cubicTo(54.744f, 18f, 55.86f, 18.242f, 56.964f, 18.832f)
            cubicTo(57.914f, 19.34f, 58.66f, 20.086f, 59.168f, 21.036f)
            cubicTo(59.758f, 22.14f, 60f, 23.256f, 60f, 26.256f)
            lineTo(60f, 41.744f)
            cubicTo(60f, 44.744f, 59.758f, 45.86f, 59.168f, 46.964f)
            cubicTo(58.66f, 47.914f, 57.914f, 48.66f, 56.964f, 49.168f)
            cubicTo(55.86f, 49.758f, 54.744f, 50f, 51.744f, 50f)
            lineTo(11.256f, 50f)
            cubicTo(8.256f, 50f, 7.14f, 49.758f, 6.036f, 49.168f)
            cubicTo(5.086f, 48.66f, 4.34f, 47.914f, 3.832f, 46.964f)
            cubicTo(3.242f, 45.86f, 3f, 44.744f, 3f, 41.744f)
            lineTo(3f, 26.256f)
            cubicTo(3f, 23.256f, 3.242f, 22.14f, 3.832f, 21.036f)
            cubicTo(4.34f, 20.086f, 5.086f, 19.34f, 6.036f, 18.832f)
            cubicTo(7.14f, 18.242f, 8.256f, 18f, 11.256f, 18f)
            close()
        }
    }
}

/** Fill growth direction inside [FilledContainerBatteryRenderer]. */
enum class BatteryFillOrientation {
    HORIZONTAL_LEFT_TO_RIGHT,
    VERTICAL_BOTTOM_TO_TOP
}

/** Shared renderer for filled translucent battery containers with edge-flush level clipping. */
open class FilledContainerBatteryRenderer(
    private val containerRect: RectF,
    private val cornerRadius: Float,
    capPath: Path? = null,
    private val fillCapOnFull: Boolean = false,
    private val fillOrientation: BatteryFillOrientation = BatteryFillOrientation.HORIZONTAL_LEFT_TO_RIGHT,
    override val viewportWidth: Float = HorizontalBatteryGeometry.VIEWPORT_WIDTH,
    override val viewportHeight: Float = HorizontalBatteryGeometry.VIEWPORT_HEIGHT,
    glyphHeight: Float = containerRect.height()
) : BatteryShapeRenderer {
    override val previewGlyphHeightFraction: Float = glyphHeight / viewportHeight

    private val containerPath: Path = Path().apply {
        addRoundRect(containerRect, cornerRadius, cornerRadius, Path.Direction.CW)
        if (capPath != null) {
            op(capPath, Path.Op.UNION)
        }
    }
    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fullFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var cachedFillLevel: Int = -1
    private var cachedFillRgb: Int = 0
    private var cachedFillShader: LinearGradient? = null

    override fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors,
        options: BatteryIndicatorOptions,
        animationStep: Int
    ) {
        drawInCenteredViewport(canvas, bounds, viewportWidth, viewportHeight) {
            val level = state.clampedLevel
            val fraction = level / 100f
            val fillEdge = when (fillOrientation) {
                BatteryFillOrientation.HORIZONTAL_LEFT_TO_RIGHT ->
                    containerRect.left + containerRect.width() * fraction
                BatteryFillOrientation.VERTICAL_BOTTOM_TO_TOP ->
                    containerRect.bottom - containerRect.height() * fraction
            }

            if (level == FULL_BATTERY_LEVEL && fillCapOnFull) {
                if (colors.fillAlpha < BatteryIndicatorPolicy.ALPHA_OPAQUE) {
                    containerPaint.color = colors.frameColor
                    containerPaint.alpha = colors.frameAlpha
                    canvas.drawPath(containerPath, containerPaint)
                }
                fullFillPaint.color = colors.fillColor
                fullFillPaint.alpha = colors.fillAlpha
                canvas.drawPath(containerPath, fullFillPaint)
                return@drawInCenteredViewport
            }

            val containerSave = canvas.save()
            if (level > 0 && colors.fillAlpha == BatteryIndicatorPolicy.ALPHA_OPAQUE) {
                // Keep container under the fill boundary pixel to avoid a subpixel gap,
                // while leaving the outer AA edges clean against the status bar.
                when (fillOrientation) {
                    BatteryFillOrientation.HORIZONTAL_LEFT_TO_RIGHT ->
                        canvas.clipRect(
                            fillEdge - CONTAINER_CLIP_OVERLAP,
                            0f,
                            viewportWidth,
                            viewportHeight
                        )
                    BatteryFillOrientation.VERTICAL_BOTTOM_TO_TOP ->
                        canvas.clipRect(
                            0f,
                            0f,
                            viewportWidth,
                            fillEdge + CONTAINER_CLIP_OVERLAP
                        )
                }
            }
            containerPaint.color = colors.frameColor
            containerPaint.alpha = colors.frameAlpha
            canvas.drawPath(containerPath, containerPaint)
            canvas.restoreToCount(containerSave)

            if (level > 0) {
                fillPaint.shader = if (level == FULL_BATTERY_LEVEL) {
                    null
                } else {
                    resolveFillShader(level, fillEdge, colors.fillColor)
                }
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                canvas.drawRoundRect(containerRect, cornerRadius, cornerRadius, fillPaint)
            }
        }
    }

    private fun resolveFillShader(level: Int, fillEdge: Float, fillColor: Int): LinearGradient {
        val rgb = fillColor and RGB_MASK
        val existing = cachedFillShader
        if (existing != null && cachedFillLevel == level && cachedFillRgb == rgb) {
            return existing
        }
        val shader = when (fillOrientation) {
            BatteryFillOrientation.HORIZONTAL_LEFT_TO_RIGHT -> LinearGradient(
                fillEdge - GRADIENT_RAMP,
                0f,
                fillEdge + GRADIENT_RAMP,
                0f,
                rgb or OPAQUE_ALPHA_MASK,
                rgb,
                Shader.TileMode.CLAMP
            )
            BatteryFillOrientation.VERTICAL_BOTTOM_TO_TOP -> LinearGradient(
                0f,
                fillEdge + GRADIENT_RAMP,
                0f,
                fillEdge - GRADIENT_RAMP,
                rgb or OPAQUE_ALPHA_MASK,
                rgb,
                Shader.TileMode.CLAMP
            )
        }
        cachedFillLevel = level
        cachedFillRgb = rgb
        cachedFillShader = shader
        return shader
    }

    companion object {
        private const val FULL_BATTERY_LEVEL = 100
        private const val GRADIENT_RAMP = 0.1f
        private const val CONTAINER_CLIP_OVERLAP = 0.75f
        private const val RGB_MASK = 0x00FFFFFF
        private const val OPAQUE_ALPHA_MASK = -0x1000000
    }
}

/** Renders a pure frameless translucent pill capsule container with edge-flush level fill. */
class FramelessBatteryRenderer : FilledContainerBatteryRenderer(
    containerRect = RectF(CONTAINER_LEFT, CONTAINER_TOP, CONTAINER_RIGHT, CONTAINER_BOTTOM),
    cornerRadius = CONTAINER_CORNER_RADIUS,
    capPath = null
) {
    companion object {
        private const val CONTAINER_LEFT = 2f
        private const val CONTAINER_TOP = 16f
        private const val CONTAINER_RIGHT = 64f
        private const val CONTAINER_BOTTOM = 52f
        private const val CONTAINER_CORNER_RADIUS = (CONTAINER_BOTTOM - CONTAINER_TOP) / 2f
    }
}

/** Renders an iOS-style translucent horizontal battery container with a seamless rounded-rect terminal cap. */
class IosBatteryRenderer : FilledContainerBatteryRenderer(
    containerRect = RectF(BODY_LEFT, BODY_TOP, BODY_RIGHT, BODY_BOTTOM),
    cornerRadius = BODY_CORNER_RADIUS,
    capPath = IOS_CAP_PATH,
    fillCapOnFull = true
) {
    companion object {
        // Horizontal iOS battery proportions aligned to stock 36u visual height (x: 2.5..63.5, y: 16..52).
        private const val BODY_LEFT = 2.5f
        private const val BODY_TOP = 16f
        private const val BODY_RIGHT = 59.5f
        private const val BODY_BOTTOM = 52f
        private const val BODY_CORNER_RADIUS = 9f
        private const val CAP_OVERLAP_LEFT = BODY_RIGHT - 2f
        private const val CAP_TOP = 28f
        private const val CAP_BOTTOM = 40f
        private const val CAP_PROTRUSION = 4f
        private const val CAP_CORNER_RADIUS = 2.1f

        private val IOS_CAP_PATH: Path = Path().apply {
            addRoundRect(
                RectF(CAP_OVERLAP_LEFT, CAP_TOP, BODY_RIGHT + CAP_PROTRUSION, CAP_BOTTOM),
                CAP_CORNER_RADIUS,
                CAP_CORNER_RADIUS,
                Path.Direction.CW
            )
        }
    }
}

/** Renders an AOSP/LineageOS-style vertical portrait battery with top terminal cap and bottom-up fill. */
class VerticalBatteryRenderer : FilledContainerBatteryRenderer(
    containerRect = RectF(BODY_LEFT, BODY_TOP, BODY_RIGHT, BODY_BOTTOM),
    cornerRadius = BODY_CORNER_RADIUS,
    capPath = VERTICAL_CAP_PATH,
    fillCapOnFull = true,
    fillOrientation = BatteryFillOrientation.VERTICAL_BOTTOM_TO_TOP,
    viewportWidth = VIEWPORT_WIDTH,
    viewportHeight = VIEWPORT_HEIGHT,
    glyphHeight = BODY_BOTTOM - CAP_TOP
) {
    companion object {
        // Proportions derived from AOSP/LineageOS portrait battery (12:19 silhouette scaled to 36u tall).
        private const val VIEWPORT_WIDTH = 34f
        private const val VIEWPORT_HEIGHT = 67f
        private const val BODY_LEFT = 5.5f
        private const val BODY_TOP = 19.5f
        private const val BODY_RIGHT = 28.5f
        private const val BODY_BOTTOM = 52f
        private const val BODY_CORNER_RADIUS = 2.8f
        private const val CAP_LEFT = 11.5f
        private const val CAP_TOP = 16f
        private const val CAP_RIGHT = 22.5f
        private const val CAP_OVERLAP_BOTTOM = BODY_TOP + 2f
        private const val CAP_CORNER_RADIUS = 1.4f

        private val VERTICAL_CAP_PATH: Path = Path().apply {
            addRoundRect(
                RectF(CAP_LEFT, CAP_TOP, CAP_RIGHT, CAP_OVERLAP_BOTTOM),
                CAP_CORNER_RADIUS,
                CAP_CORNER_RADIUS,
                Path.Direction.CW
            )
        }
    }
}

private object CircularBatteryGeometry {
    const val VIEWPORT_WIDTH = 44f
    const val VIEWPORT_HEIGHT = 67f
    const val CENTER_X = VIEWPORT_WIDTH / 2f
    const val CENTER_Y = VIEWPORT_HEIGHT / 2f
    const val OUTER_DIAMETER = 36f
    const val OUTER_RADIUS = OUTER_DIAMETER / 2f
    const val START_ANGLE = 270f
    const val FULL_CIRCLE_DEGREES = 360f
    const val FULL_BATTERY_LEVEL = 100
}

/** Renders a LineageOS-style circular ring battery track and clockwise level arc. */
class RingBatteryRenderer : BatteryShapeRenderer {
    override val viewportWidth: Float = CircularBatteryGeometry.VIEWPORT_WIDTH
    override val viewportHeight: Float = CircularBatteryGeometry.VIEWPORT_HEIGHT
    override val previewGlyphHeightFraction: Float =
        CircularBatteryGeometry.OUTER_DIAMETER / CircularBatteryGeometry.VIEWPORT_HEIGHT

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }

    override fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors,
        options: BatteryIndicatorOptions,
        animationStep: Int
    ) {
        drawInCenteredViewport(canvas, bounds, viewportWidth, viewportHeight) {
            val level = state.clampedLevel
            if (level < CircularBatteryGeometry.FULL_BATTERY_LEVEL) {
                trackPaint.color = colors.frameColor
                trackPaint.alpha = colors.frameAlpha
                canvas.drawArc(
                    ARC_RECT,
                    CircularBatteryGeometry.START_ANGLE,
                    CircularBatteryGeometry.FULL_CIRCLE_DEGREES,
                    false,
                    trackPaint
                )
            }
            if (level > 0) {
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                val sweep = CircularBatteryGeometry.FULL_CIRCLE_DEGREES * (level / 100f)
                canvas.drawArc(
                    ARC_RECT,
                    CircularBatteryGeometry.START_ANGLE,
                    sweep,
                    false,
                    fillPaint
                )
            }
        }
    }

    companion object {
        // Matches LineageOS CircleBatteryDrawable stroke ratio (diameter / 6.5f).
        private const val STROKE_WIDTH = CircularBatteryGeometry.OUTER_DIAMETER / 6.5f
        private const val ARC_INSET = STROKE_WIDTH / 2f
        private val ARC_RECT = RectF(
            CircularBatteryGeometry.CENTER_X - CircularBatteryGeometry.OUTER_RADIUS + ARC_INSET,
            CircularBatteryGeometry.CENTER_Y - CircularBatteryGeometry.OUTER_RADIUS + ARC_INSET,
            CircularBatteryGeometry.CENTER_X + CircularBatteryGeometry.OUTER_RADIUS - ARC_INSET,
            CircularBatteryGeometry.CENTER_Y + CircularBatteryGeometry.OUTER_RADIUS - ARC_INSET
        )
    }
}

/** Renders a circular pie-chart battery indicator with a clockwise filled level sector. */
class PieBatteryRenderer : BatteryShapeRenderer {
    override val viewportWidth: Float = CircularBatteryGeometry.VIEWPORT_WIDTH
    override val viewportHeight: Float = CircularBatteryGeometry.VIEWPORT_HEIGHT
    override val previewGlyphHeightFraction: Float =
        CircularBatteryGeometry.OUTER_DIAMETER / CircularBatteryGeometry.VIEWPORT_HEIGHT

    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors,
        options: BatteryIndicatorOptions,
        animationStep: Int
    ) {
        drawInCenteredViewport(canvas, bounds, viewportWidth, viewportHeight) {
            val level = state.clampedLevel
            if (level == CircularBatteryGeometry.FULL_BATTERY_LEVEL) {
                if (colors.fillAlpha < BatteryIndicatorPolicy.ALPHA_OPAQUE) {
                    containerPaint.color = colors.frameColor
                    containerPaint.alpha = colors.frameAlpha
                    canvas.drawCircle(
                        CircularBatteryGeometry.CENTER_X,
                        CircularBatteryGeometry.CENTER_Y,
                        CircularBatteryGeometry.OUTER_RADIUS,
                        containerPaint
                    )
                }
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                canvas.drawCircle(
                    CircularBatteryGeometry.CENTER_X,
                    CircularBatteryGeometry.CENTER_Y,
                    CircularBatteryGeometry.OUTER_RADIUS,
                    fillPaint
                )
                return@drawInCenteredViewport
            }

            val sweep = CircularBatteryGeometry.FULL_CIRCLE_DEGREES * (level / 100f)
            containerPaint.color = colors.frameColor
            containerPaint.alpha = colors.frameAlpha
            if (level > 0 && colors.fillAlpha == BatteryIndicatorPolicy.ALPHA_OPAQUE) {
                val start = CircularBatteryGeometry.START_ANGLE + sweep - SECTOR_OVERLAP_DEGREES
                val remaining = (
                    CircularBatteryGeometry.FULL_CIRCLE_DEGREES -
                        sweep +
                        SECTOR_OVERLAP_DEGREES * 2f
                    ).coerceAtMost(CircularBatteryGeometry.FULL_CIRCLE_DEGREES)
                canvas.drawArc(CIRCLE_RECT, start, remaining, true, containerPaint)
            } else {
                canvas.drawCircle(
                    CircularBatteryGeometry.CENTER_X,
                    CircularBatteryGeometry.CENTER_Y,
                    CircularBatteryGeometry.OUTER_RADIUS,
                    containerPaint
                )
            }

            if (level > 0) {
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                canvas.drawArc(
                    CIRCLE_RECT,
                    CircularBatteryGeometry.START_ANGLE,
                    sweep,
                    true,
                    fillPaint
                )
            }
        }
    }

    companion object {
        private const val SECTOR_OVERLAP_DEGREES = 0.5f
        private val CIRCLE_RECT = RectF(
            CircularBatteryGeometry.CENTER_X - CircularBatteryGeometry.OUTER_RADIUS,
            CircularBatteryGeometry.CENTER_Y - CircularBatteryGeometry.OUTER_RADIUS,
            CircularBatteryGeometry.CENTER_X + CircularBatteryGeometry.OUTER_RADIUS,
            CircularBatteryGeometry.CENTER_Y + CircularBatteryGeometry.OUTER_RADIUS
        )
    }
}

/** Renders a sharp-cornered rectangular frame, cap, and 4 discrete battery level cells. */
class SegmentedBatteryRenderer : BatteryShapeRenderer {
    override val viewportWidth: Float = HorizontalBatteryGeometry.VIEWPORT_WIDTH
    override val viewportHeight: Float = HorizontalBatteryGeometry.VIEWPORT_HEIGHT
    override val previewGlyphHeightFraction: Float = GLYPH_HEIGHT / viewportHeight

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FRAME_STROKE_WIDTH
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors,
        options: BatteryIndicatorOptions,
        animationStep: Int
    ) {
        drawInCenteredViewport(canvas, bounds, viewportWidth, viewportHeight) {
            framePaint.color = colors.frameColor
            framePaint.alpha = colors.frameAlpha
            canvas.drawRect(FRAME_RECT, framePaint)

            capPaint.color = colors.frameColor
            capPaint.alpha = colors.frameAlpha
            canvas.drawRect(CAP_RECT, capPaint)

            val litCount = BatteryIndicatorPolicy.resolveSegmentedLitCount(
                level = state.clampedLevel,
                isCharging = state.isCharging,
                chargingStyle = options.segmentedChargingStyle,
                step = animationStep
            )
            if (litCount > 0) {
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                for (index in 0 until litCount) {
                    val left = CELL_LEFT_START + index * (CELL_WIDTH + CELL_GAP)
                    canvas.drawRect(left, CELL_TOP, left + CELL_WIDTH, CELL_BOTTOM, fillPaint)
                }
            }
        }
    }

    companion object {
        private const val FRAME_STROKE_WIDTH = 4f
        private const val GLYPH_HEIGHT = 36f
        private val FRAME_RECT = RectF(3f, 18f, 60f, 50f)
        private val CAP_RECT = RectF(62f, 28f, 65f, 40f)
        private const val CELL_LEFT_START = 8f
        private const val CELL_TOP = 23f
        private const val CELL_BOTTOM = 45f
        private const val CELL_WIDTH = 9.5f
        private const val CELL_GAP = 3f
    }
}
