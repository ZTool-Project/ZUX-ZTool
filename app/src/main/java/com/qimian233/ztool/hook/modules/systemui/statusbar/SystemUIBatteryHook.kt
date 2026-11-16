package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/** Hooks SystemUI status bar battery indicator rendering and percentage layout. */
// See docs/research/systemui_battery_indicator.md
@SuppressLint("PrivateApi", "DiscouragedApi")
class SystemUIBatteryHook : AppHookModule() {

    private data class Host(
        val containerField: Field,
        val iconViewField: Field,
        val percentViewField: Field,
        val levelField: Field,
        val chargingField: Field,
        val chargeRateField: Field?,
        val pluggedInField: Field?,
        val isPowerSaveField: Field?,
        val isBlackThemeField: Field?,
        val batteryStateUnknownField: Field?,
        val batteryHealthField: Field?,
        val globalBypassField: Field?,
        val protectedMinLevelField: Field?,
        val isShowPercentMethod: Method?,
        val isPowerSaveMethod: Method?,
        val bypassChargingMethod: Method?,
        val virtualBatteryMethod: Method?
    )

    private val customDrawables =
        Collections.synchronizedMap(WeakHashMap<ImageView, ZToolBatteryDrawable>())
    private val stockDrawables =
        Collections.synchronizedMap(WeakHashMap<ImageView, Drawable>())

    override fun getModuleName(): String = PreferenceKeys.SYSTEMUI_BATTERY_PERCENTAGE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun isEnabled(): Boolean = readOptions().enabled

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (param.packageName != ScopeKeys.SYSTEM_UI.packageName) return
        hookSystemUIBattery(param.defaultClassLoader)
    }

    private fun readOptions(): BatteryIndicatorOptions = try {
        val prefs = xposed.getRemotePreferences(PREFS_NAME)
        BatteryIndicatorOptions(
            externalPercent = prefs.getBoolean(
                PreferenceKeys.SYSTEMUI_BATTERY_PERCENTAGE.name,
                PreferenceKeys.SYSTEMUI_BATTERY_PERCENTAGE.default
            ),
            chargingColor = prefs.getBoolean(
                PreferenceKeys.SYSTEMUI_BATTERY_CHARGING_COLOR.name,
                PreferenceKeys.SYSTEMUI_BATTERY_CHARGING_COLOR.default
            )
        )
    } catch (_: Throwable) {
        logger.warn("Failed to read battery indicator preferences, using defaults")
        BatteryIndicatorOptions()
    }

    private fun hookSystemUIBattery(classLoader: ClassLoader) {
        try {
            val viewClass = classLoader.loadClass(BATTERY_METER_VIEW_CLASS)
            val host = resolveHost(viewClass)

            val ctor = viewClass.getDeclaredConstructor(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType
            )
            hookWithId(ctor, "battery_ctor") { chain ->
                chain.proceed()
                (chain.thisObject as? LinearLayout)?.let { syncBatteryView(host, it) }
                null
            }

            hookWithId(
                findMethod(viewClass, "updateBatteryIcon", Boolean::class.javaPrimitiveType!!),
                "battery_update_icon"
            ) { chain ->
                val result = chain.proceed()
                (chain.thisObject as? LinearLayout)?.let { syncBatteryDrawable(host, it, readOptions()) }
                result
            }

            runCatching {
                findMethod(viewClass, "updateBatteryIconOTG", Boolean::class.javaPrimitiveType!!)
            }.getOrNull()?.let { method ->
                hookWithId(method, "battery_update_icon_otg") { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? LinearLayout)?.let { syncBatteryDrawable(host, it, readOptions()) }
                    result
                }
            }

            runCatching {
                findMethod(
                    viewClass,
                    "onBatteryLevelDualChanged",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!
                )
            }.getOrNull()?.let { method ->
                hookWithId(method, "battery_level_dual") { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? LinearLayout)?.let { syncBatteryView(host, it) }
                    result
                }
            }

            runCatching {
                findMethod(
                    viewClass,
                    "onDarkChanged",
                    ArrayList::class.java,
                    Float::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!
                )
            }.getOrNull()?.let { method ->
                hookWithId(method, "battery_dark_changed") { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? LinearLayout)?.let { syncBatteryDrawable(host, it, readOptions()) }
                    result
                }
            }

            runCatching {
                findMethod(viewClass, "onPowerSaveChanged", Boolean::class.javaPrimitiveType!!)
            }.getOrNull()?.let { method ->
                hookWithId(method, "battery_power_save") { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? LinearLayout)?.let { syncBatteryDrawable(host, it, readOptions()) }
                    result
                }
            }

            hookWithId(
                findMethod(viewClass, "updateShowPercent"),
                "battery_update_show_percent"
            ) { chain ->
                val result = chain.proceed()
                (chain.thisObject as? LinearLayout)?.let { syncBatteryView(host, it) }
                result
            }

            hookWithId(
                findMethod(viewClass, "updatePercentText"),
                "battery_update_percent_text"
            ) { chain ->
                val result = chain.proceed()
                (chain.thisObject as? LinearLayout)?.let { view ->
                    val options = readOptions()
                    if (options.externalPercent) {
                        updateExternalPercentText(host, view)
                    }
                }
                result
            }

            hookWithId(
                findMethod(viewClass, "scaleBatteryMeterViews"),
                "battery_scale_views"
            ) { chain ->
                val result = chain.proceed()
                (chain.thisObject as? LinearLayout)?.let { view ->
                    val options = readOptions()
                    if (options.externalPercent) {
                        applyExternalPercentStyle(host, view)
                    }
                }
                result
            }

            logger.info("SystemUI battery indicator hook loaded")
        } catch (t: Throwable) {
            logger.error("Failed to load SystemUI battery indicator hook", t)
        }
    }

    private fun resolveHost(viewClass: Class<*>): Host = Host(
        containerField = findField(viewClass, "mBatteryPercentViewContainer"),
        iconViewField = findField(viewClass, "mBatteryIconView"),
        percentViewField = findField(viewClass, "mBatteryPercentView"),
        levelField = findField(viewClass, "mLevel"),
        chargingField = findField(viewClass, "mCharging"),
        chargeRateField = runCatching { findField(viewClass, "mChargeRate") }.getOrNull(),
        pluggedInField = runCatching { findField(viewClass, "mPluggedIn") }.getOrNull(),
        isPowerSaveField = runCatching { findField(viewClass, "mIsPowerSave") }.getOrNull(),
        isBlackThemeField = runCatching { findField(viewClass, "mIsBlackTheme") }.getOrNull(),
        batteryStateUnknownField = runCatching { findField(viewClass, "mBatteryStateUnknown") }.getOrNull(),
        batteryHealthField = runCatching { findField(viewClass, "batteryHealth") }.getOrNull(),
        globalBypassField = runCatching { findField(viewClass, "mGlobalBypassChargingStatus") }.getOrNull(),
        protectedMinLevelField = runCatching { findField(viewClass, "BATTERY_PROTECTED_MIN_LEVEL") }.getOrNull(),
        isShowPercentMethod = runCatching { findMethod(viewClass, "isShowPercent") }.getOrNull(),
        isPowerSaveMethod = runCatching { findMethod(viewClass, "isPowerSave") }.getOrNull(),
        bypassChargingMethod = runCatching { findMethod(viewClass, "getBypassChargingStatus") }.getOrNull(),
        virtualBatteryMethod = runCatching { findMethod(viewClass, "getVirtualBatteryStatus") }.getOrNull()
    )

    private fun syncBatteryView(host: Host, view: LinearLayout) {
        val options = readOptions()
        if (!options.enabled) return
        if (options.externalPercent) {
            ensureExternalPercentLayout(host, view)
            forceShowExternalPercentage(host, view)
        }
        syncBatteryDrawable(host, view, options)
    }

    private fun ensureExternalPercentLayout(host: Host, view: LinearLayout) {
        try {
            val container = host.containerField.get(view) as? FrameLayout ?: return
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            if (percentView.parent === container) {
                container.removeView(percentView)
                val layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = getQsBatteryPadding(view.context)
                }
                view.addView(percentView, layoutParams)
            }
            applyExternalPercentStyle(host, view)
        } catch (t: Throwable) {
            logger.error("Failed to move battery percentage view outside icon", t)
        }
    }

    private fun applyExternalPercentStyle(host: Host, view: LinearLayout) {
        try {
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            percentView.layoutParams?.let { lp ->
                if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
                    lp.height != ViewGroup.LayoutParams.WRAP_CONTENT
                ) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    percentView.layoutParams = lp
                }
            }
            val originalSp = getOriginalTextSizeSp(view.context)
            percentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, originalSp + EXTERNAL_TEXT_SIZE_BONUS_SP)
            percentView.setTypeface(percentView.typeface, Typeface.BOLD)
        } catch (t: Throwable) {
            logger.error("Failed to adjust external battery percentage text style", t)
        }
    }

    private fun forceShowExternalPercentage(host: Host, view: LinearLayout) {
        try {
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            if (percentView.visibility != View.VISIBLE) {
                percentView.visibility = View.VISIBLE
            }
            updateExternalPercentText(host, view)
        } catch (t: Throwable) {
            logger.error("Failed to force-show external battery percentage", t)
        }
    }

    private fun updateExternalPercentText(host: Host, view: LinearLayout) {
        try {
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            val level = host.levelField.getInt(view)
            percentView.text = String.format(Locale.US, "%d%%", level)
        } catch (t: Throwable) {
            logger.error("Failed to update external battery percentage text", t)
        }
    }

    private fun syncBatteryDrawable(
        host: Host,
        view: LinearLayout,
        options: BatteryIndicatorOptions
    ) {
        if (!options.enabled) return
        try {
            val iconView = host.iconViewField.get(view) as? ImageView ?: return
            val currentDrawable = iconView.drawable
            if (currentDrawable != null && currentDrawable !is ZToolBatteryDrawable) {
                stockDrawables[iconView] = currentDrawable
            }

            val level = host.levelField.getInt(view)
            val rawCharging = host.chargingField.getBoolean(view)
            val bypassCharging = (host.bypassChargingMethod?.invoke(view) as? Boolean) ?: false
            val virtualBattery = (host.virtualBatteryMethod?.invoke(view) as? Boolean) ?: false
            val stateUnknown = host.batteryStateUnknownField?.getBoolean(view) ?: false
            val effectiveCharging = rawCharging && !bypassCharging && !virtualBattery

            if (BatteryIndicatorPolicy.shouldUseStockDrawable(
                    level = level,
                    isCharging = effectiveCharging,
                    isStateUnknown = stateUnknown,
                    isVirtualBattery = virtualBattery
                )
            ) {
                if (currentDrawable is ZToolBatteryDrawable) {
                    stockDrawables[iconView]?.let { iconView.setImageDrawable(it) }
                }
                return
            }

            val chargeRate = host.chargeRateField?.getInt(view) ?: 0
            val pluggedIn = host.pluggedInField?.getBoolean(view) ?: rawCharging
            val isPowerSave = (host.isPowerSaveMethod?.invoke(view) as? Boolean)
                ?: (host.isPowerSaveField?.getBoolean(view) ?: false)
            val isBlackTheme = host.isBlackThemeField?.getBoolean(view) ?: false
            val batteryHealth = host.batteryHealthField?.getInt(view) ?: 0
            val globalBypass = host.globalBypassField?.getBoolean(view) ?: false
            val protectedMinLevel =
                host.protectedMinLevelField?.getInt(view) ?: DEFAULT_PROTECTED_MIN_LEVEL
            val isHealthFrameActive = (batteryHealth == 1 || globalBypass) &&
                pluggedIn &&
                !bypassCharging &&
                level >= protectedMinLevel &&
                !isPowerSave
            val systemShowPercent = (host.isShowPercentMethod?.invoke(view) as? Boolean) ?: false

            val state = BatteryIndicatorState(
                level = level,
                isCharging = effectiveCharging,
                chargeRate = chargeRate,
                isPowerSave = isPowerSave,
                isBlackTheme = isBlackTheme,
                isHealthFrameActive = isHealthFrameActive,
                systemShowPercent = systemShowPercent
            )

            val density = view.resources.displayMetrics.density
            val ztoolDrawable = customDrawables.getOrPut(iconView) {
                ZToolBatteryDrawable(density = density, state = state, options = options)
            }
            ztoolDrawable.update(state, options)
            if (iconView.drawable !== ztoolDrawable) {
                iconView.setImageDrawable(ztoolDrawable)
            }
        } catch (t: Throwable) {
            logger.error("Failed to sync custom battery drawable", t)
        }
    }

    private fun getOriginalTextSizeSp(context: Context): Float {
        return try {
            val resId = context.resources.getIdentifier(
                "status_bar_battery_text_size",
                "dimen",
                ScopeKeys.SYSTEM_UI.packageName
            )
            if (resId != 0) {
                val px = context.resources.getDimension(resId)
                val oneSpPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    1f,
                    context.resources.displayMetrics
                )
                px / oneSpPx
            } else {
                DEFAULT_TEXT_SIZE_SP
            }
        } catch (_: Throwable) {
            DEFAULT_TEXT_SIZE_SP
        }
    }

    private fun getQsBatteryPadding(context: Context): Int {
        return try {
            val resId = context.resources.getIdentifier(
                "qs_battery_padding",
                "dimen",
                ScopeKeys.SYSTEM_UI.packageName
            )
            if (resId != 0) context.resources.getDimensionPixelOffset(resId) else DEFAULT_PADDING_PX
        } catch (_: Throwable) {
            DEFAULT_PADDING_PX
        }
    }

    companion object {
        private const val PREFS_NAME = "xposed_module_config"
        private const val BATTERY_METER_VIEW_CLASS = "com.android.systemui.battery.BatteryMeterView"
        private const val DEFAULT_PROTECTED_MIN_LEVEL = 40
        private const val DEFAULT_TEXT_SIZE_SP = 12f
        private const val EXTERNAL_TEXT_SIZE_BONUS_SP = 3f
        private const val DEFAULT_PADDING_PX = 8
    }
}
