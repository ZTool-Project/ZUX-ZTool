package com.qimian233.ztool.hook.modules.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import com.qimian233.ztool.data.freeform.EdgeBubbleGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Non-touchable SystemUI overlay that crossfades a scaled freeform window into a pixel-matched
 * edge-bubble capsule before handing off to EdgeBubbleView.
 */
@SuppressLint("ViewConstructor", "PrivateApi")
class EdgeDockMorphOverlay(
    context: Context,
    private val fullLayout: EdgeBubbleGeometry.FullBubbleLayout,
    appIcon: Drawable?,
    startCenterX: Float,
    startCenterY: Float,
    private val density: Float
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val iconCenterX = fullLayout.iconRect.exactCenterX
    private val iconCenterY = fullLayout.iconRect.exactCenterY
    private val containerW = fullLayout.containerRect.width
    private val containerH = fullLayout.containerRect.height
    private val pivotXInView =
        (fullLayout.iconRect.left - fullLayout.containerRect.left) + fullLayout.iconPx / 2f
    private val pivotYInView = fullLayout.innerPx + fullLayout.iconPx / 2f

    private val host: FrameLayout
    private val capsuleView: MorphCapsuleView
    private val layoutParams: WindowManager.LayoutParams

    @Volatile
    private var attached = false

    @Volatile
    private var dismissed = false

    init {
        val startScale = FreeformAnimCurves.EDGE_DOCK_OVERLAY_START_SCALE
        val tFadeStart = FreeformAnimCurves.EDGE_DOCK_OVERLAY_FADE_START
        val cxAtFadeStart = startCenterX + (iconCenterX - startCenterX) * tFadeStart
        val cyAtFadeStart = startCenterY + (iconCenterY - startCenterY) * tFadeStart
        val shadowPad = ceil(24f * density).toInt()

        val scaledLeft = (cxAtFadeStart - pivotXInView * startScale).toInt()
        val scaledTop = (cyAtFadeStart - pivotYInView * startScale).toInt()
        val scaledRight = ceil(cxAtFadeStart + (containerW - pivotXInView) * startScale).toInt()
        val scaledBottom = ceil(cyAtFadeStart + (containerH - pivotYInView) * startScale).toInt()

        val winLeft = min(fullLayout.containerRect.left, scaledLeft) - shadowPad
        val winTop = min(fullLayout.containerRect.top, scaledTop) - shadowPad
        val winRight = max(fullLayout.containerRect.right, scaledRight) + shadowPad
        val winBottom = max(fullLayout.containerRect.bottom, scaledBottom) + shadowPad

        val clonedIcon = appIcon?.constantState?.newDrawable(context.resources)?.mutate()
            ?: appIcon?.mutate()
        capsuleView = MorphCapsuleView(context, clonedIcon).apply {
            pivotX = pivotXInView
            pivotY = pivotYInView
            alpha = 0f
        }
        host = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(
                capsuleView,
                FrameLayout.LayoutParams(containerW, containerH).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = fullLayout.containerRect.left - winLeft
                    topMargin = fullLayout.containerRect.top - winTop
                }
            )
        }
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            width = (winRight - winLeft).coerceAtLeast(containerW)
            height = (winBottom - winTop).coerceAtLeast(containerH)
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = winLeft
            y = winTop
            alpha = 1f
            title = "ZToolEdgeDockMorph"
            runCatching {
                WindowManager.LayoutParams::class.java
                    .getMethod("setTrustedOverlay")
                    .invoke(this)
            }
        }
    }

    fun show(): Boolean {
        if (dismissed || attached || wm == null) return false
        return runCatching {
            wm.addView(host, layoutParams)
            attached = true
            true
        }.getOrDefault(false)
    }

    fun update(centerX: Float, centerY: Float, frame: FreeformAnimCurves.DockMorphFrame) {
        if (dismissed || !attached) return
        capsuleView.translationX = centerX - iconCenterX
        capsuleView.translationY = centerY - iconCenterY
        capsuleView.scaleX = frame.overlayScale
        capsuleView.scaleY = frame.overlayScale
        capsuleView.alpha = frame.overlayAlpha
        capsuleView.setPadExtension(frame.padExtension)
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        if (!attached || wm == null) return
        attached = false
        runCatching { wm.removeViewImmediate(host) }
            .onFailure { runCatching { wm.removeView(host) } }
    }

    private inner class MorphCapsuleView(
        context: Context,
        private val icon: Drawable?
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            val night = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            color = if (night) EdgeBubbleGeometry.BG_DARK_INT else EdgeBubbleGeometry.BG_LIGHT_INT
        }
        private val containerRectF = RectF()
        private val baseTileWidth = (fullLayout.iconPx + fullLayout.innerPx * 2).toFloat()
        private val cornerRadiusPx = EdgeBubbleGeometry.CONTAINER_RADIUS_DP * density
        private val iconLocalLeft = (fullLayout.iconRect.left - fullLayout.containerRect.left)
        private val iconLocalTop = fullLayout.innerPx
        private var padExtension = 0f

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val path = squirclePath(
                        curPadLeft(),
                        0f,
                        curPadWidth(),
                        containerH.toFloat()
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        outline.setPath(path)
                    } else {
                        @Suppress("DEPRECATION")
                        outline.setConvexPath(path)
                    }
                }
            }
            clipToOutline = true
            stateListAnimator = null
            elevation = EdgeBubbleGeometry.ELEVATION_DP * density
            outlineAmbientShadowColor = EdgeBubbleGeometry.SHADOW_COLOR_INT
            outlineSpotShadowColor = EdgeBubbleGeometry.SHADOW_COLOR_INT
        }

        fun setPadExtension(ext: Float) {
            val clamped = ext.coerceIn(0f, 1f)
            if (abs(clamped - padExtension) <= 1e-3f) return
            padExtension = clamped
            invalidateOutline()
            invalidate()
        }

        private fun curPadLeft(): Float =
            if (fullLayout.side == EdgeBubbleGeometry.SIDE_LEFT) {
                fullLayout.screenMarginPx * (1f - padExtension)
            } else {
                0f
            }

        private fun curPadWidth(): Float =
            baseTileWidth + fullLayout.screenMarginPx * padExtension

        private fun squirclePath(l: Float, t: Float, w: Float, h: Float): Path {
            val span = min(
                cornerRadiusPx * (EdgeBubbleGeometry.SQUIRCLE_WEIGHT / 2f),
                min(w, h) / 2f - 1f
            )
            val handle = span * 0.6f
            val right = l + w
            val bottom = t + h
            return Path().apply {
                moveTo(l + span, t)
                lineTo(right - span, t)
                cubicTo(right - span + handle, t, right, t + span - handle, right, t + span)
                lineTo(right, bottom - span)
                cubicTo(right, bottom - span + handle, right - span + handle, bottom, right - span, bottom)
                lineTo(l + span, bottom)
                cubicTo(l + span - handle, bottom, l, bottom - span + handle, l, bottom - span)
                lineTo(l, t + span)
                cubicTo(l, t + span - handle, l + span - handle, t, l + span, t)
                close()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val left = curPadLeft()
            containerRectF.set(left, 0f, left + curPadWidth(), containerH.toFloat())
            canvas.drawRoundRect(containerRectF, cornerRadiusPx, cornerRadiusPx, bgPaint)
            val drawable = icon ?: return
            drawable.alpha = 255
            drawable.setBounds(
                iconLocalLeft,
                iconLocalTop,
                iconLocalLeft + fullLayout.iconPx,
                iconLocalTop + fullLayout.iconPx
            )
            drawable.draw(canvas)
        }
    }
}
