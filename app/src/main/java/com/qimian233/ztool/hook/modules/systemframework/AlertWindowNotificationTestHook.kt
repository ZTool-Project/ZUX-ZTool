package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/** Test hook that suppresses the AOSP alert-window notification for ZTool. */
@SuppressLint("PrivateApi")
class AlertWindowNotificationTestHook : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.TEST_HOOK.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    @Throws(Throwable::class)
    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val alertWindowNotificationClass = param.classLoader.loadClass(
                "com.android.server.wm.AlertWindowNotification"
            )
            val postMethod = findMethod(alertWindowNotificationClass, "post")
            val packageNameField = findField(alertWindowNotificationClass, "mPackageName")

            hookWithId(postMethod, "suppress_ztool_alert_window_notification_test") { chain ->
                val pkg = packageNameField.get(chain.thisObject) as? String
                if (pkg == ZTOOL_PACKAGE) {
                    logger.info("Suppressed AlertWindowNotification.post for $pkg")
                    null
                } else {
                    chain.proceed()
                }
            }
            logger.info("Hooked AlertWindowNotification.post for $ZTOOL_PACKAGE")
        } catch (t: Throwable) {
            logger.error("Failed to hook AlertWindowNotification.post", t)
        }
    }

    companion object {
        private const val ZTOOL_PACKAGE = "com.qimian233.ztool"
    }
}
