package com.qimian233.ztool.hook.modules.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformAnimCurvesTest {

    @Test
    fun evaluateSpring_clampsBoundaries() {
        assertEquals(0f, FreeformAnimCurves.evaluateSpring(-0.5f, 0.78f, 15.5f), 1e-5f)
        assertEquals(0f, FreeformAnimCurves.evaluateSpring(0f, 0.78f, 15.5f), 1e-5f)
        assertEquals(1f, FreeformAnimCurves.evaluateSpring(1f, 0.78f, 15.5f), 1e-5f)
        assertEquals(1f, FreeformAnimCurves.evaluateSpring(1.5f, 0.78f, 15.5f), 1e-5f)
    }

    @Test
    fun evaluateSpring_underdampedHasGentleOvershoot() {
        val samples = (0..200).map { i ->
            FreeformAnimCurves.evaluateSpring(i / 200f, 0.78f, 15.5f)
        }
        val max = samples.maxOrNull() ?: 0f
        assertTrue("Expected gentle overshoot > 1.0f, got $max", max > 1.0f)
        assertTrue("Expected overshoot <= 1.06f, got $max", max <= 1.06f)
        assertTrue("Expected non-negative values", samples.all { it >= 0f })
    }

    @Test
    fun evaluateSpring_criticallyDampedIsMonotonicWithoutOvershoot() {
        val samples = (0..200).map { i ->
            FreeformAnimCurves.evaluateSpring(i / 200f, 1.0f, 15.0f)
        }
        for (i in 1 until samples.size) {
            assertTrue(
                "Expected monotonic increase at $i: ${samples[i - 1]} -> ${samples[i]}",
                samples[i] >= samples[i - 1] - 1e-6f
            )
        }
        assertTrue("Expected no overshoot above 1.0f", samples.all { it <= 1.0001f })

        val overdamped = (0..200).map { i ->
            FreeformAnimCurves.evaluateSpring(i / 200f, 1.25f, 15.0f)
        }
        assertTrue("Expected no overshoot for dampingRatio > 1", overdamped.all { it <= 1.0001f })
    }

    @Test
    fun computeShrinkFrame_normalAndScaledTasks() {
        val start = FreeformAnimCurves.computeShrinkFrame(
            startLeft = 200f,
            startTop = 300f,
            startWidth = 600f,
            startHeight = 900f,
            baseScaleX = 1.0f,
            baseScaleY = 1.0f,
            targetCenterX = 0f,
            targetCenterY = 750f,
            endScaleRatio = 0.16f,
            progress = 0f
        )
        assertEquals(200f, start.posX, 1e-3f)
        assertEquals(300f, start.posY, 1e-3f)
        assertEquals(1.0f, start.scaleX, 1e-4f)
        assertEquals(1.0f, start.scaleY, 1e-4f)

        val end = FreeformAnimCurves.computeShrinkFrame(
            startLeft = 200f,
            startTop = 300f,
            startWidth = 600f,
            startHeight = 900f,
            baseScaleX = 1.0f,
            baseScaleY = 1.0f,
            targetCenterX = 0f,
            targetCenterY = 750f,
            endScaleRatio = 0.16f,
            progress = 1f
        )
        assertEquals(0.16f, end.scaleX, 1e-4f)
        assertEquals(0.16f, end.scaleY, 1e-4f)
        assertEquals(-(600f * 0.16f) / 2f, end.posX, 1e-3f)
        assertEquals(750f - (900f * 0.16f) / 2f, end.posY, 1e-3f)

        // WeChat scaled task (baseScale = 0.75f, visual size 600x900)
        val scaledEnd = FreeformAnimCurves.computeShrinkFrame(
            startLeft = 100f,
            startTop = 200f,
            startWidth = 600f,
            startHeight = 900f,
            baseScaleX = 0.75f,
            baseScaleY = 0.75f,
            targetCenterX = 1200f,
            targetCenterY = 650f,
            endScaleRatio = 0.16f,
            progress = 1f
        )
        assertEquals(0.75f * 0.16f, scaledEnd.scaleX, 1e-4f)
        assertEquals(0.75f * 0.16f, scaledEnd.scaleY, 1e-4f)
        assertEquals(1200f - (600f * 0.16f) / 2f, scaledEnd.posX, 1e-3f)
        assertEquals(650f - (900f * 0.16f) / 2f, scaledEnd.posY, 1e-3f)
    }

    @Test
    fun computeExpandFrame_normalAndScaledTasks() {
        val start = FreeformAnimCurves.computeExpandFrame(
            bubbleCenterX = 40f,
            bubbleCenterY = 600f,
            bubbleWidth = 140f,
            bubbleHeight = 120f,
            targetLeft = 300f,
            targetTop = 200f,
            logicalWidth = 800f,
            logicalHeight = 1200f,
            baseScaleX = 1.0f,
            baseScaleY = 1.0f,
            progress = 0f
        )
        assertEquals(140f / 800f, start.scaleX, 1e-4f)
        assertEquals(120f / 1200f, start.scaleY, 1e-4f)
        assertEquals(40f - 140f / 2f, start.posX, 1e-3f)
        assertEquals(600f - 120f / 2f, start.posY, 1e-3f)

        val end = FreeformAnimCurves.computeExpandFrame(
            bubbleCenterX = 40f,
            bubbleCenterY = 600f,
            bubbleWidth = 140f,
            bubbleHeight = 120f,
            targetLeft = 300f,
            targetTop = 200f,
            logicalWidth = 800f,
            logicalHeight = 1200f,
            baseScaleX = 1.0f,
            baseScaleY = 1.0f,
            progress = 1f
        )
        assertEquals(1.0f, end.scaleX, 1e-4f)
        assertEquals(1.0f, end.scaleY, 1e-4f)
        assertEquals(300f, end.posX, 1e-3f)
        assertEquals(200f, end.posY, 1e-3f)

        val scaledEnd = FreeformAnimCurves.computeExpandFrame(
            bubbleCenterX = 1160f,
            bubbleCenterY = 500f,
            bubbleWidth = 140f,
            bubbleHeight = 120f,
            targetLeft = 250f,
            targetTop = 180f,
            logicalWidth = 800f,
            logicalHeight = 1200f,
            baseScaleX = 0.75f,
            baseScaleY = 0.75f,
            progress = 1f
        )
        assertEquals(0.75f, scaledEnd.scaleX, 1e-4f)
        assertEquals(0.75f, scaledEnd.scaleY, 1e-4f)
        assertEquals(250f, scaledEnd.posX, 1e-3f)
        assertEquals(180f, scaledEnd.posY, 1e-3f)
    }

    @Test
    fun computeOnscreenSnapOffset_coversAllEdgeCases() {
        // Left overhang
        assertEquals(120, FreeformAnimCurves.computeOnscreenSnapOffset(-120, 480, 0, 1200))
        // Right overhang
        assertEquals(-150, FreeformAnimCurves.computeOnscreenSnapOffset(750, 1350, 0, 1200))
        // Inside display
        assertEquals(0, FreeformAnimCurves.computeOnscreenSnapOffset(100, 700, 0, 1200))
        assertEquals(0, FreeformAnimCurves.computeOnscreenSnapOffset(0, 1200, 0, 1200))
        // Oversized window spanning whole display
        assertEquals(0, FreeformAnimCurves.computeOnscreenSnapOffset(-100, 1300, 0, 1200))
        // Oversized window shifted off right leaving left gap
        assertEquals(-50, FreeformAnimCurves.computeOnscreenSnapOffset(50, 1450, 0, 1200))
        // Oversized window shifted off left leaving right gap
        assertEquals(50, FreeformAnimCurves.computeOnscreenSnapOffset(-250, 1150, 0, 1200))
    }
}
