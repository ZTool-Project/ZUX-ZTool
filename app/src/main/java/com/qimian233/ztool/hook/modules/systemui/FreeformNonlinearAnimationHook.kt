package com.qimian233.ztool.hook.modules.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.ScaleAnimation
import com.qimian233.ztool.data.freeform.EdgeBubbleGeometry
import com.qimian233.ztool.data.freeform.FreeformAnimContract
import com.qimian233.ztool.data.freeform.FreeformEdgeBubbleConfig
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Installs nonlinear freeform open/close/minimize curves, edge-dock shrink + snap-back,
 * and bubble-to-freeform restore expand animations in SystemUI WM Shell.
 */
@SuppressLint("PrivateApi", "NewApi")
class FreeformNonlinearAnimationHook : AppHookModule() {

    private data class RestoreHint(
        val bubbleRect: Rect,
        val side: Int,
        val createdAtMs: Long
    )

    private data class DragVelocityState(
        val lastX: Float,
        val lastTimeMs: Long,
        val vx: Float,
        val samples: Int
    )

    private data class DockedTaskMeta(
        val dockedAtMs: Long,
        val baseScaleX: Float,
        val baseScaleY: Float,
        val windowCornerRadius: Float,
        @Volatile var cleanedUp: Boolean = false
    )

    private class TaskInfoReflect(
        val tokenField: Field?,
        val displayIdField: Field?,
        val displayIdGetter: Method?,
        val getWindowingMode: Method,
        val getActivityType: Method?,
        val configField: Field?,
        val configGetter: Method?,
        val windowConfigField: Field?,
        val windowConfigGetter: Method?,
        val windowConfigBounds: Method,
        val scaleXField: Field?,
        val scaleYField: Field?,
        val scaleSurfaceBoundsField: Field?,
        val hangingScaleXField: Field?,
        val hangingScaleYField: Field?,
        val isHangingField: Field?,
        val isHangingGetter: Method?,
        val isVisibleRequestedField: Field?,
        val isRunningField: Field?,
        val pcModeZuiDesktopByDisplay: Method?,
        val pcModeUnderPc: Method?,
        val pcModeZuiDesktop: Method?,
        val pcModeDwOrCd: Method?,
        val pcModeConnectDisplay: Method?,
        val motionEventGetDisplayId: Method?,
        val txSetCornerRadius: Method?,
        val txShow: Method?,
        val txHide: Method?
    ) {
        fun displayId(taskInfo: ActivityManager.RunningTaskInfo): Int =
            runCatching {
                displayIdField?.getInt(taskInfo)
                    ?: (displayIdGetter?.invoke(taskInfo) as? Int)
                    ?: 0
            }.getOrDefault(0)

        fun windowingMode(taskInfo: ActivityManager.RunningTaskInfo): Int =
            runCatching { getWindowingMode.invoke(taskInfo) as? Int }.getOrNull() ?: -1

        fun activityType(taskInfo: ActivityManager.RunningTaskInfo): Int =
            runCatching { getActivityType?.invoke(taskInfo) as? Int }.getOrNull() ?: 0

        fun scaleX(taskInfo: ActivityManager.RunningTaskInfo): Float =
            runCatching { scaleXField?.getFloat(taskInfo) ?: 0f }.getOrDefault(0f)

        fun scaleY(taskInfo: ActivityManager.RunningTaskInfo): Float =
            runCatching { scaleYField?.getFloat(taskInfo) ?: 0f }.getOrDefault(0f)

        fun hangingScaleX(taskInfo: ActivityManager.RunningTaskInfo): Float =
            runCatching { hangingScaleXField?.getFloat(taskInfo) ?: 0f }.getOrDefault(0f)

        fun hangingScaleY(taskInfo: ActivityManager.RunningTaskInfo): Float =
            runCatching { hangingScaleYField?.getFloat(taskInfo) ?: 0f }.getOrDefault(0f)

        fun scaleSurfaceBounds(taskInfo: ActivityManager.RunningTaskInfo): Rect? =
            runCatching { scaleSurfaceBoundsField?.get(taskInfo) as? Rect }.getOrNull()

        fun logicalBounds(taskInfo: ActivityManager.RunningTaskInfo): Rect? =
            runCatching {
                val cfg = configGetter?.invoke(taskInfo) ?: configField?.get(taskInfo)
                val wc = (cfg as? Configuration)?.let {
                    windowConfigField?.get(it) ?: windowConfigGetter?.invoke(it)
                }
                wc?.let { windowConfigBounds.invoke(it) as? Rect }
            }.getOrNull()

        fun isHangingMode(taskInfo: ActivityManager.RunningTaskInfo): Boolean {
            if (hangingScaleX(taskInfo) > 0f) return true
            val flagged = runCatching {
                (isHangingField?.getBoolean(taskInfo) == true) ||
                    (isHangingGetter?.invoke(taskInfo) as? Boolean == true)
            }.getOrDefault(false)
            return flagged
        }

        fun isDesktopOrPcMode(taskInfo: ActivityManager.RunningTaskInfo): Boolean {
            if (runCatching { pcModeUnderPc?.invoke(null) as? Boolean == true }.getOrDefault(false)) {
                return true
            }
            val did = displayId(taskInfo)
            val perDisplay = pcModeDwOrCd ?: pcModeZuiDesktopByDisplay
            if (perDisplay != null &&
                runCatching { perDisplay.invoke(null, did) as? Boolean == true }.getOrDefault(false)
            ) {
                return true
            }
            val connectMethod = pcModeConnectDisplay
            if (connectMethod != null &&
                runCatching {
                    connectMethod.invoke(null, did) as? Boolean == true
                }.getOrDefault(false)
            ) {
                return true
            }
            return runCatching { pcModeZuiDesktop?.invoke(null) as? Boolean == true }
                .getOrDefault(false)
        }

        fun isVisibleRequested(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
            runCatching { isVisibleRequestedField?.getBoolean(taskInfo) != false }.getOrDefault(true)

        fun isRunning(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
            runCatching { isRunningField?.getBoolean(taskInfo) != false }.getOrDefault(true)

        fun taskToken(taskInfo: ActivityManager.RunningTaskInfo): Any? =
            runCatching { tokenField?.get(taskInfo) }.getOrNull()

        fun eventDisplayId(event: MotionEvent): Int =
            runCatching { motionEventGetDisplayId?.invoke(event) as? Int }.getOrNull() ?: 0

        fun setCornerRadius(tx: SurfaceControl.Transaction, leash: SurfaceControl, radius: Float) {
            runCatching { txSetCornerRadius?.invoke(tx, leash, radius) }
        }

        fun showSurface(tx: SurfaceControl.Transaction, leash: SurfaceControl) {
            val showMethod = txShow
            if (showMethod != null) {
                runCatching { showMethod.invoke(tx, leash) }
            } else {
                tx.setVisibility(leash, true)
            }
        }

        fun hideSurface(tx: SurfaceControl.Transaction, leash: SurfaceControl) {
            val hideMethod = txHide
            if (hideMethod != null) {
                runCatching { hideMethod.invoke(tx, leash) }
            } else {
                tx.setVisibility(leash, false)
            }
        }
    }

    private class ChangeReflect(
        val getMode: Method,
        val getStartAbsBounds: Method,
        val getEndAbsBounds: Method,
        val getLeash: Method,
        val getTaskInfo: Method
    )

    private class PositionerFields(
        val ownerClass: Class<*>,
        val ctrlType: Field?,
        val startPoint: Field?,
        val startBounds: Field?,
        val repositionBounds: Field?,
        val jankMonitor: Field?
    )

    private class DragReflect(
        val listenerIsDragging: Field?,
        val listenerSameMode: Field?,
        val listenerStartDisplayId: Field?,
        val listenerIsPinned: Field?,
        val listenerTaskId: Field?,
        val listenerDecorationFinder: Field?,
        val finderApply: Method?,
        val listenerDecoration: Field?,
        val listenerPositioner: Field?,
        val listenerDesktopController: Field?,
        val listenerDisplayController: Field?,
        val listenerOrganizer: Field?,
        val listenerTransitions: Field?,
        val listenerDragHandler: Field?,
        val listenerCurrentBounds: Field?,
        val listenerDragPointerId: Field?,
        val listenerDragInterrupted: Field?,
        val listenerCaptionTouchStatus: Field?,
        val captionIsPressed: Field?,
        val listenerPinnedController: Field?,
        val pinnedIsPinned: Method?,
        val listenerSetIsDragging: Method?,
        val listenerMultiDisplayIndicator: Field?,
        val multiDisplayOnDragEnd: Method?,
        val listenerTransactionFactory: Field?,
        val supplierGet: Method?,
        val decorGetTaskInfo: Method?,
        val decorTaskInfoField: Field?,
        val decorIsHandlingResize: Method?,
        val decorGetTaskSurface: Method?,
        val decorTaskSurfaceField: Field?,
        val decorGetValidDragArea: Method?,
        val decorValidDragAreaField: Field?,
        val decorSetIsDragging: Method?,
        val decorIsDraggingField: Field?,
        val decorCornerRadiusField: Field?,
        val decorCornerRadiusGetter: Method?,
        val controllerDisplayControllerField: Field?,
        val controllerOrganizerField: Field?,
        val controllerTransitionsField: Field?,
        val controllerDragHandlerField: Field?,
        val controllerGetVisualIndicator: Method?,
        val controllerVisualIndicatorField: Field?,
        val controllerReleaseVisualIndicator: Method?,
        val indicatorCalcType: Method?,
        val displayControllerGetLayout: Method,
        val displayLayoutWidth: Field,
        val displayLayoutHeight: Field,
        val setTaskDragging: Method?,
        val posFields: List<PositionerFields>,
        val jankMonitorEnd: Method?,
        val posDragExecutor: Field?,
        val posDragSession: Field?,
        val posDelegate: Field?,
        val sessionCtrlType: Field?,
        val sessionStartPoint: Field?,
        val sessionStartBounds: Field?,
        val sessionRepositionBounds: Field?,
        val execInitBounds: Field?,
        val execRescaleBounds: Field?,
        val execTmpRescaleBounds: Field?,
        val organizerScaleTaskSurface: Method?,
        val organizerApplyTransaction: Method?,
        val wctClass: Class<*>?,
        val wctCtor: java.lang.reflect.Constructor<*>?,
        val wctSetBounds: Method?,
        val transitionsStartTransition: Method?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRestoreHints = ConcurrentHashMap<Int, RestoreHint>()
    private val recentlyDockedTasks = ConcurrentHashMap<Int, DockedTaskMeta>()
    private val activeAnimators = ConcurrentHashMap<Int, ValueAnimator>()
    private val activeDockOverlays = ConcurrentHashMap<Int, EdgeDockMorphOverlay>()
    private val dockOverlayTimeouts = ConcurrentHashMap<Int, Runnable>()
    private val activeRestoreOverlays = ConcurrentHashMap<Int, EdgeRestoreMorphOverlay>()
    private val activelyRestoringTasks = ConcurrentHashMap.newKeySet<Int>()
    private val lastVisibleByTaskId = ConcurrentHashMap<Int, Boolean>()
    private val dragVelocityByTask = ConcurrentHashMap<Int, DragVelocityState>()

    @Volatile
    private var ztoolUid = -1

    @Volatile
    private var restoreReceiverRegistered = false

    @Volatile
    private var sysUiContext: Context? = null

    @Volatile
    private var freeformTaskListenerRef: Any? = null

    @Volatile
    private var freeformFindTaskSurface: Method? = null

    @Volatile
    private var activeOptions: FreeformAnimOptions = FreeformAnimOptions()

    override fun getModuleName(): String =
        PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun isEnabled(): Boolean = readOptions().enabled

    override fun handleLoadPackage(param: PackageLoadedParam) {
        try {
            install(param)
        } catch (t: Throwable) {
            logger.error("Freeform nonlinear anim install failed", t)
        }
    }

    private fun readOptions(): FreeformAnimOptions = try {
        val prefs = remotePreferences
        FreeformAnimOptions(
            master = prefs.getBoolean(
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM.name,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM.default
            ),
            open = prefs.getBoolean(
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_OPEN.name,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_OPEN.default
            ),
            close = prefs.getBoolean(
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_CLOSE.name,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_CLOSE.default
            ),
            boundsChange = prefs.getBoolean(
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BOUNDS_CHANGE.name,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BOUNDS_CHANGE.default
            ),
            edgeSnapBack = prefs.getBoolean(
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_SNAP_BACK.name,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_SNAP_BACK.default
            ),
            edgeDock = prefs.getBoolean(
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_DOCK.name,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_DOCK.default
            ),
            bubbleRestore = prefs.getBoolean(
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BUBBLE_RESTORE.name,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BUBBLE_RESTORE.default
            )
        )
    } catch (_: Throwable) {
        logger.warn("Failed to read freeform nonlinear anim preferences, using defaults")
        FreeformAnimOptions()
    }

    private fun installGroup(
        name: String,
        install: (MutableList<XposedInterface.HookHandle>) -> Unit
    ): Boolean {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        return try {
            install(handles)
            true
        } catch (failure: Throwable) {
            handles.forEach { runCatching { it.unhook() } }
            logger.warn("Freeform nonlinear $name failed: ${failure.message}")
            false
        }
    }

    private fun install(param: PackageLoadedParam) {
        val options = readOptions()
        activeOptions = options
        if (!options.enabled) return

        val loader = param.defaultClassLoader
        val taskReflect = resolveTaskInfoReflect(loader)
        val changeReflect = if (options.needsChangeReflect) resolveChangeReflect(loader) else null

        var openCloseReady = false
        if (options.needsOvfTransitionClass) {
            openCloseReady = installGroup("Part A open/close animations") { handles ->
                installOpenCloseAnimations(loader, taskReflect, changeReflect, options, handles)
            }
        }
        var edgeDragReady = false
        if (options.needsEdgeDragHook) {
            edgeDragReady = installGroup("Part B edge-dock/snap-back") { handles ->
                installEdgeDockAndSnapBack(loader, taskReflect, options, handles)
            }
        }
        var receiverReady = false
        if (options.needsSysUiContextAndReceiver) {
            receiverReady = installGroup("Part C restore broadcast receiver") { _ ->
                installRestoreBroadcastReceiver(loader)
            }
        }
        var bubbleRestoreReady = false
        if (options.needsBubbleRestoreTransition || options.needsTaskListener) {
            bubbleRestoreReady = installGroup("Part D bubble-restore expand") { handles ->
                installBubbleRestoreExpand(loader, taskReflect, changeReflect, options, handles)
            }
        }
        var dockSuppressorReady = false
        if (options.needsDockSuppressor) {
            dockSuppressorReady = installGroup("Dock transition suppressor") { handles ->
                installDockedTransitionSuppressor(loader, taskReflect, changeReflect, handles)
            }
        }

        logger.info(
            "Freeform nonlinear anim installed: " +
                "options=[open=${options.effectiveOpen}, close=${options.effectiveClose}, " +
                "boundsChange=${options.effectiveBoundsChange}, snapBack=${options.effectiveEdgeSnapBack}, " +
                "edgeDock=${options.effectiveEdgeDock}, bubbleRestore=${options.effectiveBubbleRestore}], " +
                "groups=[partA=$openCloseReady, partB=$edgeDragReady, partC=$receiverReady, " +
                "partD=$bubbleRestoreReady, dockSuppressor=$dockSuppressorReady]"
        )
    }

    // region Part A: open / close / minimize animations

    private fun installOpenCloseAnimations(
        loader: ClassLoader,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect?,
        options: FreeformAnimOptions,
        handles: MutableList<XposedInterface.HookHandle>
    ) {
        val ovfAnimClass = loadClassOptional(loader, OVF_TRANSITION_ANIM_CLASS) ?: return
        var hooksInstalled = 0

        if (options.effectiveOpen) {
            findMethodOptional(ovfAnimClass, "createTaskAnimToFront", Rect::class.java)?.let { m ->
                handles += hookWithId(m, "freeform_nonlinear_open_anim") { chain ->
                    val rect = chain.args.getOrNull(0) as? Rect ?: return@hookWithId chain.proceed()
                    buildOpenAnimationSet(rect)
                }
                hooksInstalled++
            }
        }

        if (options.effectiveClose) {
            findMethodOptional(ovfAnimClass, "createTaskAnimToBack", Rect::class.java)?.let { m ->
                handles += hookWithId(m, "freeform_nonlinear_close_back_anim") { chain ->
                    val rect = chain.args.getOrNull(0) as? Rect ?: return@hookWithId chain.proceed()
                    buildCloseAnimationSet(rect)
                }
                hooksInstalled++
            }
        }

        val changeClass = loadClassOptional(loader, TRANSITION_CHANGE_CLASS)
        if (options.effectiveClose && changeClass != null && changeReflect != null) {
            findMethodOptional(
                ovfAnimClass,
                "ovfBoundsVanishAnim",
                changeClass,
                SurfaceControl.Transaction::class.java
            )?.let { m ->
                handles += hookWithId(m, "freeform_nonlinear_vanish_bounds") { chain ->
                    handleBoundsVanishAnim(chain, taskReflect, changeReflect)
                }
                hooksInstalled++
            }

            findMethodOptional(
                ovfAnimClass,
                "ovfAlphaVanishAnim",
                changeClass,
                SurfaceControl.Transaction::class.java
            )?.let { m ->
                handles += hookWithId(m, "freeform_nonlinear_vanish_alpha") { chain ->
                    handleAlphaVanishAnim(chain, taskReflect, changeReflect)
                }
                hooksInstalled++
            }
        }

        if (options.effectiveBoundsChange) {
            val boundsChangeMethod = findMethodOptional(
                ovfAnimClass,
                "ovfBoundsChangeAnim",
                Rect::class.java,
                Rect::class.java,
                Rect::class.java,
                Rect::class.java,
                Rect::class.java,
                Interpolator::class.java,
                SurfaceControl::class.java,
                SurfaceControl.Transaction::class.java,
                IBinder::class.java
            ) ?: ovfAnimClass.declaredMethods.firstOrNull {
                it.name == "ovfBoundsChangeAnim" && it.parameterTypes.size == 9
            }?.apply { isAccessible = true }

            boundsChangeMethod?.let { m ->
                handles += hookWithId(m, "freeform_nonlinear_bounds_change") { chain ->
                    val startRect = chain.args.getOrNull(1) as? Rect
                    val endRect = chain.args.getOrNull(2) as? Rect
                    val result = chain.proceed()
                    val animator = result as? ValueAnimator ?: return@hookWithId result
                    if (startRect != null && endRect != null) {
                        if (endRect.width() >= startRect.width()) {
                            animator.duration = FreeformAnimCurves.ICON_EXPAND_DURATION_MS
                            animator.interpolator = FreeformAnimCurves.iconExpandInterpolator()
                        } else {
                            animator.duration = FreeformAnimCurves.CLOSE_SCALE_DURATION_MS
                            animator.interpolator = FreeformAnimCurves.closeScaleInterpolator()
                        }
                    }
                    animator
                }
                hooksInstalled++
            }
        }

        if (options.needsScaleFix) {
            val scaleUtilsClass = loadClassOptional(loader, OVF_SCALE_TRANSITION_UTILS_CLASS)
            if (scaleUtilsClass != null && changeClass != null && changeReflect != null) {
                val animSetScale = findMethodOptional(
                    Animation::class.java,
                    "setScale",
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
                val animHasScale = findMethodOptional(Animation::class.java, "hasScaleAnimation")
                findMethodOptional(
                    scaleUtilsClass,
                    "setScale",
                    changeClass,
                    Animation::class.java
                )?.let { m ->
                    handles += hookWithId(m, "freeform_nonlinear_scale_fix") { chain ->
                        handleScaleFix(
                            chain = chain,
                            taskReflect = taskReflect,
                            changeReflect = changeReflect,
                            animSetScale = animSetScale,
                            animHasScale = animHasScale,
                            options = options
                        )
                    }
                    hooksInstalled++
                }
            }
        }

        logger.info("Part A open/close animations installed ($hooksInstalled hooks)")
    }

    private fun buildOpenAnimationSet(rect: Rect): AnimationSet {
        val set = AnimationSet(false)
        val scale = ScaleAnimation(
            FreeformAnimCurves.OPEN_START_SCALE,
            1f,
            FreeformAnimCurves.OPEN_START_SCALE,
            1f,
            rect.width() / 2f,
            rect.height() / 2f
        ).apply {
            interpolator = FreeformAnimCurves.openSpringInterpolator()
            duration = FreeformAnimCurves.OPEN_ANIM_DURATION_MS
        }
        val alpha = AlphaAnimation(0f, 1f).apply {
            interpolator = FreeformAnimCurves.openAlphaInterpolator()
            duration = FreeformAnimCurves.OPEN_ALPHA_DURATION_MS
        }
        set.addAnimation(scale)
        set.addAnimation(alpha)
        set.detachWallpaper = true
        set.fillAfter = true
        set.initialize(rect.width(), rect.height(), rect.width(), rect.height())
        return set
    }

    private fun buildCloseAnimationSet(rect: Rect): AnimationSet {
        val set = AnimationSet(false)
        val scale = ScaleAnimation(
            1f,
            FreeformAnimCurves.CLOSE_END_SCALE,
            1f,
            FreeformAnimCurves.CLOSE_END_SCALE,
            rect.exactCenterX() - rect.left,
            rect.exactCenterY() - rect.top
        ).apply {
            interpolator = FreeformAnimCurves.closeScaleInterpolator()
            duration = FreeformAnimCurves.CLOSE_SCALE_DURATION_MS
        }
        val alpha = AlphaAnimation(1f, 0f).apply {
            interpolator = FreeformAnimCurves.closeAlphaInterpolator()
            duration = FreeformAnimCurves.CLOSE_ALPHA_DURATION_MS
        }
        set.addAnimation(scale)
        set.addAnimation(alpha)
        set.detachWallpaper = true
        set.fillAfter = true
        set.initialize(rect.width(), rect.height(), rect.width(), rect.height())
        return set
    }

    private fun handleBoundsVanishAnim(
        chain: XposedInterface.Chain,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect
    ): Any? {
        val change = chain.args.getOrNull(0) ?: return chain.proceed()
        val tx = chain.args.getOrNull(1) as? SurfaceControl.Transaction ?: return chain.proceed()
        val startBounds = runCatching {
            changeReflect.getStartAbsBounds.invoke(change) as? Rect
        }.getOrNull() ?: return chain.proceed()
        val leash = runCatching {
            changeReflect.getLeash.invoke(change) as? SurfaceControl
        }.getOrNull() ?: return chain.proceed()
        if (startBounds.isEmpty || !leash.isValid) return chain.proceed()

        val taskInfo = runCatching {
            changeReflect.getTaskInfo.invoke(change) as? ActivityManager.RunningTaskInfo
        }.getOrNull()
        if (taskInfo != null && isRecentlyDocked(taskInfo.taskId)) {
            taskReflect.hideSurface(tx, leash)
            tx.setAlpha(leash, 0f)
            tx.apply()
            return ValueAnimator.ofFloat(0f, 0f).apply { duration = 0L }
        }
        val (baseScaleX, baseScaleY) = resolveBaseScales(taskInfo, taskReflect, preferHanging = true)
        val visW = startBounds.width() * baseScaleX
        val visH = startBounds.height() * baseScaleY
        val centerX = startBounds.left + visW / 2f
        val centerY = startBounds.top + visH / 2f

        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FreeformAnimCurves.CLOSE_SCALE_DURATION_MS
            interpolator = FreeformAnimCurves.closeScaleInterpolator()
            addUpdateListener { anim ->
                if (!leash.isValid) return@addUpdateListener
                val p = anim.animatedValue as Float
                val frame = FreeformAnimCurves.computeShrinkFrame(
                    startLeft = startBounds.left.toFloat(),
                    startTop = startBounds.top.toFloat(),
                    startWidth = visW,
                    startHeight = visH,
                    baseScaleX = baseScaleX,
                    baseScaleY = baseScaleY,
                    targetCenterX = centerX,
                    targetCenterY = centerY,
                    endScaleRatio = FreeformAnimCurves.CLOSE_END_SCALE,
                    progress = p
                )
                tx.setScale(leash, frame.scaleX, frame.scaleY)
                tx.setPosition(leash, frame.posX, frame.posY)
                tx.apply()
            }
        }
    }

    private fun handleAlphaVanishAnim(
        chain: XposedInterface.Chain,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect
    ): Any? {
        val change = chain.args.getOrNull(0) ?: return chain.proceed()
        val tx = chain.args.getOrNull(1) as? SurfaceControl.Transaction ?: return chain.proceed()
        val leash = runCatching {
            changeReflect.getLeash.invoke(change) as? SurfaceControl
        }.getOrNull() ?: return chain.proceed()
        if (!leash.isValid) return chain.proceed()

        val taskInfo = runCatching {
            changeReflect.getTaskInfo.invoke(change) as? ActivityManager.RunningTaskInfo
        }.getOrNull()
        if (taskInfo != null && isRecentlyDocked(taskInfo.taskId)) {
            taskReflect.hideSurface(tx, leash)
            tx.setAlpha(leash, 0f)
            tx.apply()
            return ValueAnimator.ofFloat(0f, 0f).apply { duration = 0L }
        }
        if (taskInfo != null && taskReflect.pcModeZuiDesktopByDisplay != null) {
            val displayId = taskReflect.displayId(taskInfo)
            val inDesktop = runCatching {
                taskReflect.pcModeZuiDesktopByDisplay.invoke(null, displayId) as? Boolean == true
            }.getOrDefault(false)
            if (inDesktop && !taskReflect.isVisibleRequested(taskInfo) && taskReflect.isRunning(taskInfo)) {
                return chain.proceed()
            }
        }

        return ValueAnimator.ofFloat(1f, 0f).apply {
            duration = FreeformAnimCurves.CLOSE_ALPHA_DURATION_MS
            interpolator = FreeformAnimCurves.closeAlphaInterpolator()
            addUpdateListener { anim ->
                if (!leash.isValid) return@addUpdateListener
                tx.setAlpha(leash, anim.animatedValue as Float)
                tx.apply()
            }
        }
    }

    private fun handleScaleFix(
        chain: XposedInterface.Chain,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect,
        animSetScale: Method?,
        animHasScale: Method?,
        options: FreeformAnimOptions
    ): Any? {
        if (animSetScale == null) return chain.proceed()
        val change = chain.args.getOrNull(0) ?: return chain.proceed()
        val anim = chain.args.getOrNull(1) as? Animation ?: return chain.proceed()
        val taskInfo = runCatching {
            changeReflect.getTaskInfo.invoke(change) as? ActivityManager.RunningTaskInfo
        }.getOrNull() ?: return chain.proceed()
        val scaleX = taskReflect.scaleX(taskInfo)
        if (scaleX <= 0f) return chain.proceed()

        val mode = runCatching { changeReflect.getMode.invoke(change) as? Int }.getOrNull() ?: -1
        val hasScale = runCatching {
            if (animHasScale != null) {
                animHasScale.invoke(anim) as? Boolean == true
            } else {
                anim is ScaleAnimation ||
                    (anim is AnimationSet && anim.animations.any { it is ScaleAnimation })
            }
        }.getOrDefault(false)

        when {
            mode == 1 || mode == 3 || (mode == 6 && hasScale) -> {
                if (!options.effectiveOpen) return chain.proceed()
                val start = scaleX * FreeformAnimCurves.OPEN_START_SCALE
                animSetScale.invoke(anim, start, scaleX, start, scaleX)
                return null
            }
            mode == 2 || mode == 4 -> {
                if (!options.effectiveClose) return chain.proceed()
                val end = scaleX * FreeformAnimCurves.CLOSE_END_SCALE
                animSetScale.invoke(anim, scaleX, end, scaleX, end)
                return null
            }
            else -> return chain.proceed()
        }
    }

    // endregion

    // region Part B: edge-dock shrink & snap-back

    private fun installEdgeDockAndSnapBack(
        loader: ClassLoader,
        taskReflect: TaskInfoReflect,
        options: FreeformAnimOptions,
        handles: MutableList<XposedInterface.HookHandle>
    ) {
        val listenerClass = loadClassOptional(loader, TOUCH_EVENT_LISTENER_CLASS) ?: return
        val handleMethod = findMethodOptional(
            listenerClass,
            "handleMotionEvent",
            MotionEvent::class.java,
            View::class.java
        ) ?: findMethodOptional(
            listenerClass,
            "handleMotionEvent",
            View::class.java,
            MotionEvent::class.java
        ) ?: run {
            logger.warn("DesktopModeTouchEventListener.handleMotionEvent not found")
            return
        }

        val dragReflect = resolveDragReflect(loader, listenerClass) ?: return
        handles += hookWithId(handleMethod, "freeform_nonlinear_edge_drag") { chain ->
            try {
                val event = chain.args.firstOrNull { it is MotionEvent } as? MotionEvent
                    ?: return@hookWithId chain.proceed()
                val listener = chain.thisObject ?: return@hookWithId chain.proceed()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> recordDragDown(listener, event, dragReflect)
                    MotionEvent.ACTION_MOVE -> recordDragMove(listener, event, dragReflect)
                    MotionEvent.ACTION_CANCEL -> {
                        resolveListenerTaskId(listener, dragReflect)?.let {
                            dragVelocityByTask.remove(it)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (readEdgeBubbleEnabled() &&
                            handleDragUp(listener, event, taskReflect, dragReflect, options)
                        ) {
                            return@hookWithId true
                        }
                        resolveListenerTaskId(listener, dragReflect)?.let {
                            dragVelocityByTask.remove(it)
                        }
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Part B edge-drag handler failed: ${t.message}")
            }
            chain.proceed()
        }
        logger.info("Part B edge-dock/snap-back installed on ${listenerClass.simpleName}")
    }

    private fun resolveListenerTaskId(listener: Any, drag: DragReflect): Int? =
        runCatching { drag.listenerTaskId?.getInt(listener) }.getOrNull()

    private fun resolvePointerRawX(
        listener: Any,
        event: MotionEvent,
        drag: DragReflect
    ): Float {
        val ptrId = runCatching { drag.listenerDragPointerId?.getInt(listener) }.getOrNull()
        val ptrIdx = if (ptrId != null) {
            event.findPointerIndex(ptrId).let { if (it >= 0) it else 0 }
        } else {
            0
        }
        return runCatching { event.getRawX(ptrIdx) }.getOrDefault(event.rawX)
    }

    private fun recordDragDown(listener: Any, event: MotionEvent, drag: DragReflect) {
        val taskId = resolveListenerTaskId(listener, drag) ?: return
        val rawX = runCatching { event.getRawX(0) }.getOrDefault(event.rawX)
        dragVelocityByTask[taskId] = DragVelocityState(
            lastX = rawX,
            lastTimeMs = event.eventTime,
            vx = 0f,
            samples = 0
        )
    }

    private fun recordDragMove(listener: Any, event: MotionEvent, drag: DragReflect) {
        val taskId = resolveListenerTaskId(listener, drag) ?: return
        val rawX = resolvePointerRawX(listener, event, drag)
        val now = event.eventTime
        val prev = dragVelocityByTask[taskId]
        if (prev == null) {
            dragVelocityByTask[taskId] = DragVelocityState(rawX, now, 0f, 0)
            return
        }
        val dtMs = now - prev.lastTimeMs
        if (dtMs <= 0L) return
        if (dtMs > VELOCITY_STALE_MS * 2L) {
            dragVelocityByTask[taskId] = DragVelocityState(rawX, now, 0f, 0)
            return
        }
        val instVx = (rawX - prev.lastX) * 1000f / dtMs.toFloat()
        val smoothedVx = if (prev.samples == 0) instVx else prev.vx * 0.4f + instVx * 0.6f
        dragVelocityByTask[taskId] = DragVelocityState(
            lastX = rawX,
            lastTimeMs = now,
            vx = smoothedVx,
            samples = prev.samples + 1
        )
    }

    private fun handleDragUp(
        listener: Any,
        event: MotionEvent,
        taskReflect: TaskInfoReflect,
        drag: DragReflect,
        options: FreeformAnimOptions
    ): Boolean {
        val taskId = resolveListenerTaskId(listener, drag)
        val velocityState = taskId?.let { dragVelocityByTask.remove(it) }
        val velocityX = if (velocityState != null &&
            event.eventTime - velocityState.lastTimeMs <= VELOCITY_STALE_MS
        ) {
            velocityState.vx
        } else {
            0f
        }
        val isDragging = runCatching { drag.listenerIsDragging?.getBoolean(listener) == true }
            .getOrDefault(false)
        if (!isDragging) return false

        val decoration = resolveDecoration(listener, drag) ?: return false
        if (runCatching { drag.decorIsHandlingResize?.invoke(decoration) as? Boolean == true }
                .getOrDefault(false)
        ) {
            return false
        }

        val taskInfo = runCatching {
            drag.decorGetTaskInfo?.invoke(decoration) ?: drag.decorTaskInfoField?.get(decoration)
        }.getOrNull() as? ActivityManager.RunningTaskInfo ?: return false
        if (taskReflect.windowingMode(taskInfo) != WINDOWING_MODE_FREEFORM) return false
        if (taskReflect.isHangingMode(taskInfo)) return false
        if (taskReflect.isDesktopOrPcMode(taskInfo)) return false
        if (isTaskPinned(listener, taskInfo.taskId, drag)) return false

        val eventDisplayId = taskReflect.eventDisplayId(event)
        val startDisplayId = runCatching {
            drag.listenerStartDisplayId?.getInt(listener) ?: eventDisplayId
        }.getOrDefault(eventDisplayId)
        if (startDisplayId != eventDisplayId) return false

        val positioner = runCatching { drag.listenerPositioner?.get(listener) }.getOrNull()
        val dropBounds = resolveDropBounds(listener, positioner, taskInfo, taskReflect, drag)
        if (dropBounds == null || dropBounds.isEmpty) return false

        val controller = runCatching { drag.listenerDesktopController?.get(listener) }.getOrNull()
        val displayController = runCatching {
            drag.listenerDisplayController?.get(listener)
                ?: controller?.let { drag.controllerDisplayControllerField?.get(it) }
        }.getOrNull() ?: return false
        val displayLayout = runCatching {
            drag.displayControllerGetLayout.invoke(displayController, startDisplayId)
        }.getOrNull() ?: return false
        val displayWidth = runCatching { drag.displayLayoutWidth.getInt(displayLayout) }
            .getOrDefault(0)
        val displayHeight = runCatching { drag.displayLayoutHeight.getInt(displayLayout) }
            .getOrDefault(0)
        if (displayWidth <= 0 || displayHeight <= 0) return false

        if (hasActiveVisualIndicator(
                listener = listener,
                controller = controller,
                displayLayout = displayLayout,
                displayId = startDisplayId,
                taskInfo = taskInfo,
                dropBounds = dropBounds,
                event = event,
                drag = drag
            )
        ) {
            return false
        }

        val offLeft = (-dropBounds.left).coerceAtLeast(0)
        val offRight = (dropBounds.right - displayWidth).coerceAtLeast(0)
        if (offLeft == 0 && offRight == 0) return false

        val leash = resolveTaskSurface(decoration, drag) ?: return false
        if (!leash.isValid) return false

        val triggerPercent = readTriggerPercent()
        val pointerRawX = resolvePointerRawX(listener, event, drag)
        val density = Resources.getSystem().displayMetrics.density
        val dockSide = FreeformAnimCurves.resolveEdgeDockSide(
            offLeft = offLeft,
            offRight = offRight,
            windowWidth = dropBounds.width(),
            triggerPercent = triggerPercent,
            pointerRawX = pointerRawX,
            displayWidth = displayWidth,
            velocityXPxPerSec = velocityX,
            density = density
        )
        if (dockSide >= 0 && !options.effectiveEdgeDock) return false
        if (dockSide < 0 && !options.effectiveEdgeSnapBack) return false

        val resolvedTaskId = taskInfo.taskId

        cleanupDragState(listener, decoration, positioner, controller, resolvedTaskId, drag)
        activeAnimators.remove(resolvedTaskId)?.cancel()

        val windowCornerRadius = resolveWindowCornerRadius(decoration, drag, density)
        val (baseScaleX, baseScaleY) = resolveBaseScales(taskInfo, taskReflect, preferHanging = false)

        if (dockSide >= 0) {
            startEdgeDockAnimation(
                listener = listener,
                decoration = decoration,
                positioner = positioner,
                controller = controller,
                taskInfo = taskInfo,
                drag = drag,
                taskId = resolvedTaskId,
                side = dockSide,
                dropBounds = dropBounds,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                density = density,
                windowCornerRadius = windowCornerRadius,
                baseScaleX = baseScaleX,
                baseScaleY = baseScaleY,
                leash = leash,
                taskReflect = taskReflect
            )
            return true
        }

        startSnapBackAnimation(
            listener = listener,
            decoration = decoration,
            positioner = positioner,
            controller = controller,
            taskInfo = taskInfo,
            taskReflect = taskReflect,
            drag = drag,
            dropBounds = dropBounds,
            displayWidth = displayWidth,
            baseScaleX = baseScaleX,
            leash = leash
        )
        return true
    }

    private fun startEdgeDockAnimation(
        listener: Any,
        decoration: Any,
        positioner: Any?,
        controller: Any?,
        taskInfo: ActivityManager.RunningTaskInfo,
        drag: DragReflect,
        taskId: Int,
        side: Int,
        dropBounds: Rect,
        displayWidth: Int,
        displayHeight: Int,
        density: Float,
        windowCornerRadius: Float,
        baseScaleX: Float,
        baseScaleY: Float,
        leash: SurfaceControl,
        taskReflect: TaskInfoReflect
    ) {
        val marginY = 32f * density
        val maxY = (displayHeight - marginY).coerceAtLeast(marginY)
        val bubbleCenterY = dropBounds.centerY().toFloat().coerceIn(marginY, maxY)
        val centerYRatio = (bubbleCenterY / displayHeight.toFloat()).coerceIn(0.05f, 0.95f)
        val fullLayout = EdgeBubbleGeometry.computeFullLayout(
            side = side,
            centerYRatio = centerYRatio,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            density = density
        )
        val iconRect = fullLayout.iconRect
        val startCenterX = dropBounds.exactCenterX()
        val startCenterY = dropBounds.exactCenterY()
        val targetCenterX = iconRect.exactCenterX
        val targetCenterY = iconRect.exactCenterY
        val logicalW = (dropBounds.width().coerceAtLeast(1) / baseScaleX.coerceAtLeast(0.05f))
        val logicalH = (dropBounds.height().coerceAtLeast(1) / baseScaleY.coerceAtLeast(0.05f))
        val endVisualCorner = EdgeBubbleGeometry.CONTAINER_RADIUS_DP * density
        val overlayContext = resolveOverlayContext(taskReflect.displayId(taskInfo))
        val appIcon = overlayContext?.let { loadTaskAppIcon(it, taskInfo) }

        val runnable = Runnable {
            activeAnimators.remove(taskId)?.cancel()
            dismissDockMorphOverlay(taskId)
            dismissRestoreMorphOverlay(taskId)
            val overlay = if (overlayContext != null) {
                EdgeDockMorphOverlay(
                    context = overlayContext,
                    fullLayout = fullLayout,
                    appIcon = appIcon,
                    startCenterX = startCenterX,
                    startCenterY = startCenterY,
                    density = density
                ).takeIf { it.show() }
            } else {
                null
            }
            if (overlay != null) {
                activeDockOverlays[taskId] = overlay
            }

            val tx = SurfaceControl.Transaction()
            var cancelled = false
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FreeformAnimCurves.EDGE_DOCK_DURATION_MS
                interpolator = FreeformAnimCurves.edgeDockInterpolator()
                addUpdateListener { anim ->
                    if (!leash.isValid) return@addUpdateListener
                    val p = anim.animatedValue as Float
                    val frame = FreeformAnimCurves.computeShrinkToRectFrame(
                        startLeft = dropBounds.left.toFloat(),
                        startTop = dropBounds.top.toFloat(),
                        startWidth = dropBounds.width().toFloat(),
                        startHeight = dropBounds.height().toFloat(),
                        baseScaleX = baseScaleX,
                        baseScaleY = baseScaleY,
                        targetLeft = iconRect.left.toFloat(),
                        targetTop = iconRect.top.toFloat(),
                        targetWidth = iconRect.width.toFloat(),
                        targetHeight = iconRect.height.toFloat(),
                        progress = p
                    )
                    val morph = FreeformAnimCurves.computeDockMorphFrame(p)
                    val visualRadius = windowCornerRadius +
                        (endVisualCorner - windowCornerRadius) * p.coerceIn(0f, 1f)
                    val minScale = minOf(frame.scaleX, frame.scaleY).coerceAtLeast(0.05f)
                    val bufferRadius = (visualRadius / minScale)
                        .coerceAtMost(minOf(logicalW, logicalH) * 0.45f)
                    tx.setScale(leash, frame.scaleX, frame.scaleY)
                    tx.setPosition(leash, frame.posX, frame.posY)
                    tx.setAlpha(leash, morph.windowAlpha)
                    taskReflect.setCornerRadius(tx, leash, bufferRadius)
                    tx.apply()

                    val curCx = startCenterX + (targetCenterX - startCenterX) * p
                    val curCy = startCenterY + (targetCenterY - startCenterY) * p
                    overlay?.update(curCx, curCy, morph)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                        activeAnimators.remove(taskId, animation)
                        dismissDockMorphOverlay(taskId)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        activeAnimators.remove(taskId, animation)
                        if (cancelled) return
                        overlay?.update(
                            targetCenterX,
                            targetCenterY,
                            FreeformAnimCurves.computeDockMorphFrame(1f)
                        )
                        dockOverlayTimeouts.remove(taskId)?.let { mainHandler.removeCallbacks(it) }
                        if (overlay != null) {
                            val timeout = Runnable { dismissDockMorphOverlay(taskId) }
                            dockOverlayTimeouts[taskId] = timeout
                            mainHandler.postDelayed(timeout, DOCK_OVERLAY_HANDOFF_TIMEOUT_MS)
                        }
                        val dockMeta = DockedTaskMeta(
                            dockedAtMs = SystemClock.elapsedRealtime(),
                            baseScaleX = baseScaleX,
                            baseScaleY = baseScaleY,
                            windowCornerRadius = windowCornerRadius
                        )
                        recentlyDockedTasks[taskId] = dockMeta
                        sendMinimizeCommand(taskId, side, dropBounds, centerYRatio) { succeeded ->
                            if (succeeded) {
                                mainHandler.postDelayed({
                                    if (!dockMeta.cleanedUp &&
                                        !activelyRestoringTasks.contains(taskId) &&
                                        !pendingRestoreHints.containsKey(taskId) &&
                                        lastVisibleByTaskId[taskId] != true &&
                                        leash.isValid
                                    ) {
                                        SurfaceControl.Transaction().apply {
                                            setAlpha(leash, 1f)
                                            setScale(leash, baseScaleX, baseScaleY)
                                            taskReflect.setCornerRadius(
                                                this,
                                                leash,
                                                windowCornerRadius
                                            )
                                            apply()
                                        }
                                        dockMeta.cleanedUp = true
                                    }
                                }, DOCK_CLEANUP_DELAY_MS)
                            } else {
                                recentlyDockedTasks.remove(taskId)
                                dismissDockMorphOverlay(taskId)
                                logger.warn(
                                    "Minimize command rejected or unhandled for task=$taskId; " +
                                        "snapping back"
                                )
                                if (leash.isValid) {
                                    SurfaceControl.Transaction().apply {
                                        setAlpha(leash, 1f)
                                        setScale(leash, baseScaleX, baseScaleY)
                                        taskReflect.setCornerRadius(
                                            this,
                                            leash,
                                            windowCornerRadius
                                        )
                                        taskReflect.showSurface(this, leash)
                                        setPosition(
                                            leash,
                                            dropBounds.left.toFloat(),
                                            dropBounds.top.toFloat()
                                        )
                                        apply()
                                    }
                                    startSnapBackAnimation(
                                        listener = listener,
                                        decoration = decoration,
                                        positioner = positioner,
                                        controller = controller,
                                        taskInfo = taskInfo,
                                        taskReflect = taskReflect,
                                        drag = drag,
                                        dropBounds = dropBounds,
                                        displayWidth = displayWidth,
                                        baseScaleX = baseScaleX,
                                        leash = leash
                                    )
                                }
                            }
                        }
                    }
                })
            }
            activeAnimators[taskId] = animator
            animator.start()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    private fun dismissDockMorphOverlay(taskId: Int) {
        val dismissBlock = Runnable {
            dockOverlayTimeouts.remove(taskId)?.let { mainHandler.removeCallbacks(it) }
            activeDockOverlays.remove(taskId)?.dismiss()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissBlock.run()
        } else {
            mainHandler.post(dismissBlock)
        }
    }

    private fun dismissRestoreMorphOverlay(taskId: Int) {
        val dismissBlock = Runnable {
            activeRestoreOverlays.remove(taskId)?.dismiss()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissBlock.run()
        } else {
            mainHandler.post(dismissBlock)
        }
    }

    private fun resolveOverlayContext(displayId: Int): Context? {
        val ctx = sysUiContext ?: return null
        if (displayId == 0) return ctx
        return runCatching {
            ctx.getSystemService(DisplayManager::class.java)
                ?.getDisplay(displayId)
                ?.let { ctx.createDisplayContext(it) }
        }.getOrNull() ?: ctx
    }

    private fun loadTaskAppIcon(
        context: Context,
        taskInfo: ActivityManager.RunningTaskInfo
    ): Drawable? {
        val pkg = taskInfo.topActivity?.packageName
            ?: taskInfo.baseActivity?.packageName
            ?: taskInfo.baseIntent.component?.packageName
            ?: taskInfo.baseIntent.`package`
            ?: return null
        return runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun startSnapBackAnimation(
        listener: Any,
        decoration: Any,
        positioner: Any?,
        controller: Any?,
        taskInfo: ActivityManager.RunningTaskInfo,
        taskReflect: TaskInfoReflect,
        drag: DragReflect,
        dropBounds: Rect,
        displayWidth: Int,
        baseScaleX: Float,
        leash: SurfaceControl
    ) {
        val dx = FreeformAnimCurves.computeOnscreenSnapOffset(
            dropBounds.left,
            dropBounds.right,
            0,
            displayWidth
        )
        val snapped = Rect(dropBounds).apply { offset(dx, 0) }
        val validDragArea = resolveValidDragArea(decoration, drag)
        if (validDragArea != null && !validDragArea.isEmpty) {
            val clampedTop = snapped.top.coerceIn(
                validDragArea.top,
                validDragArea.bottom.coerceAtLeast(validDragArea.top)
            )
            snapped.offsetTo(snapped.left, clampedTop)
        }

        val taskId = taskInfo.taskId
        val startX = dropBounds.left.toFloat()
        val startY = dropBounds.top.toFloat()
        val endX = snapped.left.toFloat()
        val endY = snapped.top.toFloat()
        val tx = SurfaceControl.Transaction()

        var cancelled = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FreeformAnimCurves.SNAP_BACK_DURATION_MS
            interpolator = FreeformAnimCurves.snapBackSpringInterpolator()
            addUpdateListener { anim ->
                if (!leash.isValid) return@addUpdateListener
                val p = anim.animatedValue as Float
                tx.setPosition(leash, startX + (endX - startX) * p, startY + (endY - startY) * p)
                tx.apply()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    activeAnimators.remove(taskId, animation)
                }

                override fun onAnimationEnd(animation: Animator) {
                    activeAnimators.remove(taskId, animation)
                    if (cancelled) return
                    commitSnappedBounds(
                        listener = listener,
                        positioner = positioner,
                        controller = controller,
                        taskInfo = taskInfo,
                        snapped = snapped,
                        scaleX = taskReflect.scaleX(taskInfo).takeIf { it > 0f } ?: baseScaleX,
                        isScaled = taskReflect.scaleX(taskInfo) > 0f,
                        leash = leash,
                        taskReflect = taskReflect,
                        drag = drag
                    )
                }
            })
        }
        activeAnimators[taskId] = animator
        animator.start()
    }

    private fun commitSnappedBounds(
        listener: Any,
        positioner: Any?,
        controller: Any?,
        taskInfo: ActivityManager.RunningTaskInfo,
        snapped: Rect,
        scaleX: Float,
        isScaled: Boolean,
        leash: SurfaceControl,
        taskReflect: TaskInfoReflect,
        drag: DragReflect
    ) {
        val organizer = runCatching {
            drag.listenerOrganizer?.get(listener)
                ?: controller?.let { drag.controllerOrganizerField?.get(it) }
        }.getOrNull()

        runCatching { (drag.listenerCurrentBounds?.get(listener) as? Rect)?.set(snapped) }
        updatePositionerBounds(positioner, snapped, drag)

        if (isScaled && organizer != null && drag.organizerScaleTaskSurface != null) {
            if (leash.isValid) {
                SurfaceControl.Transaction().apply {
                    setPosition(leash, snapped.left.toFloat(), snapped.top.toFloat())
                    apply()
                }
            }
            val executor = resolveDragExecutor(positioner, drag)
            val initBounds = runCatching {
                executor?.let { drag.execInitBounds?.get(it) as? Rect }
            }.getOrNull() ?: taskReflect.logicalBounds(taskInfo) ?: snapped
            runCatching {
                (executor?.let { drag.execRescaleBounds?.get(it) as? Rect })?.set(snapped)
                drag.organizerScaleTaskSurface.invoke(
                    organizer,
                    taskInfo.taskId,
                    snapped,
                    initBounds,
                    scaleX,
                    scaleX
                )
            }.onFailure {
                logger.warn("scaleTaskSurface snap commit failed: ${it.message}")
            }
            return
        }

        val token = taskReflect.taskToken(taskInfo) ?: return
        val wct = runCatching {
            val txObj = drag.wctCtor?.newInstance() ?: return
            drag.wctSetBounds?.invoke(txObj, token, snapped)
            txObj
        }.getOrNull() ?: return

        val transitions = runCatching {
            drag.listenerTransitions?.get(listener)
                ?: controller?.let { drag.controllerTransitionsField?.get(it) }
        }.getOrNull()
        val handler = runCatching {
            drag.listenerDragHandler?.get(listener)
                ?: controller?.let { drag.controllerDragHandlerField?.get(it) }
        }.getOrNull()
        if (transitions != null && drag.transitionsStartTransition != null) {
            runCatching {
                drag.transitionsStartTransition.invoke(transitions, TRANSIT_CHANGE, wct, handler)
                return
            }.onFailure {
                logger.debug("startTransition snap commit failed: ${it.message}")
            }
        }
        if (organizer != null && drag.organizerApplyTransaction != null) {
            runCatching {
                drag.organizerApplyTransaction.invoke(organizer, wct)
            }.onFailure {
                logger.warn("applyTransaction snap commit failed: ${it.message}")
            }
        }
    }

    private fun sendMinimizeCommand(
        taskId: Int,
        side: Int,
        restoreBounds: Rect,
        centerYRatio: Float,
        onResult: (Boolean) -> Unit
    ) {
        val context = sysUiContext ?: run {
            logger.warn("SystemUI context unavailable; minimize command not sent")
            onResult(false)
            return
        }
        val intent = Intent(ACTION_BUBBLE_COMMAND).setPackage(PACKAGE_ANDROID).apply {
            putExtra(FreeformAnimContract.EXTRA_CMD, CMD_MINIMIZE_TASK)
            putExtra(FreeformAnimContract.EXTRA_TASK_ID, taskId)
            putExtra(FreeformAnimContract.EXTRA_SIDE, side)
            putExtra(FreeformAnimContract.EXTRA_RESTORE_LEFT, restoreBounds.left)
            putExtra(FreeformAnimContract.EXTRA_RESTORE_TOP, restoreBounds.top)
            putExtra(FreeformAnimContract.EXTRA_RESTORE_RIGHT, restoreBounds.right)
            putExtra(FreeformAnimContract.EXTRA_RESTORE_BOTTOM, restoreBounds.bottom)
            putExtra(FreeformAnimContract.EXTRA_CENTER_Y_RATIO, centerYRatio)
            putExtra(FreeformAnimContract.EXTRA_NOTIFY_BUBBLE, true)
        }
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, received: Intent) {
                onResult(resultCode == 1)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    options,
                    resultReceiver,
                    mainHandler,
                    0,
                    null,
                    null
                )
            } else {
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    resultReceiver,
                    mainHandler,
                    0,
                    null,
                    null
                )
            }
        }.onFailure {
            logger.warn("Failed to send minimize broadcast: ${it.message}")
            onResult(false)
        }
    }

    // endregion

    // region Part C: restore-hint broadcast receiver

    private fun installRestoreBroadcastReceiver(loader: ClassLoader) {
        if (restoreReceiverRegistered) return
        val atClass = loadClassOptional(loader, ACTIVITY_THREAD_CLASS) ?: return
        val currentApplication = findMethodOptional(atClass, "currentApplication")
        val currentAt = findMethodOptional(atClass, "currentActivityThread")
        val getApplication = findMethodOptional(atClass, "getApplication")
        if (currentApplication == null && (currentAt == null || getApplication == null)) return
        val getSentFromUid = if (Build.VERSION.SDK_INT >= 34) {
            findMethodOptional(BroadcastReceiver::class.java, "getSentFromUid")
        } else {
            null
        }

        fun tryRegister(attempt: Int) {
            if (restoreReceiverRegistered) return
            val app = runCatching {
                (currentApplication?.invoke(null) as? Context)
                    ?: currentAt?.invoke(null)?.let { getApplication?.invoke(it) as? Context }
            }.getOrNull()
            if (app == null || app.packageName != ScopeKeys.SYSTEM_UI.packageName) {
                if (attempt < RECEIVER_RETRY_MAX_ATTEMPTS) {
                    mainHandler.postDelayed({ tryRegister(attempt + 1) }, RECEIVER_RETRY_DELAY_MS)
                } else {
                    logger.warn(
                        "SystemUI Application context unavailable (pkg=${app?.packageName}); " +
                            "restore hint receiver inactive"
                    )
                }
                return
            }
            val filter = IntentFilter(FreeformAnimContract.ACTION_SYSTEMUI_ANIM_CMD)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val ordered = isOrderedBroadcast
                    val uid = if (getSentFromUid != null) {
                        runCatching { getSentFromUid.invoke(this) as Int }.getOrDefault(-1)
                    } else {
                        0
                    }
                    if (!isAllowedRestoreCaller(context, uid)) {
                        logger.warn("Rejected anim command from untrusted uid=$uid")
                        if (ordered) resultCode = 0
                        return
                    }
                    val cmd = intent.getIntExtra(FreeformAnimContract.EXTRA_CMD, -1)
                    val currentOptions = activeOptions
                    when (cmd) {
                        FreeformAnimContract.CMD_PREPARE_RESTORE -> {
                            if (!currentOptions.effectiveBubbleRestore) {
                                if (ordered) resultCode = 0
                                return
                            }
                            val taskId = intent.getIntExtra(FreeformAnimContract.EXTRA_TASK_ID, -1)
                            val side = intent.getIntExtra(FreeformAnimContract.EXTRA_SIDE, 0)
                            val l = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_LEFT, 0)
                            val t = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_TOP, 0)
                            val r = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_RIGHT, 0)
                            val b = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_BOTTOM, 0)
                            if (taskId > 0 && r > l && b > t) {
                                dismissDockMorphOverlay(taskId)
                                recentlyDockedTasks.remove(taskId)
                                val hint = RestoreHint(
                                    bubbleRect = Rect(l, t, r, b),
                                    side = side,
                                    createdAtMs = SystemClock.elapsedRealtime()
                                )
                                pendingRestoreHints[taskId] = hint
                                preBlankTaskLeash(taskId, hint)
                                logger.info(
                                    "Restore hint queued task=$taskId rect=${hint.bubbleRect} " +
                                        "side=$side"
                                )
                                if (ordered) resultCode = 1
                            } else {
                                if (ordered) resultCode = 0
                            }
                        }
                        FreeformAnimContract.CMD_DOCK_OVERLAY_DONE -> {
                            if (!currentOptions.effectiveEdgeDock) {
                                if (ordered) resultCode = 0
                                return
                            }
                            val taskId = intent.getIntExtra(FreeformAnimContract.EXTRA_TASK_ID, -1)
                            if (taskId > 0) {
                                dismissDockMorphOverlay(taskId)
                                if (ordered) resultCode = 1
                            } else {
                                if (ordered) resultCode = 0
                            }
                        }
                        else -> if (ordered) resultCode = 0
                    }
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
            val registered = runCatching {
                app.registerReceiver(receiver, filter, flags)
                true
            }.onFailure {
                logger.warn("registerReceiver failed on ${app.packageName}: ${it.message}")
            }.getOrDefault(false)
            if (registered) {
                sysUiContext = app
                restoreReceiverRegistered = true
                logger.info("Part C SystemUI restore hint receiver registered on ${app.packageName}")
            } else if (attempt < RECEIVER_RETRY_MAX_ATTEMPTS) {
                mainHandler.postDelayed({ tryRegister(attempt + 1) }, RECEIVER_RETRY_DELAY_MS)
            }
        }

        tryRegister(0)
    }

    private fun preBlankTaskLeash(taskId: Int, hint: RestoreHint) {
        val listener = freeformTaskListenerRef ?: return
        val findSurface = freeformFindTaskSurface ?: return
        val leash = runCatching {
            findSurface.invoke(listener, taskId) as? SurfaceControl
        }.getOrNull() ?: return
        if (!leash.isValid) return
        SurfaceControl.Transaction().apply {
            setAlpha(leash, 0f)
            apply()
        }
        mainHandler.postDelayed({
            if (!activelyRestoringTasks.contains(taskId) &&
                pendingRestoreHints.remove(taskId, hint) &&
                leash.isValid
            ) {
                SurfaceControl.Transaction().apply {
                    setAlpha(leash, 1f)
                    apply()
                }
            }
        }, RESTORE_HINT_TTL_MS)
    }

    private fun isAllowedRestoreCaller(context: Context, uid: Int): Boolean {
        if (uid == 0 || uid == ADB_SHELL_UID) return true
        if (uid == ztoolUid && ztoolUid > 0) return true
        if (uid <= 0) return false
        return runCatching {
            val resolved = context.packageManager.getPackageUid(PACKAGE_ZTOOL, 0)
            ztoolUid = resolved
            uid == resolved
        }.getOrDefault(false)
    }

    // endregion

    // region Part D: bubble -> freeform restore expand animation

    private fun installBubbleRestoreExpand(
        loader: ClassLoader,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect?,
        options: FreeformAnimOptions,
        handles: MutableList<XposedInterface.HookHandle>
    ) {
        var layers = 0
        if (options.needsBubbleRestoreTransition) {
            val handlerClass = loadClassOptional(loader, DEFAULT_TRANSITION_HANDLER_CLASS)
            val transitionInfoClass = loadClassOptional(loader, TRANSITION_INFO_CLASS)
            val finishCallbackClass = loadClassOptional(loader, TRANSITION_FINISH_CALLBACK_CLASS)
            if (handlerClass != null && transitionInfoClass != null &&
                finishCallbackClass != null && changeReflect != null
            ) {
                val startAnimation = findMethodOptional(
                    handlerClass,
                    "startAnimation",
                    IBinder::class.java,
                    transitionInfoClass,
                    SurfaceControl.Transaction::class.java,
                    SurfaceControl.Transaction::class.java,
                    finishCallbackClass
                )
                val getChanges = findMethodOptional(transitionInfoClass, "getChanges")
                val onTransitionFinished = findMethodOptional(
                    finishCallbackClass,
                    "onTransitionFinished",
                    loadClassOptional(loader, WCT_CLASS)
                )
                val mainExecutorField = optionalFieldAny(handlerClass, "mMainExecutor", "mainExecutor")
                if (startAnimation != null && getChanges != null && onTransitionFinished != null) {
                    handles += hookWithId(
                        startAnimation,
                        "freeform_nonlinear_bubble_restore_transit"
                    ) { chain ->
                        handleRestoreTransition(
                            chain = chain,
                            taskReflect = taskReflect,
                            changeReflect = changeReflect,
                            getChanges = getChanges,
                            onTransitionFinished = onTransitionFinished,
                            mainExecutorField = mainExecutorField
                        )
                    }
                    layers++
                }
            }
        }

        if (options.needsTaskListener) {
            val listenerClass = loadClassOptional(loader, FREEFORM_TASK_LISTENER_CLASS)
            if (listenerClass != null) {
                val onTaskAppeared = findMethodOptional(
                    listenerClass,
                    "onTaskAppeared",
                    ActivityManager.RunningTaskInfo::class.java,
                    SurfaceControl::class.java
                )
                val onTaskInfoChanged = findMethodOptional(
                    listenerClass,
                    "onTaskInfoChanged",
                    ActivityManager.RunningTaskInfo::class.java
                )
                val findTaskSurface = findMethodOptional(
                    listenerClass,
                    "findTaskSurface",
                    Int::class.javaPrimitiveType
                )
                freeformFindTaskSurface = findTaskSurface
                if (onTaskAppeared != null) {
                    handles += hookWithId(
                        onTaskAppeared,
                        "freeform_nonlinear_task_appeared"
                    ) { chain ->
                        freeformTaskListenerRef = chain.thisObject
                        (chain.args.getOrNull(0) as? ActivityManager.RunningTaskInfo)?.let { ti ->
                            lastVisibleByTaskId[ti.taskId] = ti.isVisible
                        }
                        chain.proceed()
                    }
                }
                if (onTaskInfoChanged != null &&
                    (!options.effectiveBubbleRestore || findTaskSurface != null)
                ) {
                    handles += hookWithId(
                        onTaskInfoChanged,
                        "freeform_nonlinear_bubble_restore_listener"
                    ) { chain ->
                        freeformTaskListenerRef = chain.thisObject
                        val res = chain.proceed()
                        val listener = chain.thisObject ?: return@hookWithId res
                        val taskInfo = chain.args.getOrNull(0) as? ActivityManager.RunningTaskInfo
                            ?: return@hookWithId res
                        val taskId = taskInfo.taskId
                        lastVisibleByTaskId[taskId] = taskInfo.isVisible
                        if (!options.effectiveBubbleRestore || findTaskSurface == null) {
                            return@hookWithId res
                        }
                        if (taskInfo.isVisible &&
                            !activelyRestoringTasks.contains(taskId) &&
                            pendingRestoreHints.containsKey(taskId)
                        ) {
                            val hint = pendingRestoreHints.remove(taskId) ?: return@hookWithId res
                            recentlyDockedTasks.remove(taskId)
                            val leash = runCatching {
                                findTaskSurface.invoke(listener, taskId) as? SurfaceControl
                            }.getOrNull()
                            val expired =
                                SystemClock.elapsedRealtime() - hint.createdAtMs > RESTORE_HINT_TTL_MS
                            if (expired) {
                                if (leash != null && leash.isValid) {
                                    SurfaceControl.Transaction().apply {
                                        setAlpha(leash, 1f)
                                        apply()
                                    }
                                }
                                return@hookWithId res
                            }
                            if (leash != null && leash.isValid) {
                                playExpandAnimator(
                                    taskId = taskId,
                                    hint = hint,
                                    taskInfo = taskInfo,
                                    targetRectOverride = null,
                                    leash = leash,
                                    startT = SurfaceControl.Transaction(),
                                    finishT = null,
                                    onFinished = null,
                                    taskReflect = taskReflect
                                )
                            }
                        }
                        res
                    }
                    layers++
                }
            }
        }

        logger.info("Part D bubble-restore expand installed ($layers layer(s))")
    }

    private fun handleRestoreTransition(
        chain: XposedInterface.Chain,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect,
        getChanges: Method,
        onTransitionFinished: Method,
        mainExecutorField: Field?
    ): Any? {
        if (pendingRestoreHints.isEmpty()) return chain.proceed()
        val now = SystemClock.elapsedRealtime()
        val iterator = pendingRestoreHints.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAtMs > RESTORE_HINT_TTL_MS) {
                iterator.remove()
            }
        }
        if (pendingRestoreHints.isEmpty()) return chain.proceed()

        val info = chain.args.getOrNull(1) ?: return chain.proceed()
        val startT = chain.args.getOrNull(2) as? SurfaceControl.Transaction
            ?: return chain.proceed()
        val finishT = chain.args.getOrNull(3) as? SurfaceControl.Transaction
            ?: return chain.proceed()
        val finishCallback = chain.args.getOrNull(4) ?: return chain.proceed()

        val changes = runCatching { getChanges.invoke(info) as? List<*> }.getOrNull()
            ?: return chain.proceed()
        var matchedChange: Any? = null
        var matchedTask: ActivityManager.RunningTaskInfo? = null
        for (change in changes) {
            if (change == null) continue
            val mode = runCatching { changeReflect.getMode.invoke(change) as? Int }.getOrNull() ?: -1
            if (mode != 1 && mode != 3) continue
            val ti = runCatching {
                changeReflect.getTaskInfo.invoke(change) as? ActivityManager.RunningTaskInfo
            }.getOrNull() ?: continue
            if (taskReflect.windowingMode(ti) != WINDOWING_MODE_FREEFORM) continue
            if (pendingRestoreHints.containsKey(ti.taskId)) {
                matchedChange = change
                matchedTask = ti
                break
            }
        }
        if (matchedChange == null || matchedTask == null) return chain.proceed()

        val taskId = matchedTask.taskId
        val hint = pendingRestoreHints.remove(taskId) ?: return chain.proceed()
        recentlyDockedTasks.remove(taskId)
        val leash = runCatching {
            changeReflect.getLeash.invoke(matchedChange) as? SurfaceControl
        }.getOrNull() ?: return chain.proceed()
        if (!leash.isValid) return chain.proceed()

        val endAbsBounds = runCatching {
            changeReflect.getEndAbsBounds.invoke(matchedChange) as? Rect
        }.getOrNull()
        val handlerObj = chain.thisObject
        val mainExecutor = handlerObj?.let {
            runCatching { mainExecutorField?.get(it) as? Executor }.getOrNull()
        }

        playExpandAnimator(
            taskId = taskId,
            hint = hint,
            taskInfo = matchedTask,
            targetRectOverride = endAbsBounds,
            leash = leash,
            startT = startT,
            finishT = finishT,
            onFinished = {
                val invokeFinish = Runnable {
                    runCatching { onTransitionFinished.invoke(finishCallback, null) }
                        .onFailure { logger.warn("onTransitionFinished failed: ${it.message}") }
                }
                if (mainExecutor != null) {
                    mainExecutor.execute(invokeFinish)
                } else {
                    mainHandler.post(invokeFinish)
                }
            },
            taskReflect = taskReflect
        )
        return true
    }

    private fun playExpandAnimator(
        taskId: Int,
        hint: RestoreHint,
        taskInfo: ActivityManager.RunningTaskInfo,
        targetRectOverride: Rect?,
        leash: SurfaceControl,
        startT: SurfaceControl.Transaction? = null,
        finishT: SurfaceControl.Transaction?,
        onFinished: (() -> Unit)?,
        taskReflect: TaskInfoReflect
    ) {
        activelyRestoringTasks.add(taskId)

        val (baseScaleX, baseScaleY) = resolveBaseScales(taskInfo, taskReflect, preferHanging = false)
        val isScaled = taskReflect.scaleX(taskInfo) > 0f
        val scaleBounds = taskReflect.scaleSurfaceBounds(taskInfo)
        val logicalBounds = if (isScaled) {
            taskReflect.logicalBounds(taskInfo)?.takeUnless { it.isEmpty }
                ?: targetRectOverride?.takeUnless { it.isEmpty }
                ?: scaleBounds
                ?: Rect(hint.bubbleRect)
        } else {
            targetRectOverride?.takeUnless { it.isEmpty }
                ?: taskReflect.logicalBounds(taskInfo)?.takeUnless { it.isEmpty }
                ?: scaleBounds
                ?: Rect(hint.bubbleRect)
        }
        val targetRect = if (isScaled && scaleBounds != null && !scaleBounds.isEmpty) {
            Rect(scaleBounds)
        } else {
            Rect(logicalBounds)
        }
        val logicalW = logicalBounds.width().coerceAtLeast(1).toFloat()
        val logicalH = logicalBounds.height().coerceAtLeast(1).toFloat()
        val bubbleW = hint.bubbleRect.width().coerceAtLeast(1).toFloat()
        val bubbleH = hint.bubbleRect.height().coerceAtLeast(1).toFloat()
        val bubbleCx = hint.bubbleRect.exactCenterX()
        val bubbleCy = hint.bubbleRect.exactCenterY()

        val density = Resources.getSystem().displayMetrics.density
        val startCorner = EdgeBubbleGeometry.CONTAINER_RADIUS_DP * density
        val endCorner = DEFAULT_WINDOW_CORNER_RADIUS_DP * density
        val iconPx = (EdgeBubbleGeometry.ICON_SIZE_DP * density).toInt().coerceAtLeast(1)
        val innerPx = (EdgeBubbleGeometry.INNER_MARGIN_DP * density).toInt()
        val screenMarginPx = (EdgeBubbleGeometry.SCREEN_MARGIN_DP * density).toInt()
        val startIconLeft = hint.bubbleRect.left + if (hint.side == EdgeBubbleGeometry.SIDE_LEFT) {
            screenMarginPx + innerPx
        } else {
            innerPx
        }
        val startIconTop = hint.bubbleRect.top + innerPx
        val startIconCx = startIconLeft + iconPx / 2f
        val startIconCy = startIconTop + iconPx / 2f
        val targetVisualRight = targetRect.left + logicalW * baseScaleX
        val targetVisualBottom = targetRect.top + logicalH * baseScaleY
        val targetVisualRect = Rect(
            targetRect.left,
            targetRect.top,
            kotlin.math.ceil(targetVisualRight.toDouble()).toInt(),
            kotlin.math.ceil(targetVisualBottom.toDouble()).toInt()
        )
        val initialMorph = FreeformAnimCurves.computeRestoreMorphFrame(
            bubbleLeft = hint.bubbleRect.left.toFloat(),
            bubbleTop = hint.bubbleRect.top.toFloat(),
            bubbleRight = hint.bubbleRect.right.toFloat(),
            bubbleBottom = hint.bubbleRect.bottom.toFloat(),
            startIconCenterX = startIconCx,
            startIconCenterY = startIconCy,
            startIconSizePx = iconPx.toFloat(),
            targetLeft = targetRect.left.toFloat(),
            targetTop = targetRect.top.toFloat(),
            targetRight = targetVisualRight,
            targetBottom = targetVisualBottom,
            startCornerPx = startCorner,
            endCornerPx = endCorner,
            density = density,
            rawTimeFraction = 0f
        )
        val overlayContext = resolveOverlayContext(taskReflect.displayId(taskInfo))
        val appIcon = overlayContext?.let { loadTaskAppIcon(it, taskInfo) }

        val startFrame = FreeformAnimCurves.computeExpandFrame(
            bubbleCenterX = bubbleCx,
            bubbleCenterY = bubbleCy,
            bubbleWidth = bubbleW,
            bubbleHeight = bubbleH,
            targetLeft = targetRect.left.toFloat(),
            targetTop = targetRect.top.toFloat(),
            logicalWidth = logicalW,
            logicalHeight = logicalH,
            baseScaleX = baseScaleX,
            baseScaleY = baseScaleY,
            progress = 0f
        )
        if (startT != null && leash.isValid) {
            if (overlayContext != null) {
                startT.setScale(leash, baseScaleX, baseScaleY)
                startT.setPosition(leash, targetRect.left.toFloat(), targetRect.top.toFloat())
                startT.setAlpha(leash, 0f)
                taskReflect.setCornerRadius(startT, leash, endCorner)
                taskReflect.showSurface(startT, leash)
                startT.apply()
            } else {
                startT.setScale(leash, startFrame.scaleX, startFrame.scaleY)
                startT.setPosition(leash, startFrame.posX, startFrame.posY)
                startT.setAlpha(leash, RESTORE_START_ALPHA)
                taskReflect.setCornerRadius(startT, leash, startCorner)
                taskReflect.showSurface(startT, leash)
                startT.apply()
            }
        }
        if (finishT != null && leash.isValid) {
            finishT.setScale(leash, baseScaleX, baseScaleY)
            finishT.setPosition(leash, targetRect.left.toFloat(), targetRect.top.toFloat())
            finishT.setAlpha(leash, 1f)
            taskReflect.setCornerRadius(finishT, leash, endCorner)
        }

        val runnable = Runnable {
            activeAnimators.remove(taskId)?.cancel()
            dismissDockMorphOverlay(taskId)
            dismissRestoreMorphOverlay(taskId)

            val overlay = if (overlayContext != null) {
                EdgeRestoreMorphOverlay(
                    context = overlayContext,
                    bubbleRect = hint.bubbleRect,
                    targetRect = targetVisualRect,
                    appIcon = appIcon,
                    initialFrame = initialMorph,
                    density = density
                ).takeIf { it.show() }
            } else {
                null
            }
            if (overlay != null) {
                activeRestoreOverlays[taskId] = overlay
                var handoffSent = false
                overlay.postOnAnimation {
                    if (!activelyRestoringTasks.contains(taskId)) {
                        activeRestoreOverlays.remove(taskId, overlay)
                        overlay.dismiss()
                        if (!handoffSent) {
                            handoffSent = true
                            sendBubbleHideHandoff(taskId)
                        }
                        return@postOnAnimation
                    }
                    if (!handoffSent) {
                        handoffSent = true
                        sendBubbleHideHandoff(taskId)
                    }
                    val tx = SurfaceControl.Transaction()
                    var cancelled = false
                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = FreeformAnimCurves.RESTORE_DURATION_MS
                        interpolator = LinearInterpolator()
                        addUpdateListener { anim ->
                            val rawT = anim.animatedValue as Float
                            val morph = FreeformAnimCurves.computeRestoreMorphFrame(
                                bubbleLeft = hint.bubbleRect.left.toFloat(),
                                bubbleTop = hint.bubbleRect.top.toFloat(),
                                bubbleRight = hint.bubbleRect.right.toFloat(),
                                bubbleBottom = hint.bubbleRect.bottom.toFloat(),
                                startIconCenterX = startIconCx,
                                startIconCenterY = startIconCy,
                                startIconSizePx = iconPx.toFloat(),
                                targetLeft = targetRect.left.toFloat(),
                                targetTop = targetRect.top.toFloat(),
                                targetRight = targetVisualRight,
                                targetBottom = targetVisualBottom,
                                startCornerPx = startCorner,
                                endCornerPx = endCorner,
                                density = density,
                                rawTimeFraction = rawT
                            )
                            overlay.update(morph)
                            if (!leash.isValid) return@addUpdateListener
                            if (morph.windowAlpha > 0f) {
                                val frame = FreeformAnimCurves.computeExpandFrame(
                                    bubbleCenterX = bubbleCx,
                                    bubbleCenterY = bubbleCy,
                                    bubbleWidth = bubbleW,
                                    bubbleHeight = bubbleH,
                                    targetLeft = targetRect.left.toFloat(),
                                    targetTop = targetRect.top.toFloat(),
                                    logicalWidth = logicalW,
                                    logicalHeight = logicalH,
                                    baseScaleX = baseScaleX,
                                    baseScaleY = baseScaleY,
                                    progress = morph.springProgress
                                )
                                tx.setScale(leash, frame.scaleX, frame.scaleY)
                                tx.setPosition(leash, frame.posX, frame.posY)
                                tx.setAlpha(leash, morph.windowAlpha)
                                taskReflect.setCornerRadius(tx, leash, endCorner)
                                tx.apply()
                            } else {
                                tx.setAlpha(leash, 0f)
                                tx.apply()
                            }
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationCancel(animation: Animator) {
                                cancelled = true
                                activeAnimators.remove(taskId, animation)
                                activelyRestoringTasks.remove(taskId)
                                dismissRestoreMorphOverlay(taskId)
                                if (leash.isValid) {
                                    SurfaceControl.Transaction().apply {
                                        setScale(leash, baseScaleX, baseScaleY)
                                        setPosition(
                                            leash,
                                            targetRect.left.toFloat(),
                                            targetRect.top.toFloat()
                                        )
                                        setAlpha(leash, 1f)
                                        taskReflect.setCornerRadius(this, leash, endCorner)
                                        apply()
                                    }
                                }
                                if (!handoffSent) {
                                    handoffSent = true
                                    sendBubbleHideHandoff(taskId)
                                }
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                activeAnimators.remove(taskId, animation)
                                activelyRestoringTasks.remove(taskId)
                                if (!handoffSent) {
                                    handoffSent = true
                                    sendBubbleHideHandoff(taskId)
                                }
                                if (!cancelled) {
                                    if (leash.isValid) {
                                        SurfaceControl.Transaction().apply {
                                            setScale(leash, baseScaleX, baseScaleY)
                                            setPosition(
                                                leash,
                                                targetRect.left.toFloat(),
                                                targetRect.top.toFloat()
                                            )
                                            setAlpha(leash, 1f)
                                            taskReflect.setCornerRadius(this, leash, endCorner)
                                            apply()
                                        }
                                    }
                                    dismissRestoreMorphOverlay(taskId)
                                    onFinished?.invoke()
                                }
                            }
                        })
                    }
                    activeAnimators[taskId] = animator
                    animator.start()
                }
                return@Runnable
            }

            val tx = SurfaceControl.Transaction()
            var cancelled = false
            var bubbleHideSent = false
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FreeformAnimCurves.RESTORE_DURATION_MS
                interpolator = FreeformAnimCurves.restoreSpringInterpolator()
                addUpdateListener { anim ->
                    if (!leash.isValid) return@addUpdateListener
                    val p = anim.animatedValue as Float
                    val frame = FreeformAnimCurves.computeExpandFrame(
                        bubbleCenterX = bubbleCx,
                        bubbleCenterY = bubbleCy,
                        bubbleWidth = bubbleW,
                        bubbleHeight = bubbleH,
                        targetLeft = targetRect.left.toFloat(),
                        targetTop = targetRect.top.toFloat(),
                        logicalWidth = logicalW,
                        logicalHeight = logicalH,
                        baseScaleX = baseScaleX,
                        baseScaleY = baseScaleY,
                        progress = p
                    )
                    val alphaT = (p / 0.45f).coerceIn(0f, 1f)
                    val alpha = RESTORE_START_ALPHA + (1f - RESTORE_START_ALPHA) * alphaT
                    val corner = startCorner + (endCorner - startCorner) * p.coerceIn(0f, 1f)
                    tx.setScale(leash, frame.scaleX, frame.scaleY)
                    tx.setPosition(leash, frame.posX, frame.posY)
                    tx.setAlpha(leash, alpha)
                    taskReflect.setCornerRadius(tx, leash, corner)
                    tx.apply()
                    if (!bubbleHideSent && p >= FreeformAnimCurves.RESTORE_BUBBLE_HIDE_PROGRESS) {
                        bubbleHideSent = true
                        sendBubbleHideHandoff(taskId)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                        activeAnimators.remove(taskId, animation)
                        activelyRestoringTasks.remove(taskId)
                        if (!bubbleHideSent) {
                            bubbleHideSent = true
                            sendBubbleHideHandoff(taskId)
                        }
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        activeAnimators.remove(taskId, animation)
                        activelyRestoringTasks.remove(taskId)
                        if (!bubbleHideSent) {
                            bubbleHideSent = true
                            sendBubbleHideHandoff(taskId)
                        }
                        if (!cancelled) {
                            onFinished?.invoke()
                        }
                    }
                })
            }
            activeAnimators[taskId] = animator
            animator.start()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    private fun sendBubbleHideHandoff(taskId: Int) {
        val context = sysUiContext ?: return
        val intent = Intent(FreeformAnimContract.ACTION_BUBBLE_EVENT).setPackage(PACKAGE_ZTOOL).apply {
            putExtra(FreeformAnimContract.EXTRA_EVENT, FreeformAnimContract.EVENT_BUBBLE_HIDE_HANDOFF)
            putExtra(FreeformAnimContract.EXTRA_TASK_ID, taskId)
        }
        runCatching {
            context.sendBroadcast(intent)
        }.onFailure {
            logger.warn("Bubble hide handoff broadcast failed: ${it.message}")
        }
    }

    private fun isRecentlyDocked(taskId: Int): Boolean {
        val meta = recentlyDockedTasks[taskId] ?: return false
        if (SystemClock.elapsedRealtime() - meta.dockedAtMs > DOCK_SUPPRESS_TTL_MS) {
            recentlyDockedTasks.remove(taskId, meta)
            return false
        }
        return true
    }

    private fun installDockedTransitionSuppressor(
        loader: ClassLoader,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect?,
        handles: MutableList<XposedInterface.HookHandle>
    ) {
        if (changeReflect == null) return
        val transitionsClass = loadClassOptional(loader, TRANSITIONS_CLASS) ?: return
        val infoClass = loadClassOptional(loader, TRANSITION_INFO_CLASS) ?: return
        val callbackClass = loadClassOptional(loader, TRANSITION_FINISH_CALLBACK_CLASS) ?: return
        val handlerClass = loadClassOptional(loader, TRANSITION_HANDLER_CLASS) ?: return
        val wctClass = loadClassOptional(loader, WCT_CLASS) ?: return
        val dispatchMethod = findMethodOptional(
            transitionsClass,
            "dispatchTransition",
            IBinder::class.java,
            infoClass,
            SurfaceControl.Transaction::class.java,
            SurfaceControl.Transaction::class.java,
            callbackClass,
            handlerClass
        ) ?: return
        val getChanges = findMethodOptional(infoClass, "getChanges") ?: return
        val onTransitionFinished = findMethodOptional(
            callbackClass,
            "onTransitionFinished",
            wctClass
        ) ?: return
        val handlersField = optionalFieldAny(transitionsClass, "mHandlers", "handlers")
        val mainExecutorField = optionalFieldAny(transitionsClass, "mMainExecutor", "mainExecutor")

        handles += hookWithId(dispatchMethod, "freeform_nonlinear_suppress_dock_transit") { chain ->
            handleDockedTransitionDispatch(
                chain = chain,
                taskReflect = taskReflect,
                changeReflect = changeReflect,
                getChanges = getChanges,
                onTransitionFinished = onTransitionFinished,
                handlersField = handlersField,
                mainExecutorField = mainExecutorField
            )
        }
        logger.info("Docked transition suppressor installed")
    }

    private fun handleDockedTransitionDispatch(
        chain: XposedInterface.Chain,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect,
        getChanges: Method,
        onTransitionFinished: Method,
        handlersField: Field?,
        mainExecutorField: Field?
    ): Any? {
        if (recentlyDockedTasks.isEmpty()) return chain.proceed()
        val now = SystemClock.elapsedRealtime()
        val iter = recentlyDockedTasks.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value.dockedAtMs > DOCK_SUPPRESS_TTL_MS) {
                iter.remove()
            }
        }
        if (recentlyDockedTasks.isEmpty()) return chain.proceed()

        val transitions = chain.thisObject ?: return chain.proceed()
        val info = chain.args.getOrNull(1) ?: return chain.proceed()
        val startT = chain.args.getOrNull(2) as? SurfaceControl.Transaction
            ?: return chain.proceed()
        val finishT = chain.args.getOrNull(3) as? SurfaceControl.Transaction
            ?: return chain.proceed()
        val finishCallback = chain.args.getOrNull(4) ?: return chain.proceed()
        val changes = runCatching { getChanges.invoke(info) as? List<*> }.getOrNull()
            ?: return chain.proceed()

        val matched = ArrayList<Triple<Any, Int, DockedTaskMeta>>()
        var hasOtherAppAnim = false
        for (change in changes) {
            if (change == null) continue
            val mode = runCatching { changeReflect.getMode.invoke(change) as? Int }.getOrNull() ?: -1
            val ti = runCatching {
                changeReflect.getTaskInfo.invoke(change) as? ActivityManager.RunningTaskInfo
            }.getOrNull() ?: continue
            val taskId = ti.taskId
            val meta = recentlyDockedTasks[taskId]
            if (meta != null && (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK)) {
                matched.add(Triple(change, taskId, meta))
            } else if (meta == null && mode in 1..4) {
                val actType = taskReflect.activityType(ti)
                if (actType != ACTIVITY_TYPE_HOME && actType != ACTIVITY_TYPE_RECENTS) {
                    hasOtherAppAnim = true
                }
            }
        }
        if (matched.isEmpty() || hasOtherAppAnim) return chain.proceed()

        val defaultHandler = runCatching {
            (handlersField?.get(transitions) as? List<*>)?.firstOrNull()
        }.getOrNull() ?: return chain.proceed()

        for ((change, taskId, meta) in matched) {
            val leash = runCatching {
                changeReflect.getLeash.invoke(change) as? SurfaceControl
            }.getOrNull()
            if (leash != null && leash.isValid) {
                taskReflect.hideSurface(startT, leash)
                startT.setAlpha(leash, 0f)
                taskReflect.hideSurface(finishT, leash)
                finishT.setAlpha(leash, 1f)
                finishT.setScale(leash, meta.baseScaleX, meta.baseScaleY)
                taskReflect.setCornerRadius(finishT, leash, meta.windowCornerRadius)
            }
            meta.cleanedUp = true
            recentlyDockedTasks.remove(taskId)
        }
        startT.apply()

        val mainExecutor = runCatching { mainExecutorField?.get(transitions) as? Executor }.getOrNull()
        val invokeFinish = Runnable {
            runCatching { onTransitionFinished.invoke(finishCallback, null) }
                .onFailure { logger.warn("Suppressed dock finish failed: ${it.message}") }
        }
        if (mainExecutor != null) {
            mainExecutor.execute(invokeFinish)
        } else {
            mainHandler.post(invokeFinish)
        }
        logger.info("Suppressed follow-up TO_BACK transition for docked tasks=${matched.map { it.second }}")
        return defaultHandler
    }

    // endregion

    // region reflection & state helpers

    private fun resolveTaskInfoReflect(loader: ClassLoader): TaskInfoReflect {
        val ti = ActivityManager.RunningTaskInfo::class.java
        val pcModeClass = loadClassOptional(loader, PC_MODE_CLASS)
            ?: loadClassOptional(loader, LEGACY_PC_MODE_CLASS)
        val configGetter = findMethodOptional(ti, "getConfiguration")
        val configField = optionalField(ti, "configuration")
        val windowConfigField = optionalField(Configuration::class.java, "windowConfiguration")
        val windowConfigGetter = findMethodOptional(Configuration::class.java, "getWindowConfiguration")
        val wcClass = windowConfigField?.type ?: windowConfigGetter!!.returnType
        val windowConfigBounds = findMethod(wcClass, "getBounds")
        return TaskInfoReflect(
            tokenField = optionalField(ti, "token"),
            displayIdField = optionalField(ti, "displayId"),
            displayIdGetter = findMethodOptional(ti, "getDisplayId"),
            getWindowingMode = findMethod(ti, "getWindowingMode"),
            getActivityType = findMethodOptional(ti, "getActivityType"),
            configField = configField,
            configGetter = configGetter,
            windowConfigField = windowConfigField,
            windowConfigGetter = windowConfigGetter,
            windowConfigBounds = windowConfigBounds,
            scaleXField = optionalField(ti, "scaleX"),
            scaleYField = optionalField(ti, "scaleY"),
            scaleSurfaceBoundsField = optionalField(ti, "scaleSurfaceBounds"),
            hangingScaleXField = optionalField(ti, "hangingScaleX"),
            hangingScaleYField = optionalField(ti, "hangingScaleY"),
            isHangingField = optionalFieldAny(ti, "isHangingMode", "mIsHangingMode", "isHanging"),
            isHangingGetter = findMethodOptional(ti, "isHangingMode")
                ?: findMethodOptional(ti, "isHanging"),
            isVisibleRequestedField = optionalField(ti, "isVisibleRequested"),
            isRunningField = optionalField(ti, "isRunning"),
            pcModeZuiDesktopByDisplay = pcModeClass?.let {
                findMethodOptional(it, "isInZuiDesktopModeStatus", Int::class.javaPrimitiveType)
            },
            pcModeUnderPc = pcModeClass?.let { findMethodOptional(it, "isUnderPcMode") },
            pcModeZuiDesktop = pcModeClass?.let {
                findMethodOptional(it, "isInZuiDesktopModeStatus")
            },
            pcModeDwOrCd = pcModeClass?.let {
                findMethodOptional(it, "isInDWOrCDStatus", Int::class.javaPrimitiveType)
            },
            pcModeConnectDisplay = pcModeClass?.let {
                findMethodOptional(it, "isFocusInConnectDisplay", Int::class.javaPrimitiveType)
            },
            motionEventGetDisplayId = findMethodOptional(MotionEvent::class.java, "getDisplayId"),
            txSetCornerRadius = findMethodOptional(
                SurfaceControl.Transaction::class.java,
                "setCornerRadius",
                SurfaceControl::class.java,
                Float::class.javaPrimitiveType
            ),
            txShow = findMethodOptional(
                SurfaceControl.Transaction::class.java,
                "show",
                SurfaceControl::class.java
            ),
            txHide = findMethodOptional(
                SurfaceControl.Transaction::class.java,
                "hide",
                SurfaceControl::class.java
            )
        )
    }

    private fun resolveChangeReflect(loader: ClassLoader): ChangeReflect? {
        val changeClass = loadClassOptional(loader, TRANSITION_CHANGE_CLASS) ?: return null
        return try {
            ChangeReflect(
                getMode = findMethod(changeClass, "getMode"),
                getStartAbsBounds = findMethod(changeClass, "getStartAbsBounds"),
                getEndAbsBounds = findMethod(changeClass, "getEndAbsBounds"),
                getLeash = findMethod(changeClass, "getLeash"),
                getTaskInfo = findMethod(changeClass, "getTaskInfo")
            )
        } catch (t: Throwable) {
            logger.warn("TransitionInfo.Change methods missing: ${t.message}")
            null
        }
    }

    private fun resolveDragReflect(loader: ClassLoader, listenerClass: Class<*>): DragReflect? {
        val controllerField = optionalFieldAny(
            listenerClass,
            "mDesktopTasksController",
            "desktopTasksController"
        )
        val controllerClass = controllerField?.type
            ?: loadClassOptional(loader, DESKTOP_TASKS_CONTROLLER_CLASS)
        val controllerDisplayControllerField = controllerClass?.let {
            optionalFieldAny(it, "displayController", "mDisplayController")
        }
        val displayControllerField = optionalFieldAny(
            listenerClass,
            "mDisplayController",
            "displayController"
        )
        if (displayControllerField == null && controllerDisplayControllerField == null) {
            logger.warn("Part B drag reflect failed: DisplayController field missing on listener & controller")
            return null
        }
        val displayControllerClass = displayControllerField?.type
            ?: controllerDisplayControllerField?.type
            ?: loadClassOptional(loader, DISPLAY_CONTROLLER_CLASS)
        if (displayControllerClass == null) {
            logger.warn("Part B drag reflect failed: DisplayController class unavailable")
            return null
        }
        val getDisplayLayout = findMethodOptional(
            displayControllerClass,
            "getDisplayLayout",
            Int::class.javaPrimitiveType
        ) ?: run {
            logger.warn("Part B drag reflect failed: DisplayController.getDisplayLayout(int) missing")
            return null
        }
        val displayLayoutClass = getDisplayLayout.returnType
        val displayLayoutWidth = optionalField(displayLayoutClass, "mWidth") ?: run {
            logger.warn("Part B drag reflect failed: DisplayLayout.mWidth missing")
            return null
        }
        val displayLayoutHeight = optionalField(displayLayoutClass, "mHeight") ?: run {
            logger.warn("Part B drag reflect failed: DisplayLayout.mHeight missing")
            return null
        }

        val listenerTaskId = optionalFieldAny(listenerClass, "mTaskId", "taskId")
        val listenerDecorationFinder = optionalFieldAny(
            listenerClass,
            "mWindowDecorationFinder",
            "windowDecorationFinder"
        )
        val finderApply = listenerDecorationFinder?.type?.let { finderClass ->
            findMethodOptional(finderClass, "apply", Any::class.java)
                ?: findMethodOptional(java.util.function.Function::class.java, "apply", Any::class.java)
        }
        val decorField = optionalFieldAny(
            listenerClass,
            "mWindowDecoration",
            "windowDecoration",
            "mDesktopWindowDecoration",
            "desktopWindowDecoration"
        )
        if (decorField == null &&
            (listenerTaskId == null || listenerDecorationFinder == null || finderApply == null)
        ) {
            logger.warn(
                "Part B drag reflect failed: neither mWindowDecoration nor " +
                    "(mTaskId + mWindowDecorationFinder.apply) available"
            )
            return null
        }
        val decorClass = decorField?.type
            ?: loadClassOptional(loader, WINDOW_DECORATION_WRAPPER_CLASS)
            ?: run {
                logger.warn("Part B drag reflect failed: WindowDecoration class unavailable")
                return null
            }

        val getVisualIndicator = controllerClass?.let {
            findMethodOptional(it, "getVisualIndicator")
        }
        val visualIndicatorField = controllerClass?.let {
            optionalFieldAny(it, "mVisualIndicator", "visualIndicator")
        }
        val indicatorClass = getVisualIndicator?.returnType
            ?: visualIndicatorField?.type
            ?: loadClassOptional(loader, VISUAL_INDICATOR_CLASS)
        val indicatorCalcType = indicatorClass?.declaredMethods?.firstOrNull {
            it.name == "calculateIndicatorType"
        }?.apply { isAccessible = true }

        val organizerField = optionalFieldAny(
            listenerClass,
            "mShellTaskOrganizer",
            "shellTaskOrganizer",
            "mTaskOrganizer",
            "taskOrganizer"
        )
        val controllerOrganizerField = controllerClass?.let {
            optionalFieldAny(it, "shellTaskOrganizer", "mShellTaskOrganizer")
        }
        val organizerClass = organizerField?.type
            ?: controllerOrganizerField?.type
            ?: loadClassOptional(loader, SHELL_TASK_ORGANIZER_CLASS)

        val wctClass = loadClassOptional(loader, WCT_CLASS)
        val tokenClass = loadClassOptional(loader, WCT_TOKEN_CLASS)
        val transitionsField = optionalFieldAny(listenerClass, "mTransitions", "transitions")
        val controllerTransitionsField = controllerClass?.let {
            optionalFieldAny(it, "transitions", "mTransitions")
        }
        val transitionsClass = transitionsField?.type
            ?: controllerTransitionsField?.type
            ?: loadClassOptional(loader, TRANSITIONS_CLASS)
        val controllerDragHandlerField = controllerClass?.let {
            optionalFieldAny(it, "windowDragTransitionHandler", "mWindowDragTransitionHandler")
        }

        val posField = optionalFieldAny(
            listenerClass,
            "mDragPositioningCallback",
            "dragPositioningCallback",
            "mTaskPositioner",
            "taskPositioner"
        )
        val posClass = posField?.type
        val veiledPosClass = loadClassOptional(loader, MULTI_DISPLAY_POSITIONER_CLASS)
            ?: loadClassOptional(loader, LEGACY_VEILED_POSITIONER_CLASS)
        val scalePosClass = loadClassOptional(loader, SCALE_POSITIONER_CLASS)
        val resizePosClass = loadClassOptional(loader, RESIZE_POSITIONER_CLASS)
        val executorClass = loadClassOptional(loader, BASE_SCALE_EXECUTOR_CLASS)
            ?: loadClassOptional(loader, UNIFORM_SCALE_EXECUTOR_CLASS)
        val sessionClass = loadClassOptional(loader, DRAG_SESSION_CLASS)
        val ovcMenuClass = loadClassOptional(loader, OVC_MENU_ANIMATION_CLASS)
            ?: loadClassOptional(loader, OVC_MENU_ANIMATION_ALT_CLASS)

        val posFields = listOfNotNull(scalePosClass, veiledPosClass, resizePosClass, posClass)
            .distinct()
            .map { cls ->
                PositionerFields(
                    ownerClass = cls,
                    ctrlType = optionalFieldAny(cls, "ctrlType", "mCtrlType"),
                    startPoint = optionalFieldAny(cls, "repositionStartPoint", "mRepositionStartPoint"),
                    startBounds = optionalFieldAny(cls, "taskBoundsAtDragStart", "mTaskBoundsAtDragStart"),
                    repositionBounds = optionalFieldAny(cls, "repositionTaskBounds", "mRepositionTaskBounds"),
                    jankMonitor = optionalFieldAny(cls, "mInteractionJankMonitor", "interactionJankMonitor")
                )
            }
        val jankMonitorClass = posFields.firstNotNullOfOrNull { it.jankMonitor?.type }
            ?: loadClassOptional(loader, INTERACTION_JANK_MONITOR_CLASS)
        val jankMonitorEnd = jankMonitorClass?.let {
            findMethodOptional(it, "end", Int::class.javaPrimitiveType)
        }

        val listenerCaptionTouchStatus = optionalFieldAny(
            listenerClass,
            "mCaptionTouchStatusListener",
            "captionTouchStatusListener"
        )
        val captionIsPressed = listenerCaptionTouchStatus?.type?.let {
            optionalFieldAny(it, "isCaptionPressed", "mIsCaptionPressed")
        }
        val listenerPinnedController = optionalFieldAny(
            listenerClass,
            "mPinnedLayerController",
            "pinnedLayerController"
        )
        val pinnedIsPinned = listenerPinnedController?.type?.let {
            findMethodOptional(it, "isPinned", Int::class.javaPrimitiveType)
        }
        val listenerMultiDisplayIndicator = optionalFieldAny(
            listenerClass,
            "mMultiDisplayDragMoveIndicatorController",
            "multiDisplayDragMoveIndicatorController"
        )
        val multiDisplayOnDragEnd = listenerMultiDisplayIndicator?.type?.let {
            findMethodOptional(
                it,
                "onDragEnd",
                Int::class.javaPrimitiveType,
                SurfaceControl.Transaction::class.java
            )
        }
        val listenerTransactionFactory = optionalFieldAny(
            listenerClass,
            "mTransactionFactory",
            "transactionFactory"
        )
        val supplierGet = listenerTransactionFactory?.type?.let {
            findMethodOptional(it, "get")
                ?: findMethodOptional(java.util.function.Supplier::class.java, "get")
        }

        return DragReflect(
            listenerIsDragging = optionalFieldAny(listenerClass, "mIsDragging", "isDragging"),
            listenerSameMode = optionalFieldAny(
                listenerClass,
                "isTouchedInSameWindowingMode",
                "mIsTouchedInSameWindowingMode"
            ),
            listenerStartDisplayId = optionalFieldAny(
                listenerClass,
                "mStartDisplayID",
                "startDisplayID",
                "mStartDisplayId",
                "startDisplayId"
            ),
            listenerIsPinned = optionalFieldAny(listenerClass, "mIsPinned", "isPinned"),
            listenerTaskId = listenerTaskId,
            listenerDecorationFinder = listenerDecorationFinder,
            finderApply = finderApply,
            listenerDecoration = decorField,
            listenerPositioner = posField,
            listenerDesktopController = controllerField,
            listenerDisplayController = displayControllerField,
            listenerOrganizer = organizerField,
            listenerTransitions = transitionsField,
            listenerDragHandler = optionalFieldAny(
                listenerClass,
                "mWindowDragTransitionHandler",
                "windowDragTransitionHandler"
            ),
            listenerCurrentBounds = optionalFieldAny(listenerClass, "mCurrentBounds", "currentBounds"),
            listenerDragPointerId = optionalFieldAny(listenerClass, "mDragPointerId", "dragPointerId"),
            listenerDragInterrupted = optionalFieldAny(listenerClass, "mDragInterrupted", "dragInterrupted"),
            listenerCaptionTouchStatus = listenerCaptionTouchStatus,
            captionIsPressed = captionIsPressed,
            listenerPinnedController = listenerPinnedController,
            pinnedIsPinned = pinnedIsPinned,
            listenerSetIsDragging = findMethodOptional(
                listenerClass,
                "setIsDragging",
                decorClass,
                Boolean::class.javaPrimitiveType
            ),
            listenerMultiDisplayIndicator = listenerMultiDisplayIndicator,
            multiDisplayOnDragEnd = multiDisplayOnDragEnd,
            listenerTransactionFactory = listenerTransactionFactory,
            supplierGet = supplierGet,
            decorGetTaskInfo = findMethodOptional(decorClass, "getTaskInfo"),
            decorTaskInfoField = optionalFieldAny(decorClass, "taskInfo", "mTaskInfo"),
            decorIsHandlingResize = findMethodOptional(decorClass, "isHandlingDragResize"),
            decorGetTaskSurface = findMethodOptional(decorClass, "getTaskSurface")
                ?: findMethodOptional(decorClass, "getLeash"),
            decorTaskSurfaceField = optionalFieldAny(
                decorClass,
                "taskSurface",
                "mTaskSurface",
                "leash",
                "mLeash"
            ),
            decorGetValidDragArea = findMethodOptional(decorClass, "getValidDragArea"),
            decorValidDragAreaField = optionalFieldAny(
                decorClass,
                "lastValidDragArea",
                "mLastValidDragArea"
            ),
            decorSetIsDragging = findMethodOptional(
                decorClass,
                "setIsDragging",
                Boolean::class.javaPrimitiveType
            ),
            decorIsDraggingField = optionalFieldAny(decorClass, "isDragging", "mIsDragging"),
            decorCornerRadiusField = optionalFieldAny(
                decorClass,
                "cornerRadius",
                "mCornerRadius",
                "windowCornerRadius"
            ),
            decorCornerRadiusGetter = findMethodOptional(decorClass, "getCornerRadius"),
            controllerDisplayControllerField = controllerDisplayControllerField,
            controllerOrganizerField = controllerOrganizerField,
            controllerTransitionsField = controllerTransitionsField,
            controllerDragHandlerField = controllerDragHandlerField,
            controllerGetVisualIndicator = getVisualIndicator,
            controllerVisualIndicatorField = visualIndicatorField,
            controllerReleaseVisualIndicator = controllerClass?.declaredMethods?.firstOrNull {
                it.name == "releaseVisualIndicator" && it.parameterTypes.size <= 1
            }?.apply { isAccessible = true },
            indicatorCalcType = indicatorCalcType,
            displayControllerGetLayout = getDisplayLayout,
            displayLayoutWidth = displayLayoutWidth,
            displayLayoutHeight = displayLayoutHeight,
            setTaskDragging = ovcMenuClass?.let {
                findMethodOptional(
                    it,
                    "setTaskDragging",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            },
            posFields = posFields,
            jankMonitorEnd = jankMonitorEnd,
            posDragExecutor = scalePosClass?.let {
                optionalFieldAny(it, "mDragExecutor", "dragExecutor")
            },
            posDragSession = posClass?.let { optionalFieldAny(it, "dragSession", "mDragSession") }
                ?: resizePosClass?.let { optionalFieldAny(it, "dragSession", "mDragSession") },
            posDelegate = scalePosClass?.let {
                optionalFieldAny(it, "mTaskPositioner", "taskPositioner")
            },
            sessionCtrlType = sessionClass?.let { optionalFieldAny(it, "ctrlType", "mCtrlType") },
            sessionStartPoint = sessionClass?.let {
                optionalFieldAny(it, "repositionStartPoint", "mRepositionStartPoint")
            },
            sessionStartBounds = sessionClass?.let {
                optionalFieldAny(it, "taskBoundsAtDragStart", "mTaskBoundsAtDragStart")
            },
            sessionRepositionBounds = sessionClass?.let {
                optionalFieldAny(it, "repositionTaskBounds", "mRepositionTaskBounds")
            },
            execInitBounds = executorClass?.let { optionalField(it, "mInitTaskBounds") },
            execRescaleBounds = executorClass?.let { optionalField(it, "mRescaleSurfaceBounds") },
            execTmpRescaleBounds = executorClass?.let {
                optionalField(it, "mTmpRescaleSurfaceBounds")
            },
            organizerScaleTaskSurface = organizerClass?.let {
                findMethodOptional(
                    it,
                    "scaleTaskSurface",
                    Int::class.javaPrimitiveType,
                    Rect::class.java,
                    Rect::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
            },
            organizerApplyTransaction = if (organizerClass != null && wctClass != null) {
                findMethodOptional(organizerClass, "applyTransaction", wctClass)
            } else {
                null
            },
            wctClass = wctClass,
            wctCtor = runCatching { wctClass?.getConstructor() }.getOrNull(),
            wctSetBounds = if (wctClass != null && tokenClass != null) {
                findMethodOptional(wctClass, "setBounds", tokenClass, Rect::class.java)
            } else {
                null
            },
            transitionsStartTransition = transitionsClass?.declaredMethods?.firstOrNull {
                it.name == "startTransition" && it.parameterTypes.size == 3
            }?.apply { isAccessible = true }
        )
    }

    private fun resolveDecoration(listener: Any, drag: DragReflect): Any? {
        runCatching { drag.listenerDecoration?.get(listener) }.getOrNull()?.let { return it }
        val taskId = runCatching { drag.listenerTaskId?.getInt(listener) }.getOrNull() ?: return null
        val finder = runCatching { drag.listenerDecorationFinder?.get(listener) }.getOrNull()
            ?: return null
        return runCatching { drag.finderApply?.invoke(finder, taskId) }.getOrNull()
    }

    private fun isTaskPinned(listener: Any, taskId: Int, drag: DragReflect): Boolean {
        val pinnedByCtrl = runCatching {
            val ctrl = drag.listenerPinnedController?.get(listener) ?: return@runCatching false
            drag.pinnedIsPinned?.invoke(ctrl, taskId) as? Boolean == true
        }.getOrDefault(false)
        if (pinnedByCtrl) return true
        return runCatching { drag.listenerIsPinned?.getBoolean(listener) == true }.getOrDefault(false)
    }

    private fun positionerFieldsFor(target: Any, drag: DragReflect): PositionerFields? =
        drag.posFields.firstOrNull { it.ownerClass.isInstance(target) }

    private fun updatePositionerBounds(positioner: Any?, snapped: Rect, drag: DragReflect) {
        if (positioner == null) return
        positionerFieldsFor(positioner, drag)?.let { fields ->
            runCatching { (fields.repositionBounds?.get(positioner) as? Rect)?.set(snapped) }
        }
        val session = runCatching { drag.posDragSession?.get(positioner) }.getOrNull()
        if (session != null) {
            runCatching { (drag.sessionRepositionBounds?.get(session) as? Rect)?.set(snapped) }
        }
        runCatching { drag.posDelegate?.get(positioner) }.getOrNull()?.let { delegate ->
            positionerFieldsFor(delegate, drag)?.let { fields ->
                runCatching { (fields.repositionBounds?.get(delegate) as? Rect)?.set(snapped) }
            }
        }
    }

    private fun resolveBaseScales(
        taskInfo: ActivityManager.RunningTaskInfo?,
        taskReflect: TaskInfoReflect,
        preferHanging: Boolean
    ): Pair<Float, Float> {
        if (taskInfo == null) return 1f to 1f
        if (preferHanging) {
            val hx = taskReflect.hangingScaleX(taskInfo)
            if (hx > 0f) {
                val hy = taskReflect.hangingScaleY(taskInfo)
                return hx to (if (hy > 0f) hy else hx)
            }
        }
        val sx = taskReflect.scaleX(taskInfo)
        if (sx > 0f) {
            val sy = taskReflect.scaleY(taskInfo)
            return sx to (if (sy > 0f) sy else sx)
        }
        return 1f to 1f
    }

    private fun resolveDragExecutor(positioner: Any?, drag: DragReflect): Any? {
        if (positioner == null) return null
        return runCatching { drag.posDragExecutor?.get(positioner) }.getOrNull()
    }

    private fun resolveDropBounds(
        listener: Any,
        positioner: Any?,
        taskInfo: ActivityManager.RunningTaskInfo,
        taskReflect: TaskInfoReflect,
        drag: DragReflect
    ): Rect? {
        val currentBounds = runCatching {
            drag.listenerCurrentBounds?.get(listener) as? Rect
        }.getOrNull()
        if (currentBounds != null && !currentBounds.isEmpty) {
            return Rect(currentBounds)
        }
        if (positioner != null) {
            positionerFieldsFor(positioner, drag)?.let { fields ->
                val pb = runCatching { fields.repositionBounds?.get(positioner) as? Rect }
                    .getOrNull()
                if (pb != null && !pb.isEmpty) return Rect(pb)
            }
            val session = runCatching { drag.posDragSession?.get(positioner) }.getOrNull()
            if (session != null) {
                val sb = runCatching { drag.sessionRepositionBounds?.get(session) as? Rect }
                    .getOrNull()
                if (sb != null && !sb.isEmpty) return Rect(sb)
            }
            val delegate = runCatching { drag.posDelegate?.get(positioner) }.getOrNull()
            if (delegate != null) {
                positionerFieldsFor(delegate, drag)?.let { fields ->
                    val db = runCatching { fields.repositionBounds?.get(delegate) as? Rect }
                        .getOrNull()
                    if (db != null && !db.isEmpty) return Rect(db)
                }
            }
        }
        if (taskReflect.scaleX(taskInfo) > 0f) {
            val sb = taskReflect.scaleSurfaceBounds(taskInfo)
            if (sb != null && !sb.isEmpty) return Rect(sb)
        }
        return taskReflect.logicalBounds(taskInfo)?.let { Rect(it) }
    }

    private fun resolveTaskSurface(decoration: Any, drag: DragReflect): SurfaceControl? =
        runCatching {
            (drag.decorGetTaskSurface?.invoke(decoration)
                ?: drag.decorTaskSurfaceField?.get(decoration)) as? SurfaceControl
        }.getOrNull()

    private fun resolveValidDragArea(decoration: Any, drag: DragReflect): Rect? =
        runCatching {
            (drag.decorGetValidDragArea?.invoke(decoration)
                ?: drag.decorValidDragAreaField?.get(decoration)) as? Rect
        }.getOrNull()

    private fun resolveWindowCornerRadius(
        decoration: Any,
        drag: DragReflect,
        density: Float
    ): Float {
        val raw = runCatching {
            (drag.decorCornerRadiusGetter?.invoke(decoration) as? Number)?.toFloat()
                ?: (drag.decorCornerRadiusField?.get(decoration) as? Number)?.toFloat()
        }.getOrNull()
        return if (raw != null && raw > 0f) raw else DEFAULT_WINDOW_CORNER_RADIUS_DP * density
    }

    private fun hasActiveVisualIndicator(
        listener: Any,
        controller: Any?,
        displayLayout: Any,
        displayId: Int,
        taskInfo: ActivityManager.RunningTaskInfo,
        dropBounds: Rect,
        event: MotionEvent,
        drag: DragReflect
    ): Boolean {
        if (controller == null) return false
        val indicator = runCatching {
            drag.controllerGetVisualIndicator?.invoke(controller)
                ?: drag.controllerVisualIndicatorField?.get(controller)
        }.getOrNull() ?: return false
        val calc = drag.indicatorCalcType ?: return true
        val rawX = resolvePointerRawX(listener, event, drag)
        val point = PointF(rawX, dropBounds.top.toFloat())
        val paramTypes = calc.parameterTypes
        val indicatorType = runCatching {
            when {
                paramTypes.size == 2 &&
                    (paramTypes[0] == Int::class.javaPrimitiveType ||
                        paramTypes[0] == Int::class.javaObjectType) ->
                    calc.invoke(indicator, displayId, point)
                paramTypes.size == 2 ->
                    calc.invoke(indicator, taskInfo, point)
                paramTypes.size == 3 ->
                    calc.invoke(indicator, displayLayout, taskInfo, point)
                else -> null
            }
        }.getOrNull() ?: return true
        return when (indicatorType.toString()) {
            "NO_INDICATOR",
            "TO_BUBBLE_LEFT_INDICATOR",
            "TO_BUBBLE_RIGHT_INDICATOR" -> false
            else -> true
        }
    }

    private fun cleanupDragState(
        listener: Any,
        decoration: Any,
        positioner: Any?,
        controller: Any?,
        taskId: Int,
        drag: DragReflect
    ) {
        runCatching { drag.setTaskDragging?.invoke(null, taskId, false) }
        if (controller != null && drag.controllerReleaseVisualIndicator != null) {
            runCatching {
                when (drag.controllerReleaseVisualIndicator.parameterTypes.size) {
                    0 -> drag.controllerReleaseVisualIndicator.invoke(controller)
                    1 -> drag.controllerReleaseVisualIndicator.invoke(
                        controller,
                        SurfaceControl.Transaction()
                    )
                }
            }
        }
        var isDraggingReset = false
        if (drag.listenerSetIsDragging != null) {
            isDraggingReset = runCatching {
                drag.listenerSetIsDragging.invoke(listener, decoration, false)
                true
            }.getOrDefault(false)
        }
        if (!isDraggingReset) {
            runCatching {
                if (drag.decorSetIsDragging != null) {
                    drag.decorSetIsDragging.invoke(decoration, false)
                } else {
                    drag.decorIsDraggingField?.setBoolean(decoration, false)
                }
            }
            runCatching { drag.listenerIsDragging?.setBoolean(listener, false) }
        }
        runCatching { drag.listenerDragInterrupted?.setBoolean(listener, false) }
        runCatching { drag.listenerSameMode?.setBoolean(listener, false) }
        runCatching {
            val status = drag.listenerCaptionTouchStatus?.get(listener)
            if (status != null) {
                drag.captionIsPressed?.setBoolean(status, false)
            }
        }
        val multiDisplayCtrl = runCatching { drag.listenerMultiDisplayIndicator?.get(listener) }
            .getOrNull()
        if (multiDisplayCtrl != null && drag.multiDisplayOnDragEnd != null) {
            val tx = runCatching {
                val factory = drag.listenerTransactionFactory?.get(listener)
                factory?.let { drag.supplierGet?.invoke(it) as? SurfaceControl.Transaction }
            }.getOrNull() ?: SurfaceControl.Transaction()
            runCatching {
                drag.multiDisplayOnDragEnd.invoke(multiDisplayCtrl, taskId, tx)
                tx.apply()
            }
        }

        fun resetPositionerFields(target: Any?) {
            if (target == null) return
            positionerFieldsFor(target, drag)?.let { fields ->
                runCatching {
                    val monitor = fields.jankMonitor?.get(target)
                    if (monitor != null) {
                        drag.jankMonitorEnd?.invoke(monitor, CUJ_FREEFORM_DRAG)
                    }
                }
                runCatching { fields.ctrlType?.setInt(target, 0) }
                runCatching { (fields.startPoint?.get(target) as? PointF)?.set(0f, 0f) }
                runCatching { (fields.startBounds?.get(target) as? Rect)?.setEmpty() }
            }
            val session = runCatching { drag.posDragSession?.get(target) }.getOrNull()
            if (session != null) {
                runCatching { drag.sessionCtrlType?.setInt(session, 0) }
                runCatching { (drag.sessionStartPoint?.get(session) as? PointF)?.set(0f, 0f) }
                runCatching { (drag.sessionStartBounds?.get(session) as? Rect)?.setEmpty() }
            }
        }
        resetPositionerFields(positioner)
        runCatching { drag.posDelegate?.get(positioner) }.getOrNull()?.let {
            resetPositionerFields(it)
        }
    }

    private fun readEdgeBubbleEnabled(): Boolean =
        runCatching {
            remotePreferences.getBoolean(
                PreferenceKeys.FREEFORM_EDGE_BUBBLE.name,
                PreferenceKeys.FREEFORM_EDGE_BUBBLE.default
            )
        }.getOrDefault(PreferenceKeys.FREEFORM_EDGE_BUBBLE.default)

    private fun readTriggerPercent(): Int =
        FreeformEdgeBubbleConfig.normalizeTriggerPercent(
            runCatching {
                remotePreferences.getInt(
                    PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT.name,
                    PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT.default
                )
            }.getOrDefault(PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT.default)
        )

    private fun loadClassOptional(loader: ClassLoader, name: String): Class<*>? =
        runCatching { loader.loadClass(name) }.getOrNull()

    private fun findMethodOptional(
        owner: Class<*>,
        name: String,
        vararg paramTypes: Class<*>?
    ): Method? =
        runCatching { findMethod(owner, name, *paramTypes) }.getOrNull()

    private fun optionalField(owner: Class<*>, name: String): Field? =
        runCatching { findField(owner, name) }.getOrNull()

    private fun optionalFieldAny(owner: Class<*>, vararg names: String): Field? {
        for (name in names) {
            optionalField(owner, name)?.let { return it }
        }
        return null
    }

    // endregion

    private companion object {
        const val OVF_TRANSITION_ANIM_CLASS = "com.android.wm.shell.transition.OvfTransitionAnimation"
        const val OVF_SCALE_TRANSITION_UTILS_CLASS =
            "com.android.wm.shell.transition.OvfScaleTransitionUtils"
        const val TRANSITION_INFO_CLASS = "android.window.TransitionInfo"
        const val TRANSITION_CHANGE_CLASS = $$"android.window.TransitionInfo$Change"
        const val TRANSITION_FINISH_CALLBACK_CLASS =
            $$"com.android.wm.shell.transition.Transitions$TransitionFinishCallback"
        const val TRANSITION_HANDLER_CLASS =
            $$"com.android.wm.shell.transition.Transitions$TransitionHandler"
        const val DEFAULT_TRANSITION_HANDLER_CLASS =
            "com.android.wm.shell.transition.DefaultTransitionHandler"
        const val FREEFORM_TASK_LISTENER_CLASS =
            "com.android.wm.shell.freeform.FreeformTaskListener"
        const val TOUCH_EVENT_LISTENER_CLASS =
            "com.android.wm.shell.windowdecor.DesktopModeTouchEventListener"
        const val WINDOW_DECORATION_WRAPPER_CLASS =
            "com.android.wm.shell.windowdecor.WindowDecorationWrapper"
        const val DESKTOP_TASKS_CONTROLLER_CLASS =
            "com.android.wm.shell.desktopmode.DesktopTasksController"
        const val DISPLAY_CONTROLLER_CLASS = "com.android.wm.shell.common.DisplayController"
        const val VISUAL_INDICATOR_CLASS =
            "com.android.wm.shell.desktopmode.DesktopModeVisualIndicator"
        const val SHELL_TASK_ORGANIZER_CLASS = "com.android.wm.shell.ShellTaskOrganizer"
        const val TRANSITIONS_CLASS = "com.android.wm.shell.transition.Transitions"
        const val MULTI_DISPLAY_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.MultiDisplayVeiledResizeTaskPositioner"
        const val LEGACY_VEILED_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.VeiledResizeTaskPositioner"
        const val SCALE_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.scale.OvfScaleTaskPositionerDecorator"
        const val RESIZE_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.ResizeTaskPositioner"
        const val BASE_SCALE_EXECUTOR_CLASS =
            "com.android.wm.shell.windowdecor.scale.OvfBaseScaleDragExecutor"
        const val UNIFORM_SCALE_EXECUTOR_CLASS =
            "com.android.wm.shell.windowdecor.scale.OvfUniformScaleDragExecutor"
        const val DRAG_SESSION_CLASS = "com.android.wm.shell.windowdecor.DragSession"
        const val INTERACTION_JANK_MONITOR_CLASS = "com.android.internal.jank.InteractionJankMonitor"
        const val OVC_MENU_ANIMATION_CLASS = "com.android.wm.shell.windowdecor.OvcMenuAnimation"
        const val OVC_MENU_ANIMATION_ALT_CLASS = "com.android.wm.shell.ov.utils.OvcMenuAnimation"
        const val PC_MODE_CLASS = "com.android.wm.shell.ov.utils.OvcPcModeUtils"
        const val LEGACY_PC_MODE_CLASS = "com.android.wm.shell.util.OvcShellUtils"
        const val WCT_CLASS = "android.window.WindowContainerTransaction"
        const val WCT_TOKEN_CLASS = "android.window.WindowContainerToken"
        const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"

        const val ACTION_BUBBLE_COMMAND = "com.qimian233.ztool.action.FREEFORM_BUBBLE_COMMAND"
        const val CMD_MINIMIZE_TASK = 1
        const val PACKAGE_ANDROID = "android"
        const val PACKAGE_ZTOOL = "com.qimian233.ztool"
        const val ADB_SHELL_UID = 2000
        const val WINDOWING_MODE_FREEFORM = 5
        const val TRANSIT_CLOSE = 2
        const val TRANSIT_TO_BACK = 4
        const val TRANSIT_CHANGE = 6
        const val ACTIVITY_TYPE_HOME = 2
        const val ACTIVITY_TYPE_RECENTS = 3
        const val CUJ_FREEFORM_DRAG = 110

        const val RECEIVER_RETRY_MAX_ATTEMPTS = 30
        const val RECEIVER_RETRY_DELAY_MS = 300L
        const val DOCK_CLEANUP_DELAY_MS = 350L
        const val DOCK_OVERLAY_HANDOFF_TIMEOUT_MS = 350L
        const val DOCK_SUPPRESS_TTL_MS = 2500L
        const val RESTORE_HINT_TTL_MS = 3000L
        const val VELOCITY_STALE_MS = 120L
        const val RESTORE_START_ALPHA = 0.2f
        const val BUBBLE_CORNER_RADIUS_DP = 18f
        const val DEFAULT_WINDOW_CORNER_RADIUS_DP = 24f
    }
}
