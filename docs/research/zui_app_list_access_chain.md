# ZUI/ZUX App List Access (`OP_GET_INSTALLED_APP`) Chain & Bypass Audit

Analysis target: Lenovo TB710FU (`TB710FU_CN_OPEN_USER_Q00019.0_A17_ZUXOS_2.5.10.102_ST_261112`,
Android 17 / SDK 37). Sources inspected via `services.jar` (Jadx MCP), pulled
`/system/framework/framework.jar` (`dexdump`), and empirical device probes running as a regular
`untrusted_app` without root.

Related module: [AllowGetPackages.kt](../../app/src/main/java/com/qimian233/ztool/hook/modules/systemframework/AllowGetPackages.kt).

## Summary

1. ZUI's "读取应用列表" permission (`OP_GET_INSTALLED_APP = 214`) is a **ZUI-proprietary interception
   layer** (`ComputerEngine.isOnlyCallingSelf` -> `AppOpsManager.noteOpNoThrow(214)` ->
   `ZuiOpsManager` -> `ZuiOpsService` -> `AppOpsService.checkOperationRawZui`) stacked on top of
   AOSP package visibility (`ComputerEngine.shouldFilterApplication` / `AppsFilterImpl`).
2. `AllowGetPackages` effectively bypasses ZUI's check (including explicit `MODE_IGNORED` and
   `MODE_ASK`) because `ZuiOpsService.noteOperation` reads the mode via
   `AppOpsService.checkOperationRawZui`.
3. ZUI's `isOnlyCallingSelf` gate only covers 3 `ComputerEngine` entry points (and only `MAIN`
   non-data queries in `queryIntentActivitiesInternal`). On-device testing confirms an
   `untrusted_app` with `GET_INSTALLED_APP` set to `ignore` can still enumerate up to **337/337**
   app-UID packages via standard non-privileged SDK APIs (`getPackagesForUid`,
   `getPackagesHoldingPermissions`, `queryContentProviders`, `queryBroadcastReceivers`, non-`MAIN`
   `queryIntentActivities`, `getPackageInfo`, etc.).
4. Therefore, `AllowGetPackages`'s design premise (bypassing ZUI's incomplete app-list control so
   Hide My Applist or a future unified hook can manage visibility properly) remains valid.

---

## 1. Complete Call Chain (ZUI-Proprietary vs AOSP)

### 1.1 PackageManager Entry Points (`services.jar`)

Client calls in `android.app.ApplicationPackageManager` cross Binder to
`com.android.server.pm.IPackageManagerBase`, which snapshots and delegates to
`com.android.server.pm.ComputerEngine`:

| API | `ComputerEngine` path | AOSP `shouldFilterApplication`? | ZUI `isOnlyCallingSelf`? | ZUI `mZuiHiddenApps` (`isAppHidden`)? |
| :--- | :--- | :--- | :--- | :--- |
| `getInstalledPackages` | `getInstalledPackagesBody` | Yes | **Yes** | Yes |
| `getInstalledApplications` | `getInstalledApplications` | Yes | **Yes** | Yes |
| `queryIntentActivities` | `queryIntentActivitiesInternal` | Yes | **Only if `action == ACTION_MAIN && data == null`** | Yes |
| `queryIntentServices` | `queryIntentServicesInternal` | Yes | No | Yes |
| `queryIntentReceivers` | `ResolveIntentHelper.queryIntentReceiversInternal` | Yes | No | No |
| `queryIntentContentProviders` | `ResolveIntentHelper.queryIntentContentProvidersInternal` | Yes | No | No |
| `queryContentProviders` | `ComputerEngine.queryContentProviders` | Yes | No | No |
| `getPackagesHoldingPermissions` | `getPackagesHoldingPermissions` -> `addPackageHoldingPermissions` -> `generatePackageInfo` | Yes (via `generatePackageInfo`) | No | No |
| `getPackagesForUid` | `getPackagesForUidInternalBody` | Yes | No | No |
| `getNameForUid` / `getNamesForUids` | `getNameForUid` / `getNamesForUids` | Yes | No | No |
| `getPackageInfo` / `getApplicationInfo` | `getPackageInfoInternalBody` / `getApplicationInfoInternalBody` | Yes | No | No |
| `getPersistentApplications` | `getPersistentApplications` | **No** | No | No |

When `isOnlyCallingSelf(callingUid)` returns `true`:

- `getInstalledPackagesBody` keeps only `PackageInfo` items where `applicationInfo.uid == callingUid`.
- `getInstalledApplications` keeps only `ApplicationInfo` items where `uid == callingUid`.
- `queryIntentActivitiesInternal` (only when `"android.intent.action.MAIN".equals(intent.getAction()) && intent.getData() == null`) runs `list.removeIf(ri -> ri.activityInfo.applicationInfo.uid != filterCallingUid)`.

### 1.2 `ComputerEngine.isOnlyCallingSelf(int callingUid)`

Observed in `com.android.server.pm.ComputerEngine` (`services.jar`):

1. Checks `com.lgsi.config.LgsiFeatures.enabled("ZuiPermission")`; returns `false` if disabled.
2. Checks `UserHandle.isCore(callingUid)`; returns `false` for core UIDs (`< 10000`).
3. Resolves the first package for `callingUid` via `PackageManager.getPackagesForUid(callingUid)`.
4. Calls `AppOpsManager.noteOpNoThrow(0xd6 /* 214 */, callingUid, firstPkg, null, null)`.
5. Returns `true` (restrict to self) whenever the returned mode `!= 0` (`MODE_ALLOWED`).

### 1.3 `AppOpsManager` -> `ZuiOpsManager` -> `ZuiOpsService` -> `AppOpsService`

1. In `framework.jar`, `android.app.AppOpsManager.noteOpNoThrow(int, int, String, String, int, String)` checks `ZuiOpsManager.isValidOp(op)` (`200 <= op <= 217`). For `214` (`ZuiOpsManager.OP_GET_INSTALLED_APP`, `"android:get_installed_app"`), it short-circuits to `ZuiOpsManager.noteOpNoThrow(...)` instead of `IAppOpsService.noteOperation`.
2. `ZuiOpsManager` defines ZUI custom op arrays (`200..217`):
   - `214`: name `"GET_INSTALLED_APP"`, str `"android:get_installed_app"`, permission `null`.
   - `sOpDefaultMode[14] = 0` (`MODE_ALLOWED`), `sOpDefaultStrictMode[14] = 0`.
   - `PreinstallAppPermInitializer.INTERESTING_OPS` (`{201, 202, 203, 204, 205, 208, 214}`) initializes uninstallable preinstalled apps to `MODE_ASK` (`10`) during boot (`com.zui.server.permission.PreinstallAppPermInitializer`).
3. `ZuiOpsManager.noteOpNoThrow` calls Binder service `"zuiopsservice"` (`IZuiOpsService` -> `com.android.server.appop.ZuiOpsService.noteOperation`).
4. `ZuiOpsService.noteOperation(214, uid, pkg, attr, msg)` calls:
   - `AppOpsService.checkOperationRawZui(214, uid, pkg)`
   - If raw mode is `10` (`ZuiOpsManager.MODE_ASK`), invokes `askAppOpsOperationAsync(214, uid, pkg)` (async prompt via `ZuiSecurityService`) and returns `1` (`MODE_IGNORED`) for the current call.
   - Records access/rejection via `AppOpsService.recordOpsBehaviorForZui(...)` and returns the mode.
5. `AppOpsService.checkOperationRawZui(op, uid, pkg)` reads `getNonDefaultUidModes` / `mAppOpsCheckingService.getPackageMode(...)`, falling back to `AppOpsManager.opToDefaultMode(op)`.

### 1.4 Why `AllowGetPackages.kt` Works

`AllowGetPackages` hooks two targets in `system_server`:

- `android.app.AppOpsManager.opToDefaultMode(int)`: returns `0` when `op == 214`. On ZUXOS 2.5 `ZuiOpsManager.sOpDefaultMode[14]` is already `0`, so this serves mainly as a defensive fallback for older builds or strict defaults.
- `com.android.server.appop.AppOpsService.checkOperationRawZui(int, int, String)`: returns `0` when `op == 214`. Because `ZuiOpsService.noteOperation` (and `ZuiOpsService.checkOperationRaw`, used by `AppOpsManager.unsafeCheckOpRawNoThrow`) queries `checkOperationRawZui`, this hook intercepts both `MODE_ASK` (`10`) and explicit `MODE_IGNORED` (`1`) states for `isOnlyCallingSelf`.

---

## 2. On-Device Bypass Verification (`untrusted_app`, No Root)

Tested on `TB710FU` (`allow_get_packages` disabled in ZTool) using temporary `targetSdkVersion=35` diagnostic APKs installed as ordinary `untrusted_app`s with `appops set <pkg> 214 ignore` (`GET_INSTALLED_APP: ignore`), compared against `allow`. Total packages on device at test time: **388** (of which **337** belong to UIDs `10000..10499`).

| Probe | `QUERY_ALL_PACKAGES` + `ignore` | `QUERY_ALL_PACKAGES` + `allow` | No `QUERY_ALL_PACKAGES` + `ignore` |
| :--- | :--- | :--- | :--- |
| `getInstalledPackages(0)` | `1` (self only) | `388` | `1` (self only) |
| `getInstalledApplications(0)` | `1` (self only) | `388` | `1` (self only) |
| `queryIntentActivities(ACTION_MAIN + LAUNCHER)` | `1` (self only) | `63` | `1` (self only) |
| `LauncherApps.getActivityList(null)` | `1` (self only) | `63` | `1` (self only) |
| `getPackagesForUid(10000..10499)` | **`337` pkgs** | `337` pkgs | **`168` pkgs** |
| `getNameForUid(10000..10499)` | **`330` non-null** | `330` non-null | **`161` non-null** |
| `getPackagesHoldingPermissions([INTERNET])` | **`174` pkgs** | `174` pkgs | **`95` pkgs** |
| `queryContentProviders(null, 0, 0)` | **`860` / `161` pkgs** | `860` / `161` pkgs | **`251` / `80` pkgs** |
| `queryBroadcastReceivers(BOOT_COMPLETED)` | **`98` / `68` pkgs** | `98` / `68` pkgs | **`67` / `44` pkgs** |
| `queryIntentActivities(ACTION_SEND */*, MATCH_ALL)` | **`42` / `25` pkgs** | `42` / `25` pkgs | **`4` / `3` pkgs** |
| `queryIntentActivities(ACTION_VIEW https://...)` | **`5` / `5` pkgs** | `5` / `5` pkgs | `0` |
| `queryIntentServices(android.view.InputMethod)` | **`5` / `5` pkgs** | `5` / `5` pkgs | **`2` / `2` pkgs** |
| `getPackageInfo(<4 known pkgs>, 0)` | **`4 / 4` hits** | `4 / 4` hits | **`1 / 4` hits** |
| `File("/sdcard/Android/data/com.tencent.mm").exists()` | **`true`** | `true` | **`true`** |

Key takeaways:

- `QUERY_ALL_PACKAGES` is a normal install-time manifest permission granted with zero user interaction, and `<queries>` declarations require no permission at all.
- With `QUERY_ALL_PACKAGES`, `getPackagesForUid(10000..10499)` alone recovers **100% of app-UID packages** (`337/337`) despite `GET_INSTALLED_APP: ignore`. (Core UIDs `< 10000` can likewise be queried via `getPackagesForUid(1000)`, etc.)
- Even without `QUERY_ALL_PACKAGES`, system/force-queryable packages remain enumerable via `getPackagesForUid`, `getPackagesHoldingPermissions`, `queryContentProviders`, and `queryBroadcastReceivers`, and specific third-party packages can be probed via `/sdcard/Android/data/<pkg>` `exists()` checks.

---

## 3. Notes for Future Full Protection Hook (Replacing HMA)

If ZTool implements its own app-list hiding hook in the future so users no longer need Hide My Applist:

1. **Hook the unified AOSP visibility bottleneck, not individual `getInstalled*` APIs**:
   - In `services.jar`, `ComputerEngine.shouldFilterApplication(PackageStateInternal, int callingUid, ComponentName, int callingUserId, int targetUserId, boolean ..., boolean ...)` (and `AppsFilterImpl.shouldFilterApplication(...)`) is the single shared visibility gate consulted by:
     - `getInstalledPackagesBody` and `getInstalledApplications`
     - `queryIntentActivitiesInternal` (all actions, not just `MAIN`)
     - `queryIntentServicesInternal`
     - `queryContentProviders` and `ResolveIntentHelper.queryIntentContentProvidersInternal`
     - `ResolveIntentHelper.queryIntentReceiversInternal`
     - `getPackagesForUidInternalBody`, `getNameForUid`, `getNamesForUids`
     - `getPackageInfoInternalBody`, `getApplicationInfoInternalBody`
     - `generatePackageInfo` (and therefore `getPackagesHoldingPermissions`)
     - `canPackageQuery`
   - Returning `true` ("filter out") from `ComputerEngine.shouldFilterApplication` (or `AppsFilterImpl.shouldFilterApplication`) blocks all of the above uniformly for a configured `(callerPackage, targetPackage)` policy, regardless of `QUERY_ALL_PACKAGES` or `<queries>`.
2. **Watch the edges that bypass `AppsFilterSnapshot` / `shouldFilterApplication`**:
   - **Self & Core exemption**: `shouldFilterApplication` allows `PackageManagerServiceUtils.isSystemOrRootOrShell(callingUid)` and `isCallerSameApp(...)` before reaching `AppsFilterSnapshot.shouldFilterApplication`. Hooking `ComputerEngine.shouldFilterApplication` directly lets the policy inspect `callingUid` cleanly without relying on `AppsFilterSnapshot` alone, while still respecting system/root/shell/self exemptions.
   - **`getPersistentApplications(boolean safeMode, int flags)`**: observed in `ComputerEngine` smali (`lines 14146..14333`) NOT to call `shouldFilterApplication`. Should also be filtered if hiding persistent system/third-party apps.
   - **`LauncherAppsService$LauncherAppsImpl`**: `getLauncherActivities` / `getApplicationInfo` call `clearCallingIdentity()` and pass the captured `callingUid` into `PackageManagerInternal` (`ComputerEngine`) — verify that the passed `callingUid` (not `Binder.getCallingUid()`, which becomes `1000` after `clearCallingIdentity()`) is used when evaluating caller identity. Note that `ComputerEngine.shouldFilterApplication` takes `callingUid` as an explicit parameter (`p2`), whereas `generatePackageInfo` / `getInstalledPackages` read `Binder.getCallingUid()`.
   - **ZUI `SharedPreferences` / filesystem side-channels**: `File("/sdcard/Android/data/<pkg>").exists()` is not mediated by `PackageManagerService`. Closing that vector requires either MediaProvider/FUSE-level hiding or app-process `File`/`stat` hooking for isolated targets (similar to HMA's optional file/storage protections).
   - **Interaction with `AllowGetPackages`**: Once a unified `shouldFilterApplication` policy is implemented, `AllowGetPackages` (keeping ZUI's `OP_GET_INSTALLED_APP` bypassed) remains useful so apps don't trigger redundant ZUI permission popups or `MODE_IGNORED` errors while ZTool's own visibility rules enforce hiding.
