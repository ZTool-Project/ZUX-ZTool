package com.qimian233.ztool.dexindex.base

import com.qimian233.ztool.data.keys.PreferenceKeys

/** Constants for the offline DexKit index. */
object DexIndexConstants {

    // v7 adds LauncherFreeformEntryHook keys for R8-obfuscated A17 ZUI Launcher.
    // v6 rebuilds existing SystemUI indexes for ordered paths and typed callback matching.
    // See docs/research/systemui_charging_animation_android17.md for earlier versions.
    const val SCHEMA_VERSION = 7

    const val JSON_SCHEMA_VERSION = "schemaVersion"
    const val JSON_GENERATED_AT = "generatedAt"

    const val JSON_SYSTEM_FINGERPRINT = "systemFingerprint"
    const val JSON_APK = "apk"
    const val JSON_PATH = "path"
    const val JSON_LAST_UPDATE_TIME = "lastUpdateTime"
    const val JSON_SIGNATURE_HASH = "signatureHash"
    const val JSON_MODULES = "modules"

    /** Builds the index file name for a scope. */
    fun fileName(scopePackage: String): String = "$scopePackage.json"

    /** Module keys matching each Hook's module name. */
    object ModuleKeys {
        val CLEAN_GLOBAL_SEARCH = PreferenceKeys.CLEAN_GLOBAL_SEARCH.name
        val DISABLE_FORCE_STOP = PreferenceKeys.DISABLE_FORCE_STOP.name
        val ZUI_LAUNCHER_HOTSEAT = PreferenceKeys.ZUI_LAUNCHER_HOTSEAT.name
        val DISABLE_DOCK_BACKGROUND = PreferenceKeys.DISABLE_DOCK_BACKGROUND.name
        val LAUNCHER_FORCE_FREEFORM_ENTRY = PreferenceKeys.LAUNCHER_FORCE_FREEFORM_ENTRY.name
        val NO_CHARGE_ANIMATION = PreferenceKeys.NO_CHARGE_ANIMATION.name
        val SYSTEMUI_NETWORK_SPEED_DOUBLELAYER = PreferenceKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER.name
        val BYPASS_SHARE_WARNING = PreferenceKeys.BYPASS_SHARE_WARNING.name
        val DISABLE_NEARBY_SHARE_COUNTDOWN = PreferenceKeys.DISABLE_NEARBY_SHARE_COUNTDOWN.name
        val AUTO_ACCEPT_FILE_TRANSFER = PreferenceKeys.AUTO_ACCEPT_FILE_TRANSFER.name
        val VOLUME_SLIDER_PERCENTAGE = PreferenceKeys.VOLUME_SLIDER_PERCENTAGE.name
    }

    /** Output field keys per module. */
    object Keys {
        // CleanGlobalSearch
        const val HOTWORD_INIT_METHOD = "hotwordInitMethod"
        const val HOTWORD_DATA_METHOD = "hotwordDataMethod"
        // DisableForceStop
        const val FORCE_STOP_METHOD = "forceStopMethod"
        // ZuiLauncherHotseatHook
        const val LOADER_CURSOR_B_METHOD = "loaderCursorBMethod"
        // See docs/research/zui_launcher_dock_background.md.
        const val DOCK_PAINT_FIELDS = "dockPaintFields"
        // LauncherFreeformEntryHook
        const val FREEFORM_UTILS_CLASS = "freeformUtilsClass"
        const val FREEFORM_PKG_SUPPORT_METHOD = "freeformPkgSupportMethod"
        const val FREEFORM_PKG_SUPPORT_PARAMS = "freeformPkgSupportParams"
        const val FREEFORM_TASK_SUPPORT_METHOD = "freeformTaskSupportMethod"
        const val FREEFORM_TASK_SUPPORT_PARAMS = "freeformTaskSupportParams"
        const val FREEFORM_DENY_SET_METHOD = "freeformDenySetMethod"
        const val FREEFORM_DENY_SET_PARAMS = "freeformDenySetParams"
        const val FREEFORM_STATIC_DENY_FIELD = "freeformStaticDenyField"
        const val FREEFORM_SWIPE_HANDLER_CLASS = "freeformSwipeHandlerClass"
        const val FREEFORM_SWIPE_INIT_PARAMS = "freeformSwipeInitParams"
        const val FREEFORM_SWIPE_SUPPORT_FIELD = "freeformSwipeSupportField"
        const val FREEFORM_SWIPE_TASK_INFO_FIELD = "freeformSwipeTaskInfoField"
        // NoChargeAnimation
        const val HANDLER_FIELD_NAME = "handlerFieldName"
        // SystemUINetworkSpeeddoublelayerHook
        const val HANDLER_INNER_CLASS = "handlerInnerClass"
        // BypassShareWarningHook
        const val MANAGER_CLASS = "managerClass"
        const val MANAGER_FACTORY_METHOD = "managerFactoryMethod"
        const val MANAGER_SET_METHOD = "managerSetMethod"
        const val DIALOG_METHOD = "dialogMethod"
        const val TILE_REFRESH_METHOD = "tileRefreshMethod"
        // DisableNearbyShareAutoOffHook
        const val TARGET_CLASS = "targetClass"
        const val TARGET_METHOD = "targetMethod"
        // AutoAcceptFileTransferHook
        const val VM_FIELD_NAME = "vmFieldName"
        const val ACCEPTED_FIELD_NAME = "acceptedFieldName"
        const val LIVE_DATA_FIELD_NAME = "liveDataFieldName"
        const val LIVE_DATA_UPDATE_METHOD = "liveDataUpdateMethod"
        // VolumeSliderPercentageHook
        const val VOLUME_SLIDER_PROGRESS_LISTENER_CLASS = "progressListenerClass"
    }
}
