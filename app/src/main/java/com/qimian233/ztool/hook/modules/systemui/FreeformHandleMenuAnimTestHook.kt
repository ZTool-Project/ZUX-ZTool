package com.qimian233.ztool.hook.modules.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Test hook adding symmetric nonlinear expand/collapse transitions to ZUX's OvHandleMenu.
 * Defers menu button actions until the 100ms collapse animation completes.
 */
@SuppressLint("PrivateApi", "NewApi")
class FreeformHandleMenuAnimTestHook : AppHookModule() {

    private data class AnimGeometry(
        val pivotX: Float,
        val pivotY: Float,
        val startTx: Float,
        val startTy: Float
    )

    private class ClosingSession(
        val container: Any,
        val rootView: ViewGroup,
        val contentView: View,
        val captionHandle: View?,
        val pendingAction: Runnable?,
        var animator: ValueAnimator? = null,
        var released: Boolean = false
    )

    private class ControllerState {
        var activeRootView: WeakReference<ViewGroup>? = null
        var activeContentView: WeakReference<View>? = null
        var activeGeometry: AnimGeometry? = null
        var expandAnimator: ValueAnimator? = null
        var currentOpenness: Float = 0f
        var closingSession: ClosingSession? = null
        var skipCloseHook: Boolean = false
        var forceImmediateClose: Boolean = false
        var bypassOnClickOnce: Boolean = false
    }

    private inner class HostReflect(
        val controllerClass: Class<*>,
        val handleMenuField: Field,
        val viewHolderField: Field?,
        val captionHandleField: Field?,
        val ovWinDecorInjectorField: Field?,
        val coordinatorField: Field?,
        val clearAllCallbacksMethod: Method?,
        val closeHandleMenuMethod: Method,
        val containerField: Field,
        val globalMenuPositionField: Field?,
        val captionHeightField: Field?,
        val menuTaskInfoField: Field?,
        val reservedSpaceField: Field?,
        val containerGetViewMethod: Method,
        val containerReleaseViewMethod: Method,
        val listenerTaskIdField: Field,
        val listenerIsDraggingField: Field,
        val listenerFinderField: Field,
        val finderApplyMethod: Method?,
        val wrapperGetHandleMenuControllerMethod: Method,
        val listenerOnClickMethod: Method,
        val taskScaleXField: Field?,
        val taskScaleSurfaceBoundsField: Field?,
        val taskConfigField: Field?,
        val taskConfigGetter: Method?,
        val windowConfigField: Field?,
        val windowConfigGetter: Method?,
        val windowConfigBoundsMethod: Method?
    )

    private val stateLock = Any()
    private val controllers = WeakHashMap<Any, ControllerState>()
    private val attachedLayouts = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    @Volatile
    private var lastCreatingController: WeakReference<Any>? = null

    @Volatile
    private var cachedContentContainerId: Int = -1

    @Volatile
    private var cachedMenuActionIds: Set<Int>? = null

    override fun getModuleName(): String = PreferenceKeys.TEST_HOOK.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        try {
            install(param)
        } catch (t: Throwable) {
            logger.error("Freeform handle menu anim test hook install failed", t)
        }
    }

    private fun install(param: PackageLoadedParam) {
        val loader = param.defaultClassLoader

        val layoutClass = loadClass(loader, HANDLE_MENU_LAYOUT_CLASS) ?: return
        val controllerClass = loadClass(loader, HANDLE_CONTROLLER_CLASS) ?: return
        val ovHandleMenuClass = loadClass(loader, OV_HANDLE_MENU_CLASS) ?: return
        val containerClass = loadClass(loader, ADDITIONAL_VIEW_CONTAINER_CLASS) ?: return
        val listenerClass = loadClass(loader, TOUCH_EVENT_LISTENER_CLASS) ?: return
        val wrapperClass = loadClass(loader, WINDOW_DECOR_WRAPPER_CLASS) ?: return
        val wctClass = loadClass(loader, WCT_CLASS) ?: return

        val layoutCtor = runCatching {
            layoutClass.getDeclaredConstructor(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull() ?: run {
            logger.error("OvHandleMenuLinearLayout 4-arg constructor not found")
            return
        }

        val createHandleMenuMethod = findMethodOrNull(
            controllerClass,
            "createHandleMenu",
            Boolean::class.javaPrimitiveType!!
        ) ?: return
        val closeHandleMenuMethod = findMethodOrNull(controllerClass, "closeHandleMenu") ?: return
        val controllerCloseMethod = findMethodOrNull(
            controllerClass,
            "close",
            wctClass,
            SurfaceControl.Transaction::class.java
        ) ?: return
        val onClickMethod = findMethodOrNull(listenerClass, "onClick", View::class.java) ?: return
        val setIsDraggingMethod = findMethodOrNull(
            listenerClass,
            "setIsDragging",
            wrapperClass,
            Boolean::class.javaPrimitiveType!!
        ) ?: return

        val viewHolderClass = runCatching {
            loader.loadClass(HANDLE_VIEW_HOLDER_CLASS)
        }.getOrNull()
        val injectorClass = runCatching {
            loader.loadClass(INJECTOR_CLASS)
        }.getOrNull()
        val coordinatorClass = runCatching {
            loader.loadClass(COORDINATOR_CLASS)
        }.getOrNull()

        val reflect = HostReflect(
            controllerClass = controllerClass,
            handleMenuField = field(controllerClass, "handleMenu") ?: return,
            viewHolderField = optionalField(controllerClass, "viewHolder"),
            captionHandleField = viewHolderClass?.let { optionalField(it, "captionHandle") },
            ovWinDecorInjectorField = optionalField(controllerClass, "ovWinDecorInjector"),
            coordinatorField = injectorClass?.let { optionalField(it, "captionHandleMenuCoordinator") },
            clearAllCallbacksMethod = coordinatorClass?.let {
                optionalMethod(it, "clearAllCallbacks")
            },
            closeHandleMenuMethod = closeHandleMenuMethod,
            containerField = field(ovHandleMenuClass, "handleMenuViewContainer") ?: return,
            globalMenuPositionField = optionalField(ovHandleMenuClass, "globalMenuPosition"),
            captionHeightField = optionalField(ovHandleMenuClass, "captionHeight"),
            menuTaskInfoField = optionalField(ovHandleMenuClass, "taskInfo"),
            reservedSpaceField = optionalField(
                ovHandleMenuClass,
                "mHandleMenuGroupsContainerReservedSpace"
            ),
            containerGetViewMethod = method(containerClass, "getView") ?: return,
            containerReleaseViewMethod = method(containerClass, "releaseView") ?: return,
            listenerTaskIdField = field(listenerClass, "mTaskId") ?: return,
            listenerIsDraggingField = field(listenerClass, "mIsDragging") ?: return,
            listenerFinderField = field(listenerClass, "mWindowDecorationFinder") ?: return,
            finderApplyMethod = optionalMethod(
                java.util.function.Function::class.java,
                "apply",
                Any::class.java
            ),
            wrapperGetHandleMenuControllerMethod = method(
                wrapperClass,
                "getHandleMenuController"
            ) ?: return,
            listenerOnClickMethod = onClickMethod,
            taskScaleXField = optionalFieldByWalk(ActivityManager.RunningTaskInfo::class.java, "scaleX"),
            taskScaleSurfaceBoundsField = optionalFieldByWalk(
                ActivityManager.RunningTaskInfo::class.java,
                "scaleSurfaceBounds"
            ),
            taskConfigField = optionalFieldByWalk(
                ActivityManager.RunningTaskInfo::class.java,
                "configuration"
            ),
            taskConfigGetter = optionalMethodByWalk(
                ActivityManager.RunningTaskInfo::class.java,
                "getConfiguration"
            ),
            windowConfigField = optionalFieldByWalk(Configuration::class.java, "windowConfiguration"),
            windowConfigGetter = optionalMethodByWalk(
                Configuration::class.java,
                "getWindowConfiguration"
            ),
            windowConfigBoundsMethod = runCatching {
                loader.loadClass(WINDOW_CONFIGURATION_CLASS)
                    .getMethod("getBounds")
                    .apply { isAccessible = true }
            }.getOrNull()
        )

        hookWithId(layoutCtor, "freeform_handle_menu_anim_layout_init") { chain ->
            val result = chain.proceed()
            val rootView = chain.thisObject as? ViewGroup ?: return@hookWithId result
            try {
                prepareRootLayoutForExpand(rootView, reflect)
            } catch (t: Throwable) {
                logger.error("Failed preparing OvHandleMenuLinearLayout", t)
            }
            result
        }

        hookWithId(createHandleMenuMethod, "freeform_handle_menu_anim_create") { chain ->
            val controller = chain.thisObject ?: return@hookWithId chain.proceed()
            try {
                val state = getOrCreateState(controller)
                cancelClosingSession(state, reflect)
                lastCreatingController = WeakReference(controller)
            } catch (t: Throwable) {
                logger.error("createHandleMenu pre-hook failed", t)
            }
            chain.proceed()
        }

        hookWithId(closeHandleMenuMethod, "freeform_handle_menu_anim_close_menu") { chain ->
            val controller = chain.thisObject ?: return@hookWithId chain.proceed()
            val state = getState(controller) ?: return@hookWithId chain.proceed()
            if (state.skipCloseHook) {
                return@hookWithId chain.proceed()
            }
            if (state.forceImmediateClose) {
                cancelExpandAnimator(state, reflect, controller)
                cancelClosingSession(state, reflect)
                return@hookWithId chain.proceed()
            }
            try {
                val ovHandleMenu = reflect.handleMenuField.get(controller)
                    ?: return@hookWithId chain.proceed()
                val container = reflect.containerField.get(ovHandleMenu)
                    ?: return@hookWithId chain.proceed()
                val rootView = reflect.containerGetViewMethod.invoke(container) as? ViewGroup
                    ?: return@hookWithId chain.proceed()
                startCollapse(
                    controller = controller,
                    state = state,
                    reflect = reflect,
                    ovHandleMenu = ovHandleMenu,
                    container = container,
                    rootView = rootView,
                    invokeCloseMenu = { chain.proceed() },
                    pendingAction = null
                )
                return@hookWithId null
            } catch (t: Throwable) {
                logger.error("closeHandleMenu collapse hook failed", t)
                return@hookWithId chain.proceed()
            }
        }

        hookWithId(controllerCloseMethod, "freeform_handle_menu_anim_controller_close") { chain ->
            val controller = chain.thisObject ?: return@hookWithId chain.proceed()
            val state = getState(controller) ?: return@hookWithId chain.proceed()
            cancelExpandAnimator(state, reflect, controller)
            cancelClosingSession(state, reflect)
            state.forceImmediateClose = true
            try {
                chain.proceed()
            } finally {
                state.forceImmediateClose = false
            }
        }

        hookWithId(onClickMethod, "freeform_handle_menu_anim_click") { chain ->
            val listener = chain.thisObject ?: return@hookWithId chain.proceed()
            val view = chain.args.getOrNull(0) as? View ?: return@hookWithId chain.proceed()
            if (!isMenuActionView(view)) {
                return@hookWithId chain.proceed()
            }
            try {
                if (reflect.listenerIsDraggingField.getBoolean(listener)) {
                    return@hookWithId chain.proceed()
                }
                val controller = resolveControllerFromListener(listener, reflect)
                    ?: return@hookWithId chain.proceed()
                val state = getOrCreateState(controller)
                if (state.bypassOnClickOnce) {
                    state.bypassOnClickOnce = false
                    return@hookWithId chain.proceed()
                }
                if (state.closingSession != null) {
                    return@hookWithId null
                }
                val ovHandleMenu = reflect.handleMenuField.get(controller)
                    ?: return@hookWithId chain.proceed()
                val container = reflect.containerField.get(ovHandleMenu)
                    ?: return@hookWithId chain.proceed()
                val rootView = reflect.containerGetViewMethod.invoke(container) as? ViewGroup
                    ?: return@hookWithId chain.proceed()

                val replayAction = Runnable {
                    state.bypassOnClickOnce = true
                    try {
                        reflect.listenerOnClickMethod.invoke(listener, view)
                    } catch (t: Throwable) {
                        logger.error("Deferred handle menu onClick replay failed", t)
                    } finally {
                        state.bypassOnClickOnce = false
                    }
                }
                startCollapse(
                    controller = controller,
                    state = state,
                    reflect = reflect,
                    ovHandleMenu = ovHandleMenu,
                    container = container,
                    rootView = rootView,
                    invokeCloseMenu = { reflect.closeHandleMenuMethod.invoke(controller) },
                    pendingAction = replayAction
                )
                return@hookWithId null
            } catch (t: Throwable) {
                logger.error("Handle menu click interception failed", t)
                return@hookWithId chain.proceed()
            }
        }

        hookWithId(setIsDraggingMethod, "freeform_handle_menu_anim_drag") { chain ->
            val wrapper = chain.args.getOrNull(0)
            val isDragging = chain.args.getOrNull(1) as? Boolean == true
            if (isDragging && wrapper != null) {
                try {
                    val controller = runCatching {
                        reflect.wrapperGetHandleMenuControllerMethod.invoke(wrapper)
                    }.getOrNull()
                    if (controller != null && reflect.controllerClass.isInstance(controller)) {
                        val state = getState(controller)
                        if (state != null) {
                            cancelExpandAnimator(state, reflect, controller)
                            cancelClosingSession(state, reflect)
                            if (reflect.handleMenuField.get(controller) != null) {
                                state.forceImmediateClose = true
                                try {
                                    reflect.closeHandleMenuMethod.invoke(controller)
                                } finally {
                                    state.forceImmediateClose = false
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    logger.warn("setIsDragging immediate close failed: ${t.message}")
                }
            }
            chain.proceed()
        }

        logger.info("Freeform handle menu anim test hook installed")
    }

    private fun prepareRootLayoutForExpand(rootView: ViewGroup, reflect: HostReflect) {
        synchronized(stateLock) {
            if (!attachedLayouts.add(rootView)) return
        }
        // Hide before the very first frame to prevent a 1-frame full-alpha flash.
        rootView.alpha = 0f
        rootView.clipChildren = false
        rootView.clipToPadding = false

        rootView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var pendingPreDraw: ViewTreeObserver.OnPreDrawListener? = null

            override fun onViewAttachedToWindow(v: View) {
                val contentView = findContentContainer(rootView)
                (contentView as? ViewGroup)?.apply {
                    clipChildren = false
                    clipToPadding = false
                }
                contentView.alpha = 0f

                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (pendingPreDraw === this) {
                            pendingPreDraw = null
                        }
                        val vto = rootView.viewTreeObserver
                        if (vto.isAlive) {
                            vto.removeOnPreDrawListener(this)
                        }
                        startExpandOnPreDraw(rootView, contentView, reflect)
                        return true
                    }
                }
                pendingPreDraw = listener
                rootView.viewTreeObserver.addOnPreDrawListener(listener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                pendingPreDraw?.let { listener ->
                    pendingPreDraw = null
                    val vto = rootView.viewTreeObserver
                    if (vto.isAlive) {
                        vto.removeOnPreDrawListener(listener)
                    }
                }
                val owner = findControllerByRootView(rootView, reflect) ?: return
                val state = getState(owner) ?: return
                if (state.closingSession?.rootView === rootView) return
                if (state.activeRootView?.get() === rootView) {
                    cancelExpandAnimator(state, reflect, owner)
                }
            }
        })
    }

    private fun startExpandOnPreDraw(
        rootView: ViewGroup,
        contentView: View,
        reflect: HostReflect
    ) {
        val controller = findControllerByRootView(rootView, reflect)
            ?: lastCreatingController?.get()
        if (controller == null) {
            contentView.alpha = 1f
            rootView.alpha = 1f
            return
        }
        val state = getOrCreateState(controller)
        cancelExpandAnimator(state, reflect, controller)

        val ovHandleMenu = runCatching { reflect.handleMenuField.get(controller) }.getOrNull()
        val captionHandle = getCaptionHandle(controller, reflect)
        val geometry = computeGeometry(contentView, ovHandleMenu, reflect)

        state.activeRootView = WeakReference(rootView)
        state.activeContentView = WeakReference(contentView)
        state.activeGeometry = geometry
        state.currentOpenness = 0f

        applyMenuFrame(contentView, captionHandle, geometry, openness = 0f, timeOpenness = 0f)
        if (contentView !== rootView) {
            rootView.alpha = 1f
        }

        val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXPAND_DURATION_MS
            this.interpolator = interpolator
            addUpdateListener { anim ->
                val openness = anim.animatedValue as Float
                state.currentOpenness = openness
                applyMenuFrame(
                    contentView = contentView,
                    captionHandle = captionHandle,
                    geometry = geometry,
                    openness = openness,
                    timeOpenness = anim.animatedFraction
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (state.expandAnimator === animation) {
                        state.expandAnimator = null
                    }
                    if (!cancelled) {
                        state.currentOpenness = 1f
                        applyMenuFrame(
                            contentView = contentView,
                            captionHandle = captionHandle,
                            geometry = geometry,
                            openness = 1f,
                            timeOpenness = 1f
                        )
                    }
                }
            })
        }
        state.expandAnimator = animator
        animator.start()
    }

    private fun startCollapse(
        controller: Any,
        state: ControllerState,
        reflect: HostReflect,
        ovHandleMenu: Any,
        container: Any,
        rootView: ViewGroup,
        invokeCloseMenu: () -> Unit,
        pendingAction: Runnable?
    ) {
        val contentView = state.activeContentView?.get() ?: findContentContainer(rootView)
        val wasTrackingExpand = state.expandAnimator != null
        val trackedOpenness = state.currentOpenness

        state.expandAnimator?.let { anim ->
            state.expandAnimator = null
            anim.cancel()
        }
        val startOpenness = when {
            wasTrackingExpand -> trackedOpenness.coerceIn(0f, 1f)
            trackedOpenness > 0f -> trackedOpenness.coerceIn(0f, 1f)
            rootView.isLaidOut -> 1f
            else -> 0f
        }

        clearHostDelayCallbacks(controller, reflect)

        // Detach container from OvHandleMenu before invoking host closeHandleMenu so the
        // controller resets its state without destroying the Surface mid-animation.
        reflect.containerField.set(ovHandleMenu, null)
        state.skipCloseHook = true
        try {
            invokeCloseMenu()
        } finally {
            state.skipCloseHook = false
        }

        // Absorb raw touches during collapse without mutating view.isEnabled, which
        // DesktopModeTouchEventListener.onClick relies on when pendingAction replays.
        rootView.setOnTouchListener { _, _ -> true }

        val captionHandle = getCaptionHandle(controller, reflect)
        val geometry = state.activeGeometry ?: computeGeometry(contentView, ovHandleMenu, reflect)
        state.activeRootView = null
        state.activeContentView = null
        state.activeGeometry = null
        state.currentOpenness = 0f

        if (!rootView.isLaidOut ||
            contentView.width <= 0 ||
            contentView.height <= 0 ||
            startOpenness <= 0.01f
        ) {
            restoreCaptionHandle(captionHandle)
            runCatching { reflect.containerReleaseViewMethod.invoke(container) }
            pendingAction?.run()
            return
        }

        val session = ClosingSession(
            container = container,
            rootView = rootView,
            contentView = contentView,
            captionHandle = captionHandle,
            pendingAction = pendingAction
        )
        state.closingSession = session

        val durationMs = (COLLAPSE_DURATION_MS * startOpenness).toLong().coerceAtLeast(16L)
        val animator = ValueAnimator.ofFloat(startOpenness, 0f).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { anim ->
                val openness = anim.animatedValue as Float
                val timeOpenness = startOpenness * (1f - anim.animatedFraction)
                applyMenuFrame(
                    contentView = contentView,
                    captionHandle = captionHandle,
                    geometry = geometry,
                    openness = openness,
                    timeOpenness = timeOpenness
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (state.closingSession === session) {
                        state.closingSession = null
                    }
                    restoreCaptionHandle(session.captionHandle)
                    if (!session.released) {
                        session.released = true
                        runCatching { reflect.containerReleaseViewMethod.invoke(session.container) }
                    }
                    if (!cancelled) {
                        session.pendingAction?.run()
                    }
                }
            })
        }
        session.animator = animator
        animator.start()
    }

    private fun applyMenuFrame(
        contentView: View,
        captionHandle: View?,
        geometry: AnimGeometry,
        openness: Float,
        timeOpenness: Float
    ) {
        val clampedOpenness = openness.coerceIn(0f, 1f)
        val clampedTime = timeOpenness.coerceIn(0f, 1f)
        val scale = START_SCALE + (1f - START_SCALE) * clampedOpenness

        contentView.pivotX = geometry.pivotX
        contentView.pivotY = geometry.pivotY
        contentView.scaleX = scale
        contentView.scaleY = scale
        contentView.translationX = geometry.startTx * (1f - clampedOpenness)
        contentView.translationY = geometry.startTy * (1f - clampedOpenness)
        contentView.alpha = (clampedTime / ALPHA_PORTION).coerceIn(0f, 1f)

        if (captionHandle != null) {
            if (captionHandle.width > 0 && captionHandle.height > 0) {
                captionHandle.pivotX = captionHandle.width / 2f
                captionHandle.pivotY = captionHandle.height / 2f
            }
            val handleScale = 1f - (1f - HANDLE_COLLAPSED_SCALE) * clampedOpenness
            captionHandle.scaleX = handleScale
            captionHandle.scaleY = handleScale
            captionHandle.alpha = (1f - clampedTime / HANDLE_FADE_PORTION).coerceIn(0f, 1f)
        }
    }

    private fun computeGeometry(
        contentView: View,
        ovHandleMenu: Any?,
        reflect: HostReflect
    ): AnimGeometry {
        val w = contentView.width.toFloat().coerceAtLeast(1f)
        val h = contentView.height.toFloat().coerceAtLeast(1f)
        val reservedRaw = runCatching { reflect.reservedSpaceField?.getInt(null) }.getOrNull() ?: 0
        val reserved = if (reservedRaw > 0) {
            reservedRaw.toFloat()
        } else {
            dpToPx(contentView.context, 16f)
        }
        val defaultShift = (reserved * 0.85f).coerceAtLeast(dpToPx(contentView.context, 12f))
        val defaultPivotY = reserved.coerceAtMost(h / 2f)

        if (ovHandleMenu == null) {
            return AnimGeometry(
                pivotX = w / 2f,
                pivotY = defaultPivotY,
                startTx = 0f,
                startTy = -defaultShift
            )
        }

        val taskInfo = runCatching {
            reflect.menuTaskInfoField?.get(ovHandleMenu) as? ActivityManager.RunningTaskInfo
        }.getOrNull()
        val taskRect = taskInfo?.let { resolveTaskBounds(it, reflect) }
        val menuPos = runCatching {
            reflect.globalMenuPositionField?.get(ovHandleMenu) as? Point
        }.getOrNull()

        if (taskRect == null || taskRect.isEmpty || menuPos == null) {
            return AnimGeometry(
                pivotX = w / 2f,
                pivotY = defaultPivotY,
                startTx = 0f,
                startTy = -defaultShift
            )
        }

        val captionHeight = runCatching {
            reflect.captionHeightField?.getInt(ovHandleMenu) ?: 0
        }.getOrDefault(0).coerceAtLeast(0)
        val handleCenterX = taskRect.left + taskRect.width() / 2f
        val handleCenterY = taskRect.top + captionHeight / 2f
        val anchorX = handleCenterX - menuPos.x
        val anchorY = handleCenterY - menuPos.y

        val minX = reserved.coerceAtMost(w / 2f)
        val maxX = (w - minX).coerceAtLeast(minX)
        val pivotX = anchorX.coerceIn(minX, maxX)
        val maxTx = reserved * 0.6f
        val startTx = (anchorX - pivotX).coerceIn(-maxTx, maxTx)

        val flippedAbove = anchorY > h - reserved
        val pivotY: Float
        val startTy: Float
        if (flippedAbove) {
            pivotY = (h - reserved).coerceIn(h / 2f, h)
            startTy = (anchorY - pivotY).coerceIn(0f, defaultShift)
        } else {
            pivotY = reserved.coerceIn(0f, h / 2f)
            startTy = -(pivotY - anchorY).coerceIn(0f, defaultShift)
        }

        return AnimGeometry(
            pivotX = pivotX,
            pivotY = pivotY,
            startTx = startTx,
            startTy = startTy
        )
    }

    private fun resolveTaskBounds(
        taskInfo: ActivityManager.RunningTaskInfo,
        reflect: HostReflect
    ): Rect? {
        val scaleX = runCatching { reflect.taskScaleXField?.getFloat(taskInfo) ?: 0f }
            .getOrDefault(0f)
        if (scaleX > 0f) {
            val scaled = runCatching {
                reflect.taskScaleSurfaceBoundsField?.get(taskInfo) as? Rect
            }.getOrNull()
            if (scaled != null && !scaled.isEmpty) return scaled
        }
        return runCatching {
            val cfg = reflect.taskConfigGetter?.invoke(taskInfo)
                ?: reflect.taskConfigField?.get(taskInfo)
            val wc = (cfg as? Configuration)?.let {
                reflect.windowConfigField?.get(it) ?: reflect.windowConfigGetter?.invoke(it)
            }
            wc?.let { reflect.windowConfigBoundsMethod?.invoke(it) as? Rect }
        }.getOrNull()
    }

    private fun cancelExpandAnimator(
        state: ControllerState,
        reflect: HostReflect,
        controller: Any
    ) {
        val anim = state.expandAnimator
        if (anim != null) {
            state.expandAnimator = null
            anim.cancel()
        }
        state.activeRootView = null
        state.activeContentView = null
        state.activeGeometry = null
        state.currentOpenness = 0f
        restoreCaptionHandle(getCaptionHandle(controller, reflect))
    }

    private fun cancelClosingSession(state: ControllerState, reflect: HostReflect) {
        val session = state.closingSession ?: return
        state.closingSession = null
        session.animator?.cancel()
        restoreCaptionHandle(session.captionHandle)
        if (!session.released) {
            session.released = true
            runCatching { reflect.containerReleaseViewMethod.invoke(session.container) }
        }
    }

    private fun clearHostDelayCallbacks(controller: Any, reflect: HostReflect) {
        runCatching {
            val injector = reflect.ovWinDecorInjectorField?.get(controller) ?: return
            val coordinator = reflect.coordinatorField?.get(injector) ?: return
            reflect.clearAllCallbacksMethod?.invoke(coordinator)
        }
    }

    private fun getCaptionHandle(controller: Any, reflect: HostReflect): View? =
        runCatching {
            val holder = reflect.viewHolderField?.get(controller) ?: return null
            reflect.captionHandleField?.get(holder) as? ImageView
        }.getOrNull()

    private fun restoreCaptionHandle(captionHandle: View?) {
        captionHandle ?: return
        captionHandle.alpha = 1f
        captionHandle.scaleX = 1f
        captionHandle.scaleY = 1f
    }

    private fun findContentContainer(rootView: ViewGroup): View {
        var id = cachedContentContainerId
        if (id == -1) {
            id = rootView.context.resources.getIdentifier(
                CONTENT_CONTAINER_ID_NAME,
                "id",
                SYSTEMUI_PACKAGE
            )
            cachedContentContainerId = id
        }
        if (id != 0) {
            rootView.findViewById<View>(id)?.let { return it }
        }
        return rootView
    }

    private fun isMenuActionView(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        var ids = cachedMenuActionIds
        if (ids == null) {
            val res = view.context.resources
            val resolved = HashSet<Int>(MENU_ACTION_ID_NAMES.size)
            for (name in MENU_ACTION_ID_NAMES) {
                val resId = res.getIdentifier(name, "id", SYSTEMUI_PACKAGE)
                if (resId != 0) resolved.add(resId)
            }
            ids = resolved
            cachedMenuActionIds = ids
        }
        return id in ids
    }

    private fun resolveControllerFromListener(listener: Any, reflect: HostReflect): Any? {
        val taskId = runCatching { reflect.listenerTaskIdField.getInt(listener) }.getOrNull()
            ?: return null
        val finder = runCatching { reflect.listenerFinderField.get(listener) }.getOrNull()
            ?: return null
        val applyMethod = reflect.finderApplyMethod
            ?: runCatching {
                finder.javaClass.getMethod("apply", Any::class.java).apply { isAccessible = true }
            }.getOrNull()
            ?: return null
        val wrapper = runCatching { applyMethod.invoke(finder, taskId) }.getOrNull()
            ?: return null
        val controller = runCatching {
            reflect.wrapperGetHandleMenuControllerMethod.invoke(wrapper)
        }.getOrNull() ?: return null
        return if (reflect.controllerClass.isInstance(controller)) controller else null
    }

    private fun findControllerByRootView(rootView: ViewGroup, reflect: HostReflect): Any? {
        val snapshot = synchronized(stateLock) { controllers.keys.toList() }
        for (controller in snapshot) {
            val matched = runCatching {
                val menu = reflect.handleMenuField.get(controller) ?: return@runCatching false
                val container = reflect.containerField.get(menu) ?: return@runCatching false
                reflect.containerGetViewMethod.invoke(container) === rootView
            }.getOrDefault(false)
            if (matched) return controller
        }
        return null
    }

    private fun getState(controller: Any): ControllerState? =
        synchronized(stateLock) { controllers[controller] }

    private fun getOrCreateState(controller: Any): ControllerState =
        synchronized(stateLock) {
            controllers[controller] ?: ControllerState().also { controllers[controller] = it }
        }

    private fun dpToPx(context: Context, dp: Float): Float =
        dp * context.resources.displayMetrics.density

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        try {
            loader.loadClass(name)
        } catch (t: Throwable) {
            logger.error("$name not found", t)
            null
        }

    private fun field(owner: Class<*>, name: String): Field? =
        try {
            findField(owner, name).apply { isAccessible = true }
        } catch (t: Throwable) {
            logger.error("Field $name not found on ${owner.name}", t)
            null
        }

    private fun optionalField(owner: Class<*>, name: String): Field? =
        runCatching { findField(owner, name).apply { isAccessible = true } }.getOrNull()

    private fun method(owner: Class<*>, name: String, vararg types: Class<*>): Method? =
        try {
            findMethod(owner, name, *types).apply { isAccessible = true }
        } catch (t: Throwable) {
            logger.error("Method $name not found on ${owner.name}", t)
            null
        }

    private fun optionalMethod(owner: Class<*>, name: String, vararg types: Class<*>): Method? =
        runCatching { findMethod(owner, name, *types).apply { isAccessible = true } }.getOrNull()

    private fun findMethodOrNull(owner: Class<*>, name: String, vararg types: Class<*>): Method? =
        method(owner, name, *types)

    private fun optionalFieldByWalk(owner: Class<*>, name: String): Field? {
        var type: Class<*>? = owner
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun optionalMethodByWalk(
        owner: Class<*>,
        name: String,
        vararg types: Class<*>
    ): Method? {
        var type: Class<*>? = owner
        while (type != null) {
            try {
                return type.getDeclaredMethod(name, *types).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                type = type.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private companion object {
        const val HANDLE_MENU_LAYOUT_CLASS =
            "com.android.wm.shell.windowdecor.OvHandleMenuLinearLayout"
        const val HANDLE_CONTROLLER_CLASS =
            "com.android.wm.shell.windowdecor.caption.OvTabletCaptionHandleController"
        const val HANDLE_VIEW_HOLDER_CLASS =
            "com.android.wm.shell.windowdecor.viewholder.OvTabletCaptionHandleViewHolder"
        const val OV_HANDLE_MENU_CLASS =
            "com.android.wm.shell.windowdecor.OvHandleMenu"
        const val ADDITIONAL_VIEW_CONTAINER_CLASS =
            "com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewContainer"
        const val TOUCH_EVENT_LISTENER_CLASS =
            "com.android.wm.shell.windowdecor.DesktopModeTouchEventListener"
        const val WINDOW_DECOR_WRAPPER_CLASS =
            "com.android.wm.shell.windowdecor.WindowDecorationWrapper"
        const val INJECTOR_CLASS =
            "com.android.wm.shell.windowdecor.OvWindowDecorationInjector"
        const val COORDINATOR_CLASS =
            $$"com.android.wm.shell.windowdecor.OvWindowDecorationInjector$CaptionHandleMenuCoordinator"
        const val WCT_CLASS = "android.window.WindowContainerTransaction"
        const val WINDOW_CONFIGURATION_CLASS = "android.app.WindowConfiguration"
        const val SYSTEMUI_PACKAGE = "com.android.systemui"
        const val CONTENT_CONTAINER_ID_NAME = "ovc_handle_menu_groups_layout_container"

        val MENU_ACTION_ID_NAMES = listOf(
            "ovc_wd_handle_menu_item_fullscreen",
            "ovc_wd_handle_menu_item_split_screen",
            "ovc_wd_handle_menu_item_freeform",
            "ovc_wd_handle_menu_item_minimize",
            "ovc_wd_handle_menu_item_close",
            "ovc_wd_handle_menu_item_replace_app",
            "ovc_handle_menu_linear_smart_connect"
        )

        const val EXPAND_DURATION_MS = 220L
        const val COLLAPSE_DURATION_MS = 100L
        const val START_SCALE = 0.35f
        const val ALPHA_PORTION = 0.30f
        const val HANDLE_COLLAPSED_SCALE = 0.72f
        const val HANDLE_FADE_PORTION = 0.45f
    }
}
