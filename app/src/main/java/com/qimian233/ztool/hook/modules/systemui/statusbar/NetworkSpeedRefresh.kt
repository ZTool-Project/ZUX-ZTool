package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Custom status-bar network-speed refresh interval.
 *
 * `NetworkSpeedView` samples a hardcoded 3000 ms window and reports `deltaBytes / 3`, so
 * changing only the re-arm delay would scale the shown rate. The rate message is rescaled
 * with the window actually measured between two samples.
 */
@SuppressLint("PrivateApi")
class NetworkSpeedRefresh : AppHookModule() {

    private class SampleWindow {
        var lastSampleAt = 0L
        var windowMs = 0L
    }

    private val sampleWindows = Collections.synchronizedMap(WeakHashMap<Handler, SampleWindow>())
    private var handlerClass: Class<*>? = null

    override fun getModuleName(): String = PreferenceKeys.CUSTOM_NETWORK_SPEED_REFRESH_INTERVAL.name

    override fun getTargetPackages(): Array<String> = arrayOf(SYSTEMUI_PACKAGE)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != SYSTEMUI_PACKAGE) return
        if (xposed.getRemotePreferences(PREFS_NAME)
                .getBoolean(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER.name, false)) {
            logger.info("Double-layer network speed is enabled; its hook owns interval and rate")
            return
        }
        val resolved = findNetworkSpeedHandlerClass(param.defaultClassLoader)
        if (resolved == null) {
            logger.error("Network speed handler not found; refresh interval left untouched")
            return
        }
        handlerClass = resolved
        // Without the rescale, a non-3 s interval reports a wrong rate; keep the native 3 s then.
        if (!hookRateRescale(resolved)) {
            logger.error("Rate rescale unavailable; refresh interval left untouched")
            return
        }
        hookRefreshInterval(param.defaultClassLoader)
    }

    private fun hookRateRescale(target: Class<*>): Boolean = try {
        val handleMessage = target.getDeclaredMethod("handleMessage", Message::class.java)
        hookWithId(handleMessage, "handle_message") { chain ->
            val message = chain.args[0] as Message
            val handler = chain.thisObject as Handler
            when (message.what) {
                SAMPLE_WHAT -> recordSample(handler)
                RATE_WHAT -> rescaleRate(handler, message)
            }
            chain.proceed()
        }
        true
    } catch (t: Throwable) {
        logger.error("Failed to hook NetworkSpeedView's handleMessage", t)
        false
    }

    private fun recordSample(handler: Handler) {
        val now = SystemClock.elapsedRealtime()
        val window = sampleWindows.getOrPut(handler) { SampleWindow() }
        window.windowMs = if (window.lastSampleAt > 0L) now - window.lastSampleAt else 0L
        window.lastSampleAt = now
    }

    /** `arg1` holds `deltaBytes / 3`; rescale it to bytes per second for the real window. */
    private fun rescaleRate(handler: Handler, message: Message) {
        val windowMs = sampleWindows[handler]?.windowMs ?: return
        if (windowMs < MIN_WINDOW_MS || windowMs > MAX_WINDOW_MS) return
        if (message.arg1 <= 0) return
        message.arg1 = (message.arg1 * (NATIVE_WINDOW_MS / windowMs)).roundToInt()
    }

    private fun hookRefreshInterval(classLoader: ClassLoader) {
        try {
            val intervalMs = readIntervalMs()
            val method = findMethod(
                classLoader.loadClass("android.os.Handler"),
                "sendEmptyMessageDelayed",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            hookWithId(method, "send_empty_message_delayed") { chain ->
                val handler = chain.thisObject
                val what = chain.args[0] as Int
                val delayMillis = chain.args[1] as Long
                when {
                    !isNetworkSpeedHandler(handler) -> chain.proceed()
                    delayMillis < REARM_MIN_DELAY_MS -> {
                        // The 50 ms kick re-creates the sampling baseline; drop the stale window.
                        sampleWindows.remove(handler as Handler)
                        chain.proceed()
                    }
                    else -> chain.proceed(arrayOf<Any>(what, intervalMs))
                }
            }
            logger.info("Network speed refresh interval set to $intervalMs ms")
        } catch (t: Throwable) {
            logger.error("Failed to hook Handler.sendEmptyMessageDelayed", t)
        }
    }

    private fun isNetworkSpeedHandler(handler: Any): Boolean = handlerClass?.isInstance(handler) == true

    private fun readIntervalMs(): Long {
        val seconds = xposed.getRemotePreferences(PREFS_NAME)
            .getFloat(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_REFRESH_INTERVAL.name, DEFAULT_INTERVAL_S)
        return (seconds * 1000.0).toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    private fun findNetworkSpeedHandlerClass(classLoader: ClassLoader): Class<*>? {
        for (viewClassName in NETWORK_SPEED_VIEW_CLASSES) {
            val cls = handlerFromViewFields(classLoader, viewClassName)
            if (cls != null) return cls
        }
        for (viewClassName in NETWORK_SPEED_VIEW_CLASSES) {
            for (index in 1..MAX_INNER_CLASS_INDEX) {
                val cls = loadHandlerClass(classLoader, "$viewClassName$$index")
                if (cls != null) return cls
            }
        }
        return null
    }

    private fun loadHandlerClass(classLoader: ClassLoader, className: String): Class<*>? {
        val cls = try {
            classLoader.loadClass(className)
        } catch (_: Throwable) {
            return null
        }
        return if (declaresHandleMessage(cls)) cls else null
    }

    private fun declaresHandleMessage(type: Class<*>): Boolean = try {
        type.getDeclaredMethod("handleMessage", Message::class.java)
        true
    } catch (_: Throwable) {
        false
    }

    /** `mNetworkSpeedHandler` is the view's only field whose type is a `Handler` subclass. */
    private fun handlerFromViewFields(classLoader: ClassLoader, viewClassName: String): Class<*>? {
        val viewClass = try {
            classLoader.loadClass(viewClassName)
        } catch (_: Throwable) {
            return null
        }
        for (field in viewClass.declaredFields) {
            val type = try {
                field.type
            } catch (_: Throwable) {
                continue
            }
            if (type != Handler::class.java && Handler::class.java.isAssignableFrom(type)
                    && declaresHandleMessage(type)) {
                return type
            }
        }
        return null
    }

    private companion object {
        private val SYSTEMUI_PACKAGE = ScopeKeys.SYSTEM_UI.packageName
        private val NETWORK_SPEED_VIEW_CLASSES = arrayOf(
            "com.android.systemui.statusbar.view.NetworkSpeedView",
            "com.android.systemui.zui.NetworkSpeedView",
        )

        /** Native handler message ids: the sampling tick and the rate-reporting tick. */
        private const val SAMPLE_WHAT = 10
        private const val RATE_WHAT = 1

        private const val NATIVE_WINDOW_MS = 3000.0

        /** Only the self re-arm carries the native 3000 ms literal; the 50 ms kick must pass. */
        private const val REARM_MIN_DELAY_MS = 1000L

        /**
         * One refresh costs ~4 % SystemUI CPU on the ZUX A17 device (TextView relayout plus
         * status-bar redraw), measured as 16 % at 1 s, 33 % at 0.2 s; 0.5 s is the floor.
         */
        private const val MIN_INTERVAL_MS = 500L
        private const val MAX_INTERVAL_MS = 60_000L

        private const val MIN_WINDOW_MS = 50L
        private const val MAX_WINDOW_MS = 600_000L

        private const val DEFAULT_INTERVAL_S = 3.0f

        /** R8 renumbers the view's anonymous Handler class per build; probe a bounded range. */
        private const val MAX_INNER_CLASS_INDEX = 10
    }
}
