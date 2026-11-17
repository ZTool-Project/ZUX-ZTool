package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextPaint
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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

    override fun getModuleName(): String = PreferenceKeys.SYSTEMUI_BATTERY_INDICATOR.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun isEnabled(): Boolean = readOptions().enabled

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (param.packageName != ScopeKeys.SYSTEM_UI.packageName) return
        hookSystemUIBattery(param.defaultClassLoader)
    }

    private fun readOptions(): BatteryIndicatorOptions = try {
        val prefs = xposed.getRemotePreferences(PREFS_NAME)
        val externalPercent = prefs.getBoolean(
            PreferenceKeys.SYSTEMUI_BATTERY_PERCENTAGE.name,
            PreferenceKeys.SYSTEMUI_BATTERY_PERCENTAGE.default
        )
        val chargingColor = prefs.getBoolean(
            PreferenceKeys.SYSTEMUI_BATTERY_CHARGING_COLOR.name,
            PreferenceKeys.SYSTEMUI_BATTERY_CHARGING_COLOR.default
        )
        val percentBold = prefs.getBoolean(
            PreferenceKeys.SYSTEMUI_BATTERY_PERCENT_BOLD.name,
            PreferenceKeys.SYSTEMUI_BATTERY_PERCENT_BOLD.default
        )
        val style = BatteryIndicatorStyle.fromPref(
            prefs.getString(
                PreferenceKeys.SYSTEMUI_BATTERY_INDICATOR_STYLE.name,
                PreferenceKeys.SYSTEMUI_BATTERY_INDICATOR_STYLE.default
            )
        )
        val segmentedClassicColor = prefs.getBoolean(
            PreferenceKeys.SYSTEMUI_BATTERY_SEGMENTED_CLASSIC_COLOR.name,
            PreferenceKeys.SYSTEMUI_BATTERY_SEGMENTED_CLASSIC_COLOR.default
        )
        val segmentedChargingStyle = SegmentedChargingStyle.fromPref(
            prefs.getString(
                PreferenceKeys.SYSTEMUI_BATTERY_SEGMENTED_CHARGING_STYLE.name,
                PreferenceKeys.SYSTEMUI_BATTERY_SEGMENTED_CHARGING_STYLE.default
            )
        )
        val explicitEnabled = if (prefs.contains(PreferenceKeys.SYSTEMUI_BATTERY_INDICATOR.name)) {
            prefs.getBoolean(
                PreferenceKeys.SYSTEMUI_BATTERY_INDICATOR.name,
                PreferenceKeys.SYSTEMUI_BATTERY_INDICATOR.default
            )
        } else {
            null
        }
        BatteryIndicatorOptions(
            enabled = BatteryIndicatorPolicy.resolveMasterEnabled(
                explicitEnabled = explicitEnabled,
                externalPercent = externalPercent,
                chargingColor = chargingColor,
                percentBold = percentBold,
                style = style
            ),
            externalPercent = externalPercent,
            chargingColor = chargingColor,
            percentBold = percentBold,
            style = style,
            segmentedClassicColor = segmentedClassicColor,
            segmentedChargingStyle = segmentedChargingStyle
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
                    (chain.thisObject as? LinearLayout)?.let { view ->
                        val options = readOptions()
                        if (options.enabled) {
                            syncPercentTypeface(host, view, options)
                            syncBatteryDrawable(host, view, options)
                            if (!usesExternalPercentPlacement(options)) {
                                syncInnerPercentStyle(host, view, options)
                                syncInnerPercentTextColor(host, view, options)
                            }
                        } else {
                            restoreStockDrawable(host, view)
                        }
                    }
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
                    if (options.enabled) {
                        syncPercentTypeface(host, view, options)
                        if (options.externalPercent) {
                            updateExternalPercentText(host, view)
                        } else if (!options.style.supportsInnerPercent) {
                            ensureExternalPercentLayout(host, view)
                            syncExternalPercentTextIfVisible(host, view)
                        } else {
                            syncInnerPercentStyle(host, view, options)
                            syncInnerPercentTextColor(host, view, options)
                        }
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
                    if (options.enabled) {
                        syncPercentTypeface(host, view, options)
                        if (usesExternalPercentPlacement(options)) {
                            ensureExternalPercentLayout(host, view)
                        } else {
                            syncInnerPercentStyle(host, view, options)
                        }
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

    private fun usesExternalPercentPlacement(options: BatteryIndicatorOptions): Boolean =
        options.externalPercent || !options.style.supportsInnerPercent

    private fun syncBatteryView(host: Host, view: LinearLayout) {
        val options = readOptions()
        if (!options.enabled) {
            restoreStockDrawable(host, view)
            return
        }
        val externalPlacement = usesExternalPercentPlacement(options)
        if (externalPlacement) {
            ensureExternalPercentLayout(host, view)
            if (options.externalPercent) {
                forceShowExternalPercentage(host, view)
            } else {
                syncExternalPercentTextIfVisible(host, view)
            }
        }
        syncPercentTypeface(host, view, options)
        syncBatteryDrawable(host, view, options)
        if (!externalPlacement) {
            syncInnerPercentStyle(host, view, options)
            syncInnerPercentTextColor(host, view, options)
        }
    }

    private fun syncPercentTypeface(
        host: Host,
        view: LinearLayout,
        options: BatteryIndicatorOptions
    ) {
        try {
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            val targetStyle = if (options.percentBold) Typeface.BOLD else Typeface.NORMAL
            val current = percentView.typeface
            val isCurrentlyBold = (current?.isBold == true) || percentView.paint.isFakeBoldText
            if (isCurrentlyBold != options.percentBold) {
                val targetTypeface = if (current != null) {
                    Typeface.create(current, targetStyle)
                } else {
                    Typeface.defaultFromStyle(targetStyle)
                }
                percentView.setTypeface(targetTypeface, targetStyle)
            }
        } catch (t: Throwable) {
            logger.error("Failed to sync battery percentage typeface", t)
        }
    }

    private fun syncInnerPercentStyle(
        host: Host,
        view: LinearLayout,
        options: BatteryIndicatorOptions
    ) {
        try {
            val container = host.containerField.get(view) as? FrameLayout ?: return
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            if (percentView.parent !== container) return
            val spec = if (isUsingStockFallback(host, view)) {
                BatteryIndicatorPolicy.STOCK_INNER_PERCENT_LAYOUT
            } else {
                BatteryIndicatorPolicy.resolveInnerPercentLayoutSpec(options)
            }
            applyInnerPercentLayoutSpec(percentView, view.context, spec)
        } catch (t: Throwable) {
            logger.error("Failed to sync inner battery percentage layout", t)
        }
    }

    private fun applyInnerPercentLayoutSpec(
        percentView: TextView,
        context: Context,
        spec: InnerPercentLayoutSpec
    ) {
        val targetPaddingEnd = if (spec.symmetricPadding) {
            0
        } else {
            dpToPx(context, STOCK_INNER_PADDING_END_DP)
        }
        if (percentView.paddingStart != 0 ||
            percentView.paddingTop != 0 ||
            percentView.paddingEnd != targetPaddingEnd ||
            percentView.paddingBottom != 0
        ) {
            percentView.setPaddingRelative(0, 0, targetPaddingEnd, 0)
        }
        if (percentView.includeFontPadding != spec.includeFontPadding) {
            percentView.includeFontPadding = spec.includeFontPadding
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            percentView.isFallbackLineSpacing != spec.includeFontPadding
        ) {
            percentView.isFallbackLineSpacing = spec.includeFontPadding
        }

        val density = context.resources.displayMetrics.density
        var targetPx = getOriginalTextSizePx(context) * spec.baseSizeScale
        val maxWidthDp = spec.maxWidthDp
        if (maxWidthDp != null) {
            val sampleText = percentView.text?.takeIf { it.isNotEmpty() }?.toString() ?: "100"
            val measurePaint = TextPaint(percentView.paint).apply {
                textSize = targetPx
            }
            val measuredWidth = measurePaint.measureText(sampleText)
            val maxWidthPx = maxWidthDp * density
            if (measuredWidth > maxWidthPx && measuredWidth > 0f) {
                targetPx = max(MIN_INNER_TEXT_SIZE_DP * density, targetPx * (maxWidthPx / measuredWidth))
            }
        }
        if (abs(percentView.textSize - targetPx) > TEXT_SIZE_EPSILON_PX) {
            percentView.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx)
        }

        val targetTranslationY = if (!spec.includeFontPadding) {
            val metricsPaint = TextPaint(percentView.paint).apply {
                textSize = targetPx
            }
            val fm = metricsPaint.fontMetrics
            val lineCenter = (fm.ascent + fm.descent) * 0.5f
            val bounds = Rect()
            metricsPaint.getTextBounds(VERTICAL_METRICS_SAMPLE, 0, VERTICAL_METRICS_SAMPLE.length, bounds)
            val visualCenter = if (!bounds.isEmpty) {
                (bounds.top + bounds.bottom) * 0.5f
            } else {
                lineCenter
            }
            spec.centerOffsetYDp * density + (lineCenter - visualCenter)
        } else {
            spec.centerOffsetYDp * density
        }
        if (abs(percentView.translationY - targetTranslationY) > TRANSLATION_EPSILON_PX) {
            percentView.translationY = targetTranslationY
        }
    }

    private fun syncInnerPercentTextColor(
        host: Host,
        view: LinearLayout,
        options: BatteryIndicatorOptions
    ) {
        val overrideColor = BatteryIndicatorPolicy.resolveInnerPercentTextColor(options) ?: return
        try {
            if (isUsingStockFallback(host, view)) return
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            if (percentView.currentTextColor != overrideColor) {
                percentView.setTextColor(overrideColor)
            }
        } catch (t: Throwable) {
            logger.error("Failed to sync inner battery percentage text color", t)
        }
    }

    private fun isUsingStockFallback(host: Host, view: LinearLayout): Boolean {
        val level = host.levelField.getInt(view)
        val rawCharging = host.chargingField.getBoolean(view)
        val bypassCharging = (host.bypassChargingMethod?.invoke(view) as? Boolean) ?: false
        val virtualBattery = (host.virtualBatteryMethod?.invoke(view) as? Boolean) ?: false
        val stateUnknown = host.batteryStateUnknownField?.getBoolean(view) ?: false
        val effectiveCharging = rawCharging && !bypassCharging && !virtualBattery
        return BatteryIndicatorPolicy.shouldUseStockDrawable(
            level = level,
            isCharging = effectiveCharging,
            isStateUnknown = stateUnknown,
            isVirtualBattery = virtualBattery
        )
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
            percentView.setPaddingRelative(0, 0, 0, 0)
            if (!percentView.includeFontPadding) {
                percentView.includeFontPadding = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !percentView.isFallbackLineSpacing) {
                percentView.isFallbackLineSpacing = true
            }
            if (percentView.translationY != 0f) {
                percentView.translationY = 0f
            }
            val originalSp = getOriginalTextSizeSp(view.context)
            percentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, originalSp + EXTERNAL_TEXT_SIZE_BONUS_SP)
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

    private fun syncExternalPercentTextIfVisible(host: Host, view: LinearLayout) {
        try {
            val percentView = host.percentViewField.get(view) as? TextView ?: return
            if (percentView.visibility == View.VISIBLE) {
                updateExternalPercentText(host, view)
            }
        } catch (t: Throwable) {
            logger.error("Failed to sync external battery percentage text", t)
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
        if (!options.enabled) {
            restoreStockDrawable(host, view)
            return
        }
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
            val oldWidth = ztoolDrawable.intrinsicWidth
            ztoolDrawable.update(state, options)
            if (iconView.drawable !== ztoolDrawable) {
                iconView.setImageDrawable(ztoolDrawable)
            } else if (ztoolDrawable.intrinsicWidth != oldWidth) {
                // ImageView caches mDrawableWidth until setImageDrawable re-binds a new/null drawable.
                iconView.setImageDrawable(null)
                iconView.setImageDrawable(ztoolDrawable)
                iconView.parent?.requestLayout()
                view.requestLayout()
            }
        } catch (t: Throwable) {
            logger.error("Failed to sync custom battery drawable", t)
        }
    }

    private fun restoreStockDrawable(host: Host, view: LinearLayout) {
        try {
            val iconView = host.iconViewField.get(view) as? ImageView ?: return
            if (iconView.drawable is ZToolBatteryDrawable) {
                stockDrawables[iconView]?.let { iconView.setImageDrawable(it) }
                iconView.parent?.requestLayout()
                view.requestLayout()
            }
            val container = host.containerField.get(view) as? FrameLayout
            val percentView = host.percentViewField.get(view) as? TextView
            if (container != null && percentView != null && percentView.parent === container) {
                applyInnerPercentLayoutSpec(
                    percentView,
                    view.context,
                    BatteryIndicatorPolicy.STOCK_INNER_PERCENT_LAYOUT
                )
            }
        } catch (t: Throwable) {
            logger.error("Failed to restore stock battery drawable", t)
        }
    }

    private fun getOriginalTextSizePx(context: Context): Float {
        return try {
            val resId = context.resources.getIdentifier(
                "status_bar_battery_text_size",
                "dimen",
                ScopeKeys.SYSTEM_UI.packageName
            )
            if (resId != 0) {
                context.resources.getDimension(resId)
            } else {
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    DEFAULT_TEXT_SIZE_SP,
                    context.resources.displayMetrics
                )
            }
        } catch (_: Throwable) {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                DEFAULT_TEXT_SIZE_SP,
                context.resources.displayMetrics
            )
        }
    }

    private fun getOriginalTextSizeSp(context: Context): Float {
        val px = getOriginalTextSizePx(context)
        val oneSpPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            1f,
            context.resources.displayMetrics
        )
        return px / oneSpPx
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
        private const val STOCK_INNER_PADDING_END_DP = 2f
        private const val MIN_INNER_TEXT_SIZE_DP = 5f
        private const val TEXT_SIZE_EPSILON_PX = 0.25f
        private const val TRANSLATION_EPSILON_PX = 0.25f
        private const val VERTICAL_METRICS_SAMPLE = "80"
    }
}

private fun dpToPx(context: Context, dp: Float): Int =
    (dp * context.resources.displayMetrics.density).roundToInt()
