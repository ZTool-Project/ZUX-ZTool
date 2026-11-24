package com.qimian233.ztool.hook.modules.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PointF
import android.graphics.Rect
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
import android.view.animation.ScaleAnimation
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

    private class TaskInfoReflect(
        val tokenField: Field?,
        val displayIdField: Field?,
        val displayIdGetter: Method?,
        val getWindowingMode: Method,
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
        val txShow: Method?
    ) {
        fun displayId(taskInfo: ActivityManager.RunningTaskInfo): Int =
            runCatching {
                displayIdField?.getInt(taskInfo)
                    ?: (displayIdGetter?.invoke(taskInfo) as? Int)
                    ?: 0
            }.getOrDefault(0)

        fun windowingMode(taskInfo: ActivityManager.RunningTaskInfo): Int =
            runCatching { getWindowingMode.invoke(taskInfo) as? Int }.getOrNull() ?: -1

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
    }

    private class ChangeReflect(
        val getMode: Method,
        val getStartAbsBounds: Method,
        val getEndAbsBounds: Method,
        val getLeash: Method,
        val getTaskInfo: Method
    )

    private class DragReflect(
        val listenerIsDragging: Field?,
        val listenerSameMode: Field?,
        val listenerStartDisplayId: Field?,
        val listenerIsPinned: Field?,
        val listenerDecoration: Field?,
        val listenerPositioner: Field?,
        val listenerDesktopController: Field?,
        val listenerDisplayController: Field?,
        val listenerOrganizer: Field?,
        val listenerTransitions: Field?,
        val listenerDragHandler: Field?,
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
        val controllerOrganizerField: Field?,
        val controllerGetVisualIndicator: Method?,
        val controllerVisualIndicatorField: Field?,
        val controllerReleaseVisualIndicator: Method?,
        val indicatorCalcType: Method?,
        val displayControllerGetLayout: Method,
        val displayLayoutWidth: Field,
        val displayLayoutHeight: Field,
        val setTaskDragging: Method?,
        val posCtrlType: Field?,
        val posStartPoint: Field?,
        val posStartBounds: Field?,
        val posRepositionBounds: Field?,
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
    private val activeAnimators = ConcurrentHashMap<Int, ValueAnimator>()
    private val activelyRestoringTasks = ConcurrentHashMap.newKeySet<Int>()
    private val lastVisibleByTaskId = ConcurrentHashMap<Int, Boolean>()

    @Volatile
    private var ztoolUid = -1

    @Volatile
    private var restoreReceiverRegistered = false

    @Volatile
    private var sysUiContext: Context? = null

    override fun getModuleName(): String =
        PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        try {
            install(param)
        } catch (t: Throwable) {
            logger.error("Freeform nonlinear anim install failed", t)
        }
    }

    private fun install(param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val taskReflect = resolveTaskInfoReflect(loader)
        val changeReflect = resolveChangeReflect(loader)

        runCatching {
            installOpenCloseAnimations(loader, taskReflect, changeReflect)
        }.onFailure {
            logger.warn("Part A open/close animations failed: ${it.message}")
        }
        runCatching {
            installEdgeDockAndSnapBack(loader, taskReflect)
        }.onFailure {
            logger.warn("Part B edge-dock/snap-back failed: ${it.message}")
        }
        runCatching {
            installRestoreBroadcastReceiver(loader)
        }.onFailure {
            logger.warn("Part C restore broadcast receiver failed: ${it.message}")
        }
        runCatching {
            installBubbleRestoreExpand(loader, taskReflect, changeReflect)
        }.onFailure {
            logger.warn("Part D bubble-restore expand failed: ${it.message}")
        }
    }

    // region Part A: open / close / minimize animations

    private fun installOpenCloseAnimations(
        loader: ClassLoader,
        taskReflect: TaskInfoReflect,
        changeReflect: ChangeReflect?
    ) {
        val ovfAnimClass = loadClassOptional(loader, OVF_TRANSITION_ANIM_CLASS) ?: return
        var hooksInstalled = 0

        findMethodOptional(ovfAnimClass, "createTaskAnimToFront", Rect::class.java)?.let { m ->
            hookWithId(m, "freeform_nonlinear_open_anim") { chain ->
                val rect = chain.args.getOrNull(0) as? Rect ?: return@hookWithId chain.proceed()
                buildOpenAnimationSet(rect)
            }
            hooksInstalled++
        }

        findMethodOptional(ovfAnimClass, "createTaskAnimToBack", Rect::class.java)?.let { m ->
            hookWithId(m, "freeform_nonlinear_close_back_anim") { chain ->
                val rect = chain.args.getOrNull(0) as? Rect ?: return@hookWithId chain.proceed()
                buildCloseAnimationSet(rect)
            }
            hooksInstalled++
        }

        val changeClass = loadClassOptional(loader, TRANSITION_CHANGE_CLASS)
        if (changeClass != null && changeReflect != null) {
            findMethodOptional(
                ovfAnimClass,
                "ovfBoundsVanishAnim",
                changeClass,
                SurfaceControl.Transaction::class.java
            )?.let { m ->
                hookWithId(m, "freeform_nonlinear_vanish_bounds") { chain ->
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
                hookWithId(m, "freeform_nonlinear_vanish_alpha") { chain ->
                    handleAlphaVanishAnim(chain, taskReflect, changeReflect)
                }
                hooksInstalled++
            }
        }

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
            hookWithId(m, "freeform_nonlinear_bounds_change") { chain ->
                val startRect = chain.args.getOrNull(0) as? Rect
                val endRect = chain.args.getOrNull(1) as? Rect
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
                hookWithId(m, "freeform_nonlinear_scale_fix") { chain ->
                    handleScaleFix(chain, taskReflect, changeReflect, animSetScale, animHasScale)
                }
                hooksInstalled++
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
        animHasScale: Method?
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
                val start = scaleX * FreeformAnimCurves.OPEN_START_SCALE
                animSetScale.invoke(anim, start, scaleX, start, scaleX)
                return null
            }
            mode == 2 || mode == 4 -> {
                val end = scaleX * FreeformAnimCurves.CLOSE_END_SCALE
                animSetScale.invoke(anim, scaleX, end, scaleX, end)
                return null
            }
            else -> return chain.proceed()
        }
    }

    // endregion

    // region Part B: edge-dock shrink & snap-back

    private fun installEdgeDockAndSnapBack(loader: ClassLoader, taskReflect: TaskInfoReflect) {
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
        hookWithId(handleMethod, "freeform_nonlinear_edge_drag") { chain ->
            try {
                val event = chain.args.firstOrNull { it is MotionEvent } as? MotionEvent
                if (event == null || event.actionMasked != MotionEvent.ACTION_UP) {
                    return@hookWithId chain.proceed()
                }
                if (!readEdgeBubbleEnabled()) {
                    return@hookWithId chain.proceed()
                }
                val listener = chain.thisObject ?: return@hookWithId chain.proceed()
                if (handleDragUp(listener, event, taskReflect, dragReflect)) {
                    return@hookWithId true
                }
            } catch (t: Throwable) {
                logger.warn("Part B edge-drag handler failed: ${t.message}")
            }
            chain.proceed()
        }
        logger.info("Part B edge-dock/snap-back installed on ${listenerClass.simpleName}")
    }

    private fun handleDragUp(
        listener: Any,
        event: MotionEvent,
        taskReflect: TaskInfoReflect,
        drag: DragReflect
    ): Boolean {
        val isDragging = runCatching { drag.listenerIsDragging?.getBoolean(listener) == true }
            .getOrDefault(false)
        if (!isDragging) return false

        val decoration = runCatching { drag.listenerDecoration?.get(listener) }.getOrNull()
            ?: return false
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
        if (runCatching { drag.listenerIsPinned?.getBoolean(listener) == true }.getOrDefault(false)) {
            return false
        }

        val eventDisplayId = taskReflect.eventDisplayId(event)
        val startDisplayId = runCatching {
            drag.listenerStartDisplayId?.getInt(listener) ?: eventDisplayId
        }.getOrDefault(eventDisplayId)
        if (startDisplayId != eventDisplayId) return false

        val positioner = runCatching { drag.listenerPositioner?.get(listener) }.getOrNull()
        val dropBounds = resolveDropBounds(positioner, taskInfo, taskReflect, drag)
        if (dropBounds == null || dropBounds.isEmpty) return false

        val controller = runCatching { drag.listenerDesktopController?.get(listener) }.getOrNull()
        val displayController = runCatching { drag.listenerDisplayController?.get(listener) }
            .getOrNull() ?: return false
        val displayLayout = runCatching {
            drag.displayControllerGetLayout.invoke(displayController, startDisplayId)
        }.getOrNull() ?: return false
        val displayWidth = runCatching { drag.displayLayoutWidth.getInt(displayLayout) }
            .getOrDefault(0)
        val displayHeight = runCatching { drag.displayLayoutHeight.getInt(displayLayout) }
            .getOrDefault(0)
        if (displayWidth <= 0 || displayHeight <= 0) return false

        if (hasActiveVisualIndicator(controller, displayLayout, taskInfo, event, drag)) {
            return false
        }

        val offLeft = (-dropBounds.left).coerceAtLeast(0)
        val offRight = (dropBounds.right - displayWidth).coerceAtLeast(0)
        if (offLeft == 0 && offRight == 0) return false

        val leash = resolveTaskSurface(decoration, drag) ?: return false
        if (!leash.isValid) return false

        val triggerPercent = readTriggerPercent()
        val triggerWidth = dropBounds.width().coerceAtLeast(1) * triggerPercent / 100f
        val taskId = taskInfo.taskId

        cleanupDragState(listener, decoration, positioner, controller, taskId, drag)
        activeAnimators.remove(taskId)?.cancel()

        val density = Resources.getSystem().displayMetrics.density
        val windowCornerRadius = resolveWindowCornerRadius(decoration, drag, density)
        val (baseScaleX, baseScaleY) = resolveBaseScales(taskInfo, taskReflect, preferHanging = false)

        if (offLeft >= triggerWidth || offRight >= triggerWidth) {
            val side = if (offLeft >= triggerWidth) 0 else 1
            startEdgeDockAnimation(
                taskId = taskId,
                side = side,
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
        val edgeX = if (side == 0) 0f else displayWidth.toFloat()
        val marginY = 32f * density
        val maxY = (displayHeight - marginY).coerceAtLeast(marginY)
        val bubbleCenterY = dropBounds.centerY().toFloat().coerceIn(marginY, maxY)
        val centerYRatio = (bubbleCenterY / displayHeight.toFloat()).coerceIn(0.05f, 0.95f)
        val startCorner = 24f * density
        val endCorner = 48f * density
        val tx = SurfaceControl.Transaction()

        var cancelled = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FreeformAnimCurves.EDGE_DOCK_DURATION_MS
            interpolator = FreeformAnimCurves.edgeDockInterpolator()
            addUpdateListener { anim ->
                if (!leash.isValid) return@addUpdateListener
                val p = anim.animatedValue as Float
                val frame = FreeformAnimCurves.computeShrinkFrame(
                    startLeft = dropBounds.left.toFloat(),
                    startTop = dropBounds.top.toFloat(),
                    startWidth = dropBounds.width().toFloat(),
                    startHeight = dropBounds.height().toFloat(),
                    baseScaleX = baseScaleX,
                    baseScaleY = baseScaleY,
                    targetCenterX = edgeX,
                    targetCenterY = bubbleCenterY,
                    endScaleRatio = FreeformAnimCurves.EDGE_DOCK_END_SCALE,
                    progress = p
                )
                val alpha = if (p <= 0.30f) {
                    1f
                } else {
                    val alphaT = ((p - 0.30f) / 0.70f).coerceIn(0f, 1f)
                    1f - alphaT
                }
                val radius = startCorner + (endCorner - startCorner) * p.coerceIn(0f, 1f)
                tx.setScale(leash, frame.scaleX, frame.scaleY)
                tx.setPosition(leash, frame.posX, frame.posY)
                tx.setAlpha(leash, alpha)
                taskReflect.setCornerRadius(tx, leash, radius)
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
                    sendMinimizeCommand(taskId, side, dropBounds, centerYRatio)
                    mainHandler.postDelayed({
                        if (!activelyRestoringTasks.contains(taskId) && leash.isValid) {
                            SurfaceControl.Transaction().apply {
                                setAlpha(leash, 1f)
                                setScale(leash, baseScaleX, baseScaleY)
                                taskReflect.setCornerRadius(this, leash, windowCornerRadius)
                                apply()
                            }
                        }
                    }, DOCK_CLEANUP_DELAY_MS)
                }
            })
        }
        activeAnimators[taskId] = animator
        animator.start()
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
        taskReflect: TaskInfoReflect,
        drag: DragReflect
    ) {
        val organizer = runCatching {
            drag.listenerOrganizer?.get(listener)
                ?: controller?.let { drag.controllerOrganizerField?.get(it) }
        }.getOrNull()

        if (isScaled && organizer != null && drag.organizerScaleTaskSurface != null) {
            val executor = resolveDragExecutor(positioner, drag)
            val initBounds = runCatching {
                executor?.let { drag.execInitBounds?.get(it) as? Rect }
            }.getOrNull() ?: taskReflect.logicalBounds(taskInfo) ?: snapped
            runCatching {
                drag.organizerScaleTaskSurface.invoke(
                    organizer,
                    taskInfo.taskId,
                    snapped,
                    initBounds,
                    scaleX,
                    scaleX
                )
                (executor?.let { drag.execRescaleBounds?.get(it) as? Rect })?.set(snapped)
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

        val transitions = runCatching { drag.listenerTransitions?.get(listener) }.getOrNull()
        val handler = runCatching { drag.listenerDragHandler?.get(listener) }.getOrNull()
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
        centerYRatio: Float
    ) {
        val context = sysUiContext ?: return
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
        runCatching {
            context.sendBroadcast(intent)
        }.onFailure {
            logger.warn("Failed to send minimize broadcast: ${it.message}")
        }
    }

    // endregion

    // region Part C: restore-hint broadcast receiver

    private fun installRestoreBroadcastReceiver(loader: ClassLoader) {
        if (restoreReceiverRegistered) return
        val atClass = loadClassOptional(loader, ACTIVITY_THREAD_CLASS) ?: return
        val currentAt = findMethodOptional(atClass, "currentActivityThread") ?: return
        val getSystemUiContext = findMethodOptional(atClass, "getSystemUiContext") ?: return
        val getSentFromUid = if (Build.VERSION.SDK_INT >= 34) {
            findMethodOptional(BroadcastReceiver::class.java, "getSentFromUid")
        } else {
            null
        }

        fun tryRegister(attempt: Int) {
            if (restoreReceiverRegistered) return
            val ctx = runCatching {
                val at = currentAt.invoke(null) ?: return@runCatching null
                getSystemUiContext.invoke(at) as? Context
            }.getOrNull()
            if (ctx == null) {
                if (attempt < RECEIVER_RETRY_MAX_ATTEMPTS) {
                    mainHandler.postDelayed({ tryRegister(attempt + 1) }, RECEIVER_RETRY_DELAY_MS)
                } else {
                    logger.warn("SystemUI context unavailable; restore hint receiver inactive")
                }
                return
            }
            sysUiContext = ctx
            val filter = IntentFilter(FreeformAnimContract.ACTION_SYSTEMUI_ANIM_CMD)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val uid = if (getSentFromUid != null) {
                        runCatching { getSentFromUid.invoke(this) as Int }.getOrDefault(-1)
                    } else {
                        0
                    }
                    if (!isAllowedRestoreCaller(context, uid)) {
                        logger.warn("Rejected anim command from untrusted uid=$uid")
                        resultCode = 0
                        return
                    }
                    val cmd = intent.getIntExtra(FreeformAnimContract.EXTRA_CMD, -1)
                    if (cmd == FreeformAnimContract.CMD_PREPARE_RESTORE) {
                        val taskId = intent.getIntExtra(FreeformAnimContract.EXTRA_TASK_ID, -1)
                        val side = intent.getIntExtra(FreeformAnimContract.EXTRA_SIDE, 0)
                        val l = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_LEFT, 0)
                        val t = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_TOP, 0)
                        val r = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_RIGHT, 0)
                        val b = intent.getIntExtra(FreeformAnimContract.EXTRA_BUBBLE_BOTTOM, 0)
                        if (taskId > 0 && r > l && b > t) {
                            pendingRestoreHints[taskId] = RestoreHint(
                                bubbleRect = Rect(l, t, r, b),
                                side = side,
                                createdAtMs = SystemClock.elapsedRealtime()
                            )
                            resultCode = 1
                        } else {
                            resultCode = 0
                        }
                    } else {
                        resultCode = 0
                    }
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
            ctx.registerReceiver(receiver, filter, flags)
            restoreReceiverRegistered = true
            logger.info("Part C SystemUI restore hint receiver registered")
        }

        tryRegister(0)
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
        changeReflect: ChangeReflect?
    ) {
        var layers = 0
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
                hookWithId(startAnimation, "freeform_nonlinear_bubble_restore_transit") { chain ->
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

        val listenerClass = loadClassOptional(loader, FREEFORM_TASK_LISTENER_CLASS)
        if (listenerClass != null) {
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
            if (onTaskInfoChanged != null && findTaskSurface != null) {
                hookWithId(onTaskInfoChanged, "freeform_nonlinear_bubble_restore_listener") { chain ->
                    val res = chain.proceed()
                    val listener = chain.thisObject ?: return@hookWithId res
                    val taskInfo = chain.args.getOrNull(0) as? ActivityManager.RunningTaskInfo
                        ?: return@hookWithId res
                    val taskId = taskInfo.taskId
                    val wasVisible = lastVisibleByTaskId.put(taskId, taskInfo.isVisible) == true
                    if (!wasVisible && taskInfo.isVisible && pendingRestoreHints.containsKey(taskId)) {
                        mainHandler.postDelayed({
                            val hint = pendingRestoreHints.remove(taskId) ?: return@postDelayed
                            val leash = runCatching {
                                findTaskSurface.invoke(listener, taskId) as? SurfaceControl
                            }.getOrNull() ?: return@postDelayed
                            if (!leash.isValid) return@postDelayed
                            playExpandAnimator(
                                taskId = taskId,
                                hint = hint,
                                taskInfo = taskInfo,
                                targetRectOverride = null,
                                leash = leash,
                                finishT = null,
                                onFinished = null,
                                taskReflect = taskReflect
                            )
                        }, LISTENER_FALLBACK_DELAY_MS)
                    }
                    res
                }
                layers++
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
        activeAnimators.remove(taskId)?.cancel()
        activelyRestoringTasks.add(taskId)

        val (baseScaleX, baseScaleY) = resolveBaseScales(taskInfo, taskReflect, preferHanging = false)
        val isScaled = taskReflect.scaleX(taskInfo) > 0f
        val scaleBounds = taskReflect.scaleSurfaceBounds(taskInfo)
        val logicalBounds = targetRectOverride?.takeUnless { it.isEmpty }
            ?: taskReflect.logicalBounds(taskInfo)
            ?: scaleBounds
            ?: Rect(hint.bubbleRect)
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
        val startCorner = BUBBLE_CORNER_RADIUS_DP * density
        val endCorner = DEFAULT_WINDOW_CORNER_RADIUS_DP * density

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
            startT.setScale(leash, startFrame.scaleX, startFrame.scaleY)
            startT.setPosition(leash, startFrame.posX, startFrame.posY)
            startT.setAlpha(leash, RESTORE_START_ALPHA)
            taskReflect.setCornerRadius(startT, leash, startCorner)
            taskReflect.showSurface(startT, leash)
            startT.apply()
        }
        if (finishT != null && leash.isValid) {
            finishT.setScale(leash, baseScaleX, baseScaleY)
            finishT.setPosition(leash, targetRect.left.toFloat(), targetRect.top.toFloat())
            finishT.setAlpha(leash, 1f)
            taskReflect.setCornerRadius(finishT, leash, endCorner)
        }

        val runnable = Runnable {
            val tx = SurfaceControl.Transaction()
            var cancelled = false
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
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                        activeAnimators.remove(taskId, animation)
                        activelyRestoringTasks.remove(taskId)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        activeAnimators.remove(taskId, animation)
                        activelyRestoringTasks.remove(taskId)
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
        val displayControllerField = optionalFieldAny(
            listenerClass,
            "mDisplayController",
            "displayController"
        ) ?: return null
        val displayControllerClass = displayControllerField.type
        val getDisplayLayout = findMethodOptional(
            displayControllerClass,
            "getDisplayLayout",
            Int::class.javaPrimitiveType
        ) ?: return null
        val displayLayoutClass = getDisplayLayout.returnType
        val displayLayoutWidth = optionalField(displayLayoutClass, "mWidth") ?: return null
        val displayLayoutHeight = optionalField(displayLayoutClass, "mHeight") ?: return null

        val decorField = optionalFieldAny(
            listenerClass,
            "mWindowDecoration",
            "windowDecoration",
            "mDesktopWindowDecoration",
            "desktopWindowDecoration"
        ) ?: return null
        val decorClass = decorField.type

        val controllerField = optionalFieldAny(
            listenerClass,
            "mDesktopTasksController",
            "desktopTasksController"
        )
        val controllerClass = controllerField?.type
            ?: loadClassOptional(loader, DESKTOP_TASKS_CONTROLLER_CLASS)
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
        val transitionsClass = transitionsField?.type ?: loadClassOptional(loader, TRANSITIONS_CLASS)

        val posField = optionalFieldAny(listenerClass, "mTaskPositioner", "taskPositioner")
        val posClass = posField?.type
        val veiledPosClass = loadClassOptional(loader, MULTI_DISPLAY_POSITIONER_CLASS)
            ?: loadClassOptional(loader, LEGACY_VEILED_POSITIONER_CLASS)
        val scalePosClass = loadClassOptional(loader, SCALE_POSITIONER_CLASS)
        val executorClass = loadClassOptional(loader, UNIFORM_SCALE_EXECUTOR_CLASS)
        val sessionClass = loadClassOptional(loader, DRAG_SESSION_CLASS)
        val ovcMenuClass = loadClassOptional(loader, OVC_MENU_ANIMATION_CLASS)
            ?: loadClassOptional(loader, OVC_MENU_ANIMATION_ALT_CLASS)

        val posLookupClass = scalePosClass ?: veiledPosClass ?: posClass
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
            controllerOrganizerField = controllerOrganizerField,
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
            posCtrlType = posLookupClass?.let { optionalFieldAny(it, "ctrlType", "mCtrlType") }
                ?: veiledPosClass?.let { optionalFieldAny(it, "ctrlType", "mCtrlType") },
            posStartPoint = posLookupClass?.let {
                optionalFieldAny(it, "repositionStartPoint", "mRepositionStartPoint")
            } ?: veiledPosClass?.let {
                optionalFieldAny(it, "repositionStartPoint", "mRepositionStartPoint")
            },
            posStartBounds = posLookupClass?.let {
                optionalFieldAny(it, "taskBoundsAtDragStart", "mTaskBoundsAtDragStart")
            } ?: veiledPosClass?.let {
                optionalFieldAny(it, "taskBoundsAtDragStart", "mTaskBoundsAtDragStart")
            },
            posRepositionBounds = posLookupClass?.let {
                optionalFieldAny(it, "repositionTaskBounds", "mRepositionTaskBounds")
            } ?: veiledPosClass?.let {
                optionalFieldAny(it, "repositionTaskBounds", "mRepositionTaskBounds")
            },
            posDragExecutor = scalePosClass?.let {
                optionalFieldAny(it, "mDragExecutor", "dragExecutor")
            },
            posDragSession = posClass?.let { optionalFieldAny(it, "dragSession", "mDragSession") },
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
        positioner: Any?,
        taskInfo: ActivityManager.RunningTaskInfo,
        taskReflect: TaskInfoReflect,
        drag: DragReflect
    ): Rect? {
        if (positioner != null) {
            val exec = resolveDragExecutor(positioner, drag)
            if (exec != null) {
                val tmp = runCatching { drag.execTmpRescaleBounds?.get(exec) as? Rect }.getOrNull()
                if (tmp != null && !tmp.isEmpty) return Rect(tmp)
                val rescaled = runCatching { drag.execRescaleBounds?.get(exec) as? Rect }.getOrNull()
                if (rescaled != null && !rescaled.isEmpty) return Rect(rescaled)
            }
            val session = runCatching { drag.posDragSession?.get(positioner) }.getOrNull()
            if (session != null) {
                val sb = runCatching { drag.sessionRepositionBounds?.get(session) as? Rect }
                    .getOrNull()
                if (sb != null && !sb.isEmpty) return Rect(sb)
            }
            val pb = runCatching { drag.posRepositionBounds?.get(positioner) as? Rect }.getOrNull()
            if (pb != null && !pb.isEmpty) return Rect(pb)
            val delegate = runCatching { drag.posDelegate?.get(positioner) }.getOrNull()
            if (delegate != null) {
                val db = runCatching { drag.posRepositionBounds?.get(delegate) as? Rect }
                    .getOrNull()
                if (db != null && !db.isEmpty) return Rect(db)
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
        controller: Any?,
        displayLayout: Any,
        taskInfo: ActivityManager.RunningTaskInfo,
        event: MotionEvent,
        drag: DragReflect
    ): Boolean {
        if (controller == null) return false
        val indicator = runCatching {
            drag.controllerGetVisualIndicator?.invoke(controller)
                ?: drag.controllerVisualIndicatorField?.get(controller)
        }.getOrNull() ?: return false
        val calc = drag.indicatorCalcType ?: return true
        val indicatorType = runCatching {
            val point = PointF(event.rawX, event.rawY)
            when (calc.parameterTypes.size) {
                3 -> calc.invoke(indicator, displayLayout, taskInfo, point)
                2 -> calc.invoke(indicator, taskInfo, point)
                else -> null
            }
        }.getOrNull() ?: return true
        return indicatorType.toString() != "NO_INDICATOR"
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
        runCatching {
            if (drag.decorSetIsDragging != null) {
                drag.decorSetIsDragging.invoke(decoration, false)
            } else {
                drag.decorIsDraggingField?.setBoolean(decoration, false)
            }
        }
        runCatching { drag.listenerIsDragging?.setBoolean(listener, false) }
        runCatching { drag.listenerSameMode?.setBoolean(listener, false) }

        fun resetPositionerFields(target: Any?) {
            if (target == null) return
            runCatching { drag.posCtrlType?.setInt(target, 0) }
            runCatching { (drag.posStartPoint?.get(target) as? PointF)?.set(0f, 0f) }
            runCatching { (drag.posStartBounds?.get(target) as? Rect)?.setEmpty() }
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
        const val DEFAULT_TRANSITION_HANDLER_CLASS =
            "com.android.wm.shell.transition.DefaultTransitionHandler"
        const val FREEFORM_TASK_LISTENER_CLASS =
            "com.android.wm.shell.freeform.FreeformTaskListener"
        const val TOUCH_EVENT_LISTENER_CLASS =
            "com.android.wm.shell.windowdecor.DesktopModeTouchEventListener"
        const val DESKTOP_TASKS_CONTROLLER_CLASS =
            "com.android.wm.shell.desktopmode.DesktopTasksController"
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
        const val UNIFORM_SCALE_EXECUTOR_CLASS =
            "com.android.wm.shell.windowdecor.scale.OvfUniformScaleDragExecutor"
        const val DRAG_SESSION_CLASS = "com.android.wm.shell.windowdecor.DragSession"
        const val OVC_MENU_ANIMATION_CLASS = "com.android.wm.shell.ov.utils.OvcMenuAnimation"
        const val OVC_MENU_ANIMATION_ALT_CLASS = "com.android.wm.shell.windowdecor.OvcMenuAnimation"
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
        const val TRANSIT_CHANGE = 6

        const val RECEIVER_RETRY_MAX_ATTEMPTS = 20
        const val RECEIVER_RETRY_DELAY_MS = 300L
        const val DOCK_CLEANUP_DELAY_MS = 140L
        const val LISTENER_FALLBACK_DELAY_MS = 24L
        const val RESTORE_HINT_TTL_MS = 3000L
        const val RESTORE_START_ALPHA = 0.2f
        const val BUBBLE_CORNER_RADIUS_DP = 18f
        const val DEFAULT_WINDOW_CORNER_RADIUS_DP = 24f
    }
}
