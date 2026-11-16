package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
    }

    companion object {
        // Matches stock stat_sys_battery_*.xml intrinsic dimensions (26.4dp x 26.8dp).
        private const val INTRINSIC_WIDTH_DP = 26.4f
        private const val INTRINSIC_HEIGHT_DP = 26.8f
    }
}

/** Renders the stock ZUI 66x67 framed battery outline, terminal cap, and continuous level bar. */
class DefaultFramedBatteryRenderer : BatteryShapeRenderer {
    override val viewportWidth: Float = VIEWPORT_WIDTH
    override val viewportHeight: Float = VIEWPORT_HEIGHT

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
        val saveCount = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(
            bounds.width() / VIEWPORT_WIDTH,
            bounds.height() / VIEWPORT_HEIGHT
        )

        framePaint.color = colors.frameColor
        framePaint.alpha = colors.frameAlpha
        canvas.drawPath(FRAME_PATH, framePaint)

        capPaint.color = colors.frameColor
        capPaint.alpha = colors.frameAlpha
        canvas.drawPath(CAP_PATH, capPaint)

        val level = state.clampedLevel
        if (level > 0) {
            val fillWidth = FILL_MAX_WIDTH * (level / 100f)
            fillRect.set(FILL_LEFT, FILL_TOP, FILL_LEFT + fillWidth, FILL_BOTTOM)
            val cornerRadius = min(FILL_MAX_CORNER_RADIUS, fillWidth / 2f)
            fillPaint.color = colors.fillColor
            fillPaint.alpha = colors.fillAlpha
            canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, fillPaint)
        }

        canvas.restoreToCount(saveCount)
    }

    companion object {
        // Exact coordinate system from SystemUI res/drawable/stat_sys_battery_*.xml.
        private const val VIEWPORT_WIDTH = 66f
        private const val VIEWPORT_HEIGHT = 67f
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

        private val CAP_PATH: Path = Path().apply {
            moveTo(62f, 28f)
            lineTo(64.073f, 31.015f)
            cubicTo(64.677f, 31.893f, 65f, 32.934f, 65f, 34f)
            cubicTo(65f, 35.066f, 64.677f, 36.107f, 64.073f, 36.985f)
            lineTo(62f, 40f)
            close()
        }
    }
}
