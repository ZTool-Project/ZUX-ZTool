package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.qimian233.ztool.data.freeform.FreeformAnimContract
import com.qimian233.ztool.data.freeform.FreeformEdgeBubbleConfig
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server side of freeform edge-bubble minimize, restore, and edge detection.
 *
 * Bridges commands/events with the ZTool service over ordered/targeted broadcasts,
 * bypasses OvFreeformService permission checks for ZTool, and tracks freeform bounds.
 */
// See docs/research/freeform_edge_bubble_android17.md
@SuppressLint("PrivateApi", "SdkLintPrivateApi", "BlockedPrivateApi")
class FreeformEdgeBubbleHook : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.FREEFORM_EDGE_BUBBLE.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    companion object {
        private const val ACTION_COMMAND = "com.qimian233.ztool.action.FREEFORM_BUBBLE_COMMAND"
        private const val ACTION_EVENT = "com.qimian233.ztool.action.FREEFORM_BUBBLE_EVENT"
        private const val ZTOOL_PACKAGE = "com.qimian233.ztool"

        // Command ids (app->server, extra "cmd"), mirrored in app-side FreeformBubbleBridge.
        private const val CMD_MINIMIZE_TASK = 1
        private const val CMD_RESTORE_TASK = 2
        private const val CMD_LIST_MINIMIZED = 3
        private const val CMD_GET_PACKAGE = 4
        private const val CMD_LIST_FREEFORM_TASKS = 5
        private const val CMD_TOGGLE_PROBE = 9

        // Event ids (server->app, extra "event").
        const val EVENT_BUBBLE_ADDED = 11
        const val EVENT_BUBBLE_REMOVED = 12

        private const val WINDOWING_MODE_FREEFORM = 5
        private const val MATCH_TASK_MODE_ANY = 0
        /** Fully-offscreen placement leaves this much extra gap beyond the window edge. */
        private const val OFFSCREEN_GAP_PX = 32
        /** Smallest inward nudge applied to restored bounds. */
        private const val RESTORE_CLEARANCE_MIN_PX = 96
        private const val BRIDGE_REGISTER_MAX_ATTEMPTS = 120
        private const val BRIDGE_REGISTER_RETRY_INTERVAL_MS = 500L
        private const val ADB_SHELL_UID = 2000

        @Volatile private var edgeTriggerPercent =
            PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT.default

        private val restoreRects = ConcurrentHashMap<Int, Rect>()
        private val minimizedSides = ConcurrentHashMap<Int, Int>()
        private val taskPackages = ConcurrentHashMap<Int, String>()
        /** Guards against re-entering bounds hooks from our own moves. */
        private val internalMove = ThreadLocal.withInitial { false }

        @Volatile private var ztoolUid = -1
        @Volatile private var systemUiUid = -1
        @Volatile private var atmsHandlesCache: AtmsHandles? = null
        @Volatile private var probeLogEnabled = false
        @Volatile private var hideShowControllerCache: Triple<Any?, Method?, Method?>? = null
        @Volatile private var bridgeRegistered = false
        private var eventPostHandler: Handler? = null
    }

    // region server-start wiring

    @Throws(Throwable::class)
    override fun handleSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        edgeTriggerPercent = readEdgeTriggerPercent()

        installCheckPermissionPassthrough(classLoader)
        installEdgeDetectionHook(classLoader)
        registerBroadcastBridge(classLoader)
    }

    private fun readEdgeTriggerPercent(): Int {
        val key = PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT
        val configured = try {
            remotePreferences.getInt(key.name, key.default)
        } catch (_: Throwable) {
            key.default
        }
        return FreeformEdgeBubbleConfig.normalizeTriggerPercent(configured)
    }

    private fun registerBroadcastBridge(classLoader: ClassLoader) {
        if (bridgeRegistered) return
        Thread({
            var lastError: Throwable? = null
            for (attempt in 1..BRIDGE_REGISTER_MAX_ATTEMPTS) {
                try {
                    if (registerCommandReceiver(classLoader)) return@Thread
                } catch (t: Throwable) {
                    lastError = (t as? InvocationTargetException)?.cause ?: t
                }
                try {
                    Thread.sleep(BRIDGE_REGISTER_RETRY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            logger.error(
                "register broadcast bridge failed after $BRIDGE_REGISTER_MAX_ATTEMPTS attempts",
                lastError
            )
        }, "ztool-bubble-bridge").start()
    }

    private fun registerCommandReceiver(classLoader: ClassLoader): Boolean {
        val systemContext = resolveSystemContext(classLoader) ?: return false
        if (eventPostHandler == null) {
            val thread = HandlerThread("ztool-bubble-events")
            thread.start()
            eventPostHandler = Handler(thread.looper)
        }
        val filter = IntentFilter(ACTION_COMMAND)
        val receiver = CommandReceiver(classLoader)
        val registerReceiver = systemContext.javaClass.getMethod(
            "registerReceiver",
            BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType
        )
        val flags = if (Build.VERSION.SDK_INT >= 33) 0x2 /* RECEIVER_EXPORTED */ else 0
        registerReceiver.invoke(systemContext, receiver, filter, flags)
        eventPostContext = systemContext
        bridgeRegistered = true
        logger.info("freeform bubble broadcast bridge registered")
        return true
    }

    private fun resolveSystemContext(classLoader: ClassLoader): Context? {
        return try {
            val atClass = classLoader.loadClass("android.app.ActivityThread")
            val current = atClass.getMethod("currentActivityThread").invoke(null) ?: return null
            val getSystemContext = atClass.getMethod("getSystemContext")
            getSystemContext.invoke(current) as? Context
        } catch (t: Throwable) {
            logger.debug("system context resolve failed: ${t.message}")
            null
        }
    }

    // endregion

    // region command receiver

    private inner class CommandReceiver(private val classLoader: ClassLoader) : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val ordered = isOrderedBroadcast
            val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    javaClass.getMethod("getSentFromUid").invoke(this) as Int
                } catch (_: Throwable) {
                    -1
                }
            } else {
                0
            }
            if (!isAllowedCaller(uid, classLoader)) {
                logger.warn("bridge command from untrusted uid=$uid")
                if (ordered) resultCode = 0
                return
            }
            val pending = goAsync()
            val startedAt = SystemClock.elapsedRealtime()
            val handler = eventPostHandler
            if (handler == null) {
                val (code, extras) = executeCommand(intent, startedAt)
                if (ordered) {
                    pending.resultCode = code
                    if (extras != null) pending.setResultExtras(extras)
                }
                pending.finish()
                return
            }
            handler.post {
                try {
                    val (code, extras) = executeCommand(intent, startedAt)
                    if (ordered) {
                        pending.resultCode = code
                        if (extras != null) pending.setResultExtras(extras)
                    }
                } catch (t: Throwable) {
                    logger.error("bridge command crashed", t)
                    if (ordered) pending.resultCode = 0
                } finally {
                    pending.finish()
                }
            }
        }

        private fun executeCommand(intent: Intent, startedAt: Long): Pair<Int, Bundle?> {
            var result: Int
            var extras: Bundle? = null
            when (intent.getIntExtra("cmd", -1)) {
                CMD_MINIMIZE_TASK -> {
                    val taskId = intent.getIntExtra(FreeformAnimContract.EXTRA_TASK_ID, -1)
                    val side = intent.getIntExtra(FreeformAnimContract.EXTRA_SIDE, 0)
                    val rl = intent.getIntExtra(FreeformAnimContract.EXTRA_RESTORE_LEFT, 0)
                    val rt = intent.getIntExtra(FreeformAnimContract.EXTRA_RESTORE_TOP, 0)
                    val rr = intent.getIntExtra(FreeformAnimContract.EXTRA_RESTORE_RIGHT, 0)
                    val rb = intent.getIntExtra(FreeformAnimContract.EXTRA_RESTORE_BOTTOM, 0)
                    val restoreBounds = if (rr > rl && rb > rt) Rect(rl, rt, rr, rb) else null
                    val centerYRatio = intent.getFloatExtra(
                        FreeformAnimContract.EXTRA_CENTER_Y_RATIO,
                        Float.NaN
                    )
                    val notifyBubble = intent.getBooleanExtra(
                        FreeformAnimContract.EXTRA_NOTIFY_BUBBLE,
                        false
                    )
                    result = if (withTaskHandles(classLoader) {
                            doMinimize(it, taskId, side, restoreBounds, centerYRatio, notifyBubble)
                        } == true
                    ) 1 else 0
                }
                CMD_RESTORE_TASK -> {
                    val taskId = intent.getIntExtra("task_id", -1)
                    result = if (withTaskHandles(classLoader) { doRestore(it, taskId) } == true) 1 else 0
                }
                CMD_LIST_MINIMIZED -> {
                    val bundle = withTaskHandles(classLoader) { collectLiveMinimized(it) }
                        ?: Bundle().apply {
                            val snapshot = minimizedSides.entries.toList()
                            putIntArray("task_ids", snapshot.map { it.key }.toIntArray())
                            putIntArray("sides", snapshot.map { it.value }.toIntArray())
                        }
                    result = 1
                    extras = bundle
                }
                CMD_GET_PACKAGE -> {
                    val bundle = Bundle()
                    bundle.putString("pkg", taskPackages[intent.getIntExtra("task_id", -1)])
                    result = 1
                    extras = bundle
                }
                CMD_TOGGLE_PROBE -> {
                    probeLogEnabled = !probeLogEnabled
                    logger.info("edge probe logging -> $probeLogEnabled")
                    result = 1
                }
                CMD_LIST_FREEFORM_TASKS -> {
                    val bundle = withTaskHandles(classLoader) { collectFreeformTaskInfos(it) }
                    result = if (bundle != null) 1 else 0
                    extras = bundle
                }
                else -> result = 0
            }
            logger.trace(
                "cmd=${intent.getIntExtra("cmd", -1)} result=$result " +
                    "took=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            return Pair(result, extras)
        }

        private fun isAllowedCaller(uid: Int, classLoader: ClassLoader): Boolean {
            if (uid == 0 || uid == ADB_SHELL_UID) return true
            if (ztoolUid > 0 && uid == ztoolUid) return true
            if (systemUiUid > 0 && uid == systemUiUid) return true
            if (uid <= 0) return false
            return try {
                val context = resolveSystemContext(classLoader) ?: return false
                val pm = context.packageManager
                if (ztoolUid <= 0) {
                    runCatching { ztoolUid = pm.getPackageUid(ZTOOL_PACKAGE, 0) }
                }
                if (systemUiUid <= 0) {
                    runCatching {
                        systemUiUid = pm.getPackageUid(ScopeKeys.SYSTEM_UI.packageName, 0)
                    }
                }
                uid == ztoolUid || uid == systemUiUid
            } catch (_: Throwable) {
                false
            }
        }
    }

    // endregion

    // region task operations (WMS lock held)

    private inline fun <T> withTaskHandles(
        classLoader: ClassLoader,
        block: (AtmsHandles) -> T
    ): T? {
        return try {
            val handles = resolveAtmsHandles(classLoader)
            synchronized(handles.globalLock) { block(handles) }
        } catch (t: Throwable) {
            logger.error(
                "bridge operation failed",
                (t as? InvocationTargetException)?.cause ?: t
            )
            null
        }
    }

    private class AtmsHandles(
        val globalLock: Any,
        val rootWindowContainer: Any,
        val hideShowController: Any?,
        val bringToBack: Method?,
        val bringToFront: Method?,
        val getRootTask: Method?,
        val setAlwaysOnTop: Method?,
        val moveTaskToBack: Method?,
        val resumeFocusedTasks: Method?,
        val getAllRootTaskInfos: Method?,
        val getAllRootTaskInfosByDisplay: Method?,
        val anyTaskForId: Method,
        val taskClass: Class<*>,
        val getBounds: Method,
        val getBoundsWithScale: Method?,
        val setBounds: Method,
        val setScaleTaskSurface: Method?,
        val scaleTaskSurface: Method?,
        val scaleXField: Field?,
        val scaleYField: Field?,
        val scaleSurfaceBoundsField: Field?,
        val moveToFront: Method?,
        val setLastNonFullscreenBounds: Method?
    )

    private fun resolveAtms(classLoader: ClassLoader): Any {
        val atmsClass = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
        return try {
            val getInstance = atmsClass.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            getInstance.invoke(null)
        } catch (_: Throwable) {
            // Fallback: LocalServices holds ActivityTaskManagerInternal; scan fields for ATMS.
            val localServicesClass = classLoader.loadClass("com.android.server.LocalServices")
            val internalClass = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerInternal")
            val getService = localServicesClass.getDeclaredMethod("getService", Class::class.java)
            val internal = getService.invoke(null, internalClass)
                ?: error("ActivityTaskManagerInternal not in LocalServices")
            for (cls in generateSequence(internal.javaClass) { it.superclass }) {
                for (field in cls.declaredFields) {
                    if (!Modifier.isStatic(field.modifiers)) {
                        try {
                            field.isAccessible = true
                            val value = field.get(internal)
                            if (value != null && value.javaClass.name == atmsClass.name) return value
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            error("no ATMS reference found on " + internal.javaClass.name)
        }
    }

    private fun resolveAtmsHandles(classLoader: ClassLoader): AtmsHandles {
        atmsHandlesCache?.let { cached ->
            if (cached.taskClass.classLoader === classLoader) return cached
        }
        val handles = resolveAtmsHandlesUncached(classLoader)
        atmsHandlesCache = handles
        return handles
    }

    private fun resolveAtmsHandlesUncached(classLoader: ClassLoader): AtmsHandles {
        val atms = resolveAtms(classLoader)
        val atmsClass = atms.javaClass
        val globalLock = findField(atmsClass, "mGlobalLock").get(atms)
            ?: error("ATMS.mGlobalLock is null")
        val rwc = findField(atmsClass, "mRootWindowContainer").get(atms)
            ?: error("ATMS.mRootWindowContainer is null")
        val rwcClass = classLoader.loadClass("com.android.server.wm.RootWindowContainer")
        val anyTaskForId = rwcClass.getMethod(
            "anyTaskForId", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )

        val taskClass = classLoader.loadClass("com.android.server.wm.Task")
        val hideShow = resolveHideShowController(classLoader, atms)
        return AtmsHandles(
            globalLock = globalLock,
            rootWindowContainer = rwc,
            hideShowController = hideShow?.first,
            bringToBack = hideShow?.second,
            bringToFront = hideShow?.third,
            getRootTask = try { taskClass.getMethod("getRootTask") } catch (_: Throwable) { null },
            setAlwaysOnTop = try {
                taskClass.getMethod("setAlwaysOnTop", Boolean::class.javaPrimitiveType)
            } catch (_: Throwable) { null },
            moveTaskToBack = try { taskClass.getMethod("moveTaskToBack", taskClass) } catch (_: Throwable) { null },
            resumeFocusedTasks = try {
                rwcClass.getMethod("resumeFocusedTasksTopActivities")
            } catch (_: Throwable) { null },
            getAllRootTaskInfos = try { rwcClass.getMethod("getAllRootTaskInfos") } catch (_: Throwable) { null },
            getAllRootTaskInfosByDisplay = try {
                rwcClass.getMethod("getAllRootTaskInfos", Int::class.javaPrimitiveType)
            } catch (_: Throwable) { null },
            anyTaskForId = anyTaskForId,
            taskClass = taskClass,
            getBounds = taskClass.getMethod("getBounds"),
            getBoundsWithScale = try {
                taskClass.getMethod("getBounds", Boolean::class.javaPrimitiveType)
            } catch (_: Throwable) { null },
            setBounds = taskClass.getMethod("setBounds", Rect::class.java),
            setScaleTaskSurface = try {
                findMethod(
                    taskClass,
                    "setScaleTaskSurface",
                    Rect::class.java,
                    Rect::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            } catch (_: Throwable) { null },
            scaleTaskSurface = try {
                findMethod(
                    taskClass,
                    "scaleTaskSurface",
                    Rect::class.java,
                    Rect::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
            } catch (_: Throwable) { null },
            scaleXField = try { findField(taskClass, "mScaleX") } catch (_: Throwable) { null },
            scaleYField = try { findField(taskClass, "mScaleY") } catch (_: Throwable) { null },
            scaleSurfaceBoundsField = try {
                findField(taskClass, "mScaleSurfaceBounds")
            } catch (_: Throwable) { null },
            moveToFront = try { taskClass.getMethod("moveToFront", String::class.java) } catch (_: Throwable) { null },
            setLastNonFullscreenBounds = try {
                taskClass.getMethod("setLastNonFullscreenBounds", Rect::class.java)
            } catch (_: Throwable) { null }
        )
    }

    private fun resolveHideShowController(classLoader: ClassLoader, atms: Any): Triple<Any?, Method?, Method?>? {
        if (hideShowControllerCache != null) return hideShowControllerCache
        return try {
            var manager: Any? = null
            val steps = StringBuilder()
            for (getter in arrayOf("getOvfMgr", "getOvcWmManager")) {
                try {
                    val result = atms.javaClass.getMethod(getter).invoke(atms)
                    if (result != null) {
                        steps.append(getter).append("=ok ")
                        if (result.javaClass.simpleName == "OvfWmFreeformManager") {
                            manager = result
                            break
                        }
                        try {
                            val fm = result.javaClass.getMethod("getFreeformManager").invoke(result)
                            if (fm != null) {
                                steps.append("getFreeformManager=ok ")
                                manager = fm
                                break
                            }
                        } catch (t: Throwable) {
                            steps.append("getFreeformManager=fail ").append(t.message).append(' ')
                        }
                    }
                } catch (t: Throwable) {
                    steps.append(getter).append("=fail ").append(t.message).append(' ')
                }
            }
            if (manager == null) {
                for (cls in generateSequence(atms.javaClass) { it.superclass }) {
                    for (field in cls.declaredFields) {
                        if (Modifier.isStatic(field.modifiers)) continue
                        try {
                            field.isAccessible = true
                            val value = field.get(atms)
                            if (value != null && value.javaClass.simpleName == "OvfWmFreeformManager") {
                                manager = value
                                steps.append("atmsFieldScan=ok ")
                                break
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    if (manager != null) break
                }
            }
            if (manager == null) {
                logger.info("hideShow resolve failed: no OvfWmFreeformManager; $steps")
                return null
            }
            var controller: Any? = null
            for (getter in arrayOf("getHideShowCtrl", "getHideShowController")) {
                try {
                    controller = manager.javaClass.getMethod(getter).invoke(manager)
                    steps.append(getter).append(if (controller != null) "=ok " else "=null ")
                    if (controller != null) break
                } catch (t: Throwable) {
                    steps.append(getter).append("=fail ").append(t.message).append(' ')
                }
            }
            if (controller == null) {
                for (cls in generateSequence(manager.javaClass) { it.superclass }) {
                    for (field in cls.declaredFields) {
                        if (Modifier.isStatic(field.modifiers)) continue
                        try {
                            field.isAccessible = true
                            val value = field.get(manager)
                            if (value != null && value.javaClass.simpleName == "OvfWmHideShowController") {
                                controller = value
                                steps.append("mgrFieldScan=ok ")
                                break
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    if (controller != null) break
                }
            }
            if (controller == null) {
                logger.info("hideShow resolve failed: no controller; $steps")
                return null
            }
            val toBack = controller.javaClass.getMethod(
                "bringToBack",
                classLoader.loadClass("com.android.server.wm.Task")
            )
            val toFront = controller.javaClass.getMethod(
                "bringToFront",
                classLoader.loadClass("com.android.server.wm.Task"),
                String::class.java,
                classLoader.loadClass("com.android.server.wm.TransitionController"),
                classLoader.loadClass("com.android.server.wm.Transition")
            )
            hideShowControllerCache = Triple(controller, toBack, toFront)
            logger.info("hideShow resolved: $steps")
            hideShowControllerCache
        } catch (t: Throwable) {
            logger.debug("resolveHideShowController failed: ${t.message}")
            null
        }
    }

    private fun findTask(handles: AtmsHandles, taskId: Int): Any? =
        handles.anyTaskForId.invoke(handles.rootWindowContainer, taskId, MATCH_TASK_MODE_ANY)

    private fun isScaledTask(handles: AtmsHandles, task: Any): Boolean {
        val scaleX = try {
            handles.scaleXField?.getFloat(task) ?: 0f
        } catch (_: Throwable) {
            0f
        }
        if (scaleX <= 0f) return false
        val scaleBounds = try {
            handles.scaleSurfaceBoundsField?.get(task) as? Rect
        } catch (_: Throwable) {
            null
        }
        return scaleBounds != null && !scaleBounds.isEmpty
    }

    private fun getEffectiveBounds(handles: AtmsHandles, task: Any): Rect? {
        val scaled = try {
            handles.getBoundsWithScale?.invoke(task, true) as? Rect
        } catch (_: Throwable) {
            null
        }
        if (scaled != null && !scaled.isEmpty) return scaled
        return try {
            handles.getBounds.invoke(task) as? Rect
        } catch (_: Throwable) {
            null
        }
    }

    private fun dockTaskBehindHome(handles: AtmsHandles, task: Any): Boolean {
        if (handles.getRootTask != null && handles.setAlwaysOnTop != null &&
            handles.moveTaskToBack != null
        ) {
            // Skip OvfWmHideShowController.bringToBack's TO_BACK transition; its BLAST sync
            // waits up to 5 s on paused surfaces.
            val rootTask = handles.getRootTask.invoke(task)
            internalMove.set(true)
            try {
                handles.setAlwaysOnTop.invoke(rootTask, false)
                handles.moveTaskToBack.invoke(rootTask, task)
                try {
                    handles.resumeFocusedTasks?.invoke(handles.rootWindowContainer)
                } catch (t: Throwable) {
                    logger.debug("resumeFocusedTasks failed: ${t.message}")
                }
            } finally {
                internalMove.set(false)
            }
            return true
        }
        if (handles.hideShowController != null && handles.bringToBack != null) {
            handles.bringToBack.invoke(handles.hideShowController, task)
            return true
        }
        return false
    }

    private fun moveTaskBounds(handles: AtmsHandles, task: Any, target: Rect) {
        if (isScaledTask(handles, task)) {
            // Scaled tasks (e.g. WeChat with i_f_drag_mode=0) position via mScaleSurfaceBounds;
            // Task.setBounds(Rect) returns 0 when hasUniformScaleSurface() is true.
            val initBounds = handles.getBounds.invoke(task) as Rect
            val scaleX = handles.scaleXField?.getFloat(task) ?: 0f
            val scaleY = handles.scaleYField?.getFloat(task) ?: scaleX
            when {
                handles.setScaleTaskSurface != null ->
                    handles.setScaleTaskSurface.invoke(task, target, initBounds, scaleX, scaleY, true)
                handles.scaleTaskSurface != null ->
                    handles.scaleTaskSurface.invoke(task, target, initBounds, scaleX, scaleY)
                else -> handles.setBounds.invoke(task, target)
            }
        } else {
            handles.setBounds.invoke(task, target)
        }
    }

    private fun doMinimize(
        handles: AtmsHandles,
        taskId: Int,
        side: Int,
        restoreBounds: Rect? = null,
        centerYRatio: Float = Float.NaN,
        notifyBubble: Boolean = false
    ): Boolean {
        val task = findTask(handles, taskId) ?: run {
            logger.warn("minimize: task $taskId not found")
            return false
        }
        val bounds = if (restoreBounds != null && !restoreBounds.isEmpty) {
            Rect(restoreBounds)
        } else {
            val effective = getEffectiveBounds(handles, task) ?: return false
            if (effective.isEmpty) return false
            Rect(effective)
        }
        restoreRects[taskId] = Rect(bounds)
        minimizedSides[taskId] = side
        if (!isScaledTask(handles, task)) {
            try {
                handles.setLastNonFullscreenBounds?.invoke(task, bounds)
            } catch (t: Throwable) {
                logger.debug("setLastNonFullscreenBounds failed: ${t.message}")
            }
        }
        if (dockTaskBehindHome(handles, task)) {
            if (notifyBubble) {
                notifyBubbleAdded(task, handles.taskClass, side, centerYRatio)
            }
            logger.info("minimize task=$taskId side=$side via dockToBack, bounds=$bounds")
            return true
        }
        val offscreen = Rect(bounds)
        offscreen.offset(
            if (side == 0) -(bounds.width() + OFFSCREEN_GAP_PX) else bounds.width() + OFFSCREEN_GAP_PX,
            0
        )
        internalMove.set(true)
        try {
            moveTaskBounds(handles, task, offscreen)
        } finally {
            internalMove.set(false)
        }
        logger.info("minimize task=$taskId side=$side bounds=$bounds -> $offscreen")
        return true
    }

    private fun doRestore(handles: AtmsHandles, taskId: Int): Boolean {
        val task = findTask(handles, taskId) ?: run {
            logger.warn("restore: task $taskId not found")
            restoreRects.remove(taskId)
            minimizedSides.remove(taskId)
            return false
        }
        val stored = restoreRects.remove(taskId) ?: run {
            logger.warn("restore: no stored bounds for task $taskId")
            return false
        }
        minimizedSides.remove(taskId)
        val displayBounds = try {
            val dc = handles.taskClass.getMethod("getDisplayContent").invoke(task)
            dc?.javaClass?.getMethod("getBounds")?.invoke(dc) as? Rect
        } catch (_: Throwable) {
            null
        }
        val target = Rect(stored)
        if (displayBounds != null && !displayBounds.isEmpty) {
            val clearance = RESTORE_CLEARANCE_MIN_PX
            val dx = when {
                target.left < displayBounds.left + clearance ->
                    displayBounds.left + clearance - target.left
                target.right > displayBounds.right - clearance ->
                    displayBounds.right - clearance - target.right
                else -> 0
            }
            val dy = when {
                target.top < displayBounds.top + clearance ->
                    displayBounds.top + clearance - target.top
                target.bottom > displayBounds.bottom - clearance ->
                    displayBounds.bottom - clearance - target.bottom
                else -> 0
            }
            target.offset(dx, dy)
        }
        if (handles.hideShowController != null && handles.bringToFront != null) {
            try {
                internalMove.set(true)
                moveTaskBounds(handles, task, target)
            } catch (t: Throwable) {
                logger.warn(
                    "restore moveTaskBounds failed: " + ((t as? InvocationTargetException)?.cause ?: t)
                )
            } finally {
                internalMove.set(false)
            }
            try {
                handles.bringToFront.invoke(
                    handles.hideShowController, task, "ztool_freeform_bubble", null, null
                )
            } catch (t: Throwable) {
                logger.warn(
                    "bringToFront failed: " + ((t as? InvocationTargetException)?.cause ?: t)
                )
            }
            notifyBubbleRemoved(taskId)
            logger.info("restore task=$taskId via bringToFront -> $target")
            return true
        }
        internalMove.set(true)
        try {
            moveTaskBounds(handles, task, target)
            try {
                handles.moveToFront?.invoke(task, "ztool_freeform_bubble")
            } catch (t: Throwable) {
                logger.debug("moveToFront failed: ${t.message}")
            }
        } finally {
            internalMove.set(false)
        }
        notifyBubbleRemoved(taskId)
        logger.info("restore task=$taskId -> $target")
        return true
    }

    private fun collectFreeformTaskInfos(handles: AtmsHandles): Bundle? {
        return try {
            val infos = (handles.getAllRootTaskInfos?.invoke(handles.rootWindowContainer)
                ?: handles.getAllRootTaskInfosByDisplay?.invoke(handles.rootWindowContainer, -1)
                ) as? List<*>
            if (infos == null) {
                logger.warn("getAllRootTaskInfos unavailable")
                return null
            }
            val taskIds = ArrayList<Int>()
            val boundsList = ArrayList<Int>()
            val pkgs = ArrayList<String?>()
            val hidden = ArrayList<Boolean>()
            val taskClass = handles.taskClass
            val getMode = try { taskClass.getMethod("getWindowingMode") } catch (_: Throwable) { null }
            val isVisible = try { taskClass.getMethod("isVisible") } catch (_: Throwable) { null }
            for (info in infos) {
                val ti = info ?: continue
                val taskId = try {
                    ti.javaClass.getField("taskId").getInt(ti)
                } catch (_: Throwable) {
                    continue
                }
                val task = findTask(handles, taskId) ?: continue
                val mode = try { getMode?.invoke(task) as? Int } catch (_: Throwable) { null } ?: continue
                if (mode != WINDOWING_MODE_FREEFORM) continue
                val bounds = getEffectiveBounds(handles, task) ?: continue
                if (bounds.isEmpty) continue
                val pkg = try {
                    (ti.javaClass.getField("topActivity").get(ti) as? ComponentName)?.packageName
                } catch (_: Throwable) {
                    null
                }
                val visible = try { isVisible?.invoke(task) as? Boolean } catch (_: Throwable) { null } ?: true
                taskIds.add(taskId)
                boundsList.addAll(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
                pkgs.add(pkg)
                hidden.add(!visible)
            }
            Bundle().apply {
                putIntArray("task_ids", taskIds.toIntArray())
                putIntArray("bounds", boundsList.toIntArray())
                putStringArray("pkgs", pkgs.toTypedArray())
                putBooleanArray("hidden", hidden.toBooleanArray())
            }
        } catch (t: Throwable) {
            logger.warn("collectFreeformTaskInfos failed: ${t.message}")
            null
        }
    }

    private fun collectLiveMinimized(handles: AtmsHandles): Bundle {
        val getMode = try {
            handles.taskClass.getMethod("getWindowingMode")
        } catch (_: Throwable) {
            null
        }
        val isVisible = try {
            handles.taskClass.getMethod("isVisible")
        } catch (_: Throwable) {
            null
        }
        val taskIds = ArrayList<Int>(minimizedSides.size)
        val sides = ArrayList<Int>(minimizedSides.size)
        for ((taskId, side) in minimizedSides.entries.toList()) {
            val task = findTask(handles, taskId)
            val mode = if (task != null) {
                try { getMode?.invoke(task) as? Int } catch (_: Throwable) { null }
            } else {
                null
            }
            val visible = if (task != null && handles.moveTaskToBack != null) {
                try { isVisible?.invoke(task) as? Boolean } catch (_: Throwable) { null }
            } else {
                null
            }
            if (task == null ||
                (mode != null && mode != WINDOWING_MODE_FREEFORM) ||
                visible == true
            ) {
                minimizedSides.remove(taskId)
                restoreRects.remove(taskId)
                taskPackages.remove(taskId)
                continue
            }
            taskIds.add(taskId)
            sides.add(side)
        }
        return Bundle().apply {
            putIntArray("task_ids", taskIds.toIntArray())
            putIntArray("sides", sides.toIntArray())
        }
    }

    // endregion

    // region hooks

    @Throws(Throwable::class)
    private fun installCheckPermissionPassthrough(classLoader: ClassLoader) {
        val serviceClass = try {
            classLoader.loadClass("com.android.server.wm.OvFreeformService")
        } catch (_: Throwable) {
            logger.debug("OvFreeformService not present on this build, skip passthrough")
            return
        }
        val checkPermission: Method = try {
            findMethod(serviceClass, "checkPermission", String::class.java)
        } catch (_: Throwable) {
            logger.debug("OvFreeformService.checkPermission not found, skip passthrough")
            return
        }
        hookWithId(checkPermission, "freeform_edge_bubble_ovf_perm") { chain ->
            val callingUid = Binder.getCallingUid()
            if (callingUid == ztoolUid || callingUid == ADB_SHELL_UID) {
                logger.debug("checkPermission passthrough for uid=$callingUid")
                return@hookWithId null
            }
            chain.proceed()
        }
    }

    @Throws(Throwable::class)
    private fun installEdgeDetectionHook(classLoader: ClassLoader) {
        val windowContainerClass = classLoader.loadClass("com.android.server.wm.WindowContainer")
        val setBounds: Method = findMethod(windowContainerClass, "setBounds", Rect::class.java)
        val taskClass = classLoader.loadClass("com.android.server.wm.Task")
        val getWindowingMode = taskClass.getMethod("getWindowingMode")
        val getDisplayContent = taskClass.getMethod("getDisplayContent")

        hookWithId(setBounds, "freeform_edge_bubble_detect") { chain ->
            val task = chain.thisObject
            if (internalMove.get() == true || !taskClass.isInstance(task)) {
                return@hookWithId chain.proceed()
            }
            val newBounds = chain.getArg(0) as? Rect ?: return@hookWithId chain.proceed()
            val candidate = evaluateEdgeCandidate(task, newBounds, getWindowingMode, getDisplayContent)
            val result = chain.proceed()
            if (candidate != null) {
                applyEdgeTransition(classLoader, taskClass, task, newBounds, candidate)
            }
            result
        }

        val setScaleTaskSurface = try {
            findMethod(
                taskClass,
                "setScaleTaskSurface",
                Rect::class.java,
                Rect::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            null
        }
        if (setScaleTaskSurface != null) {
            hookWithId(setScaleTaskSurface, "freeform_edge_bubble_detect_scale") { chain ->
                val task = chain.thisObject
                if (internalMove.get() == true || !taskClass.isInstance(task)) {
                    return@hookWithId chain.proceed()
                }
                val newScaleBounds = chain.getArg(0) as? Rect ?: return@hookWithId chain.proceed()
                val scaleX = chain.getArg(2) as? Float ?: 0f
                if (scaleX <= 0f) {
                    return@hookWithId chain.proceed()
                }
                val candidate = evaluateEdgeCandidate(task, newScaleBounds, getWindowingMode, getDisplayContent)
                val result = chain.proceed()
                if (candidate != null) {
                    applyEdgeTransition(classLoader, taskClass, task, newScaleBounds, candidate)
                }
                result
            }
        }
    }

    private data class EdgeCandidate(val taskId: Int, val wasTracked: Boolean, val side: Int)

    private fun evaluateEdgeCandidate(
        task: Any?,
        newBounds: Rect,
        getWindowingMode: Method,
        getDisplayContent: Method
    ): EdgeCandidate? {
        if (task == null || newBounds.isEmpty) return null
        val windowingMode = try {
            getWindowingMode.invoke(task) as Int
        } catch (_: Throwable) {
            -1
        }
        if (windowingMode != WINDOWING_MODE_FREEFORM) return null
        val displayBounds = try {
            val dc = getDisplayContent.invoke(task)
            dc?.javaClass?.getMethod("getBounds")?.invoke(dc) as? Rect
        } catch (_: Throwable) {
            null
        }
        val taskId = task.taskIdCompat()
        val wasTracked = minimizedSides.containsKey(taskId)
        val side = if (displayBounds == null) {
            -1
        } else {
            val offLeft = (displayBounds.left - newBounds.left).coerceAtLeast(0)
            val offRight = (newBounds.right - displayBounds.right).coerceAtLeast(0)
            val triggerWidth = newBounds.width().coerceAtLeast(1) * edgeTriggerPercent / 100f
            when {
                offLeft >= triggerWidth -> 0
                offRight >= triggerWidth -> 1
                else -> -1
            }
        }
        return EdgeCandidate(taskId, wasTracked, side)
    }

    private fun applyEdgeTransition(
        classLoader: ClassLoader,
        taskClass: Class<*>,
        task: Any?,
        newBounds: Rect,
        candidate: EdgeCandidate
    ) {
        if (task == null) return
        if (candidate.side >= 0 && !candidate.wasTracked) {
            restoreRects[candidate.taskId] = Rect(newBounds)
            minimizedSides[candidate.taskId] = candidate.side
            notifyBubbleAdded(task, taskClass, candidate.side)
            try {
                val h = resolveAtmsHandles(classLoader)
                if (dockTaskBehindHome(h, task)) {
                    logger.info("auto-dock task=${candidate.taskId}")
                }
            } catch (t: Throwable) {
                logger.warn("auto-dock failed: ${t.message}")
            }
        } else if (candidate.side < 0 && candidate.wasTracked) {
            minimizedSides.remove(candidate.taskId)
            restoreRects.remove(candidate.taskId)
            notifyBubbleRemoved(candidate.taskId)
        }
    }

    private fun Any?.taskIdCompat(): Int {
        val self = this ?: return -1
        return try {
            self.javaClass.getMethod("getTaskId").invoke(self) as Int
        } catch (_: Throwable) {
            val f = self.javaClass.getField("mTaskId")
            f.getInt(self)
        }
    }

    private fun notifyBubbleAdded(
        task: Any,
        taskClass: Class<*>,
        side: Int,
        centerYRatio: Float = Float.NaN
    ) {
        val taskId = task.taskIdCompat()
        val pkg = try {
            val intent = taskClass.getMethod("getBaseIntent").invoke(task) as? Intent
            intent?.component?.packageName ?: intent?.`package`
        } catch (_: Throwable) {
            null
        }
        if (pkg != null) taskPackages[taskId] = pkg
        minimizedSides[taskId] = side
        sendEvent(EVENT_BUBBLE_ADDED) { intent ->
            intent.putExtra(FreeformAnimContract.EXTRA_TASK_ID, taskId)
            intent.putExtra(FreeformAnimContract.EXTRA_SIDE, side)
            intent.putExtra("pkg", pkg)
            if (!centerYRatio.isNaN()) {
                intent.putExtra(FreeformAnimContract.EXTRA_CENTER_Y_RATIO, centerYRatio)
            }
        }
        logger.info("bubble add task=$taskId side=$side pkg=$pkg")
    }

    private fun notifyBubbleRemoved(taskId: Int) {
        taskPackages.remove(taskId)
        sendEvent(EVENT_BUBBLE_REMOVED) { intent ->
            intent.putExtra("task_id", taskId)
        }
        logger.info("bubble remove task=$taskId")
    }

    private fun sendEvent(event: Int, payload: (Intent) -> Unit) {
        val handler = eventPostHandler ?: return
        handler.post {
            try {
                val context = eventPostContext ?: return@post
                val intent = Intent(ACTION_EVENT).setPackage(ZTOOL_PACKAGE)
                intent.putExtra("event", event)
                payload(intent)
                context.sendBroadcast(intent)
            } catch (t: Throwable) {
                logger.warn("sendEvent event=$event failed: ${t.message}")
            }
        }
    }

    @Volatile private var eventPostContext: Context? = null

    // endregion
}
