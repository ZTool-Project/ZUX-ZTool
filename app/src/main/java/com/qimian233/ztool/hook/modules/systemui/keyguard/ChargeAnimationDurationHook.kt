package com.qimian233.ztool.hook.modules.systemui.keyguard

import android.os.Handler
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.SystemUiDexTargets
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Modifier

/** Customizes the lock-screen charging animation finish timeout. */
// See docs/research/systemui_charging_animation_android17.md.
class ChargeAnimationDurationHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.CHARGE_ANIMATION_DURATION.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val durationMs = remotePreferences.getInt(
            PreferenceKeys.CHARGE_ANIMATION_DURATION_MS.name,
            PreferenceKeys.CHARGE_ANIMATION_DURATION_MS.default
        ).toLong()
        try {
            val target = SystemUiDexTargets.CHARGING_ACTIVITIES.firstNotNullOfOrNull { name ->
                runCatching {
                    val activity = param.defaultClassLoader.loadClass(name)
                    val field = listOf("mAnimationDuration", "DURATION").firstNotNullOfOrNull {
                        runCatching { findField(activity, it) }.getOrNull()?.takeIf { it.type == Long::class.javaPrimitiveType }
                    } ?: return@runCatching null
                    findMethod(activity, "onResume") to field
                }.getOrNull()
            } ?: error("ChargingActivity duration contract not found")
            val (onResume, durationField) = target
            val finishField = runCatching { findField(onResume.declaringClass, "mTimeoutFinishRunnable") }.getOrNull()
            val pendingFinish = ThreadLocal<Any>()
            if (finishField != null) {
                // lapis bypasses mAnimationDuration with a literal 6200 ms delay.
                hookWithId(
                    findMethod(Handler::class.java, "postDelayed", Runnable::class.java, Long::class.javaPrimitiveType),
                    "charge_duration_post_delayed"
                ) { chain ->
                    val finish = pendingFinish.get()
                    if (finish != null && chain.args[0] === finish) {
                        chain.proceed(arrayOf(chain.args[0], durationMs))
                    } else chain.proceed()
                }
            }
            hookWithId(onResume, "charge_duration_on_resume") { chain ->
                val previous = pendingFinish.get()
                try {
                    try {
                        durationField.setLong(if (Modifier.isStatic(durationField.modifiers)) null else chain.thisObject, durationMs)
                        finishField?.get(chain.thisObject)?.let(pendingFinish::set)
                    } catch (t: Throwable) {
                        logger.error("Failed to set ChargingActivity duration", t)
                    }
                    chain.proceed()
                } finally {
                    if (previous == null) pendingFinish.remove() else pendingFinish.set(previous)
                }
            }
            logger.info("ChargeAnimationDurationHook applied to ${onResume.declaringClass.name}, duration=${durationMs}ms")
        } catch (t: Throwable) {
            logger.error("ChargeAnimationDurationHook failed", t)
        }
    }
}
