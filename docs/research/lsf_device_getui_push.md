# Device Service (com.lenovo.lsf.device) hook targets — V5.0.2.0284tn vs V6.0.0.0002tn

Analysis note. Static comparison of every ZTool `lsfdevice` hook target between the two Device
Service builds, plus the GeTui push channel that V6 bundles and the reason its receiver is the
extra choke point.

Facts that scope this note:

| Item | V5 | V6 |
|---|---|---|
| APK | `设备服务_V5.0.2.0284tn.apk` (Android 16 dump) | `设备服务_V6.0.0.0002tn.apk` (Android 17 dump) |
| versionName / versionCode | `V5.0.2.0284tn` / `500020284` | `V6.0.0.0002tn` / `600000002` |
| compileSdk / targetSdk / minSdk | 33 / 34 / 26 | 33 / 34 / 26 |
| Source classes | 622 | 875 |
| GeTui (`com.getui.vendor.push`) | absent (0 references) | 11 classes, 1 receiver + 1 service + 1 activity |

Method: V6 read through the jadx MCP server, V5 decompiled with the jadx CLI 1.5.6
(`jadx -d`); both trees diffed class-by-class and manifest-by-manifest, then every hook target's
call sites inspected.

## 1. Hook targets

| Module / hook id | Target | V5 | V6 |
|---|---|---|---|
| `DisableLsfDevicePush` / `lsf_device_push_receiver` | `com.lenovo.lsf.push.receiver.PushReceiver#onReceive(Context, Intent)` | present, manifest `BOOT_COMPLETED`, `CONNECTIVITY_CHANGE`, power, package events | same class and filter set |
| `DisableLsfDevicePush` / `lsf_device_upgrade_receiver` | `com.lenovo.lsf.upgrade.UpgradeReceiver#onReceive(Context, Intent)` | sole receiver of `com.lenovo.lsf.sdac.ALARM`, re-arms `+3600000` | same, only the obfuscated gateway method was renamed (`m.s` -> `m.r`) |
| `DisableLsfDeviceAutoInstall` / `lsf_device_install_commit` | `PackageInstaller.Session#commit(IntentSender)` | `InstallManager` session path | identical, `commit(PendingIntent.getBroadcast(...))` unchanged |
| `DisableLsfDeviceAutoInstall` / `lsf_device_install_legacy` | reflective `installPackage` / `installByPackageInstaller` | first attempt before the session path | identical |
| `DisableLsfDeviceAppListReporting` / `lsf_device_report_installed_packages` | `ApplicationPackageManager#getInstalledPackages(int)` | `DisplayService.l()`, filter `flags & 129` | `DisplayService.runInstallAppListReport()`, same filter; also 2 new GeTui call sites |
| `DisableLsfDeviceAppListReporting` / `lsf_device_report_installed_apps` | `ApplicationPackageManager#getInstalledApplications(int)` | `LenovoApks` whitelist builder | same builder, renamed class |

Obfuscation was re-rolled between the builds (`g0` -> `o1`, `x` -> `j1`, ...), but every hooked
class is manifest-anchored, so no hook target moved.

Manifest delta is limited to the GeTui components and three storage/phone permissions; all LSF
components keep their names, filters and permissions.

## 2. The GeTui channel added in V6

V6 vendors the GeTui push SDK as `com.getui.vendor.push`, and LSF's own push stack now talks to it
(`PushService` consumes `s1`/`t1`/`u1` SDK callbacks instead of V5's own socket stack).

Start paths that reach `PushCoreService`:

| Path | Trigger | Covered by `DisableLsfDevicePush` |
|---|---|---|
| `com.getui.vendor.push.PushReceiver#onReceive` -> `startService(PushCoreService)` | `BOOT_COMPLETED`, `PACKAGE_REMOVED` | yes, after `lsf_device_getui_push_receiver` |
| `v1.c.k()` binds/`queryIntentServices("com.getui.vendor.action.push")` | any `q1.b` SDK call, i.e. when LSF `PushService` runs | no, but `PushService` itself is only reached from the process being awake |

`PushCoreService` is exported with action `com.getui.vendor.action.push`, so the manifest entry
alone does not keep the process alive; what V6 adds is a receiver that wakes the process from a
cold boot or package change, which is the same shape as LSF's own `PushReceiver`.

Hence blocking the receiver is the choke point that restores parity with V5: the switch keeps its
single semantic ("no push keep-alive"), and no new toggle is needed for users who already had it
enabled.

## 3. Side effects of the app-list hook on the vendored SDK

`DisableLsfDeviceAppListReporting` hooks `ApplicationPackageManager`, so it applies to the whole
process, GeTui included:

- `com.getui.vendor.push.a.d.n()` (`reportAppUsageStats`) intersects `getInstalledPackages(0)` with
  `UsageStats`; the empty list just yields an empty `upload_usage_stats` payload. The path also
  requires `PACKAGE_USAGE_STATS`, which the manifest does not declare.
- `com.getui.vendor.push.a.d.t()` builds `pkg#versionName#versionCode#firstInstall#lastUpdate` for
  non-system packages; with the empty list it returns `null` instead of uploading.

Neither degrades the SDK: both branch on `isEmpty()`.

## 4. Known leftover

`PushCoreService` itself is not hooked. Blocking it would break the SDK client bind path
(`v1.c`), which is only entered while the process is already awake for another reason, so the
receiver remains the correct boundary.
