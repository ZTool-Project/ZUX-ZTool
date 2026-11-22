package com.qimian233.ztool.dexindex.base

import com.qimian233.ztool.data.keys.PreferenceKeys

/** Constants for the offline DexKit index. */
object DexIndexConstants {

    // v11 restores MobileDesktopDexIndexer for BypassShareWarningHook tile/manager targets.
    // v10 adds BigFolderAlignHook FolderAnimationManager keys and LauncherNoLabelMode folder title fade keys.
    // v9 updates BigFolderAlignHook keys for the square 3x3 rewrite.
    // v8 adds Launcher grid/dock/misc/unmask/icon-pack keys and refreshes broken A17 queries.
    // v7 adds LauncherFreeformEntryHook keys for R8-obfuscated A17 ZUI Launcher.
    // v6 rebuilds existing SystemUI indexes for ordered paths and typed callback matching.
    // See docs/research/systemui_charging_animation_android17.md for earlier versions.
    const val SCHEMA_VERSION = 11

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
        val DISABLE_RECENT_APPS_DISPLAY = PreferenceKeys.DISABLE_RECENT_APPS_DISPLAY.name
        val LAUNCHER_BATCH_UNINSTALL = PreferenceKeys.LAUNCHER_BATCH_UNINSTALL.name
        val LAUNCHER_FORCE_FREEFORM_ENTRY = PreferenceKeys.LAUNCHER_FORCE_FREEFORM_ENTRY.name
        val LAUNCHER_HIDE_BLUE_POINT = PreferenceKeys.LAUNCHER_HIDE_BLUE_POINT.name
        val LAUNCHER_BIG_FOLDER_BLUR_GUARD = PreferenceKeys.LAUNCHER_BIG_FOLDER_BLUR_GUARD.name
        const val LAUNCHER_GRID_METRICS = "launcher_grid_metrics"
        val LAUNCHER_BIG_FOLDER_ALIGN = PreferenceKeys.LAUNCHER_BIG_FOLDER_ALIGN.name
        val LAUNCHER_NO_LABEL_MODE = PreferenceKeys.LAUNCHER_NO_LABEL_MODE.name
        val LAUNCHER_DRAWER_NO_LABEL_MODE = PreferenceKeys.LAUNCHER_DRAWER_NO_LABEL_MODE.name
        val LAUNCHER_WIDE_GRID = PreferenceKeys.LAUNCHER_WIDE_GRID.name
        val LAUNCHER_ICON_SCALE_OVERRIDE = PreferenceKeys.LAUNCHER_ICON_SCALE_OVERRIDE.name
        val LAUNCHER_APP_ICON_UNMASK = PreferenceKeys.LAUNCHER_APP_ICON_UNMASK.name
        val LAUNCHER_ICON_PACK = PreferenceKeys.LAUNCHER_ICON_PACK.name
        val NO_CHARGE_ANIMATION = PreferenceKeys.NO_CHARGE_ANIMATION.name
        val SYSTEMUI_NETWORK_SPEED_DOUBLELAYER = PreferenceKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER.name
        val BYPASS_SHARE_WARNING = PreferenceKeys.BYPASS_SHARE_WARNING.name
        val VOLUME_SLIDER_PERCENTAGE = PreferenceKeys.VOLUME_SLIDER_PERCENTAGE.name
    }

    /** Output field keys per module. */
    object Keys {
        // CleanGlobalSearch
        const val CLEAN_SEARCH_CLASS = "cleanSearchClass"
        const val CLEAN_SEARCH_HOTWORD_FIELD = "cleanSearchHotwordField"
        const val HOTWORD_INIT_METHOD = "hotwordInitMethod"
        const val HOTWORD_DATA_METHOD = "hotwordDataMethod"
        const val HOTWORD_DATA_PARAMS = "hotwordDataParams"
        const val HOTWORD_HINT_METHOD = "hotwordHintMethod"
        // DisableForceStop
        const val FORCE_STOP_CLASS = "forceStopClass"
        const val FORCE_STOP_METHOD = "forceStopMethod"
        const val FORCE_STOP_PARAMS = "forceStopParams"
        const val FORCE_STOP_DISMISS_CLASS = "forceStopDismissClass"
        const val FORCE_STOP_DISMISS_METHOD = "forceStopDismissMethod"
        const val FORCE_STOP_DISMISS_PARAMS = "forceStopDismissParams"
        // ZuiLauncherHotseatHook
        const val LOADER_CURSOR_B_METHOD = "loaderCursorBMethod"
        const val HOTSEAT_CALC_CLASS = "hotseatCalcClass"
        const val HOTSEAT_CREATE_PROFILE_METHOD = "hotseatCreateProfileMethod"
        const val HOTSEAT_CREATE_PROFILE_PARAMS = "hotseatCreateProfileParams"
        const val HOTSEAT_RECALC_METHOD = "hotseatRecalcMethod"
        const val HOTSEAT_RECALC_PARAMS = "hotseatRecalcParams"
        // DisableDockBackground (see docs/research/zui_launcher_dock_background.md)
        const val DOCK_PAINT_FIELDS = "dockPaintFields"
        const val DOCK_SPLIT_LINE_VIEW_FIELD = "dockSplitLineViewField"
        const val DOCK_BLUR_PROVIDER_CLASS = "dockBlurProviderClass"
        const val DOCK_ADD_BLUR_METHOD = "dockAddBlurMethod"
        // DisableRecentAppsDisplay
        const val RECENT_APPS_CONTROLLER_CLASS = "recentAppsControllerClass"
        const val RECENT_APPS_ON_CHANGED_METHOD = "recentAppsOnChangedMethod"
        // BatchUninstall
        const val BATCH_UNINSTALL_UPDATE_UI_METHOD = "batchUninstallUpdateUiMethod"
        const val BATCH_UNINSTALL_CLEAR_METHOD = "batchUninstallClearMethod"
        const val BATCH_UNINSTALL_SELECTED_FIELD = "batchUninstallSelectedField"
        const val BATCH_UNINSTALL_LAUNCHER_FIELD = "batchUninstallLauncherField"
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
        // BluePointRemovalHook
        const val BLUE_POINT_CLASS = "bluePointClass"
        const val BLUE_POINT_IS_PACKAGE_NEW_METHOD = "bluePointIsPackageNewMethod"
        // LauncherBigFolderBlurGuardHook
        const val BIG_FOLDER_BLUR_DATA_METHOD = "bigFolderBlurDataMethod"
        const val BIG_FOLDER_BLUR_DRAG_FLAG_FIELD = "bigFolderBlurDragFlagField"
        const val BIG_FOLDER_BLUR_ACTIVITY_FIELD = "bigFolderBlurActivityField"
        const val BIG_FOLDER_BLUR_DRAG_OBJECT_METHOD = "bigFolderBlurDragObjectMethod"
        const val BIG_FOLDER_BLUR_DRAG_OBJECT_FIELD = "bigFolderBlurDragObjectField"
        // LauncherGridMetrics
        const val GRID_METRICS_WORKSPACE_PROFILE_CLASS = "gridMetricsWorkspaceProfileClass"
        const val GRID_METRICS_FOLDER_PROFILE_CLASS = "gridMetricsFolderProfileClass"
        const val GRID_METRICS_DP_WORKSPACE_PROFILE_FIELD = "gridMetricsDpWorkspaceProfileField"
        const val GRID_METRICS_DP_FOLDER_PROFILE_FIELD = "gridMetricsDpFolderProfileField"
        const val GRID_METRICS_DP_GET_WORKSPACE_PROFILE_METHOD = "gridMetricsDpGetWorkspaceProfileMethod"
        const val GRID_METRICS_DP_SET_WORKSPACE_PROFILE_METHOD = "gridMetricsDpSetWorkspaceProfileMethod"
        const val GRID_METRICS_DP_GET_FOLDER_PROFILE_METHOD = "gridMetricsDpGetFolderProfileMethod"
        const val GRID_METRICS_WS_CELL_LAYOUT_PADDING_FIELD = "gridMetricsWsCellLayoutPaddingField"
        const val GRID_METRICS_WS_BORDER_SPACE_FIELD = "gridMetricsWsBorderSpaceField"
        const val GRID_METRICS_WS_CELL_HEIGHT_FIELD = "gridMetricsWsCellHeightField"
        const val GRID_METRICS_WS_ICON_SIZE_FIELD = "gridMetricsWsIconSizeField"
        const val GRID_METRICS_WS_ICON_TEXT_SIZE_FIELD = "gridMetricsWsIconTextSizeField"
        const val GRID_METRICS_WS_ICON_DRAWABLE_PADDING_FIELD = "gridMetricsWsIconDrawablePaddingField"
        const val GRID_METRICS_WS_WIDGET_PADDING_FIELD = "gridMetricsWsWidgetPaddingField"
        const val GRID_METRICS_WS_CELL_Y_PADDING_FIELD = "gridMetricsWsCellYPaddingField"
        const val GRID_METRICS_WS_DESIRED_H_MARGIN_FIELD = "gridMetricsWsDesiredHMarginField"
        const val GRID_METRICS_FOLDER_ICON_SIZE_FIELD = "gridMetricsFolderIconSizeField"
        const val GRID_METRICS_FOLDER_ICON_OFFSET_Y_FIELD = "gridMetricsFolderIconOffsetYField"
        // BigFolderAlignHook
        const val BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_FIELD = "bigFolderAlignPbPreviewSizeField"
        const val BIG_FOLDER_ALIGN_PB_OFFSET_X_FIELD = "bigFolderAlignPbOffsetXField"
        const val BIG_FOLDER_ALIGN_PB_OFFSET_Y_FIELD = "bigFolderAlignPbOffsetYField"
        const val BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_Y_FIELD = "bigFolderAlignPbPreviewSizeYField"
        const val BIG_FOLDER_ALIGN_RULE_ICON_SIZE_FIELD = "bigFolderAlignRuleIconSizeField"
        const val BIG_FOLDER_ALIGN_RULE_BG_WIDTH_FIELD = "bigFolderAlignRuleBgWidthField"
        const val BIG_FOLDER_ALIGN_RULE_BG_HEIGHT_FIELD = "bigFolderAlignRuleBgHeightField"
        const val BIG_FOLDER_ALIGN_RULE_COMPUTE_METHOD = "bigFolderAlignRuleComputeMethod"
        const val BIG_FOLDER_ALIGN_RULE_COMPUTE_PARAMS = "bigFolderAlignRuleComputeParams"
        const val BIG_FOLDER_ALIGN_BFC_CLASS = "bigFolderAlignBfcClass"
        const val BIG_FOLDER_ALIGN_BFC_CHILD_COUNT_METHOD = "bigFolderAlignBfcChildCountMethod"
        const val BIG_FOLDER_ALIGN_FI_INFO_FIELD = "bigFolderAlignFiInfoField"
        const val BIG_FOLDER_ALIGN_FI_LABEL_FIELD = "bigFolderAlignFiLabelField"
        const val BIG_FOLDER_ALIGN_FI_LABEL_METHOD = "bigFolderAlignFiLabelMethod"
        const val BIG_FOLDER_ALIGN_FI_BG_FIELD = "bigFolderAlignFiBgField"
        const val BIG_FOLDER_ALIGN_FI_PAD_TOP_METHOD = "bigFolderAlignFiPadTopMethod"
        const val BIG_FOLDER_ALIGN_FI_NAME_TOP_MARGIN_FIELD = "bigFolderAlignFiNameTopMarginField"
        const val BIG_FOLDER_ALIGN_FI_LAYOUT_RULE_FIELD = "bigFolderAlignFiLayoutRuleField"
        const val BIG_FOLDER_ALIGN_FI_PIM_FIELD = "bigFolderAlignFiPimField"
        const val BIG_FOLDER_ALIGN_PIM_ICON_FIELD = "bigFolderAlignPimIconField"
        const val BIG_FOLDER_ALIGN_PIM_FIRST_PAGE_FIELD = "bigFolderAlignPimFirstPageField"
        const val BIG_FOLDER_ALIGN_PIM_DRAW_PARAMS_METHOD = "bigFolderAlignPimDrawParamsMethod"
        const val BIG_FOLDER_ALIGN_PIM_CHECK_CLICK_METHOD = "bigFolderAlignPimCheckClickMethod"
        const val BIG_FOLDER_ALIGN_PIM_ICON_SIZE_FIELD = "bigFolderAlignPimIconSizeField"
        const val BIG_FOLDER_ALIGN_FAM_ICON_FIELD = "bigFolderAlignFamIconField"
        const val BIG_FOLDER_ALIGN_FAM_TMP_PARAMS_FIELD = "bigFolderAlignFamTmpParamsField"
        const val BIG_FOLDER_ALIGN_FAM_COMPUTE_BIG_ANIM_METHOD = "bigFolderAlignFamComputeBigAnimMethod"
        const val BIG_FOLDER_ALIGN_FAM_COMPUTE_BIG_ANIM_PARAMS = "bigFolderAlignFamComputeBigAnimParams"
        // LauncherNoLabelMode
        const val NO_LABEL_FOLDER_ANIM_CREATE_METHOD = "noLabelFolderAnimCreateMethod"
        const val NO_LABEL_POPUP_IMPL_CLASS = "noLabelPopupImplClass"
        const val NO_LABEL_FLOAT_BI_FUNC_METHOD = "noLabelFloatBiFuncMethod"
        const val NO_LABEL_FOLDER_ANIM_CREATE_SET_METHOD = "noLabelFolderAnimCreateSetMethod"
        const val NO_LABEL_FOLDER_ANIM_FOLDER_FIELD = "noLabelFolderAnimFolderField"
        const val NO_LABEL_FOLDER_NAME_ANIM_FLAG_FIELD = "noLabelFolderNameAnimFlagField"
        const val NO_LABEL_FOLDER_NAME_DELEGATE_FIELD = "noLabelFolderNameDelegateField"
        // LauncherDrawerNoLabelMode
        const val DRAWER_NO_LABEL_ACTIVE_BIND_ITEM_METHOD = "drawerNoLabelActiveBindItemMethod"
        const val DRAWER_NO_LABEL_BUBBLE_BIND_ITEM_METHOD = "drawerNoLabelBubbleBindItemMethod"
        // LauncherWideGridHook
        const val WIDE_GRID_SET_CELL_DIMS_METHOD = "wideGridSetCellDimsMethod"
        const val WIDE_GRID_CONTAINER_CELL_SIZE_FIELDS = "wideGridContainerCellSizeFields"
        const val WIDE_GRID_CONTAINER_COUNT_X_FIELD = "wideGridContainerCountXField"
        const val WIDE_GRID_CONTAINER_COUNT_Y_FIELD = "wideGridContainerCountYField"
        const val WIDE_GRID_CONTAINER_BORDER_SPACE_FIELD = "wideGridContainerBorderSpaceField"
        const val WIDE_GRID_CELL_LP_CLASS = "wideGridCellLpClass"
        const val WIDE_GRID_CELL_LP_LOCKED_FIELD = "wideGridCellLpLockedField"
        // IconScaleOverrideHook
        const val ICON_SCALE_LEGACY_UTILS_METHOD = "iconScaleLegacyUtilsMethod"
        const val ICON_SCALE_IDP_CLASS = "iconScaleIdpClass"
        const val ICON_SCALE_IDP_INIT_GRID_METHOD = "iconScaleIdpInitGridMethod"
        const val ICON_SCALE_IDP_CUSTOM_SCALE_FIELD = "iconScaleIdpCustomScaleField"
        const val ICON_SCALE_DP_CLASS = "iconScaleDpClass"
        const val ICON_SCALE_DP_INIT_PARAMS = "iconScaleDpInitParams"
        // LauncherAppIconUnmaskHook
        const val UNMASK_FACTORY_CLASS = "unmaskFactoryClass"
        const val UNMASK_OPTIONS_CLASS = "unmaskOptionsClass"
        const val UNMASK_BADGED_METHOD = "unmaskBadgedMethod"
        const val UNMASK_BADGED_ZUI_METHOD = "unmaskBadgedZuiMethod"
        const val UNMASK_BADGED_PARAMS = "unmaskBadgedParams"
        const val UNMASK_BITMAP_INFO_CLASS = "unmaskBitmapInfoClass"
        const val UNMASK_SIZE_FIELD = "unmaskSizeField"
        const val UNMASK_SIZE_METHOD = "unmaskSizeMethod"
        const val UNMASK_BITMAP_ICON_FIELD = "unmaskBitmapIconField"
        const val UNMASK_COLOR_FIELD = "unmaskColorField"
        const val UNMASK_FLAGS_FIELD = "unmaskFlagsField"
        const val UNMASK_CREATION_FLAGS_FIELD = "unmaskCreationFlagsField"
        const val UNMASK_EXTENDER_CLASS = "unmaskExtenderClass"
        const val UNMASK_THEME_CONTROLLER_FIELD = "unmaskThemeControllerField"
        const val UNMASK_THEME_CONTROLLER_METHOD = "unmaskThemeControllerMethod"
        const val UNMASK_THEME_CONTROLLER_CLASS = "unmaskThemeControllerClass"
        const val UNMASK_THEMED_BITMAP_CLASS = "unmaskThemedBitmapClass"
        const val UNMASK_CREATE_THEMED_METHOD = "unmaskCreateThemedMethod"
        const val UNMASK_CREATE_THEMED_PARAMS = "unmaskCreateThemedParams"
        const val UNMASK_THEMED_BITMAP_FIELD = "unmaskThemedBitmapField"
        const val UNMASK_THEMED_BITMAP_SETTER = "unmaskThemedBitmapSetter"
        // LauncherIconPackHook
        const val ICON_PACK_PROVIDER_CLASS = "iconPackProviderClass"
        const val ICON_PACK_SUBCLASS = "iconPackSubclass"
        const val ICON_PACK_CONTEXT_FIELD = "iconPackContextField"
        const val ICON_PACK_UNIFIED_METHOD = "iconPackUnifiedMethod"
        const val ICON_PACK_UNIFIED_PARAMS = "iconPackUnifiedParams"
        const val ICON_PACK_OVERRIDES_METHOD = "iconPackOverridesMethod"
        const val ICON_PACK_OVERRIDES_PARAMS = "iconPackOverridesParams"
        const val ICON_PACK_STATE_METHOD = "iconPackStateMethod"
        const val ICON_PACK_APPEND_METHOD = "iconPackAppendMethod"
        // NoChargeAnimation
        const val HANDLER_FIELD_NAME = "handlerFieldName"
        const val TARGET_CLASS = "targetClass"
        // SystemUINetworkSpeeddoublelayerHook
        const val HANDLER_INNER_CLASS = "handlerInnerClass"
        // BypassShareWarningHook
        const val MANAGER_CLASS = "managerClass"
        const val MANAGER_FACTORY_METHOD = "managerFactoryMethod"
        const val MANAGER_SET_METHOD = "managerSetMethod"
        const val TILE_REFRESH_METHOD = "tileRefreshMethod"
        // VolumeSliderPercentageHook
        const val VOLUME_SLIDER_PROGRESS_LISTENER_CLASS = "progressListenerClass"
    }
}
