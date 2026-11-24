package com.qimian233.ztool.hook.modules.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.qimian233.ztool.data.freeform.EdgeBubbleGeometry
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Non-touchable SystemUI overlay that expands the bubble rounded rectangle and app icon
 * to the restored freeform bounds, then dissolves to reveal the live window underneath.
 */
@SuppressLint("ViewConstructor")
class EdgeRestoreMorphOverlay(
    context: Context,
    bubbleRect: Rect,
    targetRect: Rect,
    appIcon: Drawable?,
    initialFrame: FreeformAnimCurves.RestoreMorphFrame,
    density: Float
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val winLeft: Int
    private val winTop: Int
    private val canvasView: MorphCanvasView
    private val layoutParams: WindowManager.LayoutParams

    @Volatile
    private var attached = false

    @Volatile
    private var dismissed = false

    init {
        val overshootW = ceil(targetRect.width() * 0.05f).toInt()
        val overshootH = ceil(targetRect.height() * 0.05f).toInt()
        val shadowPad = ceil(24f * density).toInt()

        val unionLeft = min(bubbleRect.left, targetRect.left - overshootW) - shadowPad
        val unionTop = min(bubbleRect.top, targetRect.top - overshootH) - shadowPad
        val unionRight = max(bubbleRect.right, targetRect.right + overshootW) + shadowPad
        val unionBottom = max(bubbleRect.bottom, targetRect.bottom + overshootH) + shadowPad

        winLeft = unionLeft
        winTop = unionTop

        val clonedIcon = appIcon?.constantState?.newDrawable(context.resources)?.mutate()
            ?: appIcon?.mutate()
        canvasView = MorphCanvasView(context, clonedIcon, density, initialFrame).apply {
            alpha = initialFrame.maskAlpha
        }
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            width = (unionRight - unionLeft).coerceAtLeast(1)
            height = (unionBottom - unionTop).coerceAtLeast(1)
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = winLeft
            y = winTop
        }
    }

    fun show(): Boolean {
        if (dismissed || attached || wm == null) return false
        return runCatching {
            wm.addView(canvasView, layoutParams)
            attached = true
            true
        }.getOrDefault(false)
    }

    fun postOnAnimation(action: Runnable) {
        if (dismissed || !attached) {
            action.run()
            return
        }
        canvasView.postOnAnimation(action)
    }

    fun update(frame: FreeformAnimCurves.RestoreMorphFrame) {
        if (dismissed || !attached) return
        canvasView.updateFrame(frame)
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        if (!attached || wm == null) return
        attached = false
        runCatching { wm.removeViewImmediate(canvasView) }
            .onFailure { runCatching { wm.removeView(canvasView) } }
    }

    private inner class MorphCanvasView(
        context: Context,
        private val icon: Drawable?,
        density: Float,
        initialFrame: FreeformAnimCurves.RestoreMorphFrame
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            val night = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            color = if (night) EdgeBubbleGeometry.BG_DARK_INT else EdgeBubbleGeometry.BG_LIGHT_INT
            setShadowLayer(
                EdgeBubbleGeometry.ELEVATION_DP * density,
                0f,
                1.5f * density,
                EdgeBubbleGeometry.SHADOW_COLOR_INT
            )
        }
        private val squircle = Path()
        private var currentFrame = initialFrame

        fun updateFrame(frame: FreeformAnimCurves.RestoreMorphFrame) {
            currentFrame = frame
            alpha = frame.maskAlpha
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val frame = currentFrame
            val l = frame.left - winLeft
            val t = frame.top - winTop
            val r = frame.right - winLeft
            val b = frame.bottom - winTop
            val w = (r - l).coerceAtLeast(2f)
            val h = (b - t).coerceAtLeast(2f)

            buildSquirclePath(squircle, l, t, w, h, frame.cornerRadiusPx)
            canvas.drawPath(squircle, bgPaint)

            val drawable = icon ?: return
            val saveCount = canvas.save()
            canvas.clipPath(squircle)
            val half = frame.iconSizePx / 2f
            val cx = frame.iconCenterX - winLeft
            val cy = frame.iconCenterY - winTop
            drawable.alpha = 255
            drawable.setBounds(
                (cx - half).roundToInt(),
                (cy - half).roundToInt(),
                (cx + half).roundToInt(),
                (cy + half).roundToInt()
            )
            drawable.draw(canvas)
            canvas.restoreToCount(saveCount)
        }

        private fun buildSquirclePath(
            out: Path,
            l: Float,
            t: Float,
            w: Float,
            h: Float,
            radiusPx: Float
        ) {
            val span = min(
                radiusPx * (EdgeBubbleGeometry.SQUIRCLE_WEIGHT / 2f),
                max(0f, min(w, h) / 2f - 1f)
            )
            val handle = span * 0.6f
            val right = l + w
            val bottom = t + h
            out.rewind()
            out.moveTo(l + span, t)
            out.lineTo(right - span, t)
            out.cubicTo(right - span + handle, t, right, t + span - handle, right, t + span)
            out.lineTo(right, bottom - span)
            out.cubicTo(right, bottom - span + handle, right - span + handle, bottom, right - span, bottom)
            out.lineTo(l + span, bottom)
            out.cubicTo(l + span - handle, bottom, l, bottom - span + handle, l, bottom - span)
            out.lineTo(l, t + span)
            out.cubicTo(l, t + span - handle, l + span - handle, t, l + span, t)
            out.close()
        }
    }
}
