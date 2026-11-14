package com.qimian233.ztool.hook.base

import com.qimian233.ztool.hook.modules.documentsui.DocumentsUIBypass
import com.qimian233.ztool.hook.modules.freeformbar.SidebarDrawerHook
import com.qimian233.ztool.hook.modules.gametool.AutoMistakeTouchHook
import com.qimian233.ztool.hook.modules.gametool.CpuFrequencyFix
import com.qimian233.ztool.hook.modules.gametool.DeviceModelDisguiseHook
import com.qimian233.ztool.hook.modules.gametool.DisableGameAudioApp
import com.qimian233.ztool.hook.modules.gametool.SocTemperatureFix
import com.qimian233.ztool.hook.modules.launcher.LauncherFreeformEntryHook
import com.qimian233.ztool.hook.modules.systemui.FreeformFullscreenThresholdHook
import com.qimian233.ztool.hook.modules.systemui.PcTaskbarCompactControls
import com.qimian233.ztool.hook.modules.systemui.FreeformBottomEdgeResizeHook
import com.qimian233.ztool.hook.modules.systemui.FreeformCornerHandleIndicatorHook
import com.qimian233.ztool.hook.modules.systemui.FreeformEdgeBubbleDragLimitHook
import com.qimian233.ztool.hook.modules.systemui.FreeformForeheadOverlayHook
import com.qimian233.ztool.hook.modules.launcher.FreeformKeepAliveLauncherHook
import com.qimian233.ztool.hook.modules.launcher.dockbar.DisableDockBar
import com.qimian233.ztool.hook.modules.launcher.dockbar.DisableDockBackground
import com.qimian233.ztool.hook.modules.launcher.dockbar.DisableRecentAppsDisplay
import com.qimian233.ztool.hook.modules.launcher.dockbar.ZuiLauncherHotseatHook
import com.qimian233.ztool.hook.modules.launcher.grid.BluePointRemovalHook
import com.qimian233.ztool.hook.modules.launcher.grid.BigFolderAlignHook
import com.qimian233.ztool.hook.modules.launcher.grid.CustomGridSize
import com.qimian233.ztool.hook.modules.launcher.grid.DismissCloudFolderConfirmation
import com.qimian233.ztool.hook.modules.launcher.grid.LauncherDrawerNoLabelMode
import com.qimian233.ztool.hook.modules.launcher.grid.LauncherNoLabelMode
import com.qimian233.ztool.hook.modules.launcher.grid.LauncherWideGridHook
import com.qimian233.ztool.hook.modules.launcher.grid.LauncherBigFolderBlurGuardHook
import com.qimian233.ztool.hook.modules.launcher.grid.IconScaleOverrideHook
import com.qimian233.ztool.hook.modules.launcher.misc.BatchUninstall
import com.qimian233.ztool.hook.modules.launcher.misc.CleanGlobalSearch
import com.qimian233.ztool.hook.modules.launcher.misc.DisableForceStop
import com.qimian233.ztool.hook.modules.launcher.LauncherAppIconUnmaskHook
import com.qimian233.ztool.hook.modules.launcher.LauncherIconPackHook
import com.qimian233.ztool.hook.modules.launcher.misc.RecentTaskMemoryViewHook
import com.qimian233.ztool.hook.modules.desktoplauncher.PcAllAppsPortraitGrid
import com.qimian233.ztool.hook.modules.desktoplauncher.WorkDesktopPortrait
import com.qimian233.ztool.hook.modules.desktoplauncher.WorkspaceLabelTwoLine
import com.qimian233.ztool.hook.modules.lsfdevice.DisableLsfDeviceAppListReporting
import com.qimian233.ztool.hook.modules.lsfdevice.DisableLsfDeviceAutoInstall
import com.qimian233.ztool.hook.modules.lsfdevice.DisableLsfDevicePush
import com.qimian233.ztool.hook.modules.lsfdevice.DisableTabPushoutSdacRegister
import com.qimian233.ztool.hook.modules.mobiledesktop.AutoAcceptFileTransferHook
import com.qimian233.ztool.hook.modules.mobiledesktop.BypassShareWarningHook
import com.qimian233.ztool.hook.modules.mobiledesktop.DisableNearbyShareAutoOffHook
import com.qimian233.ztool.hook.modules.ota.BlockOtaInstallDialog
import com.qimian233.ztool.hook.modules.ota.DisableOtaCheck
import com.qimian233.ztool.hook.modules.ota.HideOtaNotifications
import com.qimian233.ztool.hook.modules.tbengine.LenovoOTAHook
import com.qimian233.ztool.hook.modules.ota.NoAutoOtaInstall
import com.qimian233.ztool.hook.modules.packageinstaller.DisableInstallerAdvertisement
import com.qimian233.ztool.hook.modules.pp.BlockGamePolicyUpdate
import com.qimian233.ztool.hook.modules.pp.BlockPowerPolicySync
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerHookScan
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerNoDeleteModule
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerPermissionHook
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerStyleHook
import com.qimian233.ztool.hook.modules.packageinstaller.SkipInstallWarnPage
import com.qimian233.ztool.hook.modules.safecenter.DisableAllVirusScans
import com.qimian233.ztool.hook.modules.safecenter.EnableAutorunByDefault
import com.qimian233.ztool.hook.modules.setting.AllowDisplayDolbyHook
import com.qimian233.ztool.hook.modules.tbengine.DisableTbEngineAppUpdate
import com.qimian233.ztool.hook.modules.tbengine.DisableTbEngineAutoDownload
import com.qimian233.ztool.hook.modules.tbengine.DisableTbEngineAutoInstall
import com.qimian233.ztool.hook.modules.tbengine.DisableTbEnginePush
import com.qimian233.ztool.hook.modules.tbengine.DisableTbEngineReporting
import com.qimian233.ztool.hook.modules.tbengine.SignTbEngineLocalOta
import com.qimian233.ztool.hook.modules.setting.AppInfoHeaderDetailsHook
import com.qimian233.ztool.hook.modules.setting.CustomizeAboutDeviceInfo
import com.qimian233.ztool.hook.modules.setting.HideOtaUpdateHint
import com.qimian233.ztool.hook.modules.setting.LocaleListEditorHook
import com.qimian233.ztool.hook.modules.setting.OneVisionCompletion
import com.qimian233.ztool.hook.modules.setting.PermissionControllerHook
import com.qimian233.ztool.hook.modules.setting.SettingsAppIconUnmaskHook
import com.qimian233.ztool.hook.modules.setting.SplitScreenMandatory as SettingSplitScreenMandatory
import com.qimian233.ztool.hook.modules.setting.ZToolSettingsEntryHook
import com.qimian233.ztool.hook.modules.sogouime.HalfWidthPunctHook
import com.qimian233.ztool.hook.modules.sogouime.SogouEnterKeyHook
import com.qimian233.ztool.hook.modules.systemframework.AiInputExpand
import com.qimian233.ztool.hook.modules.systemframework.AllowAllRestrictedSettings
import com.qimian233.ztool.hook.modules.systemframework.AllowGetPackages
import com.qimian233.ztool.hook.modules.systemframework.AllowRelativeAppLaunch
import com.qimian233.ztool.hook.modules.systemframework.AllowUntrustedTouch
import com.qimian233.ztool.hook.modules.systemframework.ClipboardAccessToastHook
import com.qimian233.ztool.hook.modules.systemframework.DisableFlagSecure
import com.qimian233.ztool.hook.modules.systemframework.DisableGameAudio
import com.qimian233.ztool.hook.modules.systemframework.DisableHbmThermalLimit
import com.qimian233.ztool.hook.modules.systemframework.DisableVolumeWarnDialog
import com.qimian233.ztool.hook.modules.systemframework.FineVolumeSteps
import com.qimian233.ztool.hook.modules.systemframework.FreeformEdgeBubbleHook
import com.qimian233.ztool.hook.modules.systemframework.FreeformKeepAliveSystemHook
import com.qimian233.ztool.hook.modules.systemframework.ForceRelativeAppFreeform
import com.qimian233.ztool.hook.modules.systemframework.ForceScreenOnOffAnimation
import com.qimian233.ztool.hook.modules.systemframework.KeepRotation
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerArscBypassHook
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerDigestBypassHook
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerDowngradeHook
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerExactSigMatchBypassHook
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerHiddenApiHook
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerSharedUserBypassHook
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerSignatureBypassHook
import com.qimian233.ztool.hook.modules.systemframework.PackageManagerVerificationAgentHook
import com.qimian233.ztool.hook.modules.systemframework.PenHapticWaveformRedirectHook
import com.qimian233.ztool.hook.modules.systemframework.VolumeKeyNonlinearRamp
import com.qimian233.ztool.hook.modules.systemframework.WorkModePortrait
import com.qimian233.ztool.hook.modules.systemframework.SplitScreenMandatory as SystemSplitScreenMandatory
import com.qimian233.ztool.hook.modules.systemui.keyguard.ChargeAnimationDurationHook
import com.qimian233.ztool.hook.modules.systemui.keyguard.BypassFaceAuthTimeout
import com.qimian233.ztool.hook.modules.systemui.keyguard.LockScreenClockColorHook
import com.qimian233.ztool.hook.modules.systemui.keyguard.ForceLenovoAOD
import com.qimian233.ztool.hook.modules.systemui.keyguard.ForceNativeAod
import com.qimian233.ztool.hook.modules.systemui.keyguard.SystemUIChargeWattsHook
import com.qimian233.ztool.hook.modules.systemui.keyguard.OwnerInfoSystemUiHook
import com.qimian233.ztool.hook.modules.systemui.keyguard.SystemUIRealWatts
import com.qimian233.ztool.hook.modules.systemui.misc.AospScrollCaptureHook
import com.qimian233.ztool.hook.modules.systemui.misc.CustomChargeAnimation
import com.qimian233.ztool.hook.modules.systemui.misc.FineVolumeStepsSliderHook
import com.qimian233.ztool.hook.modules.systemui.misc.CustomControlCenterDate
import com.qimian233.ztool.hook.modules.systemui.misc.DisableBiometricErrorVibration
import com.qimian233.ztool.hook.modules.systemui.misc.ForceImmersiveMode
import com.qimian233.ztool.hook.modules.systemui.misc.GuestModeController
import com.qimian233.ztool.hook.modules.systemui.misc.NoChargeAnimation
import com.qimian233.ztool.hook.modules.systemui.misc.NotificationCenterTransparency
import com.qimian233.ztool.hook.modules.systemui.misc.ShadeReboundFix
import com.qimian233.ztool.hook.modules.systemui.qs.BrightnessSliderPercentageHook
import com.qimian233.ztool.hook.modules.systemui.qs.ControlCenterNoTileLabelsHook
import com.qimian233.ztool.hook.modules.systemui.qs.MediaOutputDialogCenterHook
import com.qimian233.ztool.hook.modules.systemui.qs.MediaOutputDialogColorHook
import com.qimian233.ztool.hook.modules.systemui.qs.CustomQsColor
import com.qimian233.ztool.hook.modules.systemui.qs.CustomQsRoundCorner
import com.qimian233.ztool.hook.modules.systemui.qs.QsPanelWidthHook
import com.qimian233.ztool.hook.modules.systemui.qs.ControlCenterLongPressHook
import com.qimian233.ztool.hook.modules.systemui.qs.HideDetailIndicatorHook
import com.qimian233.ztool.hook.modules.systemui.qs.SliderStyleHook
import com.qimian233.ztool.hook.modules.systemui.qs.VolumeSliderFidelityHook
import com.qimian233.ztool.hook.modules.systemui.qs.VolumeSliderLongPressHook
import com.qimian233.ztool.hook.modules.systemui.qs.VolumeSliderPercentageHook
import com.qimian233.ztool.hook.modules.systemui.statusbar.CustomStatusBarClock
import com.qimian233.ztool.hook.modules.systemui.statusbar.NativeNotificationIcon
import com.qimian233.ztool.hook.modules.systemui.statusbar.NotificationIconHook
import com.qimian233.ztool.hook.modules.systemui.statusbar.NotificationOverflowDotHook
import com.qimian233.ztool.hook.modules.systemui.statusbar.StatusBarClockSecondsHook
import com.qimian233.ztool.hook.modules.systemui.statusbar.SystemUIBatteryHook
import com.qimian233.ztool.hook.modules.systemui.statusbar.SystemUINetworkSpeedHook
import com.qimian233.ztool.hook.modules.systemui.wallpaper.DesktopLiveWallpaperHook
import com.qimian233.ztool.hook.modules.wallpaper.ChargeAnimationFixModule
import com.qimian233.ztool.hook.modules.zuiui.ZuiDialogSkinHook
import com.qimian233.ztool.hook.modules.zuiui.ZuiLoadingIndicatorHook
import com.qimian233.ztool.hook.modules.zuiui.ZuiSwitchStyleHook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

/** Registers and dispatches hooks by target scope. */
// See docs/research/network_speed_refactor_plan.md for lifecycle replay constraints.
object HookManager {

    private val hookModules: MutableList<BaseHookModule> = ArrayList()
    private var initialized = false

    private val savedPackageParams: MutableList<XposedModuleInterface.PackageLoadedParam> =
        ArrayList()
    private var savedSystemServerParam: XposedModuleInterface.SystemServerStartingParam? = null

    fun initialize(xposed: XposedInterface) {
        if (initialized) return
        registerAllModules(xposed)
    }

    private fun registerAllModules(xposed: XposedInterface) {
        registerSystemServerModules()
        registerAndroidFrameworkModules()
        registerAllProcessModules()
        registerZuiWidgetModules()
        registerSystemUIModules()
        registerSettingsModules()
        registerPermissionControllerModules()
        registerPackageInstallerModules()
        registerLauncherModules()
        registerDesktopLauncherModules()
        registerGameServiceModules()
        registerOtaModules()
        registerWallpaperSettingsModules()
        registerDocumentsUIModules()
        registerFreeformSidebarModules()
        registerSafeCenterModules()
        registerMobileDesktopModules()
        registerTbEngineModules()
        registerLenovoDeviceServiceModules()
        registerTabPushoutModules()
        registerZuiPerformanceModules()
        registerSogouImeModules()

        for (module in hookModules) {
            module.setXposedInterface(xposed)
        }

        initialized = true
    }

    private fun registerSystemServerModules() {
        registerHookModule(DisableFlagSecure())
        registerHookModule(AllowGetPackages())
        registerHookModule(AllowAllRestrictedSettings())
        registerHookModule(AllowUntrustedTouch())
        registerHookModule(ForceScreenOnOffAnimation())
        registerHookModule(DisableVolumeWarnDialog())
        registerHookModule(KeepRotation())
        registerHookModule(WorkModePortrait())
        registerHookModule(AllowRelativeAppLaunch())
        registerHookModule(ForceRelativeAppFreeform())
        registerHookModule(FreeformEdgeBubbleHook())
        registerHookModule(FreeformKeepAliveSystemHook())
        registerHookModule(DisableHbmThermalLimit())
        registerHookModule(ClipboardAccessToastHook())
        registerHookModule(SystemSplitScreenMandatory())

        // Package manager core patches
        registerHookModule(PackageManagerDowngradeHook())
        registerHookModule(PackageManagerSignatureBypassHook())
        registerHookModule(PackageManagerVerificationAgentHook())
        registerHookModule(PackageManagerDigestBypassHook())
        registerHookModule(PackageManagerExactSigMatchBypassHook())
        registerHookModule(PackageManagerSharedUserBypassHook())
        registerHookModule(PackageManagerHiddenApiHook())
        registerHookModule(PackageManagerArscBypassHook())
    }

    private fun registerAndroidFrameworkModules() {
        registerHookModule(FineVolumeSteps())
        registerHookModule(VolumeKeyNonlinearRamp())
        registerHookModule(PenHapticWaveformRedirectHook())
        registerHookModule(DisableGameAudio())
    }

    private fun registerAllProcessModules() {
        registerHookModule(AiInputExpand())
    }

    private fun registerZuiWidgetModules() {
        registerHookModule(ZuiDialogSkinHook())
        registerHookModule(ZuiLoadingIndicatorHook())
        registerHookModule(ZuiSwitchStyleHook())
    }

    private fun registerSystemUIModules() {
        registerHookModule(OwnerInfoSystemUiHook())
        registerHookModule(StatusBarClockSecondsHook())
        registerHookModule(CustomStatusBarClock())
        registerHookModule(SystemUIChargeWattsHook())
        registerHookModule(SystemUIRealWatts())
        registerHookModule(NotificationIconHook())
        registerHookModule(NotificationOverflowDotHook())
        registerHookModule(CustomControlCenterDate())
        registerHookModule(ControlCenterNoTileLabelsHook())
        registerHookModule(NoChargeAnimation())
        registerHookModule(NativeNotificationIcon())
        registerHookModule(SystemUINetworkSpeedHook())
        registerHookModule(SystemUIBatteryHook())
        registerHookModule(ForceImmersiveMode())
        registerHookModule(AospScrollCaptureHook())
        registerHookModule(ShadeReboundFix())
        registerHookModule(ChargeAnimationDurationHook())
        registerHookModule(ForceLenovoAOD())
        registerHookModule(ForceNativeAod())
        registerHookModule(BypassFaceAuthTimeout())
        registerHookModule(LockScreenClockColorHook())
        registerHookModule(CustomQsRoundCorner())
        registerHookModule(BrightnessSliderPercentageHook())
        registerHookModule(VolumeSliderPercentageHook())
        registerHookModule(VolumeSliderFidelityHook())
        registerHookModule(ControlCenterLongPressHook())
        registerHookModule(VolumeSliderLongPressHook())
        registerHookModule(HideDetailIndicatorHook())
        registerHookModule(CustomQsColor())
        registerHookModule(NotificationCenterTransparency())
        registerHookModule(GuestModeController())
        registerHookModule(QsPanelWidthHook())
        registerHookModule(SliderStyleHook())
        registerHookModule(CustomChargeAnimation())
        registerHookModule(FineVolumeStepsSliderHook())
        registerHookModule(DisableBiometricErrorVibration())
        registerHookModule(MediaOutputDialogCenterHook())
        registerHookModule(MediaOutputDialogColorHook())
        registerHookModule(DesktopLiveWallpaperHook())

        // Freeform window chrome
        registerHookModule(FreeformFullscreenThresholdHook())
        registerHookModule(FreeformBottomEdgeResizeHook())
        registerHookModule(FreeformCornerHandleIndicatorHook())
        registerHookModule(FreeformEdgeBubbleDragLimitHook())
        registerHookModule(FreeformForeheadOverlayHook())
        registerHookModule(PcTaskbarCompactControls())
    }

    private fun registerSettingsModules() {
        registerHookModule(OneVisionCompletion())
        registerHookModule(AllowDisplayDolbyHook())
        registerHookModule(SettingSplitScreenMandatory())
        registerHookModule(AppInfoHeaderDetailsHook())
        registerHookModule(CustomizeAboutDeviceInfo())
        registerHookModule(ZToolSettingsEntryHook())
        registerHookModule(HideOtaUpdateHint())
        registerHookModule(LocaleListEditorHook())
        registerHookModule(SettingsAppIconUnmaskHook())
    }

    private fun registerPermissionControllerModules() {
        registerHookModule(PermissionControllerHook())
    }

    private fun registerPackageInstallerModules() {
        registerHookModule(PackageInstallerHookScan())
        registerHookModule(PackageInstallerPermissionHook())
        registerHookModule(SkipInstallWarnPage())
        registerHookModule(DisableInstallerAdvertisement())
        registerHookModule(PackageInstallerStyleHook())
        registerHookModule(PackageInstallerNoDeleteModule())
    }

    private fun registerLauncherModules() {
        registerHookModule(DisableForceStop())
        registerHookModule(ZuiLauncherHotseatHook())
        registerHookModule(LauncherFreeformEntryHook())
        registerHookModule(FreeformKeepAliveLauncherHook())
        registerHookModule(LauncherAppIconUnmaskHook())
        registerHookModule(LauncherIconPackHook())
        registerHookModule(CustomGridSize())
        registerHookModule(CleanGlobalSearch())
        registerHookModule(DisableDockBar())
        registerHookModule(DisableDockBackground())
        registerHookModule(RecentTaskMemoryViewHook())
        registerHookModule(LauncherNoLabelMode())
        registerHookModule(LauncherDrawerNoLabelMode())
        registerHookModule(BluePointRemovalHook())
        registerHookModule(DismissCloudFolderConfirmation())
        registerHookModule(BigFolderAlignHook())
        registerHookModule(LauncherBigFolderBlurGuardHook())
        registerHookModule(DisableRecentAppsDisplay())
        registerHookModule(BatchUninstall())
        registerHookModule(LauncherWideGridHook())
        registerHookModule(IconScaleOverrideHook())
    }

    private fun registerDesktopLauncherModules() {
        registerHookModule(WorkDesktopPortrait())
        registerHookModule(PcAllAppsPortraitGrid())
        registerHookModule(WorkspaceLabelTwoLine())
    }

    private fun registerGameServiceModules() {
        registerHookModule(AutoMistakeTouchHook())
        registerHookModule(DisableGameAudioApp())
        registerHookModule(DeviceModelDisguiseHook())
        registerHookModule(CpuFrequencyFix())
        registerHookModule(SocTemperatureFix())
    }

    private fun registerOtaModules() {
        registerHookModule(DisableOtaCheck())
        registerHookModule(NoAutoOtaInstall())
        registerHookModule(BlockOtaInstallDialog())
        registerHookModule(HideOtaNotifications())
    }

    private fun registerWallpaperSettingsModules() {
        registerHookModule(ChargeAnimationFixModule())
    }

    private fun registerDocumentsUIModules() {
        registerHookModule(DocumentsUIBypass())
    }

    private fun registerFreeformSidebarModules() {
        registerHookModule(SidebarDrawerHook())
    }

    private fun registerSafeCenterModules() {
        registerHookModule(DisableAllVirusScans())
        registerHookModule(EnableAutorunByDefault())
    }

    private fun registerMobileDesktopModules() {
        registerHookModule(AutoAcceptFileTransferHook())
        registerHookModule(BypassShareWarningHook())
        registerHookModule(DisableNearbyShareAutoOffHook())
    }

    private fun registerTbEngineModules() {
        registerHookModule(DisableTbEngineAutoDownload())
        registerHookModule(DisableTbEngineAutoInstall())
        registerHookModule(DisableTbEngineAppUpdate())
        registerHookModule(DisableTbEnginePush())
        registerHookModule(DisableTbEngineReporting())
        registerHookModule(SignTbEngineLocalOta())
        registerHookModule(LenovoOTAHook())
    }

    private fun registerLenovoDeviceServiceModules() {
        registerHookModule(DisableLsfDevicePush())
        registerHookModule(DisableLsfDeviceAutoInstall())
        registerHookModule(DisableLsfDeviceAppListReporting())
    }

    private fun registerTabPushoutModules() {
        registerHookModule(DisableTabPushoutSdacRegister())
    }

    private fun registerZuiPerformanceModules() {
        registerHookModule(BlockPowerPolicySync())
        registerHookModule(BlockGamePolicyUpdate())
    }

    private fun registerSogouImeModules() {
        registerHookModule(HalfWidthPunctHook())
        registerHookModule(SogouEnterKeyHook())
    }

    fun registerHookModule(module: BaseHookModule) {
        if (!hookModules.contains(module)) {
            hookModules.add(module)
        }
    }

    fun handlePackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        savedPackageParams.add(param)
        for (module in hookModules) {
            module.safeHandleLoadPackage(param)
        }
    }

    fun handleSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        savedSystemServerParam = param
        for (module in hookModules) {
            module.safeHandleSystemServerStarting(param)
        }
    }

    /** Returns saved package-load parameters for hot reload. */
    fun getSavedPackageParams(): List<XposedModuleInterface.PackageLoadedParam> =
        savedPackageParams

    /** Returns saved system-server parameters for hot reload. */
    fun getSavedSystemServerParam(): XposedModuleInterface.SystemServerStartingParam? =
        savedSystemServerParam

    /** Restores lifecycle parameters from the previous module generation. */
    fun restoreLifecycleParams(
        packageParams: List<XposedModuleInterface.PackageLoadedParam>?,
        systemServerParam: XposedModuleInterface.SystemServerStartingParam?
    ) {
        savedPackageParams.clear()
        if (packageParams != null) {
            savedPackageParams.addAll(packageParams)
        }
        savedSystemServerParam = systemServerParam
    }

    /** Registers the new generation of Hook modules. */
    fun reinitializeForHotReload(xposed: XposedInterface) {
        hookModules.clear()
        registerAllModules(xposed)
    }

    /** Replays saved lifecycle events for the current Hook modules. */
    fun replayAllHooks() {
        for (module in hookModules) {
            val systemServer = savedSystemServerParam
            if (systemServer != null) {
                try {
                    module.safeHandleSystemServerStarting(systemServer)
                } catch (_: Throwable) {
                }
            }
            for (param in savedPackageParams) {
                try {
                    module.safeHandleLoadPackage(param)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
