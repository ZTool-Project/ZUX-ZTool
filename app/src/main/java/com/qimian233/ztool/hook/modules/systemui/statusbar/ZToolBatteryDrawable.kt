package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
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

/** Renders a frameless translucent capsule container with an edge-flush continuous level fill. */
class FramelessBatteryRenderer : BatteryShapeRenderer {
    override val viewportWidth: Float = HorizontalBatteryGeometry.VIEWPORT_WIDTH
    override val viewportHeight: Float = HorizontalBatteryGeometry.VIEWPORT_HEIGHT

    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val containerRect = RectF(
        CONTAINER_LEFT,
        CONTAINER_TOP,
        CONTAINER_RIGHT,
        CONTAINER_BOTTOM
    )
    private val fillRect = RectF()

    override fun draw(
        canvas: Canvas,
        bounds: Rect,
        state: BatteryIndicatorState,
        colors: BatteryResolvedColors
    ) {
        HorizontalBatteryGeometry.drawInViewport(canvas, bounds) {
            containerPaint.color = colors.frameColor
            containerPaint.alpha = colors.frameAlpha
            canvas.drawRoundRect(
                containerRect,
                CONTAINER_CORNER_RADIUS,
                CONTAINER_CORNER_RADIUS,
                containerPaint
            )

            capPaint.color = colors.frameColor
            capPaint.alpha = colors.frameAlpha
            canvas.drawPath(HorizontalBatteryGeometry.CAP_PATH, capPaint)

            val level = state.clampedLevel
            if (level > 0) {
                // SRC_IN inside an offscreen layer preserves anti-aliased capsule corners
                // without gaps or edge overhang at any battery percentage.
                val layerSave = canvas.saveLayer(
                    CONTAINER_LEFT - LAYER_PAD,
                    CONTAINER_TOP - LAYER_PAD,
                    CONTAINER_RIGHT + LAYER_PAD,
                    CONTAINER_BOTTOM + LAYER_PAD,
                    null
                )
                canvas.drawRoundRect(
                    containerRect,
                    CONTAINER_CORNER_RADIUS,
                    CONTAINER_CORNER_RADIUS,
                    maskPaint
                )
                val fillWidth = CONTAINER_WIDTH * (level / 100f)
                fillRect.set(
                    CONTAINER_LEFT,
                    CONTAINER_TOP,
                    CONTAINER_LEFT + fillWidth,
                    CONTAINER_BOTTOM
                )
                fillPaint.color = colors.fillColor
                fillPaint.alpha = colors.fillAlpha
                canvas.drawRect(fillRect, fillPaint)
                canvas.restoreToCount(layerSave)
            }
        }
    }

    companion object {
        // Matches stock outer stroke bounds vertically (y: 16..52) with a 2u gap before CAP_PATH (x: 62).
        private const val CONTAINER_LEFT = 2f
        private const val CONTAINER_TOP = 16f
        private const val CONTAINER_RIGHT = 60f
        private const val CONTAINER_BOTTOM = 52f
        private const val CONTAINER_WIDTH = CONTAINER_RIGHT - CONTAINER_LEFT
        private const val CONTAINER_CORNER_RADIUS = 10f
        private const val LAYER_PAD = 1f
    }
}
