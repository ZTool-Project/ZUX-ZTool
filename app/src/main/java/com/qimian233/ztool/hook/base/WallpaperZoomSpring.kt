package com.qimian233.ztool.hook.base

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/** Choreographer-driven damped spring for main-thread wallpaper zoom overshoot bounce. */
class WallpaperZoomSpring(
    private val stiffness: Float = 420f,
    private val dampingRatio: Float = 0.80f,
    private val minValue: Float = 0f,
    private val maxValue: Float = 1.55f,
    private val onUpdate: (Float) -> Unit
) {
    private var position = 0f
    private var target = 1f
    private var velocity = 0f
    private var lastFrameNanos = 0L

    var isRunning: Boolean = false
        private set

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            val dt = if (lastFrameNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.032f)
            }
            lastFrameNanos = frameTimeNanos

            val damping = 2f * dampingRatio * sqrt(stiffness)
            val accel = -stiffness * (position - target) - damping * velocity
            velocity += accel * dt
            position = (position + velocity * dt).coerceIn(minValue, maxValue)
            if ((position == minValue && velocity < 0f) || (position == maxValue && velocity > 0f)) {
                velocity = 0f
            }

            if (abs(position - target) < 0.001f && abs(velocity) < 0.005f) {
                isRunning = false
                position = target
                onUpdate(target)
            } else {
                onUpdate(position)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun start(from: Float, target: Float, velocity: Float) {
        cancel()
        position = from.coerceIn(minValue, maxValue)
        this.target = target.coerceIn(minValue, maxValue)
        this.velocity = velocity
        lastFrameNanos = System.nanoTime()
        isRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun cancel() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }
}
