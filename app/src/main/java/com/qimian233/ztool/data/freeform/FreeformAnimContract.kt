package com.qimian233.ztool.data.freeform

/** Shared IPC constants for SystemUI, system_server, and EdgeBubbleService animation handoffs. */
object FreeformAnimContract {
    const val ACTION_SYSTEMUI_ANIM_CMD = "com.qimian233.ztool.action.FREEFORM_ANIM_COMMAND"
    const val ACTION_BUBBLE_EVENT = "com.qimian233.ztool.action.FREEFORM_BUBBLE_EVENT"
    const val CMD_PREPARE_RESTORE = 1
    const val EVENT_BUBBLE_HIDE_HANDOFF = 13

    const val EXTRA_CMD = "cmd"
    const val EXTRA_EVENT = "event"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_SIDE = "side"

    const val EXTRA_BUBBLE_LEFT = "bubble_left"
    const val EXTRA_BUBBLE_TOP = "bubble_top"
    const val EXTRA_BUBBLE_RIGHT = "bubble_right"
    const val EXTRA_BUBBLE_BOTTOM = "bubble_bottom"

    const val EXTRA_RESTORE_LEFT = "restore_left"
    const val EXTRA_RESTORE_TOP = "restore_top"
    const val EXTRA_RESTORE_RIGHT = "restore_right"
    const val EXTRA_RESTORE_BOTTOM = "restore_bottom"
    const val EXTRA_CENTER_Y_RATIO = "center_y_ratio"
    const val EXTRA_NOTIFY_BUBBLE = "notify_bubble"
}
