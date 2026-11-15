package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface

/** Bypasses non-strong biometric idle and fallback expiry on Android 17 and later. */
@SuppressLint("PrivateApi")
class BypassWeakBiometricTimeout : SystemHookModule() {
    override fun getModuleName(): String = PreferenceKeys.BYPASS_WEAK_BIOMETRIC_TIMEOUT.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return

        // See docs/research/face_auth_four_hour_idle_timeout.md.
        val callbacks = listOf(
            "com.android.server.locksettings.LockSettingsStrongAuth\$NonStrongBiometricIdleTimeoutAlarmListener" to
                "weak_biometric_idle_timeout_bypass",
            "com.android.server.locksettings.LockSettingsStrongAuth\$NonStrongBiometricTimeoutAlarmListener" to
                "weak_biometric_fallback_timeout_bypass"
        )
        for ((className, hookId) in callbacks) {
            try {
                val callback = findMethod(param.classLoader.loadClass(className), "onAlarm")
                hookWithId(callback, hookId) { chain ->
                    if (isEnabled()) null else chain.proceed()
                }
                logger.info("Installed weak biometric timeout bypass: $className.onAlarm")
            } catch (t: Throwable) {
                logger.error("Failed to hook $className.onAlarm", t)
            }
        }
    }
}
