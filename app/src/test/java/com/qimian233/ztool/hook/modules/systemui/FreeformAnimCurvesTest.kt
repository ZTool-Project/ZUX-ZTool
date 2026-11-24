package com.qimian233.ztool.hook.modules.systemui

import com.qimian233.ztool.data.freeform.EdgeBubbleGeometry
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

    @Test
    fun resolveEdgeDockSide_handlesDirectThresholdEdgeZoneAndFling() {
        // Direct threshold reach on left and right
        assertEquals(
            0,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 500,
                offRight = 0,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 300f,
                displayWidth = 3200,
                velocityXPxPerSec = 0f,
                density = 2.5f
            )
        )
        assertEquals(
            1,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 0,
                offRight = 494,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 2900f,
                displayWidth = 3200,
                velocityXPxPerSec = 0f,
                density = 2.5f
            )
        )
        // Exact tablet bezel case: centered handle hits right bezel at 491px overhang (< 494px)
        assertEquals(
            1,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 0,
                offRight = 491,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 3197f,
                displayWidth = 3200,
                velocityXPxPerSec = 0f,
                density = 2.5f
            )
        )
        // Tiny overhang inside edge zone must NOT accidentally dock
        assertEquals(
            -1,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 0,
                offRight = 50,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 3190f,
                displayWidth = 3200,
                velocityXPxPerSec = 0f,
                density = 2.5f
            )
        )
        // Edge-directed fling triggers with >= 15% overhang
        assertEquals(
            1,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 0,
                offRight = 180,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 2880f,
                displayWidth = 3200,
                velocityXPxPerSec = 1800f,
                density = 2.5f
            )
        )
        // Fling away from edge does not dock
        assertEquals(
            -1,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 0,
                offRight = 180,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 2880f,
                displayWidth = 3200,
                velocityXPxPerSec = -1800f,
                density = 2.5f
            )
        )
        // Release short of bezel without fling snaps back
        assertEquals(
            -1,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 0,
                offRight = 150,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 2856f,
                displayWidth = 3200,
                velocityXPxPerSec = 0f,
                density = 2.5f
            )
        )
        // Fully onscreen never docks
        assertEquals(
            -1,
            FreeformAnimCurves.resolveEdgeDockSide(
                offLeft = 0,
                offRight = 0,
                windowWidth = 988,
                triggerPercent = 50,
                pointerRawX = 3198f,
                displayWidth = 3200,
                velocityXPxPerSec = 2500f,
                density = 2.5f
            )
        )
    }

    @Test
    fun animDurations_reflect300msExtension() {
        assertEquals(680L, FreeformAnimCurves.OPEN_ANIM_DURATION_MS)
        assertEquals(480L, FreeformAnimCurves.OPEN_ALPHA_DURATION_MS)
        assertEquals(560L, FreeformAnimCurves.CLOSE_SCALE_DURATION_MS)
        assertEquals(510L, FreeformAnimCurves.CLOSE_ALPHA_DURATION_MS)
        assertEquals(650L, FreeformAnimCurves.ICON_EXPAND_DURATION_MS)
        assertEquals(540L, FreeformAnimCurves.EDGE_DOCK_DURATION_MS)
        assertEquals(560L, FreeformAnimCurves.SNAP_BACK_DURATION_MS)
        assertEquals(680L, FreeformAnimCurves.RESTORE_DURATION_MS)
    }

    @Test
    fun restoreBubbleHideProgress_crossesEarlyInSpring() {
        val threshold = FreeformAnimCurves.RESTORE_BUBBLE_HIDE_PROGRESS
        assertTrue("Expected threshold in (0f, 0.5f), got $threshold", threshold > 0f && threshold < 0.5f)
        val atQuarter = FreeformAnimCurves.evaluateSpring(
            0.25f,
            FreeformAnimCurves.RESTORE_SPRING_DAMPING,
            FreeformAnimCurves.RESTORE_SPRING_OMEGA
        )
        assertTrue(
            "Expected restore spring to cross $threshold before t=0.25f, got $atQuarter",
            atQuarter >= threshold
        )
    }

    @Test
    fun computeFullLayout_leftRightAndClampingMatchEdgeBubbleView() {
        val density = 2.5f
        val left = EdgeBubbleGeometry.computeFullLayout(
            side = EdgeBubbleGeometry.SIDE_LEFT,
            centerYRatio = 0.5f,
            displayWidth = 3200,
            displayHeight = 2000,
            density = density
        )
        assertEquals(235, left.containerRect.width)
        assertEquals(160, left.containerRect.height)
        assertEquals(-60, left.containerRect.left)
        assertEquals(920, left.containerRect.top)
        assertEquals(35, left.iconRect.left)
        assertEquals(155, left.iconRect.right)
        assertEquals(940, left.iconRect.top)
        assertEquals(1060, left.iconRect.bottom)

        val right = EdgeBubbleGeometry.computeFullLayout(
            side = EdgeBubbleGeometry.SIDE_RIGHT,
            centerYRatio = 0.5f,
            displayWidth = 3200,
            displayHeight = 2000,
            density = density
        )
        assertEquals(235, right.containerRect.width)
        assertEquals(3025, right.containerRect.left)
        assertEquals(3260, right.containerRect.right)
        assertEquals(3045, right.iconRect.left)
        assertEquals(3165, right.iconRect.right)
        assertEquals(940, right.iconRect.top)
        assertEquals(1060, right.iconRect.bottom)

        val clampedTop = EdgeBubbleGeometry.computeFullLayout(
            side = EdgeBubbleGeometry.SIDE_LEFT,
            centerYRatio = 0f,
            displayWidth = 3200,
            displayHeight = 2000,
            density = density
        )
        assertEquals(20, clampedTop.containerRect.top)

        val clampedBottom = EdgeBubbleGeometry.computeFullLayout(
            side = EdgeBubbleGeometry.SIDE_RIGHT,
            centerYRatio = 1f,
            displayWidth = 3200,
            displayHeight = 2000,
            density = density
        )
        assertEquals(1820, clampedBottom.containerRect.top)
    }

    @Test
    fun computeShrinkToRectFrame_normalAndScaledTasksLandOnIconRect() {
        val start = FreeformAnimCurves.computeShrinkToRectFrame(
            startLeft = 200f,
            startTop = 300f,
            startWidth = 600f,
            startHeight = 900f,
            baseScaleX = 1.0f,
            baseScaleY = 1.0f,
            targetLeft = 35f,
            targetTop = 940f,
            targetWidth = 120f,
            targetHeight = 120f,
            progress = 0f
        )
        assertEquals(200f, start.posX, 1e-3f)
        assertEquals(300f, start.posY, 1e-3f)
        assertEquals(1.0f, start.scaleX, 1e-4f)
        assertEquals(1.0f, start.scaleY, 1e-4f)

        val end = FreeformAnimCurves.computeShrinkToRectFrame(
            startLeft = 200f,
            startTop = 300f,
            startWidth = 600f,
            startHeight = 900f,
            baseScaleX = 1.0f,
            baseScaleY = 1.0f,
            targetLeft = 35f,
            targetTop = 940f,
            targetWidth = 120f,
            targetHeight = 120f,
            progress = 1f
        )
        assertEquals(35f, end.posX, 1e-3f)
        assertEquals(940f, end.posY, 1e-3f)
        assertEquals(120f / 600f, end.scaleX, 1e-4f)
        assertEquals(120f / 900f, end.scaleY, 1e-4f)

        val scaledEnd = FreeformAnimCurves.computeShrinkToRectFrame(
            startLeft = 2500f,
            startTop = 200f,
            startWidth = 600f,
            startHeight = 900f,
            baseScaleX = 0.75f,
            baseScaleY = 0.75f,
            targetLeft = 3045f,
            targetTop = 940f,
            targetWidth = 120f,
            targetHeight = 120f,
            progress = 1f
        )
        assertEquals(3045f, scaledEnd.posX, 1e-3f)
        assertEquals(940f, scaledEnd.posY, 1e-3f)
        assertEquals(0.75f * (120f / 600f), scaledEnd.scaleX, 1e-4f)
        assertEquals(0.75f * (120f / 900f), scaledEnd.scaleY, 1e-4f)
    }

    @Test
    fun computeDockMorphFrame_crossfadesAndExtendsPadSmoothly() {
        val start = FreeformAnimCurves.computeDockMorphFrame(0f)
        assertEquals(1f, start.windowAlpha, 1e-5f)
        assertEquals(0f, start.overlayAlpha, 1e-5f)
        assertEquals(0f, start.padExtension, 1e-5f)
        assertEquals(FreeformAnimCurves.EDGE_DOCK_OVERLAY_START_SCALE, start.overlayScale, 1e-4f)

        val mid = FreeformAnimCurves.computeDockMorphFrame(0.45f)
        assertTrue("Expected window alpha in (0, 1), got ${mid.windowAlpha}", mid.windowAlpha in 0.01f..0.99f)
        assertTrue("Expected overlay alpha in (0, 1), got ${mid.overlayAlpha}", mid.overlayAlpha in 0.01f..0.99f)
        assertTrue(
            "Expected shrinking overlay scale < startScale, got ${mid.overlayScale}",
            mid.overlayScale < FreeformAnimCurves.EDGE_DOCK_OVERLAY_START_SCALE && mid.overlayScale > 1f
        )
        assertEquals(0f, mid.padExtension, 1e-5f)

        val late = FreeformAnimCurves.computeDockMorphFrame(0.80f)
        assertEquals(0f, late.windowAlpha, 1e-5f)
        assertEquals(1f, late.overlayAlpha, 1e-5f)
        assertTrue("Expected padExtension in (0, 1), got ${late.padExtension}", late.padExtension in 0.01f..0.99f)

        val end = FreeformAnimCurves.computeDockMorphFrame(1f)
        assertEquals(0f, end.windowAlpha, 1e-5f)
        assertEquals(1f, end.overlayAlpha, 1e-5f)
        assertEquals(1f, end.overlayScale, 1e-5f)
        assertEquals(1f, end.padExtension, 1e-5f)
    }
}
