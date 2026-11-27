package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/** Suppresses the system "displaying over other apps" alert-window notification. */
@SuppressLint("PrivateApi")
class DisableAlertWindowNotification : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.DISABLE_ALERT_WINDOW_NOTIFICATION.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    @Throws(Throwable::class)
    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val alertWindowNotificationClass = param.classLoader.loadClass(
                "com.android.server.wm.AlertWindowNotification"
            )
            val postMethod = findMethod(alertWindowNotificationClass, "post")
            hookWithId(postMethod, "disable_alert_window_notification") { null }
            logger.info("Hooked AlertWindowNotification.post")
        } catch (t: Throwable) {
            logger.error("Failed to hook AlertWindowNotification.post", t)
        }
    }
}
