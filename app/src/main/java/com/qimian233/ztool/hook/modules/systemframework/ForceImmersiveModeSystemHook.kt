package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface

/** Forces system bars through the Android 17 hiding control target. */
@SuppressLint("PrivateApi")
class ForceImmersiveModeSystemHook : SystemHookModule() {
    override fun getModuleName(): String = PreferenceKeys.FORCE_IMMERSIVE_MODE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val policyClass = param.classLoader.loadClass("com.android.server.wm.InsetsPolicy")
        val windowClass = param.classLoader.loadClass("com.android.server.wm.WindowState")
        val hidingTarget = findField(policyClass, "mHidingPermanentControlTarget")
        val showingTarget = findField(policyClass, "mShowingTransientControlTarget")
        val showingTypes = findField(policyClass, "mShowingTransientTypes")
        val methods = listOf(
            findMethod(policyClass, "getStatusControlTargetInner", windowClass, Boolean::class.javaPrimitiveType)
                to ImmersiveModePolicy.STATUS_BAR,
            findMethod(policyClass, "getNavControlTargetInner", windowClass, Boolean::class.javaPrimitiveType)
                to ImmersiveModePolicy.NAVIGATION_BAR
        )

        // See docs/research/force_immersive_mode.md for control ownership and transient layout.
        for ((method, barType) in methods) {
            hookWithId(method, "force_immersive_control_target_$barType") { chain ->
                val original = chain.proceed()
                if (!isEnabled() || chain.args[0] == null) return@hookWithId original
                val policy = chain.thisObject ?: return@hookWithId original
                val transientTarget = showingTarget.get(policy)
                val keepTransient = ImmersiveModePolicy.keepTransient(
                    chain.args[1] as Boolean,
                    showingTypes.getInt(policy),
                    barType
                )
                if (keepTransient || original === transientTarget) {
                    transientTarget
                } else {
                    hidingTarget.get(policy)
                }
            }
        }
        logger.info("Immersive system bar control hooks installed")
    }
}

internal object ImmersiveModePolicy {
    // WindowInsets.Type status/navigation bits; IME, caption, and gesture types stay intact.
    const val STATUS_BAR = 1
    const val NAVIGATION_BAR = 2
    const val TRANSIENT_BY_SWIPE = 2

    fun hideBars(visibleTypes: Int): Int = visibleTypes and (STATUS_BAR or NAVIGATION_BAR).inv()

    fun keepTransient(fake: Boolean, showingTypes: Int, barType: Int): Boolean =
        !fake && (showingTypes and barType) != 0
}
