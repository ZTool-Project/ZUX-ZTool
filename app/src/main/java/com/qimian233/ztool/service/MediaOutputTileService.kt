package com.qimian233.ztool.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Media output switcher tile: tapping it launches the system media output switcher dialog
 * (the MediaOutputDialog shown from the "Output switch" entry on the media card).
 *
 * Android 17 declares the AOSP "system" action as a protected broadcast that only
 * system/root processes may send, so this build switches to the unprotected action and
 * supplies the package name it expects; older builds keep the previous action.
 */
class MediaOutputTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(launchAction()).apply {
            setClassName(SYSTEMUI_PACKAGE, RECEIVER_CLASS)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        try {
            sendBroadcast(intent)
        } catch (t: Throwable) {
            // The protected-broadcast list is firmware-owned; a rejected tap must not kill
            // the module process (see docs/research/media_output_dialog_broadcast_android17.md).
            Log.w(TAG, "Media output dialog broadcast rejected", t)
        }
    }

    private fun launchAction(): String =
        if (Build.VERSION.SDK_INT >= 37) ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG
        else ACTION_LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG

    private companion object {
        const val TAG = "MediaOutputTile"
        const val SYSTEMUI_PACKAGE = "com.android.systemui"
        const val RECEIVER_CLASS = "com.android.systemui.media.dialog.MediaOutputDialogReceiver"
        // Public AOSP SystemUI action for the dialog bound to EXTRA_PACKAGE_NAME.
        const val ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG =
            "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
        // Session-less dialog action; protected since Android 17.
        const val ACTION_LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG =
            "com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG"
        // String extra read by MediaOutputDialogReceiver; empty means "cannot launch".
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
