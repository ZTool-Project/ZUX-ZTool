package com.qimian233.ztool.hook.modules.lsfdevice

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Disables the push keep-alive channels of the Lenovo device service
 * (com.lenovo.lsf.device, the LSF push/UPS component).
 *
 * Swallows onReceive of LSF PushReceiver, UpgradeReceiver and the GeTui
 * PushReceiver bundled from V6 (boot/package-removed -> PushCoreService).
 * See docs/research/lsf_device_getui_push.md
 */
class DisableLsfDevicePush : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.DISABLE_LSF_DEVICE_PUSH.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.LENOVO_DEVICE_SERVICE.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        try {
            val pushReceiverClass = classLoader.loadClass(
                "com.lenovo.lsf.push.receiver.PushReceiver"
            )
            val onReceive = findMethod(
                pushReceiverClass, "onReceive",
                android.content.Context::class.java, android.content.Intent::class.java
            )
            hookWithId(onReceive, "lsf_device_push_receiver") { chain ->
                val action = (chain.args[1] as? android.content.Intent)?.action
                logger.debug("Blocked PushReceiver.onReceive, action=$action")
                // no-op: swallow all keep-alive triggers
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook PushReceiver", t)
        }

        try {
            val upgradeReceiverClass = classLoader.loadClass(
                "com.lenovo.lsf.upgrade.UpgradeReceiver"
            )
            val onReceive = findMethod(
                upgradeReceiverClass, "onReceive",
                android.content.Context::class.java, android.content.Intent::class.java
            )
            hookWithId(onReceive, "lsf_device_upgrade_receiver") { chain ->
                val action = (chain.args[1] as? android.content.Intent)?.action
                logger.debug("Blocked UpgradeReceiver.onReceive, action=$action")
                // no-op: swallow the SDAC hourly alarm
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook UpgradeReceiver", t)
        }

        try {
            val getuiReceiverClass = classLoader.loadClass(
                "com.getui.vendor.push.PushReceiver"
            )
            val onReceive = findMethod(
                getuiReceiverClass, "onReceive",
                android.content.Context::class.java, android.content.Intent::class.java
            )
            hookWithId(onReceive, "lsf_device_getui_push_receiver") { chain ->
                val action = (chain.args[1] as? android.content.Intent)?.action
                logger.debug("Blocked GeTui PushReceiver.onReceive, action=$action")
            }
        } catch (t: Throwable) {
            // Absent before Device Service V6.0
            logger.debug("GeTui PushReceiver not present: ${t.message}")
        }
    }
}
