# Deprecated and Retired Features Ledger

This ledger tracks features deprecated, version-gated, or retired across major Android and ZUXOS platform upgrades, ordered reverse-chronologically.

## Maintenance Contract

Whenever an Android or ZUXOS major version upgrade introduces native parity or removes host targets:

1. Append a new top-level `## Android <N> / ZUXOS <M> (API <SDK>, Build.VERSION_CODES.<CODENAME>)` section right below this contract.
2. Enforce the upper bound in both the Compose UI (`SettingItem.maxSdkVersion`, and conditional child-row guards where applicable) and the Hook entry point (`handleLoadPackage` / `handleSystemServerStarting`) so stale preferences cannot run silently.
3. Record each affected feature's name, UI key, `PreferenceKeys` key, Hook class, maximum supported SDK (`Build.VERSION_CODES.*`), deprecation reason, and native or ZTool replacement.

---

## Android 17 / ZUXOS 2.5 (API 37, `Build.VERSION_CODES.CINNAMON_BUN`)

### 1. Legacy Forced Split Screen & Forced Floating Window
- **Feature**: Force Split Screen (`强制分屏`) and Force Floating Window (`强制小窗`, legacy `force_resizable_activities` / `OneModeService` path)
- **Preference / UI Keys**: `PreferenceKeys.SPLIT_SCREEN_MANDATORY` (`Split_Screen_mandatory`); UI keys `settings_detail_split_screen_mandatory`, `settings_detail_float_app_mandatory`
- **Hook Classes**: `hook/modules/systemframework/SplitScreenMandatory.kt`, `hook/modules/setting/SplitScreenMandatory.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.BAKLAVA` (API 36); hooks return early on `> BAKLAVA`, and the UI section switches to native `OvCommon` management on `SDK_INT >= 36`
- **Reason**: `com.android.server.wm.OneModeService` was removed in ZUXOS 2.0+; split-screen and floating-window compatibility moved to `OvCommonService` / `OvCommonCompatManager`.
- **Replacement**: Native Settings -> One Vision (`设置 -> 一视界`) per-app managers (`SplitScreenManageFragment` and `FloatingWindowListFragment`), paired with `UnlockOvCommonAppListHook` (`settings_detail_unlock_ov_common_app_list`) when system or hidden packages are needed.

### 2. Freeform Sidebar Drawer Enhancement
- **Feature**: Enhance Sidebar Drawer (`增强侧边栏抽屉` — capacity expansion, drawer reordering, recent slots, pinyin sections, and search)
- **Preference / UI Keys**: `PreferenceKeys.SIDEBAR_DRAWER_ENHANCE` (`sidebar_drawer_enhance`); UI keys `sidebar_drawer_enhance`, `sidebar_drawer_editable`, `sidebar_drawer_slots`, `sidebar_drawer_recent_slots`, `sidebar_allapps_sections`, `sidebar_allapps_search`
- **Hook Class**: `hook/modules/freeformbar/SidebarDrawerHook.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.BAKLAVA` (API 36); hook returns early on `> BAKLAVA` and also skips when legacy `com.zui.freeform.sidebar.ui.AppIconListAdapter` is absent
- **Reason**: `ZuiFreeformBar 1.18.5.x` on ZUXOS 2.5 natively expands drawer capacity to 16 apps, supports in-drawer editing (`mFootViewEdit`) and drag-to-reorder (`ItemTouchHelper.Callback`), and ships with full R8 obfuscation.
- **Replacement**: Native `com.zui.freeform.sidebar` drawer management on ZUXOS 2.5.

### 3. ZUI Force Config XML Editor
- **Feature**: Direct XML editor actions for forced split screen, forced freeform scaling, and forced fixed-ratio floating windows (`强制应用支持分屏` / `强制自由小窗` / `强制固定比例小窗`)
- **Preference / UI Keys**: UI keys `settings_detail_zui_force_split`, `settings_detail_zui_force_freeform`, `settings_detail_zui_force_fixed` (edited `/data/system/zui/ov_common_persist_user_0.xml` directly via root)
- **Helper Class**: `utils/OvCommonConfigManager.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.BAKLAVA` (API 36)
- **Reason**: ZUXOS 2.5 introduces native `FloatingWindowListFragment` -> `FloatingWindowDetailFragment` (per-app floating-window switch plus free/uniform scaling mode selector with live `OV_COMMON_COMPAT_CONFIG_CHANGED` cache refresh) alongside `SplitScreenManageFragment`, and adds `overrideDesktopDragMode` persistence that legacy XML writes do not preserve.
- **Replacement**: Native Settings -> One Vision (`设置 -> 一视界`) floating-window and split-screen app managers, combined with the new **Unlock native floating-window and split-screen app lists** (`解锁原生浮窗与分屏应用列表`, UI key `settings_detail_unlock_ov_common_app_list`, `PreferenceKeys.UNLOCK_OV_COMMON_APP_LIST`, `UnlockOvCommonAppListHook` hooking `OvCommonCompatManager.visibleOvCommon`) on API 36+ to expose hidden, system, and preloaded packages.

### 4. Media Output Dialog Centering
- **Feature**: Center Media Output Dialog (`媒体输出弹窗居中`)
- **Preference / UI Keys**: `PreferenceKeys.MEDIA_OUTPUT_DIALOG_CENTER` (`media_output_dialog_center`); UI key `control_center_media_output_dialog_center`
- **Hook Class**: `hook/modules/systemui/qs/MediaOutputDialogCenterHook.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.BAKLAVA` (API 36); hook skips on `SDK_INT >= 37`
- **Reason**: ZUXOS 2.5 centers the media output dialog natively (`MediaOutputDialogDelegate`).
- **Replacement**: Native ZUXOS 2.5 behavior (no hook needed; `MediaOutputDialogColorHook` remains active independently).

### 5. Control Center Shade Rebound Fix
- **Feature**: Fix Control Center Rebound Delay (`修复控制中心回弹延迟`)
- **Preference / UI Keys**: `PreferenceKeys.SHADE_REBOUND_FIX` (`shade_rebound_fix`); UI key `system_ui_misc_shade_rebound_fix`
- **Hook Class**: `hook/modules/systemui/misc/ShadeReboundFix.kt`
- **Supported SDK Range**: `Build.VERSION_CODES.VANILLA_ICE_CREAM` (API 35) .. `Build.VERSION_CODES.BAKLAVA` (API 36)
- **Reason**: Fixed natively in ZUXOS 2.0+ (`QsReboundFeature` delay no longer occurs).
- **Replacement**: Native ZUXOS system behavior.

### 6. Legacy SystemUI Face-Auth Timeout Bypass
- **Feature**: Bypass Weak Face-Auth Idle/Fallback Timeout in SystemUI (`跳过人脸识别闲置超时`)
- **Preference / UI Keys**: `PreferenceKeys.BYPASS_FACE_AUTH_TIMEOUT` (`bypass_face_auth_timeout`); UI key `system_ui_misc_bypass_face_auth_timeout`
- **Hook Class**: `hook/modules/systemui/keyguard/BypassFaceAuthTimeout.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.BAKLAVA` (API 36); hook skips on `> BAKLAVA`
- **Reason**: `ZuiFaceAuthDelegate` and `KeyguardFaceUnlockManager` were removed on Android 17; timeout gates moved to platform `LockSettingsStrongAuth`.
- **Replacement**: `BypassWeakBiometricTimeout` (`framework_bypass_weak_biometric_timeout`, `PreferenceKeys.BYPASS_WEAK_BIOMETRIC_TIMEOUT`, `minSdkVersion = Build.VERSION_CODES.CINNAMON_BUN`) on the System Framework page.

### 7. AOSP Scroll Capture Reroute
- **Feature**: Restore AOSP Scroll Capture (`AOSP 滚动截屏`)
- **Preference / UI Keys**: `PreferenceKeys.FORCE_LONG_SCREENSHOT_AOSP` (`force_long_screenshot_aosp`); UI key `system_ui_misc_aosp_scroll_capture`
- **Hook Class**: `hook/modules/systemui/misc/AospScrollCaptureHook.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.BAKLAVA` (API 36)
- **Reason**: ZUXOS 2.5 removed `com.android.systemui.screenshot.scroll.LongScreenshot` and replaced the AOSP scroll-capture classes with empty stubs.
- **Replacement**: Stock Moto long-screenshot path (no active ZTool replacement).

### 8. Legacy Desktop Launcher Portrait Hooks
- **Feature**: Work-mode desktop launcher portrait layout & 2-line icon labels (`PcDesktop` / `com.zui.desktoplauncher` client hooks)
- **Preference / UI Keys**: `PreferenceKeys.WORK_MODE_PORTRAIT` (`work_mode_portrait`, shared with `WorkModePortrait` and `PcTaskbarCompactControls`)
- **Hook Classes**: `hook/modules/desktoplauncher/WorkDesktopPortrait.kt`, `hook/modules/desktoplauncher/PcAllAppsPortraitGrid.kt`, `hook/modules/desktoplauncher/WorkspaceLabelTwoLine.kt`
- **Max Supported SDK**: `<= Build.VERSION_CODES.BAKLAVA` (each hook returns when `Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN`)
- **Reason**: ZUXOS 2.5 migrated desktop mode to `ZuiDesktopMode` (`OVDesktopController` in `system_server`).
- **Replacement**: `WorkModePortrait` (`hook/modules/systemframework/WorkModePortrait.kt`, which hooks `OVDesktopController.fixZuiDesktopModeOrientation` on Android 17+) and `PcTaskbarCompactControls`.

---

## Android 16 / ZUXOS 1.5–2.0 (API 36, `Build.VERSION_CODES.BAKLAVA`)

### 1. Custom Lock-Screen Clock Color
- **Feature**: Custom Lock-Screen Clock Color (`自定义锁屏时钟颜色`)
- **Preference / UI Keys**: `PreferenceKeys.LOCK_SCREEN_CLOCK_COLOR_CUSTOM` (`lock_screen_clock_color_custom`); UI key `lock_screen_clock_color`
- **Hook Class**: `hook/modules/systemui/keyguard/LockScreenClockColorHook.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.VANILLA_ICE_CREAM` (API 35); hook skips on `SDK_INT >= 36`
- **Reason**: ZUXOS 1.5 natively provides lock-screen clock color customization.
- **Replacement**: System Settings -> Wallpaper & theme style -> Lock screen (`设置 -> 壁纸和主题风格 -> 锁屏`).

### 2. Native Status-Bar Notification Icons
- **Feature**: Use Native Notification Icons (`使用原生通知图标`)
- **Preference / UI Keys**: `PreferenceKeys.NATIVE_NOTIFICATION_ICON` (`NativeNotificationIcon`); UI key `status_bar_notification_icon_native`
- **Hook Class**: `hook/modules/systemui/statusbar/NativeNotificationIcon.kt`
- **Max Supported SDK**: `Build.VERSION_CODES.VANILLA_ICE_CREAM` (API 35)
- **Reason**: Deprecated in ZUXOS 1.5+ (API 36+) UI.
- **Replacement**: Native ZUXOS status-bar notification icon behavior.

---

## Android 14–15 (API 34–35, `Build.VERSION_CODES.UPSIDE_DOWN_CAKE` / `VANILLA_ICE_CREAM`)

### 1. Userspace Soft Reboot Entry
- **Feature**: Soft Reboot (`RebootTarget.Userspace`) action in the Home top-bar reboot menu
- **UI Location**: `screens/home/HomeRoute.kt` (dropdown item filtered out when `SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`)
- **Max Supported SDK**: API 33 (`TIRAMISU`, filtered below API 34)
- **Reason**: Userspace reboot reliability/support changed on Android 14+ devices.
- **Replacement**: System reboot (`RebootTarget.System`) or scoped service restart (`ScopeUtils.restartScope`).
