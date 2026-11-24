package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ScaleXSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.modules.systemui.misc.CustomDateFormatter
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.util.Calendar
import java.util.Date

/** Shares the SystemUI Clock pipeline across seconds, Red 1 hour, and custom clock features. */
@SuppressLint("PrivateApi")
class SystemUIClockHook : AppHookModule() {

    private data class Host(
        val clockClass: Class<*>,
        val showSecondsField: Field,
        val calendarField: Field?
    )

    override fun getModuleName(): String = PreferenceKeys.CUSTOM_STATUSBAR_CLOCK.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun isEnabled(): Boolean = readOptions().enabled

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (param.packageName != ScopeKeys.SYSTEM_UI.packageName) return
        val requested = readOptions()
        if (!requested.enabled) return

        val host = resolveHost(param.defaultClassLoader)
        var secondsReady = false
        if (requested.effectiveShowSeconds) {
            secondsReady = installGroup("seconds") { handles -> installSeconds(host, handles) }
        }
        var textReady = false
        if (requested.needsTextHook) {
            textReady = installGroup("text") { handles -> installText(host, handles) }
        }
        logger.info(
            "Clock hook installed: seconds=$secondsReady (requested=${requested.effectiveShowSeconds}), " +
                "text=$textReady (custom=${requested.customClock}, redOne=${requested.redOneHour})"
        )
    }

    private fun resolveHost(classLoader: ClassLoader): Host {
        val clockClass = classLoader.loadClass(CLOCK_CLASS)
        return Host(
            clockClass = clockClass,
            showSecondsField = findField(clockClass, "mShowSeconds"),
            calendarField = runCatching { findField(clockClass, "mCalendar") }.getOrNull()
        )
    }

    private fun installGroup(
        name: String,
        install: (MutableList<XposedInterface.HookHandle>) -> Unit
    ): Boolean {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        return try {
            install(handles)
            true
        } catch (failure: Throwable) {
            handles.forEach { runCatching { it.unhook() } }
            logger.error("Clock $name hooks unavailable", failure)
            false
        }
    }

    private fun installSeconds(host: Host, handles: MutableList<XposedInterface.HookHandle>) {
        val updateShowSeconds = host.clockClass.getDeclaredMethod("updateShowSeconds")

        // Set mShowSeconds before proceeding so the host creates mSecondsHandler once itself.
        val onAttached = host.clockClass.getDeclaredMethod("onAttachedToWindow")
        handles += hookWithId(onAttached, "clock_on_attached") { chain ->
            if (readOptions().effectiveShowSeconds) {
                host.showSecondsField.setBoolean(chain.thisObject, true)
            }
            val result = chain.proceed()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN &&
                readOptions().effectiveShowSeconds
            ) {
                try {
                    updateShowSeconds.invoke(chain.thisObject)
                } catch (_: Throwable) {
                }
            }
            result
        }

        val onTuning = host.clockClass.getDeclaredMethod(
            "onTuningChanged",
            String::class.java,
            String::class.java
        )
        handles += hookWithId(onTuning, "clock_on_tuning") { chain ->
            val key = chain.args[0] as? String
            if ("clock_seconds" == key && readOptions().effectiveShowSeconds) {
                host.showSecondsField.setBoolean(chain.thisObject, true)
                val result = chain.proceed(arrayOf(key, "1"))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
                    try {
                        updateShowSeconds.invoke(chain.thisObject)
                    } catch (_: Throwable) {
                    }
                }
                result
            } else {
                chain.proceed()
            }
        }

        handles += hookWithId(updateShowSeconds, "clock_update_show_seconds") { chain ->
            if (readOptions().effectiveShowSeconds) {
                host.showSecondsField.setBoolean(chain.thisObject, true)
            }
            chain.proceed()
        }
    }

    private fun installText(host: Host, handles: MutableList<XposedInterface.HookHandle>) {
        val getSmallTime = host.clockClass.getDeclaredMethod("getSmallTime")
        handles += hookWithId(getSmallTime, "clock_get_small_time") { chain ->
            val options = readOptions()
            if (!options.needsTextHook) {
                return@hookWithId chain.proceed()
            }
            if (options.customClock) {
                val formatted = CustomDateFormatter.formatWithFields(
                    options.effectiveCustomFormat,
                    resolveClockDate(host, chain.thisObject)
                )
                return@hookWithId buildCustomClockText(chain.thisObject, formatted, options)
            }
            val original = chain.proceed() as? CharSequence ?: return@hookWithId null
            applyRedOneToStockClock(original)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            installLegacyCustomClockHooks(host, handles)
        }
    }

    private fun installLegacyCustomClockHooks(
        host: Host,
        handles: MutableList<XposedInterface.HookHandle>
    ) {
        try {
            val updateClock = host.clockClass.getDeclaredMethod("updateClock")
            handles += hookWithId(updateClock, "clock_legacy_update_clock") { chain ->
                val result = chain.proceed()
                try {
                    val options = readOptions()
                    if (options.customClock) {
                        val clockInstance = chain.thisObject
                        val customTime = CustomDateFormatter.format(
                            options.effectiveCustomFormat,
                            resolveClockDate(host, clockInstance)
                        )
                        (clockInstance as? View)?.contentDescription = customTime
                        applyLegacyDirectStyles(clockInstance, options)
                    }
                } catch (_: Throwable) {
                }
                result
            }
        } catch (_: Throwable) {
        }

        try {
            // Keep getDeclaredMethod so SystemUI never hooks View.onFinishInflate globally.
            val onFinishInflate = host.clockClass.getDeclaredMethod("onFinishInflate")
            handles += hookWithId(onFinishInflate, "clock_legacy_on_finish_inflate") { chain ->
                val result = chain.proceed()
                try {
                    val options = readOptions()
                    if (options.customClock) {
                        applyLegacyDirectStyles(chain.thisObject, options)
                    }
                } catch (_: Throwable) {
                }
                result
            }
        } catch (_: Throwable) {
        }
    }

    private fun resolveClockDate(host: Host, clockInstance: Any): Date =
        runCatching { (host.calendarField?.get(clockInstance) as? Calendar)?.time }.getOrNull()
            ?: Date()

    private fun applyLegacyDirectStyles(clockInstance: Any, options: StatusBarClockOptions) {
        val clockClass = clockInstance.javaClass

        if (options.letterSpacingEnabled) {
            val spacing = options.effectiveLetterSpacing
            try {
                clockClass.getDeclaredMethod("setLetterSpacing", Float::class.javaPrimitiveType)
                    .invoke(clockInstance, spacing)
            } catch (_: NoSuchMethodError) {
                applyLegacyAlternativeLetterSpacing(clockInstance, spacing)
            } catch (_: Throwable) {
            }
        }

        if (options.effectiveCustomTextColorEnabled) {
            try {
                clockClass.getDeclaredMethod("setTextColor", Int::class.javaPrimitiveType)
                    .invoke(clockInstance, options.textColor)
            } catch (_: Throwable) {
            }
        }

        if (options.textBold) {
            try {
                clockClass.getDeclaredMethod("setTypeface", Typeface::class.java)
                    .invoke(clockInstance, Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyLegacyAlternativeLetterSpacing(clockInstance: Any, letterSpacing: Float) {
        val clockClass = clockInstance.javaClass
        try {
            val scaleX = 1.0f + letterSpacing * 0.1f
            clockClass.getDeclaredMethod("setScaleX", Float::class.javaPrimitiveType)
                .invoke(clockInstance, scaleX)

            val paddingHorizontal = (letterSpacing * 10).toInt()
            clockClass.getDeclaredMethod(
                "setPadding",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(clockInstance, paddingHorizontal, 0, paddingHorizontal, 0)
        } catch (_: Throwable) {
        }
    }

    private fun buildCustomClockText(
        clockInstance: Any,
        formatted: CustomDateFormatter.FormattedDateText,
        options: StatusBarClockOptions
    ): CharSequence {
        val text = formatted.text
        if (text.isEmpty()) return ""
        val builder = SpannableStringBuilder(text)
        val length = text.length

        if (options.textSizeEnabled) {
            val metrics = (clockInstance as? View)?.resources?.displayMetrics
                ?: Resources.getSystem().displayMetrics
            val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                options.effectiveTextSizeSp,
                metrics
            ).toInt()
            builder.setSpan(AbsoluteSizeSpan(textSizePx), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (options.letterSpacingEnabled) {
            val spacing = options.effectiveLetterSpacing
            if (spacing > 0f) {
                builder.setSpan(
                    ScaleXSpan(1.0f + spacing * 0.1f),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        if (options.textBold) {
            builder.setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (options.effectiveCustomTextColorEnabled) {
            builder.setSpan(
                ForegroundColorSpan(options.textColor),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (options.redOneHour) {
            val redIndices = StatusBarClockPolicy.findRedOneIndices(text, formatted.hourRanges)
            for (index in redIndices) {
                builder.setSpan(
                    ForegroundColorSpan(StatusBarClockPolicy.ONEPLUS_RED_COLOR),
                    index,
                    index + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return builder
    }

    private fun applyRedOneToStockClock(original: CharSequence): CharSequence {
        val redIndices = StatusBarClockPolicy.findRedOneIndices(
            original,
            StatusBarClockPolicy.findStockHourRanges(original)
        )
        if (redIndices.isEmpty()) {
            return original
        }
        val builder = SpannableStringBuilder(original)
        for (index in redIndices) {
            builder.setSpan(
                ForegroundColorSpan(StatusBarClockPolicy.ONEPLUS_RED_COLOR),
                index,
                index + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return builder
    }

    private fun readOptions(): StatusBarClockOptions = try {
        val prefs = xposed.getRemotePreferences(PREFS_NAME)
        StatusBarClockOptions(
            displaySeconds = prefs.getBoolean(
                PreferenceKeys.STATUSBAR_DISPLAY_SECONDS.name,
                PreferenceKeys.STATUSBAR_DISPLAY_SECONDS.default
            ),
            redOneHour = prefs.getBoolean(
                PreferenceKeys.STATUSBAR_CLOCK_RED_ONE.name,
                PreferenceKeys.STATUSBAR_CLOCK_RED_ONE.default
            ),
            customClock = prefs.getBoolean(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK.default
            ),
            customFormat = prefs.getString(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_FORMAT.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_FORMAT.default
            ) ?: PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_FORMAT.default,
            textSizeEnabled = prefs.getBoolean(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_SIZE_ENABLED.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_SIZE_ENABLED.default
            ),
            textSizeSp = prefs.getFloat(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_SIZE.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_SIZE.default
            ),
            letterSpacingEnabled = prefs.getBoolean(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_LETTER_SPACING_ENABLED.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_LETTER_SPACING_ENABLED.default
            ),
            letterSpacing = prefs.getFloat(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_LETTER_SPACING.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_LETTER_SPACING.default
            ),
            textColorEnabled = prefs.getBoolean(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_COLOR_ENABLED.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_COLOR_ENABLED.default
            ),
            textColor = prefs.getInt(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_COLOR.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_COLOR.default
            ),
            textBold = prefs.getBoolean(
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_BOLD.name,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_BOLD.default
            )
        )
    } catch (_: Throwable) {
        logger.warn("Failed to read clock preferences, using defaults")
        StatusBarClockOptions()
    }

    companion object {
        private const val CLOCK_CLASS = "com.android.systemui.statusbar.policy.Clock"
    }
}
