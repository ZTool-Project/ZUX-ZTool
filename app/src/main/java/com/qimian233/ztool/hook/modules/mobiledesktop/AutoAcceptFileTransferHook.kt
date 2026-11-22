package com.qimian233.ztool.hook.modules.mobiledesktop

import android.app.Activity
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Auto-accepts the PC→phone file transfer confirmation dialog of Super
 * Interconnect (FileUnion).
 */
class AutoAcceptFileTransferHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.AUTO_ACCEPT_FILE_TRANSFER.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.MOBILE_DESKTOP.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        try {
            val activityClass = classLoader.loadClass(TARGET_CLASS)
            val onStartMethod = activityClass.getDeclaredMethod("onStart")
            hookWithId(onStartMethod, "on_start") { chain ->
                val activity = chain.thisObject as? Activity
                val intent = activity?.intent
                if (intent != null && intent.getIntExtra(EXTRA_NOTIFICATION_ACTION, 0) == 0) {
                    // Reuse the native notification-accept branch in onStart() without touching ViewModel fields.
                    intent.putExtra(EXTRA_NOTIFICATION_ACTION, ACTION_ACCEPT)
                    logger.debug("Injected $EXTRA_NOTIFICATION_ACTION=$ACTION_ACCEPT into $TARGET_CLASS")
                }
                chain.proceed()
            }
            logger.info("Installed hook for auto-accept file transfer")
        } catch (t: Throwable) {
            logger.error("Failed to install auto-accept file transfer hook", t)
        }
    }

    private companion object {
        const val TARGET_CLASS =
            "com.motorola.mobiledesktop.files.pc2phone.FileConnectionConfirmActivity"
        const val EXTRA_NOTIFICATION_ACTION = "extra_notification_action"
        const val ACTION_ACCEPT = 1
    }
}
