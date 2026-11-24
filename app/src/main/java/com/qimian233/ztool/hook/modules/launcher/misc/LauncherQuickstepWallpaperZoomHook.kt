package com.qimian233.ztool.hook.modules.launcher.misc

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.graphics.PointF
import android.os.IBinder
import android.view.MotionEvent
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.WallpaperZoomModes
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.WallpaperZoomSpring
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max

/**
 * Controls QuickStep Recents wallpaper zoom in ZUX Launcher across four active modes:
 * continuous, continuous with overshoot bounce, gesture-start trigger, or disabled.
 */
@SuppressLint("PrivateApi")
class LauncherQuickstepWallpaperZoomHook : AppHookModule() {

    private var lastSentProgress = 0f
    private var homeDragActive = false
    private var bridgeStartProgress = 0f
    private var impactTriggered = false
    private var lastObservedProgress = 0f
    private var lastObservedTimeNanos = 0L
    private var instantVelocity = 0f
    private var cachedWallpaperManager: WallpaperManager? = null
    private var setWallpaperZoomOutMethod: Method? = null
    private var weakLauncherActivity: WeakReference<Activity>? = null

    private val suppressZoomDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    private val spring = WallpaperZoomSpring { progress ->
        val activity = weakLauncherActivity?.get()
        if (activity == null || !isEnabled() || currentMode() != WallpaperZoomModes.CONTINUOUS_BOUNCE) {
            cancelSpring()
            return@WallpaperZoomSpring
        }
        try {
            dispatchZoom(activity, progress)
        } catch (t: Throwable) {
            cancelSpring()
            logger.error("Failed to update launcher wallpaper zoom spring", t)
        }
    }

    private fun cancelSpring() {
        spring.cancel()
    }

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (param.packageName != ScopeKeys.LAUNCHER.packageName) return
        val classLoader = param.defaultClassLoader

        setWallpaperZoomOutMethod = try {
            findMethod(
                WallpaperManager::class.java,
                "setWallpaperZoomOut",
                IBinder::class.java,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            logger.error("Failed to resolve WallpaperManager.setWallpaperZoomOut", t)
            null
        }

        val statefulActivityClass: Class<*>
        val getZoomUtilsMethod: Method
        val zoomUtilsZoomMethod: Method
        try {
            statefulActivityClass = classLoader.loadClass(STATEFUL_ACTIVITY)
            getZoomUtilsMethod = findMethod(statefulActivityClass, "getWallpaperZoomUtils")
            val zoomUtilsClass = getZoomUtilsMethod.returnType
            zoomUtilsZoomMethod = zoomUtilsClass.declaredMethods.firstOrNull { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    method.returnType == Void.TYPE
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("No void(boolean) method on ${zoomUtilsClass.name}")
        } catch (t: Throwable) {
            logger.error("Failed to resolve StatefulActivity wallpaper zoom utils", t)
            return
        }

        val launcherStateClass = loadClassOrNull(classLoader, LAUNCHER_STATE)
        val ordinalField = launcherStateClass?.let { optionalField(it, "ordinal") }

        hookZoomUtilsGate(zoomUtilsZoomMethod)
        hookStateSuppressors(
            classLoader,
            statefulActivityClass,
            launcherStateClass,
            ordinalField
        )
        hookQuickSwitchDetector(
            classLoader,
            statefulActivityClass,
            getZoomUtilsMethod,
            zoomUtilsZoomMethod
        )
        hookHomeOverviewTouchController(
            classLoader,
            statefulActivityClass,
            getZoomUtilsMethod,
            zoomUtilsZoomMethod
        )
        hookDepthController(classLoader, ordinalField)
        hookAppSwipeUpHandlers(
            classLoader,
            statefulActivityClass,
            getZoomUtilsMethod,
            zoomUtilsZoomMethod
        )
        logger.info("LauncherQuickstepWallpaperZoomHook installed")
    }

    private fun hookZoomUtilsGate(zoomUtilsZoomMethod: Method) {
        try {
            hookWithId(zoomUtilsZoomMethod, "launcher_quickstep_zoom_utils_gate") { chain ->
                val depth = suppressZoomDepth.get() ?: 0
                if (isEnabled() && depth > 0 && currentMode() != WallpaperZoomModes.GESTURE_START) {
                    return@hookWithId null
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.warn("Failed to hook zoom utils gate: ${t.message}")
        }
    }

    private fun hookStateSuppressors(
        classLoader: ClassLoader,
        statefulActivityClass: Class<*>,
        launcherStateClass: Class<*>?,
        ordinalField: Field?
    ) {
        val stateHooks = listOf(
            OVERVIEW_STATE to "launcher_quickstep_suppress_overview",
            BACKGROUND_APP_STATE to "launcher_quickstep_suppress_bgapp"
        )
        for ((className, hookId) in stateHooks) {
            try {
                val cls = classLoader.loadClass(className)
                val method = findMethod(cls, "onStateEnabled", statefulActivityClass)
                hookWithId(method, hookId) { chain ->
                    withZoomSuppressed { chain.proceed() }
                }
            } catch (t: Throwable) {
                logger.warn("Failed to hook $className.onStateEnabled: ${t.message}")
            }
        }

        if (launcherStateClass != null) {
            try {
                val method = findMethod(launcherStateClass, "onStateEnabled", statefulActivityClass)
                hookWithId(method, "launcher_quickstep_suppress_normal") { chain ->
                    val result = withZoomSuppressed { chain.proceed() }
                    if (isEnabled() && WallpaperZoomModes.isContinuous(currentMode())) {
                        val state = chain.thisObject
                        val activity = chain.getArg(0) as? Activity
                        val ordinal = if (state != null && ordinalField != null) {
                            ordinalField.getInt(state)
                        } else {
                            -1
                        }
                        if (ordinal == ORDINAL_NORMAL && activity != null) {
                            spring.cancel()
                            homeDragActive = false
                            bridgeStartProgress = 0f
                            impactTriggered = false
                            instantVelocity = 0f
                            lastObservedTimeNanos = 0L
                            dispatchZoom(activity, 0f)
                        }
                    }
                    result
                }
            } catch (t: Throwable) {
                logger.warn("Failed to hook LauncherState.onStateEnabled: ${t.message}")
            }
        }
    }

    private fun hookQuickSwitchDetector(
        classLoader: ClassLoader,
        statefulActivityClass: Class<*>,
        getZoomUtilsMethod: Method,
        zoomUtilsZoomMethod: Method
    ) {
        try {
            val detectorClass = classLoader.loadClass(BOTH_AXES_SWIPE_DETECTOR)
            val mListenerField = findField(detectorClass, "mListener")
            val listenerClass = mListenerField.type
            val mXRangeField = optionalField(listenerClass, "mXRange")
            val mLauncherField = findField(listenerClass, "mLauncher")

            val dragStartMethod = findMethod(
                detectorClass,
                "reportDragStartInternal",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(dragStartMethod, "launcher_quickstep_quickswitch_start") { chain ->
                val result = chain.proceed()
                if (!isEnabled() || currentMode() != WallpaperZoomModes.GESTURE_START) {
                    return@hookWithId result
                }
                val isSettling = chain.getArg(0) as Boolean
                if (!isSettling) {
                    val detector = chain.thisObject ?: return@hookWithId result
                    val listener = mListenerField.get(detector) ?: return@hookWithId result
                    val launcher = mLauncherField.get(listener)
                    if (launcher != null && statefulActivityClass.isInstance(launcher)) {
                        getZoomUtilsMethod.invoke(launcher)?.let {
                            zoomUtilsZoomMethod.invoke(it, true)
                        }
                    }
                }
                result
            }

            val draggingMethod = findMethod(
                detectorClass,
                "reportDraggingInternal",
                PointF::class.java,
                MotionEvent::class.java
            )
            hookWithId(draggingMethod, "launcher_quickstep_suppress_quickswitch") { chain ->
                val result = withZoomSuppressed { chain.proceed() }
                if (!isEnabled() || !WallpaperZoomModes.isContinuous(currentMode())) {
                    return@hookWithId result
                }
                val detector = chain.thisObject ?: return@hookWithId result
                val point = chain.getArg(0) as? PointF ?: return@hookWithId result
                val listener = mListenerField.get(detector) ?: return@hookWithId result
                val launcher = mLauncherField.get(listener) as? Activity ?: return@hookWithId result
                val xRange = mXRangeField?.getFloat(listener) ?: 0f
                if (xRange > 0f) {
                    weakLauncherActivity = WeakReference(launcher)
                    val progress = (max(0f, point.x) / xRange).coerceIn(0f, 1f)
                    dispatchZoom(launcher, progress)
                }
                result
            }
        } catch (t: Throwable) {
            logger.warn("Failed to hook BothAxesSwipeDetector: ${t.message}")
        }
    }

    private fun hookHomeOverviewTouchController(
        classLoader: ClassLoader,
        statefulActivityClass: Class<*>,
        getZoomUtilsMethod: Method,
        zoomUtilsZoomMethod: Method
    ) {
        try {
            val ctrlClass = classLoader.loadClass(NO_BUTTON_NAVBAR_TO_OVERVIEW_CONTROLLER)
            val handlingOverviewMethod = findMethod(ctrlClass, "handlingOverviewAnim")
            val mLauncherField = findField(ctrlClass, "mLauncher")
            val mStartedOverviewField = findField(ctrlClass, "mStartedOverview")
            val mReachedOverviewField = optionalField(ctrlClass, "mReachedOverview")
            val mStartDisplacementField = optionalField(ctrlClass, "mStartDisplacement")
            val mStartYField = optionalField(ctrlClass, "mStartY")
            val mMotionPauseField = optionalField(ctrlClass, "mMotionPauseDetector")
            val isPausedMethod = mMotionPauseField?.type?.let { findMethod(it, "isPaused") }

            val onDragStartMethod = findMethod(
                ctrlClass,
                "onDragStart",
                Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            hookWithId(onDragStartMethod, "launcher_quickstep_home_drag_start") { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@hookWithId result
                val ctrl = chain.thisObject ?: return@hookWithId result
                val launcher = mLauncherField.get(ctrl) as? Activity ?: return@hookWithId result
                weakLauncherActivity = WeakReference(launcher)
                if (!(handlingOverviewMethod.invoke(ctrl) as Boolean)) {
                    return@hookWithId result
                }
                val mode = currentMode()
                if (mode == WallpaperZoomModes.GESTURE_START) {
                    if (statefulActivityClass.isInstance(launcher)) {
                        getZoomUtilsMethod.invoke(launcher)?.let {
                            zoomUtilsZoomMethod.invoke(it, true)
                        }
                    }
                } else if (WallpaperZoomModes.isContinuous(mode)) {
                    spring.cancel()
                    homeDragActive = true
                    bridgeStartProgress = 0f
                    impactTriggered = false
                    instantVelocity = 0f
                    lastObservedTimeNanos = 0L
                }
                result
            }

            val updateProgressMethod = findMethod(
                ctrlClass,
                "updateProgress",
                Float::class.javaPrimitiveType
            )
            hookWithId(updateProgressMethod, "launcher_quickstep_home_progress") { chain ->
                val result = chain.proceed()
                if (!isEnabled() || !WallpaperZoomModes.isContinuous(currentMode())) {
                    return@hookWithId result
                }
                val ctrl = chain.thisObject ?: return@hookWithId result
                val launcher = mLauncherField.get(ctrl) as? Activity ?: return@hookWithId result
                if ((handlingOverviewMethod.invoke(ctrl) as Boolean) &&
                    !mStartedOverviewField.getBoolean(ctrl)
                ) {
                    val f = chain.getArg(0) as Float
                    val mapped = (f * 3.0f).coerceIn(0f, 0.85f)
                    bridgeStartProgress = mapped
                    recordObservedProgress(mapped)
                    dispatchZoom(launcher, mapped)
                }
                result
            }

            val onDragMethod = findMethod(
                ctrlClass,
                "onDrag",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                MotionEvent::class.java
            )
            hookWithId(onDragMethod, "launcher_quickstep_home_overdrag") { chain ->
                val result = chain.proceed()
                if (!isEnabled() || currentMode() != WallpaperZoomModes.CONTINUOUS_BOUNCE) {
                    return@hookWithId result
                }
                val ctrl = chain.thisObject ?: return@hookWithId result
                if (mStartedOverviewField.getBoolean(ctrl) &&
                    mReachedOverviewField?.getBoolean(ctrl) == true
                ) {
                    val startDisp = mStartDisplacementField?.get(ctrl) as? PointF
                    val startY = mStartYField?.getFloat(ctrl) ?: 0f
                    if (startDisp != null) {
                        val displacementY = chain.getArg(0) as Float
                        val resist = (startDisp.y - displacementY) / startY.coerceAtLeast(1f)
                        if (resist > 0f) {
                            val launcher = mLauncherField.get(ctrl) as? Activity
                            if (launcher != null) {
                                dispatchZoom(launcher, 1.0f + resist.coerceIn(0f, 1f) * 0.30f)
                            }
                        }
                    }
                }
                result
            }

            val onDragEndMethod = findMethod(
                ctrlClass,
                "onDragEnd",
                Float::class.javaPrimitiveType
            )
            hookWithId(onDragEndMethod, "launcher_quickstep_home_drag_end") { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@hookWithId result
                homeDragActive = false
                if (currentMode() != WallpaperZoomModes.CONTINUOUS_BOUNCE) {
                    return@hookWithId result
                }
                val ctrl = chain.thisObject ?: return@hookWithId result
                val pauseDetector = mMotionPauseField?.get(ctrl)
                val isPaused = if (pauseDetector != null && isPausedMethod != null) {
                    isPausedMethod.invoke(pauseDetector) as Boolean
                } else {
                    false
                }
                if (mStartedOverviewField.getBoolean(ctrl) && isPaused) {
                    val launcher = mLauncherField.get(ctrl) as? Activity ?: return@hookWithId result
                    weakLauncherActivity = WeakReference(launcher)
                    val velocity = chain.getArg(0) as Float
                    maybeStartImpactSpring(launcher, velocity)
                }
                result
            }
        } catch (t: Throwable) {
            logger.warn("Failed to hook NoButtonNavbarToOverviewTouchController: ${t.message}")
        }
    }

    private fun hookDepthController(classLoader: ClassLoader, ordinalField: Field?) {
        try {
            val depthClass = classLoader.loadClass(BASE_DEPTH_CONTROLLER_IMPL)
            val setDepthMethod = findMethod(depthClass, "setDepth", Float::class.javaPrimitiveType)
            val mContainerField = findField(depthClass, "mContainer")
            val mDepthField = findField(depthClass, "mDepth")

            val launcherClass = loadClassOrNull(classLoader, LAUNCHER_CLASS)
            val mStateManagerField = launcherClass?.let { optionalField(it, "mStateManager") }
            val smClass = mStateManagerField?.type
            val mStateField = smClass?.let { optionalField(it, "mState") }
            val mConfigField = smClass?.let { optionalField(it, "mConfig") }
            val mTargetStateField = mConfigField?.type?.let { optionalField(it, "targetState") }

            hookWithId(setDepthMethod, "launcher_quickstep_depth_follow") { chain ->
                val result = chain.proceed()
                val mode = currentMode()
                if (!isEnabled() || !WallpaperZoomModes.isContinuous(mode)) {
                    return@hookWithId result
                }
                val ctrl = chain.thisObject ?: return@hookWithId result
                val activity = mContainerField.get(ctrl) as? Activity ?: return@hookWithId result
                weakLauncherActivity = WeakReference(activity)
                if (homeDragActive || spring.isRunning) {
                    return@hookWithId result
                }

                var currentOrdinal = -1
                var targetOrdinal = -1
                if (launcherClass != null && launcherClass.isInstance(activity) &&
                    mStateManagerField != null && mStateField != null &&
                    mConfigField != null && mTargetStateField != null && ordinalField != null
                ) {
                    val sm = mStateManagerField.get(activity)
                    val curState = sm?.let { mStateField.get(it) }
                    val cfg = sm?.let { mConfigField.get(it) }
                    val tgtState = cfg?.let { mTargetStateField.get(it) }
                    if (curState != null) currentOrdinal = ordinalField.getInt(curState)
                    targetOrdinal = if (tgtState != null) ordinalField.getInt(tgtState) else currentOrdinal
                    if ((currentOrdinal == ORDINAL_ALL_APPS || targetOrdinal == ORDINAL_ALL_APPS) &&
                        currentOrdinal !in OVERVIEW_FAMILY_ORDINALS &&
                        targetOrdinal !in OVERVIEW_FAMILY_ORDINALS
                    ) {
                        return@hookWithId result
                    }
                }

                val depth = mDepthField.getFloat(ctrl)
                val progress = if (targetOrdinal in OVERVIEW_BRIDGE_ORDINALS && bridgeStartProgress > 0f) {
                    val t = ((depth - 0.05f) / 0.95f).coerceIn(0f, 1f)
                    bridgeStartProgress + t * (1f - bridgeStartProgress)
                } else {
                    depth.coerceIn(0f, 1f)
                }
                if (depth >= 0.99f || depth <= 0.01f) {
                    bridgeStartProgress = 0f
                }
                dispatchContinuousWithImpact(activity, progress, mode)
                result
            }
        } catch (t: Throwable) {
            logger.warn("Failed to hook BaseDepthControllerImpl.setDepth: ${t.message}")
        }
    }

    private fun hookAppSwipeUpHandlers(
        classLoader: ClassLoader,
        statefulActivityClass: Class<*>,
        getZoomUtilsMethod: Method,
        zoomUtilsZoomMethod: Method
    ) {
        try {
            val logicClass = classLoader.loadClass(SWIPE_UP_ANIMATION_LOGIC)
            val absHandlerClass = classLoader.loadClass(ABS_SWIPE_UP_HANDLER)
            val mCurrentShiftField = findField(logicClass, "mCurrentShift")
            val mValueField = findField(mCurrentShiftField.type, "value")
            val mContainerField = findField(absHandlerClass, "mContainer")
            val mIsMotionPausedField = optionalField(absHandlerClass, "mIsMotionPaused")

            val gestureStartedMethod = findMethod(
                absHandlerClass,
                "onGestureStarted",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(gestureStartedMethod, "launcher_quickstep_app_gesture_start") { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@hookWithId result
                val handler = chain.thisObject ?: return@hookWithId result
                val container = mContainerField.get(handler)
                (container as? Activity)?.let { weakLauncherActivity = WeakReference(it) }
                val mode = currentMode()
                if (mode == WallpaperZoomModes.GESTURE_START) {
                    if (container != null && statefulActivityClass.isInstance(container)) {
                        getZoomUtilsMethod.invoke(container)?.let {
                            zoomUtilsZoomMethod.invoke(it, true)
                        }
                    }
                } else if (WallpaperZoomModes.isContinuous(mode)) {
                    spring.cancel()
                    impactTriggered = false
                    instantVelocity = 0f
                    lastObservedTimeNanos = 0L
                }
                result
            }

            val updateDispMethod = findMethod(
                logicClass,
                "updateDisplacement",
                Float::class.javaPrimitiveType
            )
            hookWithId(updateDispMethod, "launcher_quickstep_app_swipe_shift") { chain ->
                val result = chain.proceed()
                val mode = currentMode()
                if (!isEnabled() || !WallpaperZoomModes.isContinuous(mode)) {
                    return@hookWithId result
                }
                val logic = chain.thisObject ?: return@hookWithId result
                if (!absHandlerClass.isInstance(logic)) return@hookWithId result
                val activity = mContainerField.get(logic) as? Activity ?: return@hookWithId result
                weakLauncherActivity = WeakReference(activity)
                val shiftObj = mCurrentShiftField.get(logic) ?: return@hookWithId result
                val shift = mValueField.getFloat(shiftObj)
                if (shift.isNaN()) return@hookWithId result
                val target = if (mode == WallpaperZoomModes.CONTINUOUS_BOUNCE) {
                    if (shift <= 1f) {
                        shift.coerceAtLeast(0f)
                    } else {
                        (1f + (shift - 1f) * 0.35f).coerceAtMost(1.35f)
                    }
                } else {
                    shift.coerceIn(0f, 1f)
                }
                dispatchContinuousWithImpact(activity, target, mode)
                result
            }

            val gestureEndedMethod = findMethod(
                absHandlerClass,
                "onGestureEnded",
                Float::class.javaPrimitiveType,
                PointF::class.java,
                Boolean::class.javaPrimitiveType
            )
            hookWithId(gestureEndedMethod, "launcher_quickstep_app_gesture_end") { chain ->
                val result = chain.proceed()
                if (!isEnabled() || currentMode() != WallpaperZoomModes.CONTINUOUS_BOUNCE) {
                    return@hookWithId result
                }
                val handler = chain.thisObject ?: return@hookWithId result
                if (mIsMotionPausedField?.getBoolean(handler) == true) {
                    val activity = mContainerField.get(handler) as? Activity ?: return@hookWithId result
                    weakLauncherActivity = WeakReference(activity)
                    val velocity = chain.getArg(0) as Float
                    maybeStartImpactSpring(activity, velocity)
                }
                result
            }
        } catch (t: Throwable) {
            logger.warn("Failed to hook SwipeUpAnimationLogic/AbsSwipeUpHandler: ${t.message}")
        }
    }

    private inline fun <T> withZoomSuppressed(block: () -> T): T {
        if (!isEnabled() || currentMode() == WallpaperZoomModes.GESTURE_START) {
            return block()
        }
        val prev = suppressZoomDepth.get() ?: 0
        suppressZoomDepth.set(prev + 1)
        return try {
            block()
        } finally {
            suppressZoomDepth.set(prev)
        }
    }

    private fun recordObservedProgress(progress: Float) {
        val clamped = progress.coerceAtLeast(0f)
        if (clamped < 0.85f) {
            impactTriggered = false
        }
        val now = System.nanoTime()
        if (lastObservedTimeNanos > 0L) {
            val dt = (now - lastObservedTimeNanos) / 1e9f
            if (dt in 0.002f..0.1f) {
                instantVelocity = ((clamped - lastObservedProgress) / dt).coerceAtLeast(0f)
            }
        }
        lastObservedProgress = clamped
        lastObservedTimeNanos = now
    }

    private fun dispatchContinuousWithImpact(activity: Activity, target: Float, mode: String) {
        recordObservedProgress(target)
        if (mode == WallpaperZoomModes.CONTINUOUS_BOUNCE && spring.isRunning) {
            return
        }
        if (mode == WallpaperZoomModes.CONTINUOUS_BOUNCE &&
            !impactTriggered &&
            target >= IMPACT_THRESHOLD &&
            instantVelocity >= MIN_BOUNCE_VELOCITY
        ) {
            impactTriggered = true
            val impactFrom = max(target, 1.0f)
            dispatchZoom(activity, impactFrom)
            spring.start(
                from = impactFrom,
                target = 1.0f,
                velocity = instantVelocity.coerceIn(0f, MAX_NORM_VELOCITY) * VELOCITY_TO_SPRING_SCALE
            )
            return
        }
        dispatchZoom(activity, target)
    }

    private fun maybeStartImpactSpring(activity: Activity, velocityPxPerMs: Float) {
        val screenHeight =
            (activity.window?.decorView?.height?.takeIf { it > 0 } ?: DEFAULT_SCREEN_HEIGHT).toFloat()
        // Launcher swipe controllers report velocity in px/ms, so scale to px/s.
        val normVel = ((abs(velocityPxPerMs) * 1000f) / screenHeight).coerceIn(0f, MAX_NORM_VELOCITY)
        if (!impactTriggered && (normVel >= MIN_BOUNCE_VELOCITY || lastSentProgress > 1.005f)) {
            impactTriggered = true
            spring.start(
                from = lastSentProgress,
                target = 1.0f,
                velocity = normVel * VELOCITY_TO_SPRING_SCALE
            )
        }
    }

    private fun dispatchZoom(activity: Activity, progress: Float) {
        val target = progress.coerceIn(0f, MAX_PROGRESS)
        val shouldSend = (target == 0f && lastSentProgress != 0f) ||
            (target == 1f && lastSentProgress != 1f) ||
            abs(target - lastSentProgress) >= PROGRESS_DELTA_THRESHOLD
        if (!shouldSend) return
        val decorView = activity.window?.decorView ?: return
        if (!decorView.isAttachedToWindow) return
        val token = decorView.windowToken ?: return
        val method = setWallpaperZoomOutMethod ?: return
        val wallpaperManager = cachedWallpaperManager
            ?: activity.getSystemService(WallpaperManager::class.java)
                ?.also { cachedWallpaperManager = it }
            ?: return
        method.invoke(wallpaperManager, token, WallpaperZoomModes.encodeProgress(target))
        lastSentProgress = target
    }

    private fun currentMode(): String = try {
        WallpaperZoomModes.normalize(
            remotePreferences.getString(
                PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM_MODE.name,
                PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM_MODE.default
            )
        )
    } catch (_: Throwable) {
        PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM_MODE.default
    }

    private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? =
        try {
            classLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun optionalField(startClass: Class<*>, name: String): Field? =
        try {
            findField(startClass, name)
        } catch (_: NoSuchFieldException) {
            null
        }

    companion object {
        private const val STATEFUL_ACTIVITY = "com.android.launcher3.statemanager.StatefulActivity"
        private const val LAUNCHER_CLASS = "com.android.launcher3.Launcher"
        private const val LAUNCHER_STATE = "com.android.launcher3.LauncherState"
        private const val OVERVIEW_STATE = "com.android.launcher3.uioverrides.states.OverviewState"
        private const val BACKGROUND_APP_STATE =
            "com.android.launcher3.uioverrides.states.BackgroundAppState"
        private const val BOTH_AXES_SWIPE_DETECTOR =
            "com.android.launcher3.touch.BothAxesSwipeDetector"
        private const val NO_BUTTON_NAVBAR_TO_OVERVIEW_CONTROLLER =
            "com.android.launcher3.uioverrides.touchcontrollers.NoButtonNavbarToOverviewTouchController"
        private const val BASE_DEPTH_CONTROLLER_IMPL =
            "com.android.quickstep.util.BaseDepthControllerImpl"
        private const val SWIPE_UP_ANIMATION_LOGIC = "com.android.quickstep.SwipeUpAnimationLogic"
        private const val ABS_SWIPE_UP_HANDLER = "com.android.quickstep.AbsSwipeUpHandler"

        private const val ORDINAL_NORMAL = 0
        private const val ORDINAL_ALL_APPS = 5
        private val OVERVIEW_FAMILY_ORDINALS = setOf(2, 3, 4, 6, 9)
        private val OVERVIEW_BRIDGE_ORDINALS = setOf(2, 3, 4, 9)
        private const val DEFAULT_SCREEN_HEIGHT = 1800
        private const val MAX_PROGRESS = 1.55f
        private const val PROGRESS_DELTA_THRESHOLD = 0.0005f
        private const val IMPACT_THRESHOLD = 0.995f
        private const val MIN_BOUNCE_VELOCITY = 0.35f
        private const val MAX_NORM_VELOCITY = 6.0f
        private const val VELOCITY_TO_SPRING_SCALE = 2.6f
    }
}
