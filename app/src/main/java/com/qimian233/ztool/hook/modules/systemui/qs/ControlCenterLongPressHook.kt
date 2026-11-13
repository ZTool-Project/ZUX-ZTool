package com.qimian233.ztool.hook.modules.systemui.qs

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Unified long-press animation for control-center components, gated by the
 * single [PreferenceKeys.CONTROL_CENTER_LONG_PRESS] switch.
 *
 * Tiles (QSTileViewImpl, both large and small): the tile's own long-click
 * listener routes to its native target — DetailAdapter dialogs for Bluetooth
 * / Wi-Fi and longClickIntent settings pages for others — but the squish
 * animation never plays on this ROM. Hook onTouchEvent with a hand-rolled
 * press detector: DOWN plays the squish-in and schedules the trigger;
 * MOVE beyond slop or UP/CANCEL reverses it. The trigger calls
 * View.performLongClick(), so the tile's own routing (dialog vs settings)
 * is preserved unchanged.
 *
 * Sliders (ToggleSliderView brightness / volume): the same detector drives
 * the squish animation. On trigger the brightness slider opens
 * openBrightnessDetail() and the media volume slider opens the media/ringer
 * volume panel (see VolumeSliderLongPressHook). Which one fires is decided
 * from the slider's identity inside its host, not from the hooked class: on
 * Android 17 both bars are the same ToggleSeekBar class.
 *
 * Trigger timing follows the system configuration via
 * ViewConfiguration.scaledLongPressTimeout.
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class ControlCenterLongPressHook : AppHookModule() {

    private val pressStates = WeakHashMap<View, PressState>()

    // True while one of our gestures is active: blocks the native
    // QSLongPressEffect pipeline's delayed qsTile.longClick() for this gesture.
    @Volatile
    private var suppressNativeLongClick = false

    // True from the moment our long-click listener fires until the trailing
    // UP/CANCEL: the window where ZUI's click() fallback must stay blocked.
    // Per-gesture flag instead of scanning pressStates — stale released
    // states from other tile instances poisoned a global scan.
    @Volatile
    private var gestureTriggered = false

    override fun getModuleName(): String = PreferenceKeys.CONTROL_CENTER_LONG_PRESS.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        hookTileListenerReplacement(classLoader)
        hookTileTouchEvent(classLoader)
        hookToggleSliderTouchEvent(classLoader)
        hookNativeLongClickSuppression(classLoader)
    }

    /**
     * Tiles: replace the system's OnLongClickListener with ours after
     * QSTileViewImpl.init wires it. Ours plays the release squish and then:
     *  - large tiles (detail indicator present): indicator.performClick(),
     *    which opens the tile's DetailAdapter dialog;
     *  - small tiles: the captured original listener, preserving the native
     *    long-press routing (Settings page etc.).
     * Tiles without an original listener do nothing on long press.
     *
     * The framework long click (View.onTouchEvent -> checkForLongClick)
     * drives the timing, since QSTileViewImpl.onTouchEvent calls through to
     * super normally. The squish-in is driven from the touch hook below.
     */
    private fun hookTileListenerReplacement(classLoader: ClassLoader) {
        // CustomQSTileViewImpl (small tiles) overrides init, so the
        // superclass hook alone never fires for them. Hook both inits.
        val initClasses = listOf(QS_TILE_VIEW_CLASS, CUSTOM_QS_TILE_VIEW_CLASS)
        for (className in initClasses) {
            try {
                val initMethod: Method = classLoader.loadClass(className)
                    .getDeclaredMethod(
                        "init",
                        View.OnClickListener::class.java,
                        View.OnLongClickListener::class.java,
                        View.OnClickListener::class.java
                    )
                hookWithId(initMethod, "tile_listener_replace_$className") { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as View
                    val original = chain.args[1] as? View.OnLongClickListener
                    view.setOnLongClickListener { v ->
                        logger.debug(
                            "tile: our long click fired, indicator=" +
                                (findDetailIndicator(v) != null) +
                                ", hasOriginal=" + (original != null) +
                                ", suppress=" + suppressNativeLongClick +
                                ", gestureEnding=" + gestureEnding()
                        )
                        playReleaseAnimation(v)
                        // Our own routing below re-enters QSTileImpl.longClick;
                        // lift the suppression for the duration so we don't
                        // swallow the very call we just triggered.
                        gestureTriggered = false
                        val indicator = findDetailIndicator(v)
                        when {
                            indicator != null -> {
                                logger.debug("tile: routing to detailIndicator.performClick()")
                                indicator.performClick()
                            }
                            original != null -> {
                                logger.debug("tile: routing to original.onLongClick()")
                                try {
                                    original.onLongClick(v)
                                } catch (t: Throwable) {
                                    // The native listener dereferences
                                    // longPressEffect unconditionally; keep
                                    // this best-effort so a state-change race
                                    // can never crash the shade. The squish
                                    // already played and the tile's long-press
                                    // action (dialog vs settings) is optional.
                                    logger.error("tile: original long click failed", t)
                                }
                            }
                            else -> {
                                // No long-press behavior: animation only.
                                logger.debug("tile: no routing target, animation only")
                            }
                        }
                        gestureTriggered = true
                        true
                    }
                    // The long-press effect wiring is redone per gesture in
                    // the touch hook below; the field itself must stay
                    // non-null (see neutralizeNativeLongPressEffect).
                    result
                }
            } catch (t: Throwable) {
                logger.error("Failed to hook $className.init", t)
            }
        }
    }

    /**
     * Disables the native QSLongPressEffect pipeline for the current gesture
     * WITHOUT nulling the field: the native click listener (init$1.onClick,
     * installed by init when the effect is present) dereferences the field
     * unconditionally, so nulling crashed short taps on effect-bearing tiles
     * (WLAN / Bluetooth large tiles).
     *
     * Instead the effect's state machine is rewound to IDLE right after the
     * native DOWN handling: the effect animator is started from a runnable
     * the view posts on DOWN that bails out unless state == TIMEOUT_WAIT, so
     * rewinding here keeps the animator (and its trailing qsTile.longClick()
     * routing that fights our squish) from ever starting.
     */
    private fun neutralizeNativeLongPressEffect(view: View) {
        try {
            val effect = findField(view.javaClass, LONG_PRESS_EFFECT_FIELD).get(view) ?: return
            val stateField = findField(effect.javaClass, EFFECT_STATE_FIELD)
            val idle = idleState(effect.javaClass) ?: return
            if (stateField.get(effect) !== idle) {
                stateField.set(effect, idle)
            }
        } catch (t: Throwable) {
            logger.warn("longPressEffect state rewind failed: $t")
        }
    }

    private fun idleState(stateClass: Class<*>): Any? =
        stateClass.enumConstants?.firstOrNull { (it as Enum<*>).name == "IDLE" }

    /**
     * Visual-only touch tracking for tiles: squish-in on DOWN, release on
     * MOVE-beyond-slop / UP / CANCEL. No triggering logic — the framework
     * long click on the replaced listener handles that.
     */
    private fun hookTileTouchEvent(classLoader: ClassLoader) {
        try {
            val onTouchEvent: Method = classLoader
                .loadClass(QS_TILE_VIEW_CLASS)
                .getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
            hookWithId(
                onTouchEvent,
                "tile_touch_squish",
                XposedInterface.PRIORITY_HIGHEST
            ) { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.thisObject as View
                    val event = chain.args[0] as MotionEvent
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            suppressNativeLongClick = true
                            gestureTriggered = false
                            val state = obtainState(view)
                            state.downX = event.rawX
                            state.downY = event.rawY
                            state.released = false
                            logger.debug(
                                "tile: DOWN on " + view.javaClass.simpleName +
                                        "@" + Integer.toHexString(System.identityHashCode(view)) +
                                        ", suppress=true, gestureTriggered=false"
                            )
                            squishIn(view)
                            // Runs after the native onTouchEvent body, which
                            // has just flipped the effect to TIMEOUT_WAIT.
                            neutralizeNativeLongPressEffect(view)
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val state = pressStates[view]
                            if (state != null && !state.released) {
                                val dx = event.rawX - state.downX
                                val dy = event.rawY - state.downY
                                if (dx * dx + dy * dy > state.touchSlopSquared) {
                                    state.released = true
                                    playReleaseAnimation(view)
                                }
                            }
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            suppressNativeLongClick = false
                            val state = pressStates[view]
                            logger.debug(
                                "tile: " +
                                        (if (event.actionMasked == MotionEvent.ACTION_UP) "UP" else "CANCEL") +
                                        " on " + view.javaClass.simpleName +
                                        "@" + Integer.toHexString(System.identityHashCode(view)) +
                                        ", suppress=false, stateReleased=" + (state?.released
                                    ?: "null") +
                                        ", gestureTriggered=" + gestureTriggered
                            )
                            if (state != null && !state.released) {
                                // Long click (if any) fires around this
                                // time; release the squish so the action
                                // starts from a settled view.
                                state.released = true
                                playReleaseAnimation(view)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    logger.error("Tile squish tracking failed", t)
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook QSTileViewImpl.onTouchEvent", t)
        }
    }

    private fun squishIn(view: View) {
        view.animate()
            .scaleX(SQUISH_SCALE_X)
            .scaleY(SQUISH_SCALE_Y)
            .setDuration(SQUISH_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * The large-tile detail indicator (right-bottom ImageButton) — its click
     * opens the tile's DetailAdapter dialog. Normally requires VISIBLE;
     * when HideDetailIndicatorHook has tagged the button (dual-target tile
     * with the button forced GONE), the tag itself qualifies so long-press
     * routing still reaches performClick() instead of the native listener,
     * whose effect state machine this hook keeps neutralized.
     */
    private fun findDetailIndicator(view: View): View? {
        return try {
            val field = findField(view.javaClass, DETAIL_INDICATOR_FIELD)
            val indicator = field.get(view) as? View
            indicator?.takeIf {
                it.tag == DUAL_TARGET_TAG || (it.isVisible && it.width > 0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Blocks the native QSLongPressEffect pipeline's qsTile.longClick() and
     * ZUI's click() fallback while one of our gestures is in flight. The
     * Expandable parameter type moved packages on this ROM, so hook targets
     * are discovered from the declared method signatures instead of by name.
     */
    private fun hookNativeLongClickSuppression(classLoader: ClassLoader) {
        val implClass = classLoader.loadClass(QS_TILE_IMPL_CLASS)
        val singleParamByName = implClass.declaredMethods
            .filter { it.parameterTypes.size == 1 }
            .groupBy { it.name }
        for ((name, suppressAlways) in listOf("longClick" to true, "click" to false)) {
            val candidates = singleParamByName[name]
            if (candidates.isNullOrEmpty()) {
                logger.warn("QSTileImpl.$name not found for suppression hook")
                continue
            }
            for (method in candidates) {
                val methodName = method.name
                val paramTypeName = method.parameterTypes[0].name
                hookWithId(
                    method,
                    "tile_${name}_suppress_$paramTypeName",
                    XposedInterface.PRIORITY_HIGHEST
                ) { chain ->
                    if (suppressNativeLongClick &&
                        (!suppressAlways || gestureEnding())
                    ) {
                        logger.debug(
                            "tile: suppressed QSTileImpl.$methodName($paramTypeName)" +
                                    ", suppress=" + suppressNativeLongClick +
                                    ", gestureEnding=" + gestureEnding()
                        )
                        null
                    } else {
                        logger.debug(
                            "tile: PASSTHROUGH QSTileImpl.$methodName($paramTypeName)" +
                                    ", suppress=" + suppressNativeLongClick +
                                    ", gestureEnding=" + gestureEnding()
                        )
                        chain.proceed()
                    }
                }
            }
        }
    }

    /**
     * True from when our listener triggered until the trailing UP/CANCEL:
     * the window where ZUI's click() fallback must stay blocked. Managed
     * explicitly by the touch hook (set on trigger-clearing UP, cleared on
     * the next DOWN) — no cross-gesture state.
     */
    private fun gestureEnding(): Boolean = gestureTriggered

    /**
     * Sliders: `ToggleSeekBar` (brightness and, since Android 17, the media
     * volume bar) and bare `zui.widget.SeekBarNps`. Both classes are hooked,
     * and one DOWN reaches both for a ToggleSeekBar, so the trigger comes from
     * the slider's identity in its ToggleSliderView, never from the hooked
     * class; the first callback starts the gesture, the second is dropped.
     */
    private fun hookToggleSliderTouchEvent(classLoader: ClassLoader) {
        hookSliderTouchEvent(classLoader, TOGGLE_SEEK_BAR_CLASS, "slider_touch_long_press")
        hookSliderTouchEvent(classLoader, SEEK_BAR_NPS_CLASS, "slider_touch_long_press_nps")
    }

    private fun hookSliderTouchEvent(
        classLoader: ClassLoader,
        className: String,
        hookId: String
    ) {
        try {
            val onTouchEvent: Method = classLoader
                .loadClass(className)
                .getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
            hookWithId(
                onTouchEvent,
                hookId,
                XposedInterface.PRIORITY_HIGHEST
            ) { chain ->
                val result = chain.proceed()
                try {
                    trackPress(chain.thisObject as View, chain.args[0] as MotionEvent)
                } catch (t: Throwable) {
                    logger.error("Slider press tracking failed", t)
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook $className.onTouchEvent", t)
        }
    }

    private enum class SliderKind { BRIGHTNESS, MEDIA_VOLUME }

    /**
     * Trigger for the slider under the finger, or null when the view is not a
     * control-center slider this hook owns. The panel's own bars carry
     * [VOLUME_PANEL_SLIDER_TAG] and squish without a trigger.
     */
    private fun resolveSliderTrigger(view: View): (() -> Unit)? {
        val host = findToggleSliderView(view)
        if (host == null) {
            return if (view.tag == VOLUME_PANEL_SLIDER_TAG) NO_TRIGGER else null
        }
        return when (resolveSliderKind(host, view)) {
            SliderKind.BRIGHTNESS -> ({ openBrightnessDetail(host) })
            SliderKind.MEDIA_VOLUME -> ({ openVolumePanel(view) })
            null -> null
        }
    }

    /**
     * Which of the host's two sliders [view] is: field identity first, then the
     * containing root, so a renamed slider field still resolves through the
     * roots that hold it.
     */
    private fun resolveSliderKind(host: Any, view: View): SliderKind? {
        if (fieldHoldsView(host, BRIGHTNESS_SLIDER_FIELD, view)) return SliderKind.BRIGHTNESS
        if (fieldHoldsView(host, VOLUME_SLIDER_FIELD, view)) return SliderKind.MEDIA_VOLUME
        val brightnessRoot = readViewField(host, BRIGHTNESS_ROOT_FIELD)
        if (brightnessRoot != null && isDescendant(view, brightnessRoot)) {
            return SliderKind.BRIGHTNESS
        }
        val volumeRoot = readViewField(host, VOLUME_ROOT_FIELD)
        if (volumeRoot != null && isDescendant(view, volumeRoot)) {
            return SliderKind.MEDIA_VOLUME
        }
        return null
    }

    private fun fieldHoldsView(host: Any, fieldName: String, view: View): Boolean {
        return try {
            findField(host.javaClass, fieldName).get(host) === view
        } catch (_: Throwable) {
            false
        }
    }

    private fun readViewField(host: Any, fieldName: String): View? {
        return try {
            findField(host.javaClass, fieldName).get(host) as? View
        } catch (_: Throwable) {
            null
        }
    }

    private fun openVolumePanel(view: View) {
        logger.debug("slider: volume long press, opening detail panel")
        VolumeSliderLongPressHook.onVolumeSliderLongPress(view)
    }

    /**
     * Animation target for slider squish: the enclosing ToggleSliderView's
     * root FrameLayout (mBrightnessSliderRoot / mVolumeSliderRoot), so the
     * icon, progress bar, and the percentage-hook label scale together.
     * Sliders squish the root; tiles squish themselves.
     */
    private fun squishTarget(view: View): View {
        val target = findSliderRoot(view) ?: view
        logger.debug(
            "press: squishTarget for " + view.javaClass.simpleName +
                "@" + Integer.toHexString(System.identityHashCode(view)) +
                " -> " + target.javaClass.simpleName +
                "@" + Integer.toHexString(System.identityHashCode(target)) +
                ", isSliderRoot=" + (target !== view)
        )
        return target
    }

    /**
     * Walks up at most a few parents looking for the ToggleSliderView, then
     * reads its root fields via the base-class hierarchy-walking finder. One
     * host holds BOTH slider roots (brightness + volume), so only a root
     * that actually contains the touched view is accepted.
     */
    private fun findSliderRoot(view: View): View? {
        val host = findToggleSliderView(view)
        if (host == null) {
            logger.debug("press: findSliderRoot: no ToggleSliderView ancestor of " + view.javaClass.simpleName)
            return null
        }
        for (fieldName in SLIDER_ROOT_FIELDS) {
            try {
                val root = findField(host.javaClass, fieldName).get(host) as? View
                if (root != null && isDescendant(view, root)) {
                    logger.debug("press: findSliderRoot via " + fieldName + " -> " + root.javaClass.name)
                    return root
                }
            } catch (_: Throwable) {
            }
        }
        logger.debug(
            "press: findSliderRoot: host found (" + host.javaClass.simpleName +
                ") but no root containing the touched view"
        )
        return null
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var current = view.parent
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    /**
     * Gesture tracking shared by both slider hooks: squish + haptic on DOWN,
     * release on MOVE-beyond-slop / UP / CANCEL, trigger after the system
     * long-press timeout. One DOWN can arrive twice (both slider classes are
     * hooked); only the first call starts the gesture.
     */
    private fun trackPress(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPress(view, event)

            MotionEvent.ACTION_MOVE -> {
                val state = pressStates[view] ?: return
                if (state.released) return
                val dx = event.rawX - state.downX
                val dy = event.rawY - state.downY
                if (dx * dx + dy * dy > state.touchSlopSquared) {
                    logger.debug("press: MOVE beyond slop, cancelling long press")
                    suppressNativeLongClick = false
                    releaseSquish(view, state)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                suppressNativeLongClick = false
                val state = pressStates[view] ?: return
                releaseSquish(view, state)
            }
        }
    }

    private fun startPress(view: View, event: MotionEvent) {
        val state = obtainState(view)
        // Same gesture, second hook: never re-post the trigger (the later
        // postDelayed would cancel the earlier one and both would fire).
        if (state.downTime == event.downTime) return
        val trigger = resolveSliderTrigger(view) ?: return
        logger.debug(
            "press: ACTION_DOWN on " + view.javaClass.simpleName +
                "@" + Integer.toHexString(System.identityHashCode(view))
        )
        state.downTime = event.downTime
        state.downX = event.rawX
        state.downY = event.rawY
        state.released = false
        // Our gesture owns this touch from now on: any native qsTile.longClick()
        // firing during it is suppressed.
        suppressNativeLongClick = true
        // Resolve once per gesture, not per touch event: a MOVE storm would
        // otherwise re-run the root lookup each frame.
        state.animationTarget = squishTarget(view)
        squishIn(state.animationTarget!!)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        cancelTrigger(view)
        val runnable = Runnable {
            state.released = true
            playReleaseAnimation(state.animationTarget ?: view)
            trigger()
        }
        state.runnable = runnable
        view.postDelayed(runnable, longPressTimeout)
    }

    private fun playReleaseAnimation(view: View) {
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SQUISH_RELEASE_DURATION_MS)
            .setInterpolator(OvershootInterpolator(SQUISH_OVERSHOOT))
            .start()
    }

    private fun releaseSquish(view: View, state: PressState) {
        if (state.released) return
        state.released = true
        cancelTrigger(view)
        playReleaseAnimation(state.animationTarget ?: view)
    }

    private fun cancelTrigger(view: View) {
        val state = pressStates[view] ?: return
        state.runnable?.let { view.removeCallbacks(it) }
        state.runnable = null
    }

    private fun obtainState(view: View): PressState {
        val config = ViewConfiguration.get(view.context)
        return pressStates[view] ?: PressState(config.scaledTouchSlop.toFloat()).also { pressStates[view] = it }
    }

    private val longPressTimeout: Long
        get() = try {
            ViewConfiguration.getLongPressTimeout().toLong()
        } catch (_: Throwable) {
            500L
        }

    private fun findToggleSliderView(view: View): Any? {
        var current: View = view
        repeat(6) {
            current = current.parent as? View ?: return null
            if (current.javaClass.name == TOGGLE_SLIDER_VIEW_CLASS) return current
        }
        return null
    }

    private fun openBrightnessDetail(sliderView: Any) {
        try {
            findMethod(sliderView.javaClass, "openBrightnessDetail")
                .invoke(sliderView)
        } catch (t: Throwable) {
            logger.error("Failed to open brightness detail", t)
        }
    }

    private class PressState(slop: Float) {
        val touchSlopSquared: Float = slop * slop
        var downX: Float = 0f
        var downY: Float = 0f
        var released: Boolean = false
        var runnable: Runnable? = null

        /** downTime of the gesture whose trigger was already posted. */
        var downTime: Long = Long.MIN_VALUE

        /** View the squish animation is running on (slider root or the view itself). */
        var animationTarget: View? = null
    }

    // Internal so VolumeSliderLongPressHook can reference the shared panel
    // slider tag constant.
    internal companion object {
        const val QS_TILE_VIEW_CLASS = "com.android.systemui.qs.tileimpl.QSTileViewImpl"
        const val CUSTOM_QS_TILE_VIEW_CLASS =
            "com.android.systemui.qs.tileimpl.CustomQSTileViewImpl"
        const val LONG_PRESS_EFFECT_FIELD = "longPressEffect"
        const val EFFECT_STATE_FIELD = "state"
        const val QS_TILE_IMPL_CLASS = "com.android.systemui.qs.tileimpl.QSTileImpl"
        const val TOGGLE_SLIDER_VIEW_CLASS = "com.android.systemui.settings.ToggleSliderView"
        const val TOGGLE_SEEK_BAR_CLASS =
            "com.android.systemui.settings.brightness.ToggleSeekBar"
        const val SEEK_BAR_NPS_CLASS = "zui.widget.SeekBarNps"
        const val BRIGHTNESS_SLIDER_FIELD = "mBrightnessSlider"
        const val VOLUME_SLIDER_FIELD = "mMediaVolumeSlider"
        const val BRIGHTNESS_ROOT_FIELD = "mBrightnessSliderRoot"
        const val VOLUME_ROOT_FIELD = "mVolumeSliderRoot"

        /**
         * Tag on the panel sliders VolumeSliderLongPressHook builds itself
         * (bare SeekBarNps with no ToggleSliderView ancestor): marks them as
         * squish-animation targets without routing a long-press trigger.
         */
        const val VOLUME_PANEL_SLIDER_TAG = "ztool_volume_panel_slider"

        /** Squish-only trigger: sliders owned by the volume panel. */
        val NO_TRIGGER: () -> Unit = {}

        val SLIDER_ROOT_FIELDS = arrayOf(BRIGHTNESS_ROOT_FIELD, VOLUME_ROOT_FIELD)
        const val DETAIL_INDICATOR_FIELD = "detailIndicatorView"
        const val DUAL_TARGET_TAG = "ztool_dual_target_indicator"
        const val SQUISH_SCALE_X = 0.94f
        const val SQUISH_SCALE_Y = 0.90f
        const val SQUISH_DURATION_MS = 120L
        const val SQUISH_RELEASE_DURATION_MS = 180L
        const val SQUISH_OVERSHOOT = 1.2f
    }
}
