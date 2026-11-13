# Media output tile: `LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG` is a protected broadcast on Android 17

Tested 2026-11-13 on TB710FU (ZUXOS 2.5 / Android 17, API 37) with a root shell (KernelSU).
Sources: `F:\USER\Jadx项目\Lenovo\android-17\系统界面_17.apk` (SystemUI 17, `versionCode=37`,
`versionName=17`), `F:\USER\Jadx项目\Lenovo\android-16\系统界面_17.5.24.apk` (SystemUI 16) and the
AOSP `android16-release` branches of `BroadcastController`, `MediaRouterService`,
`MediaRouter2ServiceImpl` and `MediaRouter2`.

## Symptom

Tapping the media-output quick-settings tile kills the ZTool process:

```text
java.lang.SecurityException: Permission Denial: not allowed to send broadcast
com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG from pid=10726, uid=10314
  at com.android.server.am.BroadcastController.broadcastIntentLockedTraced(BroadcastController.java:1154)
  at android.app.ContextImpl.sendBroadcast(ContextImpl.java:1343)
  at com.qimian233.ztool.service.MediaOutputTileService.onClick(MediaOutputTileService.kt:19)
```

`ContextImpl.sendBroadcast` blocks on the binder reply, so the throw reaches `onClick` on the main
thread: the failure is a process crash, not a silently dropped broadcast. A `try/catch` would only
hide the crash.

## Breakage 1: the action became a protected broadcast

SystemUI 17 declares

```xml
<protected-broadcast android:name="com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG"/>
```

The 16 build protects only `com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG`; the
action the tile uses was not protected, which is why the tile worked before the 17 update.

`BroadcastController.broadcastIntentLockedTraced` computes `isCallerSystem` from the calling app id
(`ROOT_UID`, `SYSTEM_UID`, `PHONE_UID`, `BLUETOOTH_UID`, `NFC_UID`, `SE_UID`, `NETWORK_STACK_UID`)
or `callerApp.isPersistent()`, then throws for protected actions when it is false. Consequences:

| Caller | Protected send |
| --- | --- |
| App uid (10314) | `SecurityException` (reproduced) |
| Root (uid 0) | delivered (reproduced) |
| Shell (uid 2000) | blocked, not exempt |
| System-signed but non-persistent app | blocked, not exempt |

## Breakage 2: the receiver now requires a `user_handle` extra

`MediaOutputDialogReceiver` on 17 (both branches) reads typed parcelables:

```java
// LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG
UserHandle userHandle = intent.getParcelableExtra("user_handle", UserHandle.class);
if (userHandle == null) { Log.e("MediaOutputDlgReceiver", "Can't launch dialog. UserHandle is empty."); return; }
manager.createAndShow(userHandle, null, false, null, false, null, null, true);

// LAUNCH_MEDIA_OUTPUT_DIALOG
String pkg = intent.getStringExtra("package_name");
UserHandle user = intent.getParcelableExtra("user_handle", UserHandle.class);  // null -> userTracker
if (pkg == null || pkg.isEmpty()) { Log.e(..., "Can't launch dialog. Package name is empty."); return; }
manager.createAndShow$default(manager, pkg, user, token /* "key_media_session_token" */, 4);
```

A root send of the protected action without the extra was delivered and then dropped by the
receiver (`E MediaOutputDlgReceiver: Can't launch dialog. UserHandle is empty.`). `am broadcast`
cannot build a `UserHandle`, so **root does not fix the feature either** - the sysui-side contract
changed as well.

Both actions reach the same `MediaOutputDialogManager.createAndShow` entry with a null transition
controller and the system-color path; they differ only in the package name they pass (and the
`includePlayback` flag each static helper feeds it). The system action only exists to launch the
dialog without binding any media session.

## Verified fix path (no root, no hook change)

`com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG` is **not** protected on either build and
its receiver is `android:exported="true"`, so app identity may send it. It needs a non-empty
`package_name` (the tile's own package is enough; a null package binds no media session, which is
what the system action did anyway); `user_handle` falls back to `UserTracker` when absent.

Run as the app uid, this opened the real dialog:

```powershell
adb shell 'su 10314 -c "am broadcast --user 0 -a com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG \
  -n com.android.systemui/com.android.systemui.media.dialog.MediaOutputDialogReceiver \
  --es package_name com.qimian233.ztool"'
# W MediaOutputDlgReceiver: userHandle is null, using fallback from userTracker: UserHandle{0}
# Broadcast completed: result=0  -> dialog shown (媒体 / 连接设备 / 本机)
```

`MediaOutputDialogCenterHook` still applies: it wraps `MediaOutputDialogReceiver.onReceive` and sets
its intra-thread flag for every action, so the centering path keeps working with the new action.

## Rejected alternatives

- **Root + protected action**: blocked by breakage 2 (`am` cannot pass a `UserHandle` parcelable), so
  it needs the unprotected action anyway - which works without root.
- **`MediaRouter2.showSystemOutputSwitcher()`** (public since API 34; a `MediaSession.Token`
  overload appeared in API 36): the server side requires `PACKAGE_USAGE_STATS` and bails out with
  `showMediaOutputSwitcher only works when called from foreground` when
  `getPackageImportance(packageName) > IMPORTANCE_FOREGROUND`. A quick-settings tile is never
  foreground, so the call returns `false`; it is the in-app "cast button" API, not a tile API.
- **`MediaRouter2.transferTo`**: only routes the calling app's own media sessions, cannot move
  another app's playback.
- **SystemUI-side hook** (custom action -> `MediaOutputDialogManager.createAndShow` in-process): works
  and is independent of sysui action churn, but is more code than the broadcast switch above; keep it
  as the fallback if the unprotected action is ever protected too.

## Notes for an implementation

- Keep the 16 path untouched and branch on `Build.VERSION.SDK_INT >= 37`, or send the unprotected
  action unconditionally (it exists on 16 and 17, and `package_name` is harmless if ignored).
- The 17 receiver no longer filters `com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG`
  (16 did); do not use it as a replacement.
- `MediaOutputDialogManager` on 17 is member-injected (`mediaOutputDialogDelegateFactory`,
  `mediaSwitchingControllerFactory`), so it cannot be constructed reflectively; the receiver holds
  the only wired instance.

## Applied fix (2026-11-13)

`service/MediaOutputTileService.kt` sends `LAUNCH_MEDIA_OUTPUT_DIALOG` with the app's own
`package_name` on API 37+ and keeps `LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG` below that, wrapped in
`try`/`catch` because the rejection arrives synchronously inside `sendBroadcast` and used to kill
the module process. `user_handle` is deliberately not sent: it was never exercised on-device and
the receiver falls back to `UserTracker`.

SystemUI itself is `android:persistent="true"`, so the SystemUI-side tile in
`VolumeSliderLongPressHook` may keep sending the protected action.

## Dialog content and colours (second pass)

`MediaSwitchingController.start` only looks for a media controller when `mPackageName` is non-empty:
it scans media notifications, then `getActiveSessionsForUser(null, user)`, for a session of that
package. The receiver forwards whatever package the sender supplied (or null), so the session-less
tile dialog showed no metadata.

`MediaOutputDialogDelegate.refresh` switches to `ColorScheme(WallpaperColors.fromBitmap(artwork))`
only when the artwork is a bitmap icon **and** `mUseSystemColors == false`; both receiver actions
hardcode `useSystemColors = true`, so a broadcast alone can never produce album-art colours.

The app cannot resolve the playing package by itself: the public SDK exposes only
`getAudioAttributes()` and `getAudioDeviceInfo()` on `AudioPlaybackConfiguration`
(`getClientUid()`/`isActive()` are hidden), so the lookup has to run inside SystemUI, which holds
`MEDIA_CONTENT_CONTROL`.

Split implemented:

- `MediaOutputDialogCenterHook` (`media_output_dialog_center`, default off): gravity only, and it
  skips installation on API 37+ because that build centres the dialog in `onCreate`.
- `MediaOutputDialogColorHook` (`media_output_dialog_color`, default on through an `isEnabled()`
  override - the base class otherwise treats a missing key as "disabled"): wraps
  `MediaOutputDialogReceiver.onReceive` to mark broadcast launches, then in `createAndShow`
  substitutes the playing session package (playing > buffering > first active), forces
  `includePlaybackAndAppMetadata = true` and `useSystemColors = false`. In-app callers such as the
  media carousel are left untouched.
- On API 37+ the centring switch is shown disabled with a dedicated summary (same pattern as the
  lock-screen clock colour switch), because its hook is never installed there.
