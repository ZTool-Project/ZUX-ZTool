package com.qimian233.ztool.hook.modules.mobiledesktop

import android.os.Handler
import android.os.Message
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Disables the 10-minute auto-off countdown of nearby sharing in Super
 * Interconnect (FileUnion).
 */
class DisableNearbyShareAutoOffHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.DISABLE_NEARBY_SHARE_COUNTDOWN.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.MOBILE_DESKTOP.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        try {
            val dispatchMethod = Handler::class.java.getDeclaredMethod(
                "dispatchMessage",
                Message::class.java
            )
            hookWithId(dispatchMethod, "handler_dispatch_message") { chain ->
                val msg = chain.args.firstOrNull() as? Message
                if (msg != null && msg.what == MSG_AUTO_OFF && msg.callback == null) {
                    val handler = chain.thisObject as? Handler
                    if (handler?.looper?.thread?.name == HANDLER_THREAD_NAME) {
                        logger.debug("Blocked FileUnionSwitchManager auto-off message (what=$MSG_AUTO_OFF)")
                        return@hookWithId null
                    }
                }
                chain.proceed()
            }
            logger.info("Installed hook for $HANDLER_THREAD_NAME HandlerThread")
        } catch (t: Throwable) {
            logger.error("Failed to install FileUnionSwitchManager auto-off hook", t)
        }
    }

    private companion object {
        const val HANDLER_THREAD_NAME = "FileUnionSwitchManager"
        const val MSG_AUTO_OFF = 1
    }
}
