package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.app.Notification
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.StatusBarNotification
import com.qimian233.ztool.data.keys.BoolKey
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.Locale

/** Filters SystemUI status bar activity chips and suppresses filtered L3 cards. */
// See docs/research/systemui_dynamic_bar_chips.md
class StatusBarChipsFilterHook : AppHookModule() {

    private data class FilterConfig(
        val enabled: Boolean = false,
        val hideNotification: Boolean = false,
        val hideMedia: Boolean = false,
        val hideCharging: Boolean = false,
        val hideFlashlight: Boolean = false,
        val hideBluetoothHeadset: Boolean = false,
        val hideWelcomeBack: Boolean = false,
        val hideCall: Boolean = false,
        val hideScreenRecord: Boolean = false,
        val hideShareToApp: Boolean = false,
        val hideCastToOtherDevice: Boolean = false
    ) {
        fun hasAnyCustomizationFilter(): Boolean =
            hideMedia || hideCharging || hideFlashlight || hideBluetoothHeadset || hideWelcomeBack

        fun hasAnyFilter(): Boolean =
            hideNotification ||
                hideCall ||
                hideScreenRecord ||
                hideShareToApp ||
                hideCastToOtherDevice ||
                hasAnyCustomizationFilter()
    }

    override fun getModuleName(): String = PreferenceKeys.STATUSBAR_CHIPS_FILTER.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (param.packageName != ScopeKeys.SYSTEM_UI.packageName) return
        val classLoader = param.defaultClassLoader
        hookChipBundle(classLoader)
        hookNotificationL3(classLoader)
        hookCustomizationL3(classLoader)
        hookMediaL3(classLoader)
    }

    private fun readConfig(): FilterConfig = try {
        val prefs = remotePreferences
        FilterConfig(
            enabled = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_FILTER),
            hideNotification = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_NOTIFICATION),
            hideMedia = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_MEDIA),
            hideCharging = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_CHARGING),
            hideFlashlight = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_FLASHLIGHT),
            hideBluetoothHeadset = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_BLUETOOTH_HEADSET),
            hideWelcomeBack = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_WELCOME_BACK),
            hideCall = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_CALL),
            hideScreenRecord = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_SCREEN_RECORD),
            hideShareToApp = prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_SHARE_TO_APP),
            hideCastToOtherDevice =
                prefs.readFlag(PreferenceKeys.STATUSBAR_CHIPS_HIDE_CAST_TO_OTHER_DEVICE)
        )
    } catch (_: Throwable) {
        FilterConfig()
    }

    private fun SharedPreferences.readFlag(key: BoolKey): Boolean =
        getBoolean(key.name, key.default)

    private fun hookChipBundle(classLoader: ClassLoader) {
        try {
            val chipBundleClass = classLoader.loadClass(CHIP_BUNDLE_CLASS)
            val chipBundleCtor = chipBundleClass.declaredConstructors.firstOrNull { ctor ->
                val params = ctor.parameterTypes
                params.size == CHIP_BUNDLE_ARITY &&
                    List::class.java.isAssignableFrom(params[INDEX_NOTIFS]) &&
                    List::class.java.isAssignableFrom(params[INDEX_CUSTOMIZATION])
            } ?: run {
                logger.warn("OngoingActivityChipsViewModel\$ChipBundle 6-arg constructor not found")
                return
            }

            val inactiveClass = classLoader.loadClass(CHIP_INACTIVE_CLASS)
            val createInactive = resolveInactiveFactory(inactiveClass) ?: run {
                logger.warn("OngoingActivityChipModel\$Inactive constructor not found")
                return
            }
            val activeClass = classLoader.loadClass(CHIP_ACTIVE_CLASS)
            val keyField = findFieldOrNull(activeClass, "key")

            hookWithId(chipBundleCtor, "statusbar_chips_filter_bundle") { chain ->
                val config = readConfig()
                if (!config.enabled || !config.hasAnyFilter()) return@hookWithId chain.proceed()

                val args = chain.args.toMutableList()
                var modified = false

                for ((index, isFiltered) in SINGLE_SLOT_FILTERS) {
                    if (!isFiltered(config)) continue
                    val current = args[index]
                    if (current != null && activeClass.isInstance(current)) {
                        val inactive = createInactive()
                        if (inactive != null) {
                            args[index] = inactive
                            modified = true
                        }
                    }
                }

                if (config.hideNotification) {
                    val currentNotifs = args[INDEX_NOTIFS] as? List<*>
                    if (!currentNotifs.isNullOrEmpty()) {
                        args[INDEX_NOTIFS] = Collections.emptyList<Any>()
                        modified = true
                    }
                }

                if (config.hasAnyCustomizationFilter()) {
                    val currentCustom = args[INDEX_CUSTOMIZATION] as? List<*>
                    if (!currentCustom.isNullOrEmpty()) {
                        val filtered = ArrayList<Any?>(currentCustom.size)
                        for (item in currentCustom) {
                            val key = if (item != null && activeClass.isInstance(item)) {
                                keyField?.get(item) as? String
                            } else {
                                null
                            }
                            if (!shouldHideCustomizationKey(key, config)) {
                                filtered.add(item)
                            }
                        }
                        if (filtered.size != currentCustom.size) {
                            args[INDEX_CUSTOMIZATION] = filtered
                            modified = true
                        }
                    }
                }

                if (modified) chain.proceed(args.toTypedArray()) else chain.proceed()
            }
            logger.info("StatusBar ChipBundle filter hook installed")
        } catch (t: Throwable) {
            logger.warn("StatusBar ChipBundle filter hook skipped: ${t.message}")
        }
    }

    private fun resolveInactiveFactory(inactiveClass: Class<*>): (() -> Any?)? {
        runCatching {
            inactiveClass.getDeclaredConstructor(Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        }.getOrNull()?.let { ctor ->
            // Bitmask 3 enables shouldAnimate = true while leaving transitionManager null.
            return { runCatching { ctor.newInstance(INACTIVE_DEFAULT_FLAGS) }.getOrNull() }
        }

        runCatching {
            inactiveClass.getDeclaredConstructor(
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()?.let { ctor ->
            return { runCatching { ctor.newInstance(true, false) }.getOrNull() }
        }

        return runCatching {
            inactiveClass.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull()?.let { ctor ->
            { runCatching { ctor.newInstance() }.getOrNull() }
        }
    }

    private fun shouldHideCustomizationKey(key: String?, config: FilterConfig): Boolean {
        if (key == null) return false
        return when (key) {
            CUSTOM_KEY_MEDIA -> config.hideMedia
            CUSTOM_KEY_CHARGING -> config.hideCharging
            CUSTOM_KEY_FLASHLIGHT -> config.hideFlashlight
            CUSTOM_KEY_WELCOME_BACK, CUSTOM_KEY_WELCOME_BACK_FALLBACK -> config.hideWelcomeBack
            else -> {
                if (!config.hideBluetoothHeadset || !key.startsWith(CUSTOM_KEY_PREFIX)) {
                    false
                } else {
                    val suffix = key.removePrefix(CUSTOM_KEY_PREFIX).lowercase(Locale.ROOT)
                    suffix == "bluetooth" ||
                        suffix.startsWith("bluetooth_") ||
                        suffix.contains("headset") ||
                        suffix == "bt"
                }
            }
        }
    }

    private fun hookNotificationL3(classLoader: ClassLoader) {
        val entryClass = runCatching { classLoader.loadClass(NOTIFICATION_ENTRY_CLASS) }.getOrNull()
        val sbnField = findFieldOrNull(entryClass, "mSbn")
        val hostUnit = runCatching {
            classLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)
        }.getOrNull()

        try {
            val interactorClass = classLoader.loadClass(NOTIF_CHIPS_INTERACTOR_CLASS)
            val onShowMethod = interactorClass.declaredMethods.firstOrNull {
                it.name == "onDynamicBarL3Show" && it.parameterTypes.size == 2
            }
            if (onShowMethod != null && hostUnit != null) {
                hookWithId(onShowMethod, "statusbar_chips_filter_notif_l3_show") { chain ->
                    val config = readConfig()
                    if (!config.enabled) return@hookWithId chain.proceed()
                    if (shouldSuppressNotificationEntry(chain.args.getOrNull(0), sbnField, config)) {
                        return@hookWithId hostUnit
                    }
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            logger.warn("Notification L3 show hook skipped: ${t.message}")
        }

        try {
            val l3WindowClass = classLoader.loadClass(DYNAMIC_BAR_L3_WINDOW_CLASS)
            // Parameters are R8 synthetic lambda classes, so match by name and arity.
            val getOrCreateNotif = l3WindowClass.declaredMethods.firstOrNull {
                it.name == "getOrCreateNotificationL3" && it.parameterTypes.size == 2
            } ?: run {
                logger.warn("DynamicBarL3Window.getOrCreateNotificationL3 not found")
                return
            }

            val interactorField = findFieldOrNull(l3WindowClass, "statusBarNotificationChipsInteractor")
            val currentEntryField = findFieldOrNull(
                runCatching { classLoader.loadClass(NOTIF_CHIPS_INTERACTOR_CLASS) }.getOrNull(),
                "_currentL3Entry"
            )
            val stateFlowGetValue = resolveStateFlowGetValue(classLoader, currentEntryField?.type)

            hookWithId(getOrCreateNotif, "statusbar_chips_filter_notif_l3_window") { chain ->
                val config = readConfig()
                if (!config.enabled || (!config.hideNotification && !config.hideCall)) {
                    return@hookWithId chain.proceed()
                }
                if (config.hideNotification && config.hideCall) {
                    return@hookWithId null
                }
                val entry = readCurrentL3Entry(
                    window = chain.thisObject,
                    interactorField = interactorField,
                    currentEntryField = currentEntryField,
                    stateFlowGetValue = stateFlowGetValue
                )
                val shouldSuppress = if (entry == null) {
                    config.hideNotification
                } else {
                    shouldSuppressNotificationEntry(entry, sbnField, config)
                }
                if (shouldSuppress) null else chain.proceed()
            }
        } catch (t: Throwable) {
            logger.warn("Notification L3 window hook skipped: ${t.message}")
        }
    }

    private fun readCurrentL3Entry(
        window: Any?,
        interactorField: Field?,
        currentEntryField: Field?,
        stateFlowGetValue: Method?
    ): Any? = runCatching {
        val interactor = interactorField?.get(window) ?: return@runCatching null
        val stateFlow = currentEntryField?.get(interactor) ?: return@runCatching null
        stateFlowGetValue?.invoke(stateFlow)
    }.getOrNull()

    private fun resolveStateFlowGetValue(classLoader: ClassLoader, fieldType: Class<*>?): Method? {
        runCatching {
            return classLoader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue")
        }
        return runCatching { fieldType?.getMethod("getValue") }.getOrNull()
    }

    private fun shouldSuppressNotificationEntry(
        entry: Any?,
        sbnField: Field?,
        config: FilterConfig
    ): Boolean {
        val isCall = isCallNotificationEntry(entry, sbnField)
        return (isCall && config.hideCall) || (!isCall && config.hideNotification)
    }

    private fun isCallNotificationEntry(entry: Any?, sbnField: Field?): Boolean {
        if (entry == null || sbnField == null) return false
        val sbn = runCatching { sbnField.get(entry) as? StatusBarNotification }.getOrNull()
            ?: return false
        val notification = sbn.notification ?: return false
        val template = notification.extras?.getString(Notification.EXTRA_TEMPLATE)
        val callStyleName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Notification.CallStyle::class.java.name
        } else {
            CALL_STYLE_TEMPLATE
        }
        return template == callStyleName || template == CALL_STYLE_TEMPLATE
    }

    private fun hookCustomizationL3(classLoader: ClassLoader) {
        try {
            val l3WindowClass = classLoader.loadClass(DYNAMIC_BAR_L3_WINDOW_CLASS)
            // Parameters are R8 synthetic lambda classes, so match by name and arity.
            val getOrCreateCustom = l3WindowClass.declaredMethods.firstOrNull {
                it.name == "getOrCreateCustomizationL3" && it.parameterTypes.size == 2
            } ?: run {
                logger.warn("DynamicBarL3Window.getOrCreateCustomizationL3 not found")
                return
            }

            hookWithId(getOrCreateCustom, "statusbar_chips_filter_custom_l3_window") { chain ->
                val config = readConfig()
                if (config.enabled && config.hideFlashlight) {
                    return@hookWithId null
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.warn("Customization L3 hook skipped: ${t.message}")
        }
    }

    private fun hookMediaL3(classLoader: ClassLoader) {
        try {
            val mediaInteractorClass = classLoader.loadClass(MEDIA_CHIP_INTERACTOR_CLASS)
            val updateChipState = mediaInteractorClass.declaredMethods.firstOrNull {
                it.name == "updateChipState" && it.parameterTypes.isEmpty()
            }
            if (updateChipState != null) {
                val customInteractorField =
                    findFieldOrNull(mediaInteractorClass, "customizationChipInteractor")
                val removeChipMethod = runCatching {
                    classLoader.loadClass(CUSTOMIZATION_CHIP_INTERACTOR_CLASS)
                        .getDeclaredMethod("removeChip", String::class.java)
                        .apply { isAccessible = true }
                }.getOrNull()

                hookWithId(updateChipState, "statusbar_chips_filter_media_update") { chain ->
                    val config = readConfig()
                    if (config.enabled && config.hideMedia) {
                        runCatching {
                            val customInteractor = customInteractorField?.get(chain.thisObject)
                            if (customInteractor != null) {
                                removeChipMethod?.invoke(customInteractor, "media")
                            }
                        }
                        return@hookWithId null
                    }
                    chain.proceed()
                }
            } else {
                logger.warn("MediaChipInteractor.updateChipState not found")
            }
        } catch (t: Throwable) {
            logger.warn("Media L3 interactor hook skipped: ${t.message}")
        }

        try {
            val viewStateClass = classLoader.loadClass(MEDIA_L3_VIEW_STATE_CLASS)
            val viewStateCtor = viewStateClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType)
            hookWithId(viewStateCtor, "statusbar_chips_filter_media_l3_state") { chain ->
                val config = readConfig()
                if (config.enabled && config.hideMedia && chain.args.getOrNull(0) == true) {
                    return@hookWithId chain.proceed(arrayOf(false))
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.warn("Media L3 viewState hook skipped: ${t.message}")
        }
    }

    private fun findFieldOrNull(clazz: Class<*>?, name: String): Field? =
        if (clazz == null) null else runCatching { findField(clazz, name) }.getOrNull()

    companion object {
        private const val CHIP_BUNDLE_CLASS =
            "com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModel\$ChipBundle"
        private const val CHIP_ACTIVE_CLASS =
            "com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel\$Active"
        private const val CHIP_INACTIVE_CLASS =
            "com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel\$Inactive"
        private const val DYNAMIC_BAR_L3_WINDOW_CLASS =
            "com.android.systemui.statusbar.chips.DynamicBarL3Window"
        private const val NOTIF_CHIPS_INTERACTOR_CLASS =
            "com.android.systemui.statusbar.chips.notification.domain.interactor.StatusBarNotificationChipsInteractor"
        private const val NOTIFICATION_ENTRY_CLASS =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry"
        private const val MEDIA_CHIP_INTERACTOR_CLASS =
            "com.android.systemui.statusbar.chips.media.domain.interactor.MediaChipInteractor"
        private const val MEDIA_L3_VIEW_STATE_CLASS =
            "com.android.systemui.statusbar.chips.media.domain.interactor.MediaChipInteractor\$MediaL3ViewState"
        private const val CUSTOMIZATION_CHIP_INTERACTOR_CLASS =
            "com.android.systemui.statusbar.chips.customization.domain.interactor.CustomizationChipInteractor"

        private const val CHIP_BUNDLE_ARITY = 6
        private const val INDEX_SCREEN_RECORD = 0
        private const val INDEX_SHARE_TO_APP = 1
        private const val INDEX_CAST_TO_OTHER_DEVICE = 2
        private const val INDEX_CALL = 3
        private const val INDEX_NOTIFS = 4
        private const val INDEX_CUSTOMIZATION = 5
        private const val INACTIVE_DEFAULT_FLAGS = 3

        private const val CUSTOM_KEY_PREFIX = "CustomizationChip:"
        private const val CUSTOM_KEY_MEDIA = "CustomizationChip:media"
        private const val CUSTOM_KEY_CHARGING = "CustomizationChip:charging"
        private const val CUSTOM_KEY_FLASHLIGHT = "CustomizationChip:flashlight"
        private const val CUSTOM_KEY_WELCOME_BACK = "CustomizationChip:welcome_back"
        private const val CUSTOM_KEY_WELCOME_BACK_FALLBACK = "CustomizationChip:default"
        private const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"

        private val SINGLE_SLOT_FILTERS: List<Pair<Int, (FilterConfig) -> Boolean>> = listOf(
            INDEX_SCREEN_RECORD to { it.hideScreenRecord },
            INDEX_SHARE_TO_APP to { it.hideShareToApp },
            INDEX_CAST_TO_OTHER_DEVICE to { it.hideCastToOtherDevice },
            INDEX_CALL to { it.hideCall }
        )
    }
}
