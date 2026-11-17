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
import kotlin.math.min
import kotlin.math.roundToInt

// See docs/research/systemui_battery_indicator.md

/** Strategy interface for drawing a status bar battery indicator shape. */
interface BatteryShapeRenderer {
    val viewportWidth: Float
    val viewportHeight: Float

    fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors
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

    /** Updates battery state and options, invalidating only when values change. */
    fun update(state: BatteryIndicatorState, options: BatteryIndicatorOptions) {
        if (currentState == state && currentOptions == options) return
        val styleChanged = currentOptions.style != options.style
        currentState = state
        currentOptions = options
        if (styleChanged) {
            renderer = rendererForStyle(options.style)
        }
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = (INTRINSIC_WIDTH_DP * density).roundToInt()

    override fun getIntrinsicHeight(): Int = (INTRINSIC_HEIGHT_DP * density).roundToInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val baseColors = BatteryIndicatorPolicy.resolveColors(currentState, currentOptions)
        val scaledColors = if (drawableAlpha == 255) {
            baseColors
        } else {
            baseColors.copy(
                frameAlpha = (baseColors.frameAlpha * drawableAlpha) / 255,
                fillAlpha = (baseColors.fillAlpha * drawableAlpha) / 255
            )
        }
        renderer.draw(canvas, b, currentState, scaledColors)
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
    }

    companion object {
        // Matches stock stat_sys_battery_*.xml intrinsic dimensions (26.4dp x 26.8dp).
        private const val INTRINSIC_WIDTH_DP = 26.4f
        private const val INTRINSIC_HEIGHT_DP = 26.8f
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

    inline fun drawInViewport(canvas: Canvas, bounds: Rect, block: () -> Unit) {
        val saveCount = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / VIEWPORT_WIDTH, bounds.height() / VIEWPORT_HEIGHT)
        try {
            block()
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }
}

/** Renders the stock ZUI 66x67 framed battery outline, terminal cap, and continuous level bar. */
class DefaultFramedBatteryRenderer : BatteryShapeRenderer {
    override val viewportWidth: Float = HorizontalBatteryGeometry.VIEWPORT_WIDTH
    override val viewportHeight: Float = HorizontalBatteryGeometry.VIEWPORT_HEIGHT

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
        colors: BatteryResolvedColors
    ) {
        HorizontalBatteryGeometry.drawInViewport(canvas, bounds) {
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

/** Shared renderer for filled translucent battery containers with edge-flush level clipping. */
open class FilledContainerBatteryRenderer(
    private val containerRect: RectF,
    private val cornerRadius: Float,
    capPath: Path? = null,
    private val fillCapOnFull: Boolean = false
) : BatteryShapeRenderer {
    override val viewportWidth: Float = HorizontalBatteryGeometry.VIEWPORT_WIDTH
    override val viewportHeight: Float = HorizontalBatteryGeometry.VIEWPORT_HEIGHT

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
        colors: BatteryResolvedColors
    ) {
        HorizontalBatteryGeometry.drawInViewport(canvas, bounds) {
            val level = state.clampedLevel
            val fillRight = containerRect.left + containerRect.width() * (level / 100f)

            if (level == FULL_BATTERY_LEVEL && fillCapOnFull) {
                if (colors.fillAlpha < BatteryIndicatorPolicy.ALPHA_OPAQUE) {
                    containerPaint.color = colors.frameColor
                    containerPaint.alpha = colors.frameAlpha
                    canvas.drawPath(containerPath, containerPaint)
                }
                fullFillPaint.color = colors.fillColor
                fullFillPaint.alpha = colors.fillAlpha
                canvas.drawPath(containerPath, fullFillPaint)
                return@drawInViewport
            }

            val containerSave = canvas.save()
            if (level > 0 && colors.fillAlpha == BatteryIndicatorPolicy.ALPHA_OPAQUE) {
                // Keep container under the fill boundary pixel to avoid a subpixel gap,
                // while leaving the left/top/bottom outer AA edges clean against the status bar.
                canvas.clipRect(
                    fillRight - CONTAINER_CLIP_OVERLAP,
                    0f,
                    HorizontalBatteryGeometry.VIEWPORT_WIDTH,
                    HorizontalBatteryGeometry.VIEWPORT_HEIGHT
                )
            }
            containerPaint.color = colors.frameColor
            containerPaint.alpha = colors.frameAlpha
            canvas.drawPath(containerPath, containerPaint)
            canvas.restoreToCount(containerSave)

            if (level > 0) {
                fillPaint.shader = if (level == FULL_BATTERY_LEVEL) {
                    null
                } else {
                    resolveFillShader(level, fillRight, colors.fillColor)
                }
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                canvas.drawRoundRect(containerRect, cornerRadius, cornerRadius, fillPaint)
            }
        }
    }

    private fun resolveFillShader(level: Int, fillRight: Float, fillColor: Int): LinearGradient {
        val rgb = fillColor and RGB_MASK
        val existing = cachedFillShader
        if (existing != null && cachedFillLevel == level && cachedFillRgb == rgb) {
            return existing
        }
        val shader = LinearGradient(
            fillRight - GRADIENT_RAMP,
            0f,
            fillRight + GRADIENT_RAMP,
            0f,
            rgb or OPAQUE_ALPHA_MASK,
            rgb,
            Shader.TileMode.CLAMP
        )
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
        private const val CONTAINER_TOP = 18f
        private const val CONTAINER_RIGHT = 64f
        private const val CONTAINER_BOTTOM = 50f
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
        // Centered 1.9:1 horizontal iOS battery proportions (x: 2.5..63.5, y: 19..49).
        private const val BODY_LEFT = 2.5f
        private const val BODY_TOP = 19f
        private const val BODY_RIGHT = 59.5f
        private const val BODY_BOTTOM = 49f
        private const val BODY_CORNER_RADIUS = 7.5f
        private const val CAP_OVERLAP_LEFT = BODY_RIGHT - 2f
        private const val CAP_TOP = 29f
        private const val CAP_BOTTOM = 39f
        private const val CAP_PROTRUSION = 4f
        private const val CAP_CORNER_RADIUS = 1.8f

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
