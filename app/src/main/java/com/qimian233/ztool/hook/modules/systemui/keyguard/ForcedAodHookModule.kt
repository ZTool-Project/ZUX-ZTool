package com.qimian233.ztool.hook.modules.systemui.keyguard

import com.qimian233.ztool.hook.base.AppHookModule

/** Shares the brightness floor used by forced AOD modules. */
abstract class ForcedAodHookModule : AppHookModule() {
    protected fun hookMinimumDozeBrightness(classLoader: ClassLoader) {
        try {
            val forwarderClass = classLoader.loadClass(
                "com.android.systemui.doze.DozeBrightnessHostForwarder"
            )
            val brightnessMethods = forwarderClass.declaredMethods.filter { method ->
                method.name == "setDozeScreenBrightness" &&
                    method.parameterTypes.size == 1 &&
                    (method.parameterTypes[0] == Float::class.javaPrimitiveType ||
                        method.parameterTypes[0] == Int::class.javaPrimitiveType)
            }
            check(brightnessMethods.isNotEmpty()) { "No supported Doze brightness setter" }
            for (method in brightnessMethods) {
                hookWithId(method, "${getModuleName()}_doze_brightness_floor") { chain ->
                    if (!isEnabled()) return@hookWithId chain.proceed()
                    val brightness = chain.args[0]
                    val clamped: Any = when (brightness) {
                        is Float -> DozeBrightnessFloor.clamp(brightness)
                        is Int -> DozeBrightnessFloor.clamp(brightness)
                        else -> return@hookWithId chain.proceed()
                    }
                    if (clamped == brightness) return@hookWithId chain.proceed()
                    chain.proceed(arrayOf(clamped))
                }
            }
            logger.info("Installed forced AOD minimum brightness")
        } catch (t: Throwable) {
            logger.error("Failed to hook forced AOD minimum brightness", t)
        }
    }
}

/** Raises valid Doze brightness values while preserving platform sentinels. */
internal object DozeBrightnessFloor {
    // Initial normalized floor; device calibration is pending. See docs/research/systemui_doze_brightness_android17.md.
    private const val MINIMUM_FLOAT = 0.10f

    // BrightnessSynchronizer maps positive integers as (value - 1) / 254; ceil(0.10 * 254 + 1) = 27.
    private const val MINIMUM_INT = 27

    fun clamp(brightness: Float): Float =
        if (brightness.isFinite() && brightness >= 0f) {
            brightness.coerceAtLeast(MINIMUM_FLOAT)
        } else {
            brightness
        }

    fun clamp(brightness: Int): Int =
        if (brightness >= 0) brightness.coerceAtLeast(MINIMUM_INT) else brightness
}
