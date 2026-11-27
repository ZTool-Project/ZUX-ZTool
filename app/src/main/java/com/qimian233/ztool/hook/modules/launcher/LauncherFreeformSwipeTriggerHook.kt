package com.qimian233.ztool.hook.modules.launcher

import android.annotation.SuppressLint
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.google.gson.JsonObject
import com.qimian233.ztool.data.freeform.LauncherFreeformTriggerConfig
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Customizes the QuickStep swipe-up floating-window trigger zone bounds and LongSwipeView banner. */
@SuppressLint("PrivateApi")
class LauncherFreeformSwipeTriggerHook : AppHookModule() {

    private data class ZoneRect(
        val leftPx: Int,
        val rightPx: Int,
        val heightPx: Int,
        val screenW: Int,
        val screenH: Int
    )

    private val touchStateLock = Any()
    private val handlerTouchX = WeakHashMap<Any, Float>()
    private val handlerTouchY = WeakHashMap<Any, Float>()
    private val handlerDownY = WeakHashMap<Any, Float>()
    private val viewZoneMap = WeakHashMap<View, ZoneRect>()

    private val currentTouchX = ThreadLocal<Float?>()
    private val currentTouchY = ThreadLocal<Float?>()
    private val activeHandler = ThreadLocal<Any?>()
    private val overrideTriggerResult = ThreadLocal<Boolean?>()

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_TRIGGER.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_FREEFORM_SWIPE_TRIGGER)

        val handlerClass = resolveHandlerClass(classLoader, module) ?: run {
            logger.warn("SwipeTrigger: swipe handler class not found; skipping hook")
            return
        }
        val longSwipeViewClass = classLoader.loadClass(LONG_SWIPE_VIEW_CLASS)

        val uiLongSwipeField = resolveUiLongSwipeField(handlerClass, module)
        val longSwipeViewField = resolveLongSwipeViewField(handlerClass, longSwipeViewClass, module)
        val updateViewMethod = resolveUpdateViewMethod(longSwipeViewClass, module)
        val setPercentMethod = resolveSetPercentMethod(longSwipeViewClass, module)

        val consumerClass = classLoader.loadClass(OTHER_ACTIVITY_CONSUMER_CLASS)
        val onMotionEventMethod =
            findMethod(consumerClass, "onMotionEvent", MotionEvent::class.java)
        val activePointerIdField = findField(consumerClass, "mActivePointerId")
        val interactionHandlerField = findField(consumerClass, "mInteractionHandler")

        val windowManagerImplClass = classLoader.loadClass(WINDOW_MANAGER_IMPL_CLASS)
        val addViewMethod = findMethod(
            windowManagerImplClass,
            "addView",
            View::class.java,
            ViewGroup.LayoutParams::class.java
        )

        val onCurrentShiftUpdatedMethod = findMethod(handlerClass, "onCurrentShiftUpdated")
        val invalidateHandlerMethod = findMethod(handlerClass, "invalidateHandler")

        installMotionHook(
            onMotionEventMethod,
            activePointerIdField,
            interactionHandlerField
        )
        installAddViewHook(addViewMethod)
        installShiftUpdatedHook(
            onCurrentShiftUpdatedMethod,
            uiLongSwipeField,
            longSwipeViewField
        )
        installViewProgressHook(updateViewMethod, setPercentMethod)
        installInvalidateHook(invalidateHandlerMethod)

        logger.info("SwipeTrigger: hooks installed on ${handlerClass.name}")
    }

    private fun installMotionHook(
        onMotionEventMethod: Method,
        activePointerIdField: Field,
        interactionHandlerField: Field
    ) {
        hookWithId(onMotionEventMethod, "launcher_freeform_swipe_trigger_motion") { chain ->
            if (!isEnabled()) return@hookWithId chain.proceed()
            val consumer = chain.thisObject ?: return@hookWithId chain.proceed()
            val event = chain.args.getOrNull(0) as? MotionEvent ?: return@hookWithId chain.proceed()
            val actionMasked = event.actionMasked
            val trackEvent = actionMasked == MotionEvent.ACTION_DOWN ||
                actionMasked == MotionEvent.ACTION_MOVE ||
                actionMasked == MotionEvent.ACTION_UP

            if (!trackEvent || event.pointerCount <= 0) {
                return@hookWithId chain.proceed()
            }

            val activePointerId = activePointerIdField.getInt(consumer)
            val pointerIndex = event.findPointerIndex(activePointerId).let { idx ->
                if (idx in 0 until event.pointerCount) idx else 0
            }
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)

            currentTouchX.set(x)
            currentTouchY.set(y)
            val preHandler = interactionHandlerField.get(consumer)
            if (preHandler != null) {
                synchronized(touchStateLock) {
                    if (actionMasked == MotionEvent.ACTION_DOWN || !handlerDownY.containsKey(preHandler)) {
                        handlerDownY[preHandler] = y
                    }
                    handlerTouchX[preHandler] = x
                    handlerTouchY[preHandler] = y
                }
            }

            try {
                chain.proceed()
            } finally {
                val postHandler = interactionHandlerField.get(consumer)
                if (postHandler != null) {
                    synchronized(touchStateLock) {
                        if (actionMasked == MotionEvent.ACTION_DOWN || !handlerDownY.containsKey(postHandler)) {
                            handlerDownY[postHandler] = y
                        }
                        handlerTouchX[postHandler] = x
                        handlerTouchY[postHandler] = y
                    }
                }
                currentTouchX.remove()
                currentTouchY.remove()
            }
        }
    }

    private fun installAddViewHook(addViewMethod: Method) {
        hookWithId(addViewMethod, "launcher_freeform_swipe_trigger_add_view") { chain ->
            if (!isEnabled()) return@hookWithId chain.proceed()
            val view = chain.args.getOrNull(0) as? View ?: return@hookWithId chain.proceed()
            if (view.javaClass.name != LONG_SWIPE_VIEW_CLASS) {
                return@hookWithId chain.proceed()
            }
            val lp = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                ?: return@hookWithId chain.proceed()

            val (screenW, screenH) = resolveScreenSize(chain.thisObject as? WindowManager, view, lp.width)
            if (screenW <= 0 || screenH <= 0) {
                return@hookWithId chain.proceed()
            }

            val prefs = remotePreferences
            val topPercent = LauncherFreeformTriggerConfig.normalizeTopPercent(
                prefs.getInt(
                    PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_TOP_PERCENT.name,
                    PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_TOP_PERCENT.default
                )
            )
            val (leftPercent, rightPercent) = LauncherFreeformTriggerConfig.normalizeMargins(
                prefs.getInt(
                    PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_LEFT_PERCENT.name,
                    PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_LEFT_PERCENT.default
                ),
                prefs.getInt(
                    PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_RIGHT_PERCENT.name,
                    PreferenceKeys.LAUNCHER_FREEFORM_SWIPE_RIGHT_PERCENT.default
                )
            )

            val leftPx = (screenW * (leftPercent / 100f)).roundToInt()
            val rightEdgePx = (screenW * ((100 - rightPercent) / 100f)).roundToInt()
            val widthPx = (rightEdgePx - leftPx).coerceAtLeast(1)
            val heightPx = (screenH * (topPercent / 100f)).roundToInt().coerceAtLeast(1)

            // Use Gravity.LEFT (not START) so lp.x stays aligned with MotionEvent.getX() under RTL.
            @Suppress("RtlHardcoded")
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = leftPx
            lp.y = 0
            lp.width = widthPx
            lp.height = heightPx

            synchronized(touchStateLock) {
                viewZoneMap[view] = ZoneRect(
                    leftPx = leftPx,
                    rightPx = leftPx + widthPx,
                    heightPx = heightPx,
                    screenW = screenW,
                    screenH = screenH
                )
            }
            logger.debug(
                "SwipeTrigger addView: zone=[$leftPx..${leftPx + widthPx}, 0..$heightPx] " +
                    "on ${screenW}x$screenH (top=$topPercent%, left=$leftPercent%, right=$rightPercent%)"
            )
            chain.proceed()
        }
    }

    private fun installShiftUpdatedHook(
        onCurrentShiftUpdatedMethod: Method,
        uiLongSwipeField: Field,
        longSwipeViewField: Field
    ) {
        hookWithId(onCurrentShiftUpdatedMethod, "launcher_freeform_swipe_trigger_shift_update") { chain ->
            if (!isEnabled()) return@hookWithId chain.proceed()
            val handler = chain.thisObject ?: return@hookWithId chain.proceed()
            val prevUiLongSwipe = uiLongSwipeField.getBoolean(handler)
            activeHandler.set(handler)
            overrideTriggerResult.remove()
            try {
                val result = chain.proceed()
                val triggered = overrideTriggerResult.get()
                if (triggered != null) {
                    uiLongSwipeField.setBoolean(handler, triggered)
                    if (triggered && !prevUiLongSwipe) {
                        (longSwipeViewField.get(handler) as? View)
                            ?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
                    }
                } else {
                    val longSwipeView = longSwipeViewField.get(handler) as? View
                    if (longSwipeView == null || !longSwipeView.isAttachedToWindow) {
                        uiLongSwipeField.setBoolean(handler, false)
                    }
                }
                result
            } finally {
                activeHandler.remove()
                overrideTriggerResult.remove()
            }
        }
    }

    private fun installViewProgressHook(updateViewMethod: Method, setPercentMethod: Method) {
        hookWithId(updateViewMethod, "launcher_freeform_swipe_trigger_view_progress") { chain ->
            if (!isEnabled()) return@hookWithId chain.proceed()
            val view = chain.thisObject as? View ?: return@hookWithId chain.proceed()
            val handler = activeHandler.get()
            val zone = synchronized(touchStateLock) { viewZoneMap[view] }

            val touchX: Float?
            val touchY: Float?
            val downY: Float?
            synchronized(touchStateLock) {
                touchX = currentTouchX.get() ?: handler?.let { handlerTouchX[it] }
                touchY = currentTouchY.get() ?: handler?.let { handlerTouchY[it] }
                downY = handler?.let { handlerDownY[it] }
            }

            if (handler == null || zone == null || touchX == null || touchY == null) {
                return@hookWithId chain.proceed()
            }

            val inHorizontal = touchX in zone.leftPx.toFloat()..zone.rightPx.toFloat()
            val inVertical = touchY <= zone.heightPx.toFloat()
            overrideTriggerResult.set(inHorizontal && inVertical)

            if (view.measuredWidth > 0 && view.visibility == View.VISIBLE) {
                val yStart = ((downY ?: zone.screenH.toFloat()) - zone.screenH * 0.34f)
                    .coerceAtLeast(zone.heightPx + 1f)
                val verticalProgress = if (inVertical) {
                    1.0f
                } else {
                    ((yStart - touchY) / (yStart - zone.heightPx)).coerceIn(0f, 0.99f)
                }
                // Keep the icon hint visible when outside horizontal bounds without lighting up the text or haptic.
                val displayPercent = if (inHorizontal) {
                    verticalProgress
                } else {
                    verticalProgress.coerceAtMost(0.75f)
                }
                setPercentMethod.invoke(view, displayPercent)
            }
            null
        }
    }

    private fun installInvalidateHook(invalidateHandlerMethod: Method) {
        hookWithId(invalidateHandlerMethod, "launcher_freeform_swipe_trigger_invalidate") { chain ->
            val result = chain.proceed()
            val handler = chain.thisObject
            if (handler != null) {
                synchronized(touchStateLock) {
                    handlerTouchX.remove(handler)
                    handlerTouchY.remove(handler)
                    handlerDownY.remove(handler)
                }
            }
            result
        }
    }

    private fun resolveScreenSize(
        windowManager: WindowManager?,
        view: View,
        lpWidth: Int
    ): Pair<Int, Int> {
        val rawW: Int
        val rawH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            val bounds = windowManager.currentWindowMetrics.bounds
            rawW = bounds.width()
            rawH = bounds.height()
        } else {
            val metrics = view.resources.displayMetrics
            rawW = metrics.widthPixels
            rawH = metrics.heightPixels
        }
        if (lpWidth <= 0) return rawW to rawH
        val screenH = if (abs(lpWidth - rawW) <= abs(lpWidth - rawH)) rawH else rawW
        return lpWidth to screenH
    }

    private fun resolveHandlerClass(classLoader: ClassLoader, module: JsonObject?): Class<*>? {
        readString(module, DexIndexConstants.Keys.SWIPE_TRIGGER_HANDLER_CLASS)?.let { name ->
            runCatching { return classLoader.loadClass(name) }
        }
        var current: Class<*>? = classLoader.loadClass(LAUNCHER_SWIPE_HANDLER_V2).superclass
        while (current != null && current.name != ABS_SWIPE_UP_HANDLER && current != Any::class.java) {
            val hasMarker = current.declaredMethods.any {
                it.name == "isUiLongSwipeMode" && it.parameterCount == 0
            }
            if (hasMarker) return current
            current = current.superclass
        }
        return null
    }

    private fun resolveUiLongSwipeField(handlerClass: Class<*>, module: JsonObject?): Field {
        val indexedName = readString(module, DexIndexConstants.Keys.SWIPE_TRIGGER_UI_LONG_SWIPE_FIELD)
        if (indexedName != null) {
            runCatching { return findField(handlerClass, indexedName) }
        }
        return findField(handlerClass, "mUiLongSwipeMode")
    }

    private fun resolveLongSwipeViewField(
        handlerClass: Class<*>,
        longSwipeViewClass: Class<*>,
        module: JsonObject?
    ): Field {
        val indexedName = readString(module, DexIndexConstants.Keys.SWIPE_TRIGGER_LONG_SWIPE_VIEW_FIELD)
        if (indexedName != null) {
            runCatching { return findField(handlerClass, indexedName) }
        }
        return runCatching { findField(handlerClass, "mLongSwipeView") }.getOrElse {
            handlerClass.declaredFields
                .singleOrNull { !Modifier.isStatic(it.modifiers) && it.type == longSwipeViewClass }
                ?.apply { isAccessible = true }
                ?: throw NoSuchFieldException("LongSwipeView field not found on ${handlerClass.name}")
        }
    }

    private fun resolveUpdateViewMethod(longSwipeViewClass: Class<*>, module: JsonObject?): Method {
        val indexedName =
            readString(module, DexIndexConstants.Keys.SWIPE_TRIGGER_LONG_SWIPE_VIEW_UPDATE_METHOD)
        if (indexedName != null) {
            runCatching {
                return findMethod(
                    longSwipeViewClass,
                    indexedName,
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            }
        }
        return longSwipeViewClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(
                    arrayOf(Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                )
        }?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("LongSwipeView update(float, int) not found")
    }

    private fun resolveSetPercentMethod(longSwipeViewClass: Class<*>, module: JsonObject?): Method {
        val indexedName =
            readString(module, DexIndexConstants.Keys.SWIPE_TRIGGER_LONG_SWIPE_VIEW_SET_PERCENT_METHOD)
        if (indexedName != null) {
            runCatching {
                return findMethod(longSwipeViewClass, indexedName, Float::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            }
        }
        return runCatching {
            findMethod(longSwipeViewClass, "setViewPercent", Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrElse {
            longSwipeViewClass.declaredMethods.singleOrNull {
                !Modifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("LongSwipeView setViewPercent(float) not found")
        }
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private companion object {
        const val ABS_SWIPE_UP_HANDLER = "com.android.quickstep.AbsSwipeUpHandler"
        const val LAUNCHER_SWIPE_HANDLER_V2 = "com.android.quickstep.LauncherSwipeHandlerV2"
        const val OTHER_ACTIVITY_CONSUMER_CLASS =
            "com.android.quickstep.inputconsumers.OtherActivityInputConsumer"
        const val LONG_SWIPE_VIEW_CLASS = "com.zui.launcher.gesture.swipeview.LongSwipeView"
        const val WINDOW_MANAGER_IMPL_CLASS = "android.view.WindowManagerImpl"
    }
}
