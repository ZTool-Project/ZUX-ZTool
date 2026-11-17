package com.qimian233.ztool.hook.modules.systemframework

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.Property
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.WeakHashMap

/**
 * Re-enables DisplayPowerController's color fade screen on/off animation, with a
 * customizable animation duration via [PreferenceKeys.SCREEN_ON_OFF_ANIMATION_MS].
 * See docs/research/screen-on-off-animation.md.
 */
@SuppressLint("PrivateApi")
class ForceScreenOnOffAnimation : SystemHookModule() {

    private val syntheticOnAnimators = WeakHashMap<Any, ObjectAnimator>()

    override fun getModuleName(): String = PreferenceKeys.FORCE_SCREEN_ON_OFF_ANIMATION.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        try {
            logger.info("Executing hook for DisplayPowerController screen on/off animation...")
            hookDisplayPowerControllerAnimation(classLoader)
        } catch (e: Exception) {
            logger.error("Failed to hook DisplayPowerController: ", e)
        }
    }

    private fun hookDisplayPowerControllerAnimation(classLoader: ClassLoader) {
        try {
            val animateMethod = try {
                classLoader.loadClass(DISPLAY_POWER_CONTROLLER).getDeclaredMethod(
                    "animateScreenStateChange",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            } catch (_: NoSuchMethodException) {
                logger.warn("Fall back to android 16 and older signature")
                classLoader.loadClass(DISPLAY_POWER_CONTROLLER).getDeclaredMethod(
                    "animateScreenStateChange",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            }
            hookWithId(animateMethod, "animate_screen_state") { chain ->
                val controller = chain.thisObject
                val powerState = fieldValue(controller, "mPowerState")
                val onAnimator = getOrCreateOnAnimator(controller, powerState)
                applyAnimationDurations(controller, onAnimator)
                // Guard in-flight fades against Android 17's waitUntilClean re-entry and
                // competing screen-off transitions (matches Android 16's top-of-method check).
                if (onAnimator?.isStarted == true) {
                    return@hookWithId null
                }
                if (chain.getArg(0) as Int != DISPLAY_STATE_ON) {
                    return@hookWithId chain.proceed()
                }
                val colorFadeLevel = powerState?.let {
                    runCatching {
                        findMethod(
                            it.javaClass,
                            "getColorFadeLevel"
                        ).invoke(it)
                    }.getOrNull()
                } as? Float ?: 1.0f
                val fadeWasLive = fieldValue(powerState, "mColorFadePrepared") as? Boolean == true
                chain.proceed()
                // The host dismisses the prepared fade on most screen-on paths and does so even
                // while its animator runs, so the fade has to be rebuilt after the fact.
                if (fadeWasLive && colorFadeLevel < 1.0f && powerState != null && onAnimator != null
                    && fieldValue(powerState, "mColorFadePrepared") as? Boolean != true
                ) {
                    rebuildScreenOnColorFade(controller, powerState, onAnimator, colorFadeLevel)
                }
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to hook animateScreenStateChange", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getOrCreateOnAnimator(controller: Any, powerState: Any?): ObjectAnimator? {
        (fieldValue(controller, "mColorFadeOnAnimator") as? ObjectAnimator)?.let { return it }
        if (powerState == null || fieldValue(controller, "mColorFadeEnabled") as? Boolean == false) {
            return null
        }
        synchronized(syntheticOnAnimators) {
            syntheticOnAnimators[controller]?.takeIf { it.target === powerState }?.let { return it }
            val colorFadeLevelProp = runCatching {
                findField(powerState.javaClass, "COLOR_FADE_LEVEL").get(null) as? Property<Any, Float>
            }.getOrNull() ?: return null
            val animator = ObjectAnimator.ofFloat(powerState, colorFadeLevelProp, 0.0f, 1.0f).apply {
                duration = SCREEN_ON_ANIMATION_DURATION_MS
                (fieldValue(controller, "mAnimatorListener") as? Animator.AnimatorListener)
                    ?.let(::addListener)
            }
            syntheticOnAnimators[controller] = animator
            return animator
        }
    }

    /**
     * Applies the configured durations to both color fade animators.
     */
    private fun applyAnimationDurations(controller: Any, onAnimator: ObjectAnimator?) {
        updateAnimationDurationFromPrefs()
        setAnimatorDuration(onAnimator, SCREEN_ON_ANIMATION_DURATION_MS)
        setAnimatorDuration(
            fieldValue(controller, "mColorFadeOffAnimator") as? ObjectAnimator,
            SCREEN_OFF_ANIMATION_DURATION_MS
        )
    }

    private fun setAnimatorDuration(animator: ObjectAnimator?, durationMs: Long) {
        if (animator == null) return
        // A running animator derives its progress from mDuration on every frame, so re-timing
        // it mid-flight would make the fade jump.
        if (animator.isStarted) return
        animator.duration = durationMs
    }

    /**
     * Rebuilds the color fade the host dismissed during a screen-on state change.
     *
     * @return true when the fade was rebuilt and started again.
     */
    private fun rebuildScreenOnColorFade(
        controller: Any,
        powerState: Any,
        onAnimator: ObjectAnimator,
        colorFadeLevel: Float
    ): Boolean {
        if (powerState.let {
                runCatching {
                    findMethod(
                        it.javaClass,
                        "getScreenState"
                    ).invoke(it)
                }.getOrNull()
            } as? Int != DISPLAY_STATE_ON) {
            logger.debug("Screen-on color fade was dropped without the display turning on")
            return false
        }
        val prepared = runCatching {
            findMethod(
                powerState.javaClass, "prepareColorFade",
                Context::class.java, Int::class.javaPrimitiveType
            ).invoke(powerState, fieldValue(controller, "mContext"), COLOR_FADE_MODE_FADE)
        }.getOrNull() as? Boolean ?: false
        if (!prepared) {
            logger.warn("Could not rebuild the screen-on color fade")
            return false
        }
        runCatching {
            findMethod(powerState.javaClass, "setColorFadeLevel", Float::class.javaPrimitiveType)
                .invoke(powerState, colorFadeLevel)
        }
        return try {
            onAnimator.duration = SCREEN_ON_ANIMATION_DURATION_MS
            onAnimator.setFloatValues(colorFadeLevel, 1.0f)
            onAnimator.start()
            logger.debug("Rebuilt the screen-on color fade at level $colorFadeLevel")
            true
        } catch (t: Throwable) {
            logger.error("Failed to start the screen-on color fade: ", t)
            false
        }
    }

    private fun fieldValue(target: Any?, name: String): Any? =
        target?.let { runCatching { findField(it.javaClass, name).get(it) }.getOrNull() }

    private fun updateAnimationDurationFromPrefs() {
        try {
            val duration = remotePreferences.getInt(
                PreferenceKeys.SCREEN_ON_OFF_ANIMATION_MS.name,
                PreferenceKeys.SCREEN_ON_OFF_ANIMATION_MS.default
            )
            SCREEN_ON_ANIMATION_DURATION_MS = duration.toLong()
            SCREEN_OFF_ANIMATION_DURATION_MS = duration.toLong()
        } catch (_: Throwable) {
            SCREEN_ON_ANIMATION_DURATION_MS = DEFAULT_ANIMATION_DURATION_MS
            SCREEN_OFF_ANIMATION_DURATION_MS = DEFAULT_ANIMATION_DURATION_MS
        }
    }

    companion object {

        private const val DISPLAY_POWER_CONTROLLER =
            "com.android.server.display.DisplayPowerController"
        private const val COLOR_FADE_MODE_FADE = 2
        private const val DISPLAY_STATE_ON = 2
        private const val DEFAULT_ANIMATION_DURATION_MS = 400L
        private var SCREEN_ON_ANIMATION_DURATION_MS = DEFAULT_ANIMATION_DURATION_MS
        private var SCREEN_OFF_ANIMATION_DURATION_MS = DEFAULT_ANIMATION_DURATION_MS
    }
}
