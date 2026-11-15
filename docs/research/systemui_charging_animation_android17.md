# Charging animation contracts in the Android 17 SystemUI sample

## Evidence boundary

Inspected on 2026-11-15 through JADX MCP. The loaded manifest identifies
`com.android.systemui`, version name `17`, version code `37`, and
compile/min/target SDK `37`. The source marker is
`go/retraceme 18fb1fa2e53ac1eb6d3aabb5731499e5a83dbe8bb2e2198b13e7fc962547dfa7`;
it is not an APK checksum or a verified device build number.

The comparison baseline is the repository's Hook/indexer contracts at
`b5a1f6e8`, not a second historical APK. Findings establish compatibility with
this loaded sample; they do not establish every class or method removed between
firmware releases. The subsequent compatibility changes are recorded below;
they have not been tested on a device.

## Class relocation

The repository expects `com.android.keyguard.lockscreen.charge`. Direct lookups
of its ChargingAnimationController, ChargingActivity, ChargingVideoView, and
ChargingStyleDefault all return class-not-found. A class-name search for
`Charging` within `com.android.keyguard.lockscreen` returns zero results.

The corresponding classes exist under `com.android.keyguard.charge`:

| Class | Current role |
| --- | --- |
| `ChargingAnimationController` | Battery callback and activity/broadcast dispatch |
| `ChargingActivity` | Animation lifecycle, video selection, and timeouts |
| `ChargingVideoView` | Subclass of `android.widget.VideoView` |
| `ChargingVideoLayout` | Inflates the video player and holds the selected style |
| `ChargingStyleDefault` | Default video/preview resource selection |
| `ChargingStyle` | Style interface |
| `ChargingStyleTriangle`, `ChargingStyleTrim`, `ChargingStyleTurbo` | Other style implementations |
| `ChargingUtils` | Landscape detection |

Smali uses the new package in actual class descriptors. The manifest declares
`com.android.keyguard.charge.ChargingActivity`; the controller explicitly starts
that component. `res/layout/charging_animation_layout.xml` and
`res/layout/charging_view_player.xml` reference the new layout/view classes.
These names are therefore corroborated beyond JADX's display aliases.

## Impact on repository Hooks

| Feature / repository file | Verified current contract | Compatibility impact |
| --- | --- | --- |
| Disable animation / `NoChargeAnimation.kt` | `ChargingAnimationController.H` has type `ChargingAnimationController$1`, extending `android.os.Handler`; it declares `public final handleMessage(Landroid/os/Message;)V` | The method signature still matches. Loading the old controller class fails first. |
| Custom video / `CustomChargeAnimation.kt` | `ChargingVideoView` still extends `VideoView`; `ChargingActivity.update(int, int, boolean)` calls `VideoView.setVideoURI(Landroid/net/Uri;)V` on that view | The exact runtime class-name comparison uses the old package, so the replacement branch is skipped even if the framework Hook installs successfully. |
| Custom duration / `ChargeAnimationDurationHook.kt` | `ChargingActivity.onResume()V` still exists; `DURATION` is absent; the current field is public instance `mAnimationDuration:J` | Changing only the activity class name is insufficient. The static-field lookup and `set(null, ...)` contract also need adaptation. |

The custom-video Hook currently intercepts the framework two-argument
`VideoView.setVideoURI(Uri, Map)` overload. This APK corroborates the one-argument
call site and the view's inheritance; it does not supply the framework
implementation needed to independently verify delegation to that overload.
The changed class-name guard is a confirmed problem regardless of delegation.

## Duration and screen timing

The constructor initializes `mAnimationDuration` to 4500 ms, `mSleepDelay` to
4200 ms, and `mWakeupDelay` to 1000 ms. `onCreate(Bundle)` then overwrites all
three from integer resources. The APK's default `res/values/integers.xml` contains:

| Resource | Value (ms) |
| --- | --- |
| `config_chargingAnimationduration` | 4500 |
| `config_chargingAnimationSleepDelay` | 3800 |
| `config_chargingAnimationWakeupDelay` | 1000 |

The lowercase `d` in `config_chargingAnimationduration` is intentional here.
No charging-animation overrides were found in the APK's other integer-resource
files. Runtime resource overlays were not inspected.

In `onResume()`, when the finish runnable is not already pending:

- Normal projects post `mTimeoutFinishRunnable` with `mAnimationDuration`.
- If `ro.config.lgsi.project` contains `lapis`, the finish delay is a literal
  6200 ms, bypassing that field.
- The sleep runnable is scheduled independently with `mSleepDelay`; it can call
  `PowerManager.goToSleep()` when the screen was initially off, keyguard is locked,
  the dream manager is not dreaming, and no USB activity is recorded.

The finish runnable calls `handleFinshed()`. A future duration adaptation must
write an instance field after `onCreate` has loaded resources and before
`onResume` schedules the callback. It must also address the literal `lapis`
branch if that project is supported. Extending the finish delay alone does not
extend the independent screen-sleep delay.

## Offline index concerns

`SystemUiDexIndexer.indexNoChargeAnimation()` still names the old controller.
It also restricts its search to `scopePackage` (`com.android.systemui`), while
the actual controller is in `com.android.keyguard.charge`. APK identity and DEX
class package must not be conflated in that query.

Its field matcher asks for `android.os.Handler`, whereas the verified declared
type of `H` is the anonymous Handler subclass `ChargingAnimationController$1`.
The matching strategy needs review alongside the class/package adaptation.
The current sample retains the fallback field name `H`, so a corrected Hook
class lookup can use that fallback even when the offline index is empty.

The style selector still calls `ChargingStyleDefault.getRawId(Context):int`.
Its landscape decision uses `ChargingUtils.isLandscape(Context):boolean`,
which compares configuration orientation with `2`, matching the module's
orientation check.

## Follow-up scope

Adapt the three SystemUI Hooks and the charging controller's offline-index
query together, retaining older class/field contracts if older firmware remains
supported. `ChargeAnimationFixModule` targets the separate wallpaper APK;
SystemUI inspection alone cannot determine its compatibility.

## Implemented compatibility changes

`SystemUiDexTargets` shares ordered class paths between the indexer and Hooks:
`com.android.keyguard.charge` before `com.android.keyguard.lockscreen.charge`,
and `com.android.systemui.statusbar.view.NetworkSpeedView` before the older
`com.android.systemui.zui.NetworkSpeedView`. The verified ToggleSliderView
path remains `com.android.systemui.settings.ToggleSliderView`.

All three existing SystemUI index modules were checked against their consumers:

| Index module | Verified current target | Writer/reader alignment |
| --- | --- | --- |
| `NO_CHARGE_ANIMATION` | `ChargingAnimationController.H`, type `ChargingAnimationController$1`, callback `(Message):void` | Query classes across DEX packages in preference order; check Handler ancestry and the declared callback. Store `targetClass` alongside `handlerFieldName`. Hook validates indexed fields against the selected class, then probes `H` and other typed fields. |
| `SYSTEMUI_NETWORK_SPEED_DOUBLELAYER` | `statusbar.view.NetworkSpeedView$3`, extending Handler | Query new view first, old view second; require `(Message):void` and an assignable `this$0` owner, matching the Hook's checks. |
| `VOLUME_SLIDER_PERCENTAGE` | `settings.ToggleSliderView$5`, implementing `SeekBar.OnSeekBarChangeListener` | Require `(SeekBar,int,boolean):void` and the listener interface; prefer nested listeners, then listener types held by slider fields. Hook validates the same interface and signature for online, indexed, and legacy candidates. `$2` is a ViewOutlineProvider in this sample, so it is rejected. |

The custom-video Hook resolves the current view class before the legacy class
at installation, then checks instances against that class. Its input files remain
`/sdcard/Download/ZTool/charging_animation_portrait.mp4` and
`/sdcard/Download/ZTool/charging_animation_land.mp4`. It intercepts the framework
two-argument URI overload and passes replacement arguments explicitly.

The duration Hook prefers instance `mAnimationDuration` and falls back to legacy
static `DURATION`. It writes the selected field before `onResume`. To cover the
literal `lapis` delay, a thread-local identifies only that activity's finish
runnable during `onResume`; the `Handler.postDelayed(Runnable,long)` interceptor
replaces only its delay. The context is restored in `finally`. Sleep and wake
runnables retain their separate timing.

Index schema v6 forces rebuilding previously empty or unordered SystemUI
indexes. Earlier versions were: v5 volume listener output; v4 system fingerprint
for OTA invalidation; v3 dock-background query; v2 flat module map replacing the
v1 nested structure. Index files live directly in private `filesDir`, as documented
in `docs/dex_index/readme.md`.

Verification: `assembleDebug` passed. `SystemUiDexTargetsTest` covers current-path
priority, legacy-only firmware, and missing classes; its 3 cases passed, alongside
all 16 `NetworkSpeedPolicyTest` cases. JADX source, smali, and layout contracts
were checked for all three index modules. Native index generation and LSPosed
runtime behavior still need device verification with the `dev` APK.
