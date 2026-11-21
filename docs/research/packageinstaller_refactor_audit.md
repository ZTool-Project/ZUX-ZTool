# PackageInstaller A17 Audit & Unified OEM Hook Refactor

Status: implemented; debug build and unit tests verified. On-device verification
requires building and installing `app-dev.apk` (`.\gradlew.bat assembleDev`).

Evidence date: 2026-11-21. Host evidence comes from the `com.android.packageinstaller`
APK loaded in JADX MCP (`versionName="7.3.0.1.0635"`, `versionCode=73010635`,
`minSdkVersion=33`, `targetSdkVersion=37`).

## Decision

Keep [PackageInstallerStyleHook](../../app/src/main/java/com/qimian233/ztool/hook/modules/packageinstaller/PackageInstallerStyleHook.kt)
as the dedicated `AppHookModule` for switching to AOSP installer mode
(`PACKAGE_INSTALLER_STYLE_HOOK`).

Unify the five Lenovo OEM (`*Extra`) capabilities into one owner
[LenovoPackageInstallerHook](../../app/src/main/java/com/qimian233/ztool/hook/modules/packageinstaller/LenovoPackageInstallerHook.kt)
backed by pure-Kotlin policies in
[PackageInstallerPolicy](../../app/src/main/java/com/qimian233/ztool/hook/modules/packageinstaller/PackageInstallerPolicy.kt),
following the same owner + policy pattern as `SystemUINetworkSpeedHook`:

- Retain `PreferenceKeys.DISABLE_SCAN_APK.name` as `getModuleName()` so no new
  preference key or master switch is needed; override `isEnabled()` to require
  `!nativeStyle && (disableInstallerAd || disableDeletePackage || disableScanApk || alwaysAllowPermission || skipWarnPage)`.
- Retire `DisableInstallerAdvertisement`, `PackageInstallerNoDeleteModule`,
  `PackageInstallerHookScan`, `PackageInstallerPermissionHook`, and `SkipInstallWarnPage`.
- Keep all six `PreferenceKeys`, UI screens, ViewModel, Repository, `SearchIndex`,
  `ScopeKeys`, `scope.list`, and string resources unchanged.

## Six-Feature Audit Findings on Android 17

| Feature | Previous defect / gap on A17 | Resolution |
| --- | --- | --- |
| 原生安装器 (`PackageInstallerStyleHook`) | `doNotShowWarnTextHook` resolves `R$id.install_confirm_question_warning` inside every `startInstallConfirm` callback; that ID no longer exists on A17, throwing `NoSuchFieldException`. Callbacks lacked `isEnabled()` guards and used unnamespaced hook IDs. | Resolve `install_confirm_question_warning` once at install time and skip hooking if absent; guard all callbacks with `isEnabled()`; namespace hook IDs (`pi_native_*`). |
| 去广告 (`DisableInstallerAdvertisement`) | Only blocked `InstallSuccessExtra.initRecommendAppsData()`. Missed `InstallInstallingExtra.getRecommendApp()` HTTP request, `PackageInstallerActivityExtra.loadExpressAd()` (`LenovoUnifiedNativeAd` + `fakeView`), and `PackageInstallerApplication` `LenovoAdManager.init(...)`. | Short-circuit all four ad entry points (`initRecommendAppsData`, `getRecommendApp`, `loadExpressAd`, `LenovoAdManager.init`) in `installAdBlock`. |
| 不删除安装包 (`PackageInstallerNoDeleteModule`) | Hooked `clearCachedApkIfNeededAndFinish()`, which runs *after* `file.delete()` in `InstallSuccessExtra$6.lambda$onClick$0()`; left stale listener before `isChecked = false`. | Hook `InstallSuccessExtra.initView()` only; clear previous `OnCheckedChangeListener` before unchecking; keep `mDeleteApk = false` via guarded listener. |
| 跳过安全扫描 (`PackageInstallerHookScan`) | `handler.javaClass.getDeclaredMethod("sendEmptyMessage", Int::class.javaPrimitiveType)` failed on anonymous `Handler` subclass `PackageInstallerActivityExtra$2` (`NoSuchMethodException`). `showResultIfFinish` hook forced `mCheckSafeInstallResult = 1`, which actually enables the AppStore warning/choice layout and double-triggers `showResultIfFinish()`. `startTime()` 1 s timer still ran, and `findSafeResource()` still performed a cloud HTTP scan. | Cast `mHander` directly to `android.os.Handler`; hook both local (`bindSafeService`, `startScanApps`, `startTime`) and cloud (`findSafeResource`) backend scans; populate clean safe local/cloud fields (`mScanAppResult = 2`, `scanIsUnsafe = false`, `forbidInstall = false`, `mCheckSafeInstallResult = -1`, `gotoSearch = -1`, `interceptPrompt = 0`, `mInstallType = 0`, `mTargetUrl = null`). |
| 总是授权 (`PackageInstallerPermissionHook`) | Shrinking `PermissionsAdapter` to 1 item (`[trustItem]`) caused clicking row `0` to call `AppPermsInfoData.setPermsManageType(0)` (reverting to system default) and clicking `!` sent `arg1 = 0` (`DLG_PERMS = 10` instead of `DLG_PERMS_ALL_ALLOW = 12`). Installed global `ListView.setAdapter` and `PermissionsAdapter.getCount()` hooks. | Set `mSelectId = 2` before `startCustomInstallConfirm()`, filter `PermissionsAdapter` constructor list to `index == 2`, rewrite `AppPermsInfoData.setPermsManageType` to `2`, and rewrite `showDialogInner(10)` to `12`. Removed `ListView.setAdapter` and `getCount` hooks. |
| 跳过未上架警告页 (`SkipInstallWarnPage`) | Hooked `onResume()` with a 50 ms delay calling `handleDirectInstallInFindSameAppCase()`: (1) raced before `findSafeResource()` + `showResultIfFinish()`, when `isNext == false` (`onDirectInstall` no-oped and `showResultIfFinish()` overwrote the UI); (2) on A17 `safeInstallEnable` (`Settings.Global["pi_safeinstall_switch"]`, default `1`) is `true`, so `handleDirectInstallInFindSameAppCase()` binds `IdService` and prompts for Lenovo ID password or lock screen verification. | Leave backend scans intact when `disableScanApk` is off; in `showResultIfFinish()`, rewrite arrived cloud warning fields (`mCheckSafeInstallResult != 0`) via `clearCloudWarningState(...)` before `proceed()`, hide `safeInstallSettingContainer`, bypass `handleDirectInstallInFindSameAppCase()` verification, and once the dual-result barrier passes (`!forbidInstall`, `mScanAppResult != 3`, `isNext`, `!isClicked`), advance directly via `verifyType = 1` + `onVerifySuccess()`. |

## Verified Host Contract

| Host member | Verified behavior in `7.3.0.1.0635` |
| --- | --- |
| `InstallStart.onCreate` | Calls `Utils.isCTSandGTS(launchedFromPackage, intent)`. Branches to `InstallLaunch` (Pia V2) / `InstallStaging` / `PackageInstallerActivity` when true, else `InstallStagingExtra` / `PackageInstallerActivityExtra`. |
| `PackageInstallerActivityExtra.processPackageUri` | Calls `showScanApp()`, starts async `findSafeResource()` (`mHander` msg `7`), calls `bindSafeService()` (`com.lenovo.safe.antivirusengine.aidl.ScanAppService` -> `startScanApps()` -> msg `2`/`3`), and starts `startTime()` (5 s timeout -> msg `4`). |
| `PackageInstallerActivityExtra.showResultIfFinish` | Barrier: returns early until `mCheckSafeInstallResult != 0 && mScanAppResult != 0`. Shows `detail_find` / `detail_update`, `goSearchWarningContainer`, `appstoreWarningContainer`, `confirm_install_check_container`, and `pi_install_and_safeinstall*` when `gotoSearch != -1 || mCheckSafeInstallResult == 1` (if `!isCommercial && !isClicked`). Calls `scanCompleted()` (`isNext = true`) unless AppStore install or virus/blacklist blocked. |
| `PackageInstallerActivityExtra.onVerifySuccess` | Checks `verifyType == 1` (`VERIFY_TYPE_DIRECT_INSTALL`), inflates `pi_normal_button_layout`, hides `mChoicePanel`, calls `onDirectInstall()` -> `initiateInstall()` -> `startCustomInstallConfirm()`, and calls `fakeView.performClick()` if present. |
| `PackageInstallerActivityExtra.mHander` | Anonymous subclass `$2`: `1 -> startScanApps()`, `2 -> mScanAppResult = 2; showResultIfFinish()`, `3 -> mScanAppResult = 3; showResultIfFinish()`, `4 -> mScanAppResult = 4; showResultIfFinish()`, `5 -> showDialogInner(arg1 == 2 ? 12 : 10)`, `7 -> showResultIfFinish()`. |
| `PackageInstallerApplication.onCreate` | Spawns a thread calling `LenovoAdManager.init(this, "40214", oaid, "", false)`. |
| `InstallInstallingExtra.onResume` | Reads `Settings.Global["pi_recommendapp_switch"]` (default `0` on not-found) and calls `getRecommendApp()`. |
| `InstallSuccessExtra.initView` | Calls `initRecommendAppsData()`, sets `mDeleteApk = true` when `!isConfigChanage`, attaches `R.id.del_check_box` listener; click handler runs `file.delete()` before `clearCachedApkIfNeededAndFinish()`. |

## Unified Capability Matrix & Runtime Coordination

- **Scan Skip vs. Warning Skip Boundary**: `disableScanApk` shuts down both backend
  scans (`bindSafeService` / `startScanApps` / `startTime` locally and `findSafeResource`
  cloud check) and fills safe non-warning defaults (`mScanAppResult = 2`,
  `scanIsUnsafe = false`, `forbidInstall = false`, `mCheckSafeInstallResult = -1`,
  `gotoSearch = -1`, `interceptPrompt = 0`, `mInstallType = 0`, `mTargetUrl = null`).
  `skipWarnPage` leaves backend scans untouched when `disableScanApk` is off, rewrites
  arrived cloud warning fields inside `showResultIfFinish()`, hides
  `safeInstallSettingContainer`, bypasses `handleDirectInstallInFindSameAppCase()`,
  and advances past the warning/intermediate page via `onVerifySuccess()`.
- **Coupled Scan & Warning Barrier**: `findSafeResource()` (`what = 7`) and
  `bindSafeService()`/`startScanApps()` (`what = 2`) share the `mHander` queue.
  `shouldEnqueueCompletionMessage(hasScanOkQueued, hasSafeCheckQueued)` avoids
  double-queuing when both run together.
- **Rotation / Config Change**: `showScanApp()` resets `InstallFlowState.advancedToConfirm`
  and clears `isClicked` when `skipWarnPage && isConfigurationChanged`, preventing
  `scanCompleted()` from calling `initiateInstall()` without inflating `pi_normal_button_layout`.
- **Dynamic Option Intersect**: Runtime callbacks read `readOptions().intersect(installed)`
  so disabling a feature passes through immediately while never relying on
  uninstalled hook groups.

## Verification Scope

- Compiled via `.\gradlew.bat assembleDebug` and verified policy behavior via
  `PackageInstallerPolicyTest` and `SearchIndexConsistencyTest`.
- On-device runtime behavior on ZUI / Android 17 with LSPosed requires installing
  the R8-minified dev variant built with `.\gradlew.bat assembleDev` (`app-dev.apk`).
