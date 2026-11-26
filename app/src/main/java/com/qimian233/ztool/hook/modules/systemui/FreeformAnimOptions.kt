package com.qimian233.ztool.hook.modules.systemui

/** Effective scenario and hook-group flags for nonlinear freeform animations. */
internal data class FreeformAnimOptions(
    val master: Boolean = false,
    val open: Boolean = true,
    val close: Boolean = true,
    val boundsChange: Boolean = true,
    val edgeSnapBack: Boolean = true,
    val edgeDock: Boolean = true,
    val bubbleRestore: Boolean = true
) {
    val effectiveOpen: Boolean
        get() = master && open

    val effectiveClose: Boolean
        get() = master && close

    val effectiveBoundsChange: Boolean
        get() = master && boundsChange

    val effectiveEdgeSnapBack: Boolean
        get() = master && edgeSnapBack

    val effectiveEdgeDock: Boolean
        get() = master && edgeDock

    val effectiveBubbleRestore: Boolean
        get() = master && bubbleRestore

    val enabled: Boolean
        get() = effectiveOpen ||
            effectiveClose ||
            effectiveBoundsChange ||
            effectiveEdgeSnapBack ||
            effectiveEdgeDock ||
            effectiveBubbleRestore

    val needsOvfTransitionClass: Boolean
        get() = effectiveOpen || effectiveClose || effectiveBoundsChange

    val needsScaleFix: Boolean
        get() = effectiveOpen || effectiveClose

    val needsEdgeDragHook: Boolean
        get() = effectiveEdgeDock || effectiveEdgeSnapBack

    val needsSysUiContextAndReceiver: Boolean
        get() = effectiveEdgeDock || effectiveBubbleRestore

    val needsTaskListener: Boolean
        get() = effectiveEdgeDock || effectiveBubbleRestore

    val needsBubbleRestoreTransition: Boolean
        get() = effectiveBubbleRestore

    val needsDockSuppressor: Boolean
        get() = effectiveEdgeDock

    val needsChangeReflect: Boolean
        get() = effectiveClose || needsScaleFix || effectiveBubbleRestore || needsDockSuppressor
}
