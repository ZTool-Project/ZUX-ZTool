package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * Custom status-bar network-speed refresh interval.
 *
 * Targets `NetworkSpeedView`'s internal handler through `Handler.sendEmptyMessageDelayed`;
 * the handler-class prefix differs between the legacy ZUI and the Android 17 SystemUI builds.
 */
@SuppressLint("PrivateApi")
class NetworkSpeedRefresh : AppHookModule() {

    companion object {
        private val SYSTEMUI_PACKAGE = ScopeKeys.SYSTEM_UI.packageName
        // Ordered: the Android 17 view first, the legacy ZUI path second.
        private val NETWORK_SPEED_HANDLER_PREFIXES = arrayOf(
            "com.android.systemui.statusbar.view.NetworkSpeedView\$",
            "com.android.systemui.zui.NetworkSpeedView\$",
        )
    }

    override fun getModuleName(): String = "custom_network_speed_refresh_interval"

    override fun getTargetPackages(): Array<String> = arrayOf(SYSTEMUI_PACKAGE)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != SYSTEMUI_PACKAGE) return
        if (xposed.getRemotePreferences(PREFS_NAME).getBoolean("systemui_network_speed_doublelayer", false)) {
            logger.info("Will not load refresh interval hook when double layer network speed is enabled. This function is implemented in that hook!")
            return
        }
        hookSendEmptyMessageDelayed(param.defaultClassLoader)
    }

    private fun hookSendEmptyMessageDelayed(classLoader: ClassLoader) {
        try {
            val handlerClass = classLoader.loadClass("android.os.Handler")
            val refreshInterval: Long = (xposed.getRemotePreferences(PREFS_NAME).getFloat("systemui_network_speed_refresh_interval", 3.0f) * 1000.0).toLong()
            // Hook sendEmptyMessageDelayed(int, long)
            val method: Method = findMethod(
                handlerClass,
                "sendEmptyMessageDelayed",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )

            hookWithId(method, "method") { chain ->
                val handler = chain.thisObject
                val what = chain.args[0] as Int
                val delayMillis = chain.args[1] as Long

                // Determine whether this is NetworkSpeedView's internal Handler
                val handlerClassName = handler.javaClass.name
                if (NETWORK_SPEED_HANDLER_PREFIXES.any { handlerClassName.startsWith(it) }) {
                    logger.debug(
                        "[SpeedRefreshTest] sendEmptyMessageDelayed called: " +
                            "handler=$handlerClassName, what=$what, delayMillis=$delayMillis ms"
                    )

                    chain.proceed(arrayOf<Any>(what, refreshInterval))
                } else {
                    chain.proceed()
                }
            }

            logger.info("[SpeedRefreshTest] Handler.sendEmptyMessageDelayed hooked successfully.")
        } catch (e: Throwable) {
            logger.error("[SpeedRefreshTest] Failed to hook sendEmptyMessageDelayed", e)
        }
    }
}