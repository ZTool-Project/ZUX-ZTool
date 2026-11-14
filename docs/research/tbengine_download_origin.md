# UDS Engine: distinguishing a user-started download from an automatic one

Analysis note. Question: inside `com.lenovo.tbengine`, is there an unobfuscated point that tells
"the user downloaded this build themselves, then it installed itself" apart from "the engine
downloaded this build on its own, then installed it"?

Companion note: [tbengine_hook_audit_261029.md](tbengine_hook_audit_261029.md) covers whether the
existing hook targets still resolve and are reachable. This note covers the *origin* of a download.

| Item | Value |
|---|---|
| APK under analysis | `com.lenovo.tbengine`, `versionName=V1.1.1.261029`, `versionCode=999000002`, `targetSdk=36` |
| Process model | single process; no `android:process` on any component, `android:persistent="true"` |
| Method | jadx MCP: class/field dumps, `get_xrefs_to_method` on every candidate, manifest components |

## 0. Verdict

| Question | Answer |
|---|---|
| Is there a code path that differs between user download and auto download? | Only at the **download trigger**. Verify → install is one shared path for both |
| Does anything install-side read the download origin? | **No.** The install decision (`SwfABInstalling.canInstallNow`, `SwfCheckBattery.doMyUiBackgroundJob`) never consults it |
| Is the origin recorded anywhere unobfuscated and durable? | **Yes** — `OtaPolicy.isDownloadByUser()` / field `mIsDownloadByUser` / pref `mIsDownloadByUser`, persisted at download start and still valid at install time |
| Best hook shape | Read that flag inside the install gate instead of swallowing every install entry |

## 1. The two download entries

Everything user-initiated funnels through one method; everything automatic through another.

| Origin | Entry (unobfuscated, public static) | Workflow intent |
|---|---|---|
| **User** | `ServiceController.userStartOrResumeDownload(Context)` | `com.lenovo.tbengine.service.workflow.user.download.start.or.resume` |
| **Automatic** | `ServiceController.startOrResumeDownload(Context)` | `com.lenovo.tbengine.service.workflow.download.start.or.resume` |
| **Automatic (timer)** | `ServiceController.notifyAutoDownload(Context)` | `com.lenovo.tbengine.service.trigger.auto.download` |

Callers of `userStartOrResumeDownload` (4 xrefs):

- `NotificationReceiver.onReceive` — the three `com.lenovo.ota.user.start.or.resume.download*`
  broadcasts sent by the OTA UI / panel app.
- `OtaBroadCastReceiver.onReceive` — the notification action buttons `android.action.start.download`
  and `android.action.resume.download`.
- `MessengerService` — **`android:enabled="false"` in this build**, never invoked.
- `SwfDownloading.DownloadWatchDogTask` — see the leak in §2.3.

Callers of `startOrResumeDownload`: `SwfNewVersionFound.startDownload` (the automatic discovery
path, incl. its server-config `WIFIAUTO` branch), `NotificationReceiver` (the bare
`com.lenovo.ota.start.or.resume.download` action), `SwfDownloading.MyJobService.onStartJob`
(idle auto-resume), `AppDataDownloading.MyJobService.onStartJob`.

Both then drive the identical workflow: `SwfDownloading` → `SwfABVerifyPackage` (or
`SwfVerifyPackage`) → install. **Nothing downstream of the download is origin-aware**, so a hook
placed only on the install side has no way to infer the origin from the call path.

## 2. The discriminator: `OtaPolicy.mIsDownloadByUser`

### 2.1 Surface

| Member | Kind |
|---|---|
| `OtaPolicy.isDownloadByUser(): boolean` | public getter |
| `OtaPolicy.setDownloadByUser(boolean): void` | public setter, calls `saveToSharedPreference()` |
| field `OtaPolicy.mIsDownloadByUser` | private boolean |
| SharedPreferences `lenovo_ota_row_ota_policy`, key `mIsDownloadByUser` | persisted value |

`OtaPolicyManager.getOtaPolicy(Context)` returns the process-wide singleton, reloading from
SharedPreferences on first use in a process. `OtaPolicyManager.commit(OtaPolicy)` writes it back.

### 2.2 Lifecycle

| event | value | evidence |
|---|---|---|
| user download starts | **true**, persisted | `ServiceController.userStartOrResumeDownload` → `setDownloadByUser(true)` — the only writer of `true` (2 xrefs to the setter total) |
| download cancelled by the user | false, persisted | `SwfDownloading.downloadCancelled` |
| update finished / install failed | false | `OtaPolicy.setUpdateFinished()` / `setUpdateFinishedForInstallFaild()` — the first is followed by `OtaPolicyManager.commit(...)` in `SwfABComplete` (post-install boot) and `SwfRebootInstall.doMyUiBackgroundJob` |
| auto download starts | **unchanged** | `startOrResumeDownload` does not touch it |

So at verify/install time the value still describes *who started this download*: `true` for a user
download, `false` for a background one. `setUpdateFinished()` only mutates memory, but every caller
that matters commits right after, so SharedPreferences does not keep a stale `true` past a completed
update cycle.

### 2.3 Who reads it today — and the one leak

`isDownloadByUser()` has 9 xrefs, and **all of them are presentation**:

| Reader | Purpose |
|---|---|
| `SwfDownloading.sendProgressUpdate` / `sendPauseNotification` / `onStartCommand` | show or suppress download notifications |
| `SwfABInstalling.doMyPrimaryJob` (3×), `AnonymousClass1..4` | show or suppress install pause / progress notifications |
| `AvatarPlugin.sendDownloadResult` | analytics `type1 = isDownloadByUser() ? 1 : 2` (1 = user, 2 = auto) |

Not one install decision reads it. `AvatarPlugin`'s `type1` mapping is the vendor's own
confirmation that the field means exactly "user-started vs automatic".

Leak to be aware of: `SwfDownloading.DownloadWatchDogTask` also calls
`ServiceController.userStartOrResumeDownload` when it auto-resumes a download parked in
`STOPPED`/`INIT` with good battery and the screen off for 30 min. That path sets the flag to
`true` for a download the user did not (re)start. A hook that needs strictness should not treat
the flag as a hard guarantee.

`OtaPolicy.setUpdateFinished()` resetting the flag is also the reason the current hook cannot tell
"install already blocked earlier vs not": the flag is cleared after the install completes.

### 2.4 Secondary signal: `download_from`

`OtaPolicy.getDownload_from()` / `setDownload_from(int)` (pref key `download_from`) is set to `2`
or `1` by the OTA UI entrance only — `NotificationReceiver` and `MessengerService` in the
`com.lenovo.ota.user.start.or.resume.download*` branches, from a boolean extra. The notification
button path (`OtaBroadCastReceiver`) does **not** set it, and the automatic path never touches it,
so it stays `0` only until the first UI-entrance download and is never reset. Use it as
"which UI entrance was used", never as "user vs auto".

## 3. Install entry points, and what each can tell

| Path | Trigger | Method to gate | Origin visible? |
|---|---|---|---|
| A/B, automatic | `SwfABVerifyPackage.doMyPrimaryJob` calls `ServiceController.startABInstalling` unconditionally after the SHA-256 check | `SwfABVerifyPackage.doMyPrimaryJob` (package-private) or `ServiceController.startABInstalling` | only via `OtaPolicy` |
| A/B, user "install now" | broadcast `com.lenovo.ota.ab.installing` → `NotificationReceiver` → the same `startABInstalling` | same method, **indistinguishable without the caller** | the call itself *is* the user action |
| A/B, actual apply | `SwfABInstalling.doMyPrimaryJob` → `canInstallNow()` → `mUpdateEngine.applyPayload(...)` | `SwfABInstalling.canInstallNow()` (private, no-arg boolean) | only via `OtaPolicy` |
| A-only, automatic | `SwfCheckBattery.doMyUiBackgroundJob` at 02:00–04:00 (or the policy window) → `ServiceController.startRebootRecoveryAndInstallPackage` | `SwfCheckBattery.doMyUiBackgroundJob` or `startRebootRecoveryAndInstallPackage` — **its only caller is that automatic job** | only via `OtaPolicy` |
| A-only, actual install | `SwfRebootInstall.doMyPrimaryJob` → `android.os.RecoverySystem.installPackage` | the framework call (current hook), reached only on `!isABUpdate()` | only via `OtaPolicy` |

Two consequences:

- `ServiceController.startABInstalling` is a **shared** funnel: the automatic post-verify install
  and the UI's explicit "install now" are the same call with the same single `Context` argument.
  The current `tbengine_auto_install_ab` hook therefore blocks both, which is what the audit note
  §4 flagged as over-broad. Moving the gate up one level to `SwfABVerifyPackage.doMyPrimaryJob`
  blocks only the automatic post-download install and leaves the user-commanded one alive.
- Because the origin is never a parameter, any origin-aware behaviour has to read
  `OtaPolicy` (or the pref) at the gate.

## 4. Dead ends (checked, do not rely on them)

| Candidate | Why not |
|---|---|
| `EventListenerInterface.onInstallStart(DeviceInfo, OtaPackageInfo, boolean)` | the boolean is `userInstall` for analytics only (`AvatarPlugin` writes it as `userInstall=1/0`); it is a plugin notification, fired after the decision, and is not a gate |
| `EventListenerInterface.userClickedDownloadPackage` / `userClickedCheckNewVersion` | interface methods with **0 call sites** in this build |
| `EventListenerInterface` generally | the only registered listener is `AvatarPlugin`, added in `MainService.registerPlugins`; it reports, it decides nothing |
| `OtaPolicy.isAppDataDownloadByUser()` | written by `userStartOrResumeAppDataDownload` but **read nowhere** |
| `MessengerService` | `android:enabled="false"`, `exported="false"` — the IPC surface is dead; the UI uses broadcasts |
| `AppData*` workflow | a different model: per-package `AppPackageInfo.getAppInstallAuto()` / `getPackageUserConfirmStatus()` gate app/data installs, not the system OTA |

## 5. Recommended hook shapes

**A. Origin-aware install gate (the actual answer to the question).** Keep a gate on the automatic
install entry, but decide with the engine's own flag instead of swallowing unconditionally
(`otaPolicy` / `isDownloadByUser` below are local helpers to write around
`OtaPolicyManager.getOtaPolicy(context)` and reflection on `isDownloadByUser()`):

```kotlin
// A/B: only the post-download automatic install, not the UI "install now"
val swfABVerify = classLoader.loadClass(
    "com.lenovo.tbengine.core.services.SwfABVerifyPackage"
)
val doMyPrimaryJob = findMethod(swfABVerify, "doMyPrimaryJob")
hookWithId(doMyPrimaryJob, "tbengine_auto_install_ab_auto") { chain ->
    val policy = otaPolicy(classLoader)          // OtaPolicyManager.getOtaPolicy(appContext)
    if (isDownloadByUser(policy)) chain.proceed() // user downloaded it -> let it install
    else Unit                                     // background download -> block
}
```

**B. Read the flag from SharedPreferences** when calling into `OtaPolicy` is inconvenient: the
value is `<app data dir>/shared_prefs/lenovo_ota_row_ota_policy.xml`, key `mIsDownloadByUser`.
`OtaPolicyManager` is preferable — it is the same source the engine itself uses.

**C. If a live signal is required**, hook the pair
`ServiceController.userStartOrResumeDownload` / `ServiceController.startOrResumeDownload` and
persist the outcome yourself, remembering the §2.3 leak and that the A-only install may happen
hours later in a new process.

## 6. Caveats

- The install workflow resumes from the persisted `WorkFlow` state on
  `com.lenovo.tbengine.service.main.boot_complete`; an `AB_INSTALLING` parked across a reboot skips
  every `ServiceController` trigger. The `OtaPolicy` flag survives that, an in-memory hook flag
  does not.
- `SwfABInstalling.canInstallNow()` is the narrowest A/B gate (called once, immediately before
  `applyPayload`), but it is also reached by the user-commanded install, so it is only usable
  together with the flag.
- The A-only `RecoverySystem.installPackage` hook is vacuous on A/B devices: `MainService`
  registers `SwfRebootInstall` only when `ro.build.ab_update != "true"`.

## 7. How to re-verify

```text
jadx MCP: get_xrefs_to_method OtaPolicy.isDownloadByUser      -> must stay "presentation only"
          get_xrefs_to_method OtaPolicy.setDownloadByUser     -> must stay 2 (ServiceController, SwfDownloading)
          get_xrefs_to_method ServiceController.userStartOrResumeDownload
          get_method_by_name SwfABVerifyPackage.doMyPrimaryJob / SwfCheckBattery.doMyUiBackgroundJob
          get_fields_of_class OtaPackageInfo                  -> no per-package user/auto flag
device:   adb shell run-as? no; read /data/data/com.lenovo.tbengine/shared_prefs/lenovo_ota_row_ota_policy.xml (root)
          adb shell getprop ro.build.ab_update
```
