package com.qimian233.ztool.hook.modules.systemui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.res.Resources
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Makes the freeform bottom edge start a normal resize instead of the quick-swipe gesture.
 *
 * SystemUI treats ctrlType 8 as the bottom gesture bar and returns before
 * `onDragPositioningStart`; returning 8|16 marks the same edge while bypassing that check.
 * A fast upward drag closes the task; a fast downward drag calls moveToFullscreen directly.
 */
@SuppressLint("PrivateApi")
class FreeformBottomEdgeResizeHook : AppHookModule() {
    @Volatile
    private var transformedLogged = false

    private val velocityByTaskId = ConcurrentHashMap<Int, Float>()

    override fun getModuleName(): String =
        PreferenceKeys.SYSTEM_UI_FREEFORM_BOTTOM_EDGE_RESIZE.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        try {
            install(param)
        } catch (t: Throwable) {
            logger.error("Freeform bottom edge resize install failed", t)
        }
    }

    private fun install(param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val prefs = remotePreferences
        val thresholdEnabled = prefs.getBoolean(
            PreferenceKeys.LAUNCHER_FREEFORM_FULLSCREEN_THRESHOLD.name,
            PreferenceKeys.LAUNCHER_FREEFORM_FULLSCREEN_THRESHOLD.default
        )
        if (!thresholdEnabled) {
            logger.info("Freeform bottom edge resize skipped: fullscreen threshold disabled")
            return
        }
        val geometryClass = try {
            loader.loadClass(GEOMETRY_CLASS)
        } catch (t: Throwable) {
            logger.error("$GEOMETRY_CLASS not found", t)
            return
        }
        val calculateCtrlType = resolveCtrlTypeMethod(geometryClass) ?: return

        val isUnderPcMode = resolvePcModeMethod(loader)

        hookWithId(calculateCtrlType, "freeform_bottom_edge_resize") { chain ->
            try {
                val ctrlType = chain.proceed() as? Int ?: 0
                val pcMode = isUnderPcMode?.invoke(null) as? Boolean ?: false
                if (ctrlType == CTRL_TYPE_BOTTOM && !pcMode) {
                    if (!transformedLogged) {
                        transformedLogged = true
                        logger.info(
                            "Freeform bottom edge transformed: ctrlType=$ctrlType -> $CTRL_TYPE_BOTTOM_PROPORTIONAL"
                        )
                    }
                    CTRL_TYPE_BOTTOM_PROPORTIONAL
                } else {
                    ctrlType
                }
            } catch (t: Throwable) {
                logger.error("Freeform bottom edge transform failed", t)
                chain.proceed()
            }
        }
        try {
            installVelocityClose(loader)
        } catch (t: Throwable) {
            logger.error("Freeform bottom edge velocity install failed", t)
        }
        logger.info("Freeform bottom edge resize installed")
    }

    /**
     * Resolves the edge ctrl-type calculator. Android 17 exposes
     * `calculateEdgeResizeCtrlType(float, float)`; older builds only had the four-argument
     * `calculateCtrlType`.
     */
    private fun resolveCtrlTypeMethod(geometryClass: Class<*>): Method? {
        runCatching {
            findMethod(
                geometryClass,
                "calculateEdgeResizeCtrlType",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
        }.getOrNull()?.let { return it }
        return runCatching {
            findMethod(
                geometryClass,
                "calculateCtrlType",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
        }.getOrNull() ?: run {
            logger.error("DragResizeWindowGeometry ctrl-type method not found")
            null
        }
    }

    /**
     * PC-mode probe. Android 17 dropped the no-argument `OvcShellUtils.isUnderPcMode()`, so the
     * equivalent `OvcPcModeUtils.isUnderPcMode()` is tried first and the legacy one second.
     */
    private fun resolvePcModeMethod(loader: ClassLoader): Method? {
        for ((owner, name) in PC_MODE_CANDIDATES) {
            val method = runCatching {
                loader.loadClass(owner).getDeclaredMethod(name).apply { isAccessible = true }
            }.getOrNull()
            if (method != null) return method
        }
        logger.warn("PC mode check unavailable; bottom edge transform always applied")
        return null
    }

    private fun installVelocityClose(loader: ClassLoader) {
        logger.info("Installing freeform bottom edge velocity close")
        val injectorClass = loadClass(loader, INJECTOR_CLASS) ?: return

        val injectorCtrl = field(injectorClass, "mCtrlType") ?: return
        val injectorTaskInfo = field(injectorClass, "mTaskInfo") ?: return
        val dragStartY = field(injectorClass, "mDragStartY") ?: return
        val quickSwipeStart = field(injectorClass, "mQuickSwipeStartTimeMs") ?: return
        val touchSlop = field(injectorClass, "SCALED_TOUCH_SLOP") ?: return
        val injectorMethod = resolveGestureEventMethod(injectorClass) ?: return

        val transitionSourceClass = loadClass(loader, TRANSITION_SOURCE_CLASS)
        val taskDragSource = try {
            transitionSourceClass?.getField("TASK_DRAG")?.get(null)
        } catch (t: Throwable) {
            logger.error("DesktopModeTransitionSource.TASK_DRAG not found", t)
            null
        }
        if (taskDragSource == null) {
            logger.warn("Fullscreen transition source unavailable")
        }

        val closeVelocity = CLOSE_SPEED_DP_PER_SECOND *
            Resources.getSystem().displayMetrics.density
        logger.info(
            "Freeform bottom edge close threshold: " +
                "${CLOSE_SPEED_DP_PER_SECOND}dp/s (${closeVelocity}px/s)"
        )

        hookWithId(injectorMethod, "freeform_bottom_velocity_track") { chain ->
            try {
                val injector = chain.thisObject ?: return@hookWithId chain.proceed()
                val ctrlType: Int = injectorCtrl.getInt(injector)
                if ((ctrlType and CTRL_TYPE_PROPORTIONAL_FLAG) == 0) {
                    return@hookWithId chain.proceed()
                }
                val event = chain.args.firstOrNull { it is MotionEvent } as? MotionEvent
                    ?: return@hookWithId false
                val rawY = (chain.args.firstOrNull { it is Number } as? Number)?.toFloat()
                    ?: event.rawY
                val taskInfo = injectorTaskInfo.get(injector)
                    as? ActivityManager.RunningTaskInfo
                val taskId = taskInfo?.taskId
                if (taskId != null) {
                    var start = quickSwipeStart.getLong(injector)
                    val startY = dragStartY.getFloat(injector)
                    if (start == 0L &&
                        abs(rawY - startY) > touchSlop.getInt(injector)
                    ) {
                        start = event.eventTime
                        quickSwipeStart.setLong(injector, start)
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                            if (start != 0L) {
                                val elapsed = event.eventTime - start
                                if (elapsed > 0) {
                                    velocityByTaskId[taskId] =
                                        (rawY - startY) * 1000f / elapsed
                                }
                            }
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                quickSwipeStart.setLong(injector, 0L)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            velocityByTaskId.remove(taskId)
                            quickSwipeStart.setLong(injector, 0L)
                        }
                    }
                }
                false
            } catch (t: Throwable) {
                logger.error("Freeform bottom edge velocity tracking failed", t)
                false
            }
        }

        val legacyPositioner = runCatching { loader.loadClass(POSITIONER_CLASS) }.getOrNull()
        val legacyDecoration = runCatching { loader.loadClass(DECORATION_CLASS) }.getOrNull()
        if (legacyPositioner != null && legacyDecoration != null) {
            installLegacyVelocityEnd(
                legacyPositioner,
                legacyDecoration,
                taskDragSource,
                closeVelocity
            )
            return
        }
        installA17VelocityEnd(loader, taskDragSource, closeVelocity)
    }

    /**
     * Resolves `handleDragGestureEvent`. Android 17 swapped the parameter order to
     * `(MotionEvent, float)` from the legacy `(float, MotionEvent)`.
     */
    private fun resolveGestureEventMethod(injectorClass: Class<*>): Method? {
        runCatching {
            findMethod(
                injectorClass,
                "handleDragGestureEvent",
                MotionEvent::class.java,
                Float::class.javaPrimitiveType
            )
        }.getOrNull()?.let { return it }
        return runCatching {
            findMethod(
                injectorClass,
                "handleDragGestureEvent",
                Float::class.javaPrimitiveType,
                MotionEvent::class.java
            )
        }.getOrNull() ?: run {
            logger.error("OvfDriListenerInjector.handleDragGestureEvent not found")
            null
        }
    }

    private fun installLegacyVelocityEnd(
        positionerClass: Class<*>,
        decorationClass: Class<*>,
        taskDragSource: Any?,
        closeVelocity: Float
    ) {
        val positionerCtrl = field(positionerClass, "mCtrlType") ?: return
        val positionerDecoration = field(positionerClass, "mDesktopWindowDecoration") ?: return
        val positionerTransitions = field(positionerClass, "mTransitions") ?: return
        val positionerOrganizer = optionalField(positionerClass, "mTaskOrganizer")
        val positionerBounds = field(positionerClass, "mRepositionTaskBounds") ?: return
        val decorationTaskInfo = field(decorationClass, "mTaskInfo") ?: return
        val endMethod = resolveDragEndMethod(positionerClass) ?: return

        hookWithId(endMethod, "freeform_bottom_velocity_close") { chain ->
            try {
                val positioner = chain.thisObject ?: return@hookWithId chain.proceed()
                val ctrlType = positionerCtrl.getInt(positioner)
                val decoration = positionerDecoration.get(positioner)
                val taskInfo = decoration?.let { decorationTaskInfo.get(it) }
                    as? ActivityManager.RunningTaskInfo
                val bounds = readBounds(positionerBounds, positioner)
                val organizer = positionerOrganizer?.get(positioner)
                val transitions = positionerTransitions.get(positioner)
                handleFastEndAction(
                    positioner = positioner,
                    ctrlType = ctrlType,
                    decoration = decoration,
                    taskInfo = taskInfo,
                    bounds = bounds,
                    fullscreenScale = 0f,
                    skipCommitBounds = false,
                    organizer = organizer,
                    transitions = transitions,
                    controller = null,
                    taskDragSource = taskDragSource,
                    closeVelocity = closeVelocity,
                    resetVeil = { resetResizeVeil(positioner, "resetVeilIfVisible$2", decoration) },
                    resetDragState = {}
                )?.let { return@hookWithId it }
            } catch (t: Throwable) {
                logger.error("Freeform bottom edge velocity close failed", t)
            }
            chain.proceed()
        }
    }

    private fun installA17VelocityEnd(
        loader: ClassLoader,
        taskDragSource: Any?,
        closeVelocity: Float
    ) {
        val wrapperClass = loadClass(loader, WRAPPER_CLASS) ?: return
        val getTaskInfo = try {
            findMethod(wrapperClass, "getTaskInfo")
        } catch (t: Throwable) {
            logger.error("$WRAPPER_CLASS.getTaskInfo not found", t)
            return
        }
        val controllerOrganizerField = loadClass(loader, DESKTOP_TASKS_CONTROLLER_CLASS)
            ?.let { optionalField(it, "shellTaskOrganizer") }

        var hookedCount = 0
        if (installA17PositionerEnd(
                loader = loader,
                className = MULTI_DISPLAY_POSITIONER_CLASS,
                hookId = "freeform_bottom_velocity_close_multidisplay",
                spec = PositionerFieldSpec(
                    ctrlName = "ctrlType",
                    decorationName = "windowDecoration",
                    boundsName = "repositionTaskBounds",
                    transitionsName = "transitions",
                    organizerName = null,
                    controllerName = "desktopTasksController",
                    dragExecutorName = null,
                    dragStartBoundsName = "taskBoundsAtDragStart",
                    dragStartPointName = "repositionStartPoint",
                    movedOffScreenName = "hasMovedTaskSurfaceOffScreen",
                    resetVeilMethodName = "resetVeilIfVisible"
                ),
                getTaskInfo = getTaskInfo,
                controllerOrganizerField = controllerOrganizerField,
                taskDragSource = taskDragSource,
                closeVelocity = closeVelocity
            )
        ) {
            hookedCount++
        }
        if (installA17PositionerEnd(
                loader = loader,
                className = SCALE_POSITIONER_CLASS,
                hookId = "freeform_bottom_velocity_close_scale",
                spec = PositionerFieldSpec(
                    ctrlName = "mCtrlType",
                    decorationName = "mDesktopWindowDecoration",
                    boundsName = "mRepositionTaskBounds",
                    transitionsName = "mTransitions",
                    organizerName = "mTaskOrganizer",
                    controllerName = "mDesktopTasksController",
                    dragExecutorName = "mDragExecutor",
                    dragStartBoundsName = "mTaskBoundsAtDragStart",
                    dragStartPointName = "mRepositionStartPoint",
                    movedOffScreenName = null,
                    resetVeilMethodName = "resetVeilIfVisible$1"
                ),
                getTaskInfo = getTaskInfo,
                controllerOrganizerField = controllerOrganizerField,
                taskDragSource = taskDragSource,
                closeVelocity = closeVelocity
            )
        ) {
            hookedCount++
        }
        if (installResizeTaskPositionerEnd(
                loader = loader,
                getTaskInfo = getTaskInfo,
                controllerOrganizerField = controllerOrganizerField,
                taskDragSource = taskDragSource,
                closeVelocity = closeVelocity
            )
        ) {
            hookedCount++
        }
        if (hookedCount == 0) {
            logger.warn("No A17 task positioner available; fast-drag close and fullscreen skipped")
        }
    }

    private fun installA17PositionerEnd(
        loader: ClassLoader,
        className: String,
        hookId: String,
        spec: PositionerFieldSpec,
        getTaskInfo: Method,
        controllerOrganizerField: Field?,
        taskDragSource: Any?,
        closeVelocity: Float
    ): Boolean {
        val positionerClass = runCatching { loader.loadClass(className) }.getOrNull() ?: return false
        val ctrlField = field(positionerClass, spec.ctrlName) ?: return false
        val decorationField = field(positionerClass, spec.decorationName) ?: return false
        val boundsField = field(positionerClass, spec.boundsName) ?: return false
        val transitionsField = field(positionerClass, spec.transitionsName) ?: return false
        val organizerField = spec.organizerName?.let { optionalField(positionerClass, it) }
        val controllerField = optionalField(positionerClass, spec.controllerName)
        val dragExecutorField = spec.dragExecutorName?.let { optionalField(positionerClass, it) }
        val startBoundsField = optionalField(positionerClass, spec.dragStartBoundsName)
        val startPointField = optionalField(positionerClass, spec.dragStartPointName)
        val movedOffScreenField = spec.movedOffScreenName?.let { optionalField(positionerClass, it) }
        val endMethod = resolveDragEndMethod(positionerClass) ?: return false

        hookWithId(endMethod, hookId) { chain ->
            try {
                val positioner = chain.thisObject ?: return@hookWithId chain.proceed()
                val ctrlType = ctrlField.getInt(positioner)
                val decoration = decorationField.get(positioner)
                val taskInfo = decoration?.let {
                    runCatching { getTaskInfo.invoke(it) }.getOrNull()
                } as? ActivityManager.RunningTaskInfo
                val rawBounds = readBounds(boundsField, positioner)
                val scaleAdjustment = resolveUniformScaleAdjustment(
                    dragExecutor = dragExecutorField?.get(positioner),
                    fallbackBounds = rawBounds
                )
                val controller = resolveController(positioner, controllerField)
                val organizer = organizerField?.get(positioner)
                    ?: controller?.let { controllerOrganizerField?.get(it) }
                val transitions = transitionsField.get(positioner)
                handleFastEndAction(
                    positioner = positioner,
                    ctrlType = ctrlType,
                    decoration = decoration,
                    taskInfo = taskInfo,
                    bounds = scaleAdjustment.bounds,
                    fullscreenScale = scaleAdjustment.scale,
                    skipCommitBounds = scaleAdjustment.isUniformScale,
                    organizer = organizer,
                    transitions = transitions,
                    controller = controller,
                    taskDragSource = taskDragSource,
                    closeVelocity = closeVelocity,
                    resetVeil = {
                        resetResizeVeil(positioner, spec.resetVeilMethodName, decoration)
                    },
                    resetDragState = {
                        ctrlField.setInt(positioner, 0)
                        (startBoundsField?.get(positioner) as? Rect)?.setEmpty()
                        (startPointField?.get(positioner) as? PointF)?.set(0f, 0f)
                        movedOffScreenField?.setBoolean(positioner, false)
                    }
                )?.let { return@hookWithId it }
            } catch (t: Throwable) {
                logger.error("Freeform bottom edge velocity close failed ($className)", t)
            }
            chain.proceed()
        }
        return true
    }

    private fun installResizeTaskPositionerEnd(
        loader: ClassLoader,
        getTaskInfo: Method,
        controllerOrganizerField: Field?,
        taskDragSource: Any?,
        closeVelocity: Float
    ): Boolean {
        val positionerClass = runCatching {
            loader.loadClass(RESIZE_POSITIONER_CLASS)
        }.getOrNull() ?: return false
        val sessionClass = loadClass(loader, DRAG_SESSION_CLASS) ?: return false
        val sessionField = field(positionerClass, "dragSession") ?: return false
        val transitionsField = field(positionerClass, "transitions") ?: return false
        val controllerField = optionalField(positionerClass, "desktopTasksController")
        val taskResizerField = optionalField(positionerClass, "taskResizer")
        val sessionCtrlField = field(sessionClass, "ctrlType") ?: return false
        val sessionDecorationField = field(sessionClass, "windowDecoration") ?: return false
        val sessionBoundsField = field(sessionClass, "repositionTaskBounds") ?: return false
        val endMethod = resolveDragEndMethod(positionerClass) ?: return false

        hookWithId(endMethod, "freeform_bottom_velocity_close_resize") { chain ->
            try {
                val positioner = chain.thisObject ?: return@hookWithId chain.proceed()
                val session = sessionField.get(positioner) ?: return@hookWithId chain.proceed()
                val ctrlType = sessionCtrlField.getInt(session)
                val decoration = sessionDecorationField.get(session)
                val taskInfo = decoration?.let {
                    runCatching { getTaskInfo.invoke(it) }.getOrNull()
                } as? ActivityManager.RunningTaskInfo
                val bounds = readBounds(sessionBoundsField, session)
                val controller = resolveController(positioner, controllerField)
                val organizer = controller?.let { controllerOrganizerField?.get(it) }
                val transitions = transitionsField.get(positioner)
                val taskResizer = taskResizerField?.get(positioner)
                handleFastEndAction(
                    positioner = positioner,
                    ctrlType = ctrlType,
                    decoration = decoration,
                    taskInfo = taskInfo,
                    bounds = bounds,
                    fullscreenScale = 0f,
                    skipCommitBounds = false,
                    organizer = organizer,
                    transitions = transitions,
                    controller = controller,
                    taskDragSource = taskDragSource,
                    closeVelocity = closeVelocity,
                    resetVeil = {
                        cleanupResizeSession(taskResizer, session, decoration)
                    },
                    resetDragState = {
                        sessionField.set(positioner, null)
                    }
                )?.let { return@hookWithId it }
            } catch (t: Throwable) {
                logger.error("Freeform bottom edge velocity close failed ($RESIZE_POSITIONER_CLASS)", t)
            }
            chain.proceed()
        }
        return true
    }

    private fun resolveDragEndMethod(positionerClass: Class<*>): Method? =
        try {
            findMethod(
                positionerClass,
                "onDragPositioningEnd",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            logger.error("${positionerClass.simpleName}.onDragPositioningEnd not found", t)
            null
        }

    private fun handleFastEndAction(
        positioner: Any,
        ctrlType: Int,
        decoration: Any?,
        taskInfo: ActivityManager.RunningTaskInfo?,
        bounds: Rect?,
        fullscreenScale: Float,
        skipCommitBounds: Boolean,
        organizer: Any?,
        transitions: Any?,
        controller: Any?,
        taskDragSource: Any?,
        closeVelocity: Float,
        resetVeil: () -> Unit,
        resetDragState: () -> Unit
    ): Rect? {
        val velocity = taskInfo?.let { velocityByTaskId.remove(it.taskId) } ?: 0f
        logger.info(
            "Freeform bottom edge end: taskId=${taskInfo?.taskId}, " +
                "ctrlType=0x${Integer.toHexString(ctrlType)}, " +
                "velocity=${velocity.toInt()}px/s, bounds=$bounds"
        )
        if ((ctrlType and CTRL_TYPE_PROPORTIONAL_FLAG) == 0 ||
            taskInfo == null ||
            abs(velocity) < closeVelocity
        ) {
            return null
        }
        if (!skipCommitBounds) {
            commitBounds(organizer, taskInfo, bounds)
        }
        if (velocity > 0f) {
            if (fullscreenTask(
                    positioner = positioner,
                    controllerOverride = controller,
                    taskInfo = taskInfo,
                    taskDragSource = taskDragSource,
                    bounds = bounds,
                    scale = fullscreenScale
                )
            ) {
                logger.info(
                    "Freeform bottom edge fast drag fullscreens task: " +
                        "taskId=${taskInfo.taskId}, velocity=${velocity.toInt()}px/s"
                )
                releaseBlackVeil(decoration)
                Handler(Looper.getMainLooper()).postDelayed(resetVeil, 250L)
                resetDragState()
                return bounds ?: Rect()
            }
        } else {
            releaseBlackVeil(decoration)
            if (closeTask(taskInfo, transitions, bounds)) {
                logger.info(
                    "Freeform bottom edge fast drag closes task: " +
                        "taskId=${taskInfo.taskId}, velocity=${velocity.toInt()}px/s"
                )
                resetDragState()
                // The task teardown releases the veil surfaces; hiding it here
                // after removal would touch released SurfaceControls.
                return bounds ?: Rect()
            }
        }
        return null
    }

    private fun resolveUniformScaleAdjustment(
        dragExecutor: Any?,
        fallbackBounds: Rect?
    ): ScaleAdjustment {
        if (dragExecutor == null || dragExecutor.javaClass.name != UNIFORM_SCALE_EXECUTOR_CLASS) {
            return ScaleAdjustment(fallbackBounds, 0f, isUniformScale = false)
        }
        val tmpBounds = readRectField(dragExecutor, "mTmpRescaleSurfaceBounds")
        val rescaledBounds = readRectField(dragExecutor, "mRescaleSurfaceBounds")
        val initBounds = readRectField(dragExecutor, "mInitTaskBounds")
        val lastScale = runCatching {
            findField(dragExecutor.javaClass, "mLastScale").getFloat(dragExecutor)
        }.getOrDefault(0f)
        val visualBounds = when {
            tmpBounds != null && !tmpBounds.isEmpty -> tmpBounds
            rescaledBounds != null && !rescaledBounds.isEmpty -> rescaledBounds
            else -> fallbackBounds
        }
        val scale = if (
            visualBounds != null &&
            !visualBounds.isEmpty &&
            initBounds != null &&
            initBounds.width() > 0
        ) {
            visualBounds.width().toFloat() / initBounds.width().toFloat()
        } else {
            lastScale
        }
        return ScaleAdjustment(visualBounds, scale, isUniformScale = true)
    }

    private fun readRectField(target: Any, name: String): Rect? =
        runCatching { findField(target.javaClass, name).get(target) as? Rect }.getOrNull()

    private fun resolveController(positioner: Any, controllerField: Field?): Any? {
        runCatching {
            positioner.javaClass.methods.firstOrNull {
                it.name == "ovGetDesktopTasksController" && it.parameterTypes.isEmpty()
            }?.invoke(positioner)
        }.getOrNull()?.let { return it }
        return runCatching { controllerField?.get(positioner) }.getOrNull()
    }

    private fun readBounds(field: Field, target: Any): Rect? =
        try {
            val value: Any? = field.get(target)
            value as? Rect
        } catch (t: Throwable) {
            logger.warn("Failed to read reposition bounds: ${t.message}")
            null
        }

    private fun resetResizeVeil(positioner: Any, methodName: String, decoration: Any?) {
        try {
            val reset = positioner.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }
            if (reset != null) {
                reset.isAccessible = true
                reset.invoke(positioner)
                logger.debug("Freeform bottom edge resize veil reset")
                return
            }
            if (hideDecorationResizeVeil(decoration)) {
                return
            }
            logger.warn("$methodName not found")
        } catch (t: Throwable) {
            logger.warn("Failed to reset resize veil: ${t.message}")
        }
    }

    private fun cleanupResizeSession(taskResizer: Any?, session: Any, decoration: Any?) {
        try {
            val cleanup = taskResizer?.javaClass?.methods?.firstOrNull {
                it.name == "cleanup" && it.parameterTypes.size == 1
            }
            if (cleanup != null) {
                cleanup.isAccessible = true
                cleanup.invoke(taskResizer, session)
                return
            }
        } catch (t: Throwable) {
            logger.warn("Failed to cleanup resize session: ${t.message}")
        }
        hideDecorationResizeVeil(decoration)
    }

    private fun hideDecorationResizeVeil(decoration: Any?): Boolean {
        if (decoration == null) return false
        return try {
            val hide = decoration.javaClass.methods.firstOrNull {
                it.name == "hideResizeVeil" && it.parameterTypes.isEmpty()
            } ?: return false
            hide.isAccessible = true
            hide.invoke(decoration)
            true
        } catch (t: Throwable) {
            logger.warn("Failed to hide resize veil on decoration: ${t.message}")
            false
        }
    }

    private fun releaseBlackVeil(decoration: Any?) {
        if (decoration == null) return
        try {
            val release = decoration.javaClass.methods.firstOrNull {
                it.name == "ovfReleaseBlackVeil" && it.parameterTypes.isEmpty()
            } ?: return
            release.isAccessible = true
            release.invoke(decoration)
        } catch (t: Throwable) {
            logger.warn("Failed to release black veil: ${t.message}")
        }
    }

    private fun fullscreenTask(
        positioner: Any,
        controllerOverride: Any?,
        taskInfo: ActivityManager.RunningTaskInfo,
        taskDragSource: Any?,
        bounds: Rect?,
        scale: Float
    ): Boolean {
        return try {
            logger.info("Fullscreen attempt: taskId=${taskInfo.taskId}")
            val controller = controllerOverride ?: resolveController(positioner, null)
            if (controller == null) {
                logger.warn("DesktopTasksController is null for task ${taskInfo.taskId}")
                return false
            }
            val methods = controller.javaClass.methods.filter { it.name == "moveToFullscreen" }
            val a17BoundsMethod = methods.firstOrNull {
                val params = it.parameterTypes
                params.size == 3 &&
                    params[0] == Float::class.javaPrimitiveType &&
                    params[1] == Int::class.javaPrimitiveType &&
                    params[2] == Rect::class.java
            }
            if (a17BoundsMethod != null && bounds != null && !bounds.isEmpty) {
                a17BoundsMethod.isAccessible = true
                a17BoundsMethod.invoke(controller, scale, taskInfo.taskId, Rect(bounds))
                logger.info("Fullscreen requested (bounds): taskId=${taskInfo.taskId}")
                return true
            }
            if (taskDragSource == null) {
                logger.warn("Fullscreen transition source is null")
                return false
            }
            val a17SourceMethod = methods.firstOrNull {
                val params = it.parameterTypes
                params.size == 4 &&
                    params[0] == Int::class.javaPrimitiveType &&
                    params[3] == Boolean::class.javaPrimitiveType
            }
            if (a17SourceMethod != null) {
                a17SourceMethod.isAccessible = true
                a17SourceMethod.invoke(controller, taskInfo.taskId, taskDragSource, null, true)
                logger.info("Fullscreen requested (source4): taskId=${taskInfo.taskId}")
                return true
            }
            val legacyMethod = methods.firstOrNull {
                val params = it.parameterTypes
                params.size == 2 && params[0] == Int::class.javaPrimitiveType
            }
            if (legacyMethod == null) {
                logger.warn("moveToFullscreen not found on ${controller.javaClass.name}")
                return false
            }
            legacyMethod.isAccessible = true
            legacyMethod.invoke(controller, taskInfo.taskId, taskDragSource)
            logger.info("Fullscreen requested: taskId=${taskInfo.taskId}")
            true
        } catch (t: Throwable) {
            logger.error("Failed to fullscreen freeform task ${taskInfo.taskId}", t)
            false
        }
    }

    private fun commitBounds(
        organizer: Any?,
        taskInfo: ActivityManager.RunningTaskInfo,
        bounds: Rect?
    ): Boolean {
        if (organizer == null || bounds == null || bounds.isEmpty) return false
        return try {
            val transactionClass = Class.forName("android.window.WindowContainerTransaction")
            val tokenClass = Class.forName("android.window.WindowContainerToken")
            val token = taskToken(taskInfo) ?: return false
            val transaction = transactionClass.getConstructor().newInstance()
            transactionClass
                .getMethod("setBounds", tokenClass, Rect::class.java)
                .invoke(transaction, token, Rect(bounds))
            val apply = organizer.javaClass.methods.firstOrNull {
                it.name == "applyTransaction" && it.parameterTypes.size == 1
            } ?: return false
            apply.isAccessible = true
            apply.invoke(organizer, transaction)
            logger.info("Committed freeform bounds before fast action: $bounds")
            true
        } catch (t: Throwable) {
            logger.warn("Failed to commit freeform bounds before fast action: ${t.message}")
            false
        }
    }

    private fun closeTask(
        taskInfo: ActivityManager.RunningTaskInfo,
        transitions: Any?,
        bounds: Rect?
    ): Boolean {
        try {
            val service = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null)
            service.javaClass
                .getMethod("removeTask", Int::class.javaPrimitiveType)
                .invoke(service, taskInfo.taskId)
            return true
        } catch (t: Throwable) {
            logger.warn("Direct task close failed, falling back to transition: ${t.message}")
        }
        val transaction = try {
            Class.forName("android.window.WindowContainerTransaction")
                .getConstructor()
                .newInstance()
        } catch (t: Throwable) {
            logger.error("WindowContainerTransaction unavailable", t)
            null
        } ?: return false
        val token = taskToken(taskInfo) ?: return false
        try {
            val transactionClass = Class.forName("android.window.WindowContainerTransaction")
            val tokenClass = Class.forName("android.window.WindowContainerToken")
            if (bounds != null) {
                // Commit the live resize bounds before removal so the close transition
                // does not replay the pre-drag bounds.
                transactionClass
                    .getMethod("setBounds", tokenClass, Rect::class.java)
                    .invoke(transaction, token, bounds)
            }
            transactionClass
                .getMethod("removeTask", tokenClass)
                .invoke(transaction, token)
        } catch (t: Throwable) {
            logger.error("Failed to build removeTask transaction", t)
            return false
        }
        if (transitions != null) {
            try {
                val start = transitions.javaClass.methods.firstOrNull {
                    it.name == "startTransition" && it.parameterTypes.size == 3
                }
                if (start != null) {
                    start.isAccessible = true
                    start.invoke(transitions, REMOVE_TRANSITION, transaction, null)
                    return true
                }
            } catch (t: Throwable) {
                logger.warn("Remove transition failed, falling back to ATM: ${t.message}")
            }
        }
        return try {
            val service = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null)
            service.javaClass
                .getMethod("removeTask", Int::class.javaPrimitiveType)
                .invoke(service, taskInfo.taskId)
            true
        } catch (t: Throwable) {
            logger.error("Failed to close freeform task ${taskInfo.taskId}", t)
            false
        }
    }

    private fun taskToken(taskInfo: Any): Any? {
        var type: Class<*>? = taskInfo.javaClass
        while (type != null) {
            try {
                val tokenField = type.getDeclaredField("token")
                tokenField.isAccessible = true
                return tokenField.get(taskInfo)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (t: Throwable) {
                logger.error("Failed to read task token", t)
                return null
            }
        }
        return null
    }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        try {
            loader.loadClass(name)
        } catch (t: Throwable) {
            logger.error("$name not found", t)
            null
        }

    private fun field(owner: Class<*>, name: String): Field? =
        try {
            findField(owner, name)
        } catch (t: Throwable) {
            logger.error("Field $name not found on ${owner.name}", t)
            null
        }

    private fun optionalField(owner: Class<*>, name: String): Field? =
        runCatching { findField(owner, name) }.getOrNull()

    private class PositionerFieldSpec(
        val ctrlName: String,
        val decorationName: String,
        val boundsName: String,
        val transitionsName: String,
        val organizerName: String?,
        val controllerName: String,
        val dragExecutorName: String?,
        val dragStartBoundsName: String,
        val dragStartPointName: String,
        val movedOffScreenName: String?,
        val resetVeilMethodName: String
    )

    private class ScaleAdjustment(
        val bounds: Rect?,
        val scale: Float,
        val isUniformScale: Boolean
    )

    private companion object {
        const val GEOMETRY_CLASS = "com.android.wm.shell.windowdecor.DragResizeWindowGeometry"
        const val INJECTOR_CLASS = "com.android.wm.shell.windowdecor.OvfDriListenerInjector"
        const val POSITIONER_CLASS = "com.android.wm.shell.windowdecor.VeiledResizeTaskPositioner"
        const val DECORATION_CLASS = "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration"
        const val WRAPPER_CLASS = "com.android.wm.shell.windowdecor.WindowDecorationWrapper"
        const val MULTI_DISPLAY_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.MultiDisplayVeiledResizeTaskPositioner"
        const val SCALE_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.scale.OvfScaleTaskPositionerDecorator"
        const val RESIZE_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.ResizeTaskPositioner"
        const val DRAG_SESSION_CLASS = "com.android.wm.shell.windowdecor.DragSession"
        const val UNIFORM_SCALE_EXECUTOR_CLASS =
            "com.android.wm.shell.windowdecor.scale.OvfUniformScaleDragExecutor"
        const val DESKTOP_TASKS_CONTROLLER_CLASS =
            "com.android.wm.shell.desktopmode.DesktopTasksController"
        const val TRANSITION_SOURCE_CLASS =
            "com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource"
        const val CTRL_TYPE_BOTTOM = 8
        const val CTRL_TYPE_BOTTOM_PROPORTIONAL = 8 or 16
        const val CTRL_TYPE_PROPORTIONAL_FLAG = 16
        const val REMOVE_TRANSITION = 2
        // Original freeform quick-swipe threshold.
        const val CLOSE_SPEED_DP_PER_SECOND = 500f
        val PC_MODE_CANDIDATES = listOf(
            "com.android.wm.shell.ov.utils.OvcPcModeUtils" to "isUnderPcMode",
            "com.android.wm.shell.util.OvcShellUtils" to "isUnderPcMode"
        )
    }
}
