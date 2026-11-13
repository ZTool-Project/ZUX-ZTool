# UDS Engine (com.lenovo.tbengine) hook audit — V1.1.1.261029

Analysis note. Static review of every ZTool reflection target in
`app/src/main/java/com/qimian233/ztool/hook/modules/tbengine/` against the UDS Engine build that
ships on the Android 17 / ZUX-OS test device.

Facts that scope this note:

| Item | Value |
|---|---|
| APK under analysis | `com.lenovo.tbengine`, `versionName=V1.1.1.261029`, `versionCode=999000002`, `targetSdk=36` |
| Device | TB710FU, `/system/priv-app/UDSEngine`, same `versionName`, `ro.build.ab_update=true`, SDK 37 |
| Hook sources | 7 modules: `DisableTbEngineAutoDownload`, `DisableTbEngineAutoInstall`, `DisableTbEngineAppUpdate`, `DisableTbEnginePush`, `DisableTbEngineReporting`, `SignTbEngineLocalOta`, `LenovoOTAHook` |
| Method | each `loadClass`/`findMethod`/`findField` target resolved in the APK, then every hook method's call sites (`xrefs`) inspected |

`com.lenovo.tbengine` is still unobfuscated at the reference level: all class, method and field
names ZTool uses are present verbatim, so no hook fails to install with `NoSuchMethodException` in
this version. The problems are **reachability**, not resolution.

## 1. Resolution and reachability of every hook target

| Module / hook id | Reflection target | Resolves | Call sites in 261029 |
|---|---|---|---|
| AutoDownload / `tbengine_auto_download_start` | `ServiceController.startOrResumeDownload(Context)` | yes | `NotificationReceiver.onReceive`, `SwfNewVersionFound.startDownload`, `SwfDownloading.MyJobService.onStartJob`, `AppDataDownloading.MyJobService.onStartJob`, `MessengerService` (manifest-disabled) |
| AutoDownload / `tbengine_auto_download_policy_set` / `_get` | `OtaPolicy.set/getmSettingNormalAutoDownload` | yes | setter: `NotificationReceiver` (`change.wifi.auto`), `SwfNewVersionFound` (OOBE block); getter: `SwfNewVersionFound.startDownload`, `NotificationCenter.showNewVersionNotification`, `SwfNewVersionFound.doMyUiBackgroundJob` |
| AutoInstall / `tbengine_auto_install_ab` | `ServiceController.startABInstalling(Context)` | yes | `NotificationReceiver`, `SwfABVerifyPackage.doMyPrimaryJob`, `SwfABInstalling.MyJobService.onStartJob`, `canInstallNow`, `canPostpone`, `MessengerService` (disabled) |
| AutoInstall / `tbengine_auto_install_recovery` | `android.os.RecoverySystem.installPackage(Context, File)` | yes (API 37) | only `SwfRebootInstall.doMyPrimaryJob` — **workflow never instantiated on A/B** |
| AutoInstall / `tbengine_auto_install_policy_set` / `_get` | `OtaPolicy.set/getmSettingNormalAutoInstall` | yes | post-install reboot decisions only: `SwfABComplete.onStartCommand`, `SwfABInstalling.dealWithInstallComplete`, `rebootIfSatisfied`, `setAlarm`, `SwfCheckBattery` (A-only) |
| AppUpdate / `tbengine_appdata_auto_download` | `ServiceController.startOrResumeAppDataDownload(Context)` | yes | `AppDataNewVersionFound.doMyUiBackgroundJob`, `WhatsnewService.MessengerHandler` |
| Push / `tbengine_push_init_channel` | `PushControls.initPushChannel()` | yes | `MyApplication.onCreate`, `SmartScenarioController` (device provisioned), `SystemBroadcastReceiver` (network up) |
| Push / `tbengine_push_init_receiver` | `PushControls.registerInitReceiver()` | yes | **none** |
| Reporting / `tbengine_reporting_insert` | `ContentResolver.insert(Uri, ContentValues)` | yes | `PromptUtils.sendData` (`com.lenovo.ue.device.provider`), `AvatarPlugin`, `a.a.a.b.b.a` (`my.upsreport`) |
| Reporting / `tbengine_reporting_register_token` | `UpsApp.registToken(String)` | yes | `UpsApp.AnonymousClass1.onResult` (UPS register callback) |
| LocalOta / `tbengine_local_ota_sign` | `SwfABInstalling.doMyPrimaryJob()` | yes (package-private) | `SwfBase.startMyPrimaryJobThreadIfNotRunning` worker thread, plus `SwfABComplete.doMyPrimaryJob` / the `SwfABInstalling` inner receiver |
| LocalOta / `tbengine_apply_payload_pubkey` | `android.os.UpdateEngine.applyPayload(String, long, long, String[])` | yes | `SwfABInstalling.doMyPrimaryJob` (`mUpdateEngine`, 4-arg form unchanged) |
| LenovoOTAHook / `target` | `ServerApi.geServerResponseOrThrowError(String, Properties, String)` | yes — **now `private`** | `doQueryNewVersion`, `doQueryServerConfiguration` |

`SignTbEngineLocalOta`'s `extractContext` still works: `SwfBase` keeps the package-private field
`mService`, assigned in the `SwfBase(MainService)` constructor.

## 2. Findings

### 2.1 Dead target: `PushControls.registerInitReceiver()`

The method still exists, but nothing in the APK calls it, and its implementation delegates to
`UpsApp.registerReceiver()`, which is an **empty method body** together with
`getPushToken`/`sendTokenToService`/`setAutoInitEnabled`/`unRegisterReceiver`. The whole
`PushChannel` callback half is stubbed out in this version; only `initChannel()` does work.
`hook id tbengine_push_init_receiver` therefore intercepts nothing and can be dropped.
Push initialisation itself is still fully covered by `initPushChannel()` (all three call sites
funnel through it, and `UpsApp.initChannel` has no other caller).

### 2.2 Unreachable on A/B: `RecoverySystem.installPackage`

`MainService.initWorkFlowTable()` adds `WorkFlow.REBOOT_INSTALL → SwfRebootInstall` only when
`OtaPolicy.isABUpdate()` is false, i.e. when `ro.build.ab_update != "true"`. The test device reports
`ro.build.ab_update=true`, so `SwfRebootInstall` (the only `RecoverySystem.installPackage` caller) is
never created and the hook never fires. The hook stays useful on A-only ZUI devices only; on A/B it
can be considered a no-op that still costs a hook registration.

### 2.3 Weakened: the auto-install policy bit is no longer the install gate

`SwfABVerifyPackage.doMyPrimaryJob()` calls `ServiceController.startABInstalling(...)`
unconditionally once the MD5 check passes — it does not consult
`OtaPolicy.getmSettingNormalAutoInstall()`. That bit now only drives post-install reboot handling
(job 100 / `JOB_NOTIFY_REBOOT`, `dealWithInstallComplete`, `rebootIfSatisfied`). Blocking
`startABInstalling` remains the only effective gate; the policy hooks are useful extra ballast
against auto-reboot, not the primary switch.

By contrast `getmSettingNormalAutoDownload()` is still consulted by `SwfNewVersionFound.startDownload`,
but that method also has a branch that starts a download from server config alone
(`isCommercialFunction()` + `DOWNLOAD_MODE.WIFIAUTO`, and `forceDownloadOnWifi()`), which ignores the
policy bit. Hooking `startOrResumeDownload` must stay.

### 2.4 Still effective, with caveats

- `ServerApi.geServerResponseOrThrowError` is now `private` (`ACC_PRIVATE`). Irrelevant for the hook:
  `HookReflectionHelper.findMethod` uses `getDeclaredMethod` + `setAccessible(true)`.
  The interception still changes the wire request — `doQueryNewVersion` still passes the URL
  `https://ota.lenovo.com/engine/upgrade?` (matches the `"upgrade"` filter) and
  `HttpTool.requestPostForm` encodes the `Properties` (still containing `curfirmwarever` and
  `deviceid`) into the query string.
- Reporting: both blocked authorities are still in use (`PromptUtils` inserts into
  `content://com.lenovo.ue.device.provider/{app,platform,special}`, the UPS report helper into
  `content://my.upsreport/upsreport`), so the choke-point hook still covers the current reporters.
- The three `ServiceController` gateways are still the only writers of the
  `DOWNLOADING` / `AB_INSTALLING` / `APP_DATA_DOWNLOADING` workflow states (`WorkFlowManager`), so
  they remain the correct choke points.
- Caveat (not a version regression): `MainService` dispatches by the **persisted** `WorkFlow` state,
  and `SwfDownloading`, `AppDataDownloading`, `SwfABInstalling`, `SwfABVerifyPackage` all resume
  their primary job on `com.lenovo.tbengine.service.main.boot_complete`. The `ServiceController`
  hooks block new triggers only; a workflow that is already parked in a download/install state
  continues after a reboot without passing through them.

## 3. Consequences for the module

| Hook id | Verdict for V1.1.1.261029 |
|---|---|
| `tbengine_push_init_receiver` | dead — remove |
| `tbengine_auto_install_recovery` | only A-only devices — keep but stop claiming A/B coverage |
| `tbengine_auto_install_policy_set/_get` | no longer the install gate — keep as reboot belt |
| everything else (9 hooks) | still resolves and still sits on a live path |

The earlier `public_key` finding still holds: update_engine on ZUI verifies through
`/system/etc/security/otacerts.zip`, so `tbengine_apply_payload_pubkey` installs but has no effect
until the certificate module is present.

## 4. How to re-run this audit

```text
jadx MCP: get_android_manifest → version
          get_methods_of_class / get_fields_of_class per target class
          get_xrefs_to_method per hook method (0 xrefs = dead target)
          search_classes_by_keyword for intent actions / authorities
device:   adb shell dumpsys package com.lenovo.tbengine | findstr versionName
          adb shell getprop ro.build.ab_update
```
