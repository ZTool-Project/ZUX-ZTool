package com.qimian233.ztool.hook.modules.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformAnimOptionsTest {

    @Test
    fun masterOff_disablesAllEffectiveAndDerivedFlags() {
        val options = FreeformAnimOptions(
            master = false,
            open = true,
            close = true,
            boundsChange = true,
            edgeSnapBack = true,
            edgeDock = true,
            bubbleRestore = true
        )

        assertFalse(options.effectiveOpen)
        assertFalse(options.effectiveClose)
        assertFalse(options.effectiveBoundsChange)
        assertFalse(options.effectiveEdgeSnapBack)
        assertFalse(options.effectiveEdgeDock)
        assertFalse(options.effectiveBubbleRestore)
        assertFalse(options.enabled)
        assertFalse(options.needsOvfTransitionClass)
        assertFalse(options.needsScaleFix)
        assertFalse(options.needsEdgeDragHook)
        assertFalse(options.needsSysUiContextAndReceiver)
        assertFalse(options.needsTaskListener)
        assertFalse(options.needsBubbleRestoreTransition)
        assertFalse(options.needsDockSuppressor)
        assertFalse(options.needsChangeReflect)
    }

    @Test
    fun masterOn_allSubFlagsOff_disablesEverything() {
        val options = FreeformAnimOptions(
            master = true,
            open = false,
            close = false,
            boundsChange = false,
            edgeSnapBack = false,
            edgeDock = false,
            bubbleRestore = false
        )

        assertFalse(options.effectiveOpen)
        assertFalse(options.effectiveClose)
        assertFalse(options.effectiveBoundsChange)
        assertFalse(options.effectiveEdgeSnapBack)
        assertFalse(options.effectiveEdgeDock)
        assertFalse(options.effectiveBubbleRestore)
        assertFalse(options.enabled)
        assertFalse(options.needsOvfTransitionClass)
        assertFalse(options.needsScaleFix)
        assertFalse(options.needsEdgeDragHook)
        assertFalse(options.needsSysUiContextAndReceiver)
        assertFalse(options.needsTaskListener)
        assertFalse(options.needsBubbleRestoreTransition)
        assertFalse(options.needsDockSuppressor)
        assertFalse(options.needsChangeReflect)
    }

    @Test
    fun masterOn_defaultsEnableAllEffectiveAndDerivedFlags() {
        val options = FreeformAnimOptions(master = true)

        assertTrue(options.effectiveOpen)
        assertTrue(options.effectiveClose)
        assertTrue(options.effectiveBoundsChange)
        assertTrue(options.effectiveEdgeSnapBack)
        assertTrue(options.effectiveEdgeDock)
        assertTrue(options.effectiveBubbleRestore)
        assertTrue(options.enabled)
        assertTrue(options.needsOvfTransitionClass)
        assertTrue(options.needsScaleFix)
        assertTrue(options.needsEdgeDragHook)
        assertTrue(options.needsSysUiContextAndReceiver)
        assertTrue(options.needsTaskListener)
        assertTrue(options.needsBubbleRestoreTransition)
        assertTrue(options.needsDockSuppressor)
        assertTrue(options.needsChangeReflect)
    }

    @Test
    fun isolatedOpen_enablesOnlyOpenGroup() {
        val options = allOffMasterOn().copy(open = true)

        assertTrue(options.effectiveOpen)
        assertFalse(options.effectiveClose)
        assertFalse(options.effectiveBoundsChange)
        assertFalse(options.effectiveEdgeSnapBack)
        assertFalse(options.effectiveEdgeDock)
        assertFalse(options.effectiveBubbleRestore)
        assertTrue(options.enabled)
        assertTrue(options.needsOvfTransitionClass)
        assertTrue(options.needsScaleFix)
        assertFalse(options.needsEdgeDragHook)
        assertFalse(options.needsSysUiContextAndReceiver)
        assertFalse(options.needsTaskListener)
        assertFalse(options.needsBubbleRestoreTransition)
        assertFalse(options.needsDockSuppressor)
        assertTrue(options.needsChangeReflect)
    }

    @Test
    fun isolatedClose_enablesOnlyCloseGroup() {
        val options = allOffMasterOn().copy(close = true)

        assertFalse(options.effectiveOpen)
        assertTrue(options.effectiveClose)
        assertFalse(options.effectiveBoundsChange)
        assertFalse(options.effectiveEdgeSnapBack)
        assertFalse(options.effectiveEdgeDock)
        assertFalse(options.effectiveBubbleRestore)
        assertTrue(options.enabled)
        assertTrue(options.needsOvfTransitionClass)
        assertTrue(options.needsScaleFix)
        assertFalse(options.needsEdgeDragHook)
        assertFalse(options.needsSysUiContextAndReceiver)
        assertFalse(options.needsTaskListener)
        assertFalse(options.needsBubbleRestoreTransition)
        assertFalse(options.needsDockSuppressor)
        assertTrue(options.needsChangeReflect)
    }

    @Test
    fun isolatedBoundsChange_enablesOnlyBoundsChangeGroup() {
        val options = allOffMasterOn().copy(boundsChange = true)

        assertFalse(options.effectiveOpen)
        assertFalse(options.effectiveClose)
        assertTrue(options.effectiveBoundsChange)
        assertFalse(options.effectiveEdgeSnapBack)
        assertFalse(options.effectiveEdgeDock)
        assertFalse(options.effectiveBubbleRestore)
        assertTrue(options.enabled)
        assertTrue(options.needsOvfTransitionClass)
        assertFalse(options.needsScaleFix)
        assertFalse(options.needsEdgeDragHook)
        assertFalse(options.needsSysUiContextAndReceiver)
        assertFalse(options.needsTaskListener)
        assertFalse(options.needsBubbleRestoreTransition)
        assertFalse(options.needsDockSuppressor)
        assertFalse(options.needsChangeReflect)
    }

    @Test
    fun isolatedEdgeSnapBack_enablesOnlyEdgeDragHook() {
        val options = allOffMasterOn().copy(edgeSnapBack = true)

        assertFalse(options.effectiveOpen)
        assertFalse(options.effectiveClose)
        assertFalse(options.effectiveBoundsChange)
        assertTrue(options.effectiveEdgeSnapBack)
        assertFalse(options.effectiveEdgeDock)
        assertFalse(options.effectiveBubbleRestore)
        assertTrue(options.enabled)
        assertFalse(options.needsOvfTransitionClass)
        assertFalse(options.needsScaleFix)
        assertTrue(options.needsEdgeDragHook)
        assertFalse(options.needsSysUiContextAndReceiver)
        assertFalse(options.needsTaskListener)
        assertFalse(options.needsBubbleRestoreTransition)
        assertFalse(options.needsDockSuppressor)
        assertFalse(options.needsChangeReflect)
    }

    @Test
    fun isolatedEdgeDock_enablesEdgeDockAndSupportGroups() {
        val options = allOffMasterOn().copy(edgeDock = true)

        assertFalse(options.effectiveOpen)
        assertFalse(options.effectiveClose)
        assertFalse(options.effectiveBoundsChange)
        assertFalse(options.effectiveEdgeSnapBack)
        assertTrue(options.effectiveEdgeDock)
        assertFalse(options.effectiveBubbleRestore)
        assertTrue(options.enabled)
        assertFalse(options.needsOvfTransitionClass)
        assertFalse(options.needsScaleFix)
        assertTrue(options.needsEdgeDragHook)
        assertTrue(options.needsSysUiContextAndReceiver)
        assertTrue(options.needsTaskListener)
        assertFalse(options.needsBubbleRestoreTransition)
        assertTrue(options.needsDockSuppressor)
        assertTrue(options.needsChangeReflect)
    }

    @Test
    fun isolatedBubbleRestore_enablesBubbleRestoreAndSupportGroups() {
        val options = allOffMasterOn().copy(bubbleRestore = true)

        assertFalse(options.effectiveOpen)
        assertFalse(options.effectiveClose)
        assertFalse(options.effectiveBoundsChange)
        assertFalse(options.effectiveEdgeSnapBack)
        assertFalse(options.effectiveEdgeDock)
        assertTrue(options.effectiveBubbleRestore)
        assertTrue(options.enabled)
        assertFalse(options.needsOvfTransitionClass)
        assertFalse(options.needsScaleFix)
        assertFalse(options.needsEdgeDragHook)
        assertTrue(options.needsSysUiContextAndReceiver)
        assertTrue(options.needsTaskListener)
        assertTrue(options.needsBubbleRestoreTransition)
        assertFalse(options.needsDockSuppressor)
        assertTrue(options.needsChangeReflect)
    }

    private fun allOffMasterOn() = FreeformAnimOptions(
        master = true,
        open = false,
        close = false,
        boundsChange = false,
        edgeSnapBack = false,
        edgeDock = false,
        bubbleRestore = false
    )
}
