package com.qimian233.ztool.utils

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qimian233.ztool.data.freeform.FreeformAnimContract
import com.qimian233.ztool.data.keys.ScopeKeys

/**
 * App-side client for the freeform edge-bubble bridge inside system_server.
 *
 * The channel is ordered broadcasts (SELinux forbids publishing custom services from
 * system_server): commands go out as package-targeted ordered broadcasts to "android"
 * and the return value comes back via the broadcast result; hook-side events arrive as
 * broadcasts to ZTool. Must stay in sync with
 * [com.qimian233.ztool.hook.modules.systemframework.FreeformEdgeBubbleHook].
 */
object FreeformBubbleBridge {

    private const val TAG = "FreeformBubbleBridge"
    const val ACTION_COMMAND = "com.qimian233.ztool.action.FREEFORM_BUBBLE_COMMAND"
    const val ACTION_EVENT = "com.qimian233.ztool.action.FREEFORM_BUBBLE_EVENT"
    const val EVENT_BUBBLE_ADDED = 11
    const val EVENT_BUBBLE_REMOVED = 12

    /** A minimized freeform task as reported by the hook. */
    data class MinimizedTask(val taskId: Int, val side: Int, val packageName: String?)

    /** A freeform task snapshot for app-side edge detection (cmd 5). */
    data class FreeformTask(
        val taskId: Int,
        val bounds: android.graphics.Rect,
        val hidden: Boolean,
        val packageName: String?
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Sends a command and delivers the hook's resultCode/resultExtras to [onResult]
     * on the main thread. Returns false immediately when the ordered broadcast has no
     * receiver in system_server (bridge not registered yet).
     */
    private fun sendCommand(
        build: (Intent) -> Unit,
        onResult: (resultCode: Int, extras: Bundle?) -> Unit
    ): Boolean {
        val intent = Intent(ACTION_COMMAND).setPackage("android")
        build(intent)
        return try {
            val context = appContext ?: return false
            context.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, received: Intent) {
                        onResult(resultCode, getResultExtras(true))
                    }
                },
                mainHandler, Activity.RESULT_CANCELED, null, null
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendCommand failed: ${t.message}")
            false
        }
    }

    @Volatile private var appContext: Context? = null

    /** Call once from the Application/Service so broadcasts can be sent. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Docks the freeform task fully offscreen; `side` is 0 (left) or 1 (right). */
    fun minimizeTask(taskId: Int, side: Int, onResult: (Boolean) -> Unit = {}) {
        sendCommand({ it.putExtra("cmd", 1).putExtra("task_id", taskId).putExtra("side", side) }
        ) { code, _ -> onResult(code == 1) }
    }

    /** Brings the task back to its pre-dock bounds and to the front. */
    fun restoreTask(taskId: Int, onResult: (Boolean) -> Unit = {}) {
        sendCommand({ it.putExtra("cmd", 2).putExtra("task_id", taskId) }
        ) { code, _ -> onResult(code == 1) }
    }

    /** Sends the bubble's screen rect to SystemUI before restoring the task. */
    fun notifySystemUiRestoreHint(
        taskId: Int,
        bubbleRect: Rect,
        side: Int,
        onDone: () -> Unit
    ) {
        val context = appContext
        if (context == null) {
            onDone()
            return
        }
        val intent = Intent(FreeformAnimContract.ACTION_SYSTEMUI_ANIM_CMD)
            .setPackage(ScopeKeys.SYSTEM_UI.packageName)
            .apply {
                putExtra(FreeformAnimContract.EXTRA_CMD, FreeformAnimContract.CMD_PREPARE_RESTORE)
                putExtra(FreeformAnimContract.EXTRA_TASK_ID, taskId)
                putExtra(FreeformAnimContract.EXTRA_SIDE, side)
                putExtra(FreeformAnimContract.EXTRA_BUBBLE_LEFT, bubbleRect.left)
                putExtra(FreeformAnimContract.EXTRA_BUBBLE_TOP, bubbleRect.top)
                putExtra(FreeformAnimContract.EXTRA_BUBBLE_RIGHT, bubbleRect.right)
                putExtra(FreeformAnimContract.EXTRA_BUBBLE_BOTTOM, bubbleRect.bottom)
            }
        try {
            context.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, received: Intent) {
                        onDone()
                    }
                },
                mainHandler, Activity.RESULT_CANCELED, null, null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "notifySystemUiRestoreHint failed: ${t.message}")
            onDone()
        }
    }

    fun listMinimized(onResult: (List<MinimizedTask>) -> Unit = {}) {
        sendCommand({ it.putExtra("cmd", 3) }) { code, extras ->
            if (code != 1 || extras == null) {
                onResult(emptyList())
                return@sendCommand
            }
            val taskIds = extras.getIntArray("task_ids") ?: IntArray(0)
            val sides = extras.getIntArray("sides") ?: IntArray(0)
            val out = ArrayList<MinimizedTask>(taskIds.size)
            // Package names resolve asynchronously; chain the lookups.
            fun resolve(i: Int) {
                if (i >= taskIds.size) {
                    onResult(out)
                    return
                }
                getPackageForTask(taskIds[i]) { pkg ->
                    out.add(MinimizedTask(taskIds[i], sides.getOrElse(i) { 0 }, pkg))
                    resolve(i + 1)
                }
            }
            resolve(0)
        }
    }

    fun getPackageForTask(taskId: Int, onResult: (String?) -> Unit = {}) {
        sendCommand({ it.putExtra("cmd", 4).putExtra("task_id", taskId) }) { _, extras ->
            onResult(extras?.getString("pkg"))
        }
    }

    /**
     * Snapshots every freeform task (bounds, package, visibility) for app-side edge
     * detection. [bounds] uses real display pixel coordinates.
     */
    fun listFreeformTasks(onResult: (List<FreeformTask>) -> Unit = {}) {
        sendCommand({ it.putExtra("cmd", 5) }) { code, extras ->
            if (code != 1 || extras == null) {
                onResult(emptyList())
                return@sendCommand
            }
            val ids = extras.getIntArray("task_ids") ?: IntArray(0)
            val b = extras.getIntArray("bounds") ?: IntArray(0)
            val pkgs = extras.getStringArray("pkgs") ?: arrayOf()
            val hidden = extras.getBooleanArray("hidden") ?: BooleanArray(0)
            val out = ArrayList<FreeformTask>(ids.size)
            for (i in ids.indices) {
                val o = i * 4
                if (o + 3 >= b.size) break
                out.add(
                    FreeformTask(
                        ids[i],
                        android.graphics.Rect(b[o], b[o + 1], b[o + 2], b[o + 3]),
                        hidden.getOrElse(i) { false },
                        pkgs.getOrNull(i)
                    )
                )
            }
            onResult(out)
        }
    }

    /**
     * Registers a receiver for hook-side bubble events. The returned receiver must be
     * unregistered with [unregisterEventReceiver] when the host service stops.
     */
    fun registerEventReceiver(
        context: Context,
        onBubbleAdded: (taskId: Int, side: Int, pkg: String?, centerYRatio: Float) -> Unit,
        onBubbleRemoved: (taskId: Int) -> Unit
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra("event", -1)) {
                    EVENT_BUBBLE_ADDED -> onBubbleAdded(
                        intent.getIntExtra(FreeformAnimContract.EXTRA_TASK_ID, -1),
                        intent.getIntExtra(FreeformAnimContract.EXTRA_SIDE, 0),
                        intent.getStringExtra("pkg"),
                        intent.getFloatExtra(FreeformAnimContract.EXTRA_CENTER_Y_RATIO, Float.NaN)
                    )
                    EVENT_BUBBLE_REMOVED -> onBubbleRemoved(
                        intent.getIntExtra(FreeformAnimContract.EXTRA_TASK_ID, -1)
                    )
                }
            }
        }
        val filter = IntentFilter(ACTION_EVENT)
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        return receiver
    }

    fun unregisterEventReceiver(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterEventReceiver: ${t.message}")
        }
    }
}
