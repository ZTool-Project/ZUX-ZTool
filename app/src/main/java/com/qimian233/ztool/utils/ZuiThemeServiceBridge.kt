package com.qimian233.ztool.utils

import android.os.Build
import android.os.IBinder
import com.qimian233.ztool.EnhancedShellExecutor
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/** Reflection bridge to ZUI's `zuithemes` service and font system properties. */
object ZuiThemeServiceBridge {

    private const val THEME_SERVICE_NAME = "zuithemes"
    private const val STUB_CLASS_NAME = "zui.themes.IThemeService\$Stub"
    private const val INTERFACE_CLASS_NAME = "zui.themes.IThemeService"
    private const val SYSTEM_PROPERTIES_CLASS_NAME = "android.os.SystemProperties"
    private const val SERVICE_MANAGER_CLASS_NAME = "android.os.ServiceManager"
    private const val PROP_FONT_NAME = "persist.sys.zui.theme.fontname"
    private const val PROP_LGSI_REGION = "ro.config.lgsi.region"
    private const val DEFAULT_TX_CHANGE_SYSTEM_FONT = 2

    private val hiddenApiExempted = AtomicBoolean(false)

    private fun ensureHiddenApiBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            hiddenApiExempted.compareAndSet(false, true)
        ) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }

    /** Reads a system property via reflection, returning [defaultValue] on failure. */
    fun getSystemProperty(key: String, defaultValue: String = ""): String {
        ensureHiddenApiBypass()
        return runCatching {
            val clazz = Class.forName(SYSTEM_PROPERTIES_CLASS_NAME)
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            (method.invoke(null, key, defaultValue) as? String) ?: defaultValue
        }.getOrDefault(defaultValue)
    }

    /** Returns the regional ZUI default font name (`Rookery` for ROW, `ZukChinese` otherwise). */
    fun getDefaultSystemFontName(): String {
        val region = getSystemProperty(PROP_LGSI_REGION)
        return if (region.equals("row", ignoreCase = true)) "Rookery" else "ZukChinese"
    }

    /** Returns true if [fontName] represents one of ZUI's built-in system fonts. */
    fun isSystemDefaultFont(fontName: String): Boolean {
        val trimmed = fontName.trim()
        return trimmed.isEmpty() ||
            trimmed.equals("ZukChinese", ignoreCase = true) ||
            trimmed.equals("Rookery", ignoreCase = true) ||
            trimmed.equals("system", ignoreCase = true)
    }

    /** Queries the active font name from `zuithemes`, falling back to system properties. */
    fun getCurrentFontName(): String {
        ensureHiddenApiBypass()
        val serviceFont = runCatching {
            val service = resolveThemeService() ?: return@runCatching null
            val ifaceClass = Class.forName(INTERFACE_CLASS_NAME)
            val method = ifaceClass.getMethod("getSystemFont")
            method.invoke(service) as? String
        }.getOrNull()

        val rawName = serviceFont?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: getSystemProperty(PROP_FONT_NAME)

        if (rawName.isBlank() || rawName.equals("null", ignoreCase = true)) {
            return getDefaultSystemFontName()
        }
        return rawName.removeSuffix(".ttf").removeSuffix(".TTF")
    }

    /** Applies [fontName] from [fontFilePath] via reflected `IThemeService.changeSystemFont`. */
    fun changeSystemFont(fontFilePath: String?, fontName: String) {
        ensureHiddenApiBypass()
        try {
            val service = resolveThemeService()
                ?: throw IllegalStateException("zuithemes service unavailable")
            val ifaceClass = Class.forName(INTERFACE_CLASS_NAME)
            val method = ifaceClass.getMethod(
                "changeSystemFont",
                String::class.java,
                String::class.java
            )
            method.invoke(service, fontFilePath, fontName)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            if (cause is SecurityException) {
                changeSystemFontViaRootServiceCall(fontFilePath, fontName)
            } else {
                throw cause
            }
        } catch (_: SecurityException) {
            changeSystemFontViaRootServiceCall(fontFilePath, fontName)
        }
    }

    private fun resolveThemeService(): Any? {
        val smClass = Class.forName(SERVICE_MANAGER_CLASS_NAME)
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, THEME_SERVICE_NAME) as? IBinder ?: return null
        val stubClass = Class.forName(STUB_CLASS_NAME)
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }

    private fun resolveChangeSystemFontTransactionCode(): Int {
        return runCatching {
            val stubClass = Class.forName(STUB_CLASS_NAME)
            val field = stubClass.getDeclaredField("TRANSACTION_changeSystemFont")
            field.isAccessible = true
            field.getInt(null)
        }.getOrDefault(DEFAULT_TX_CHANGE_SYSTEM_FONT)
    }

    private fun changeSystemFontViaRootServiceCall(fontFilePath: String?, fontName: String) {
        val txCode = resolveChangeSystemFontTransactionCode()
        val script = "service call $THEME_SERVICE_NAME $txCode s16 ${shellQuote(fontFilePath.orEmpty())} s16 ${shellQuote(fontName)}"
        val result = EnhancedShellExecutor.getInstance().executeRootScript(script)
        if (!result.isSuccess) {
            val detail = result.error.ifBlank { result.output }.ifBlank { "exitCode=${result.exitCode}" }
            throw IllegalStateException("service call failed: $detail")
        }
    }
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
