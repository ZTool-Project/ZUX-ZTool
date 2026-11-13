# QS volume-slider long press on ZUXOS 2.5 (Android 17)

Long-pressing the control-center media volume bar opened the ROM's brightness detail
instead of ZTool's volume panel. Cause: the volume bar became the same view class as the
brightness bar, and one press reaches two hooks that used to be disjoint.

Decompiled inputs: `ZuiSystemUI.apk` from `ZUXOS_2.5.02.089_261030` (jadx MCP), matching
the `TB710FU_CN_OPEN_USER_Q00019.0_A17_DF_2.5.02.089_DEV_261030` test device.

## Facts that changed on Android 17 (A16 had the opposite)

| Piece | Android 16 | Android 17 |
|---|---|---|
| `media_volume_slider` in `status_bar_toggle_slider{,_detail}.xml` | plain `zui.widget.SeekBarNps` | `com.android.systemui.settings.brightness.ToggleSeekBar` |
| `ToggleSeekBar` hierarchy | `extends android.widget.SeekBar` | `extends zui.widget.SeekBarNps` |
| `SystemUIDialog` constructors | `(Context, int, boolean)`, … | `(int theme, int unused, Context)`, `(Context)`, 11-arg |
| `Expandable` | interface, static `fromView` | final class, `fromView` only on `Expandable$Companion` |

`ToggleSeekBar.onTouchEvent` overrides the NPS method and calls `super.onTouchEvent(...)`,
so a single DOWN on the volume bar is seen by both `ToggleSeekBar.onTouchEvent` and
`SeekBarNps.onTouchEvent` — the inner (NPS) hook runs its after-code first, the outer
(ToggleSeekBar) hook second. `ToggleSliderView.<init>` assigns
`mToggleSeekBarListener` to `mBrightnessSlider` only, so the stock brightness-detail
branch can never fire for the volume bar; any `openBrightnessDetail` log on a volume-bar
press therefore comes from the module.

## Symptoms before the fix

1. `ControlCenterLongPressHook`'s `ToggleSeekBar` branch called `openBrightnessDetail()`
   for any slider inside a `ToggleSliderView`, volume bar included.
2. Both branches called `trackPress`, which shares one `PressState` per view and posts
   the trigger runnable; the second call's `cancelTrigger` removed the volume trigger
   that the NPS branch had just posted. Net effect: only the brightness dialog opened.
3. `VolumeSliderLongPressHook.buildAndShowPanel` threw `NoSuchMethodException` on the
   missing `(Context, int, boolean)` constructor, so the panel could not open at all.
4. Mute/DND tiles were skipped: `Expandable` is a class on A17, so the
   `Proxy.newProxyInstance` fallback raised `IllegalArgumentException: ... is not an
   interface`.

## Fix (this commit)

- `ControlCenterLongPressHook`: one dispatch by slider identity inside its
  `ToggleSliderView` (`mBrightnessSlider` / `mMediaVolumeSlider`, root containment as
  fallback), and `PressState.downTime` makes only the first hook call of a gesture start
  it. `releaseSquish` is idempotent.
- `VolumeSliderLongPressHook`: `newSystemDialog()` tries `(int, int, Context)` then the
  pre-17 `(Context, int, boolean)`; `resolveExpandable()` tries the flattened static then
  `Expandable$Companion.fromView`, falling back to a null Expandable (animation only).
- `VolumeSliderFidelityHook`: scopes to `mMediaVolumeSlider` identity instead of "any
  bar under a ToggleSliderView", which would also cancel the brightness settle animation.

## Verification

```powershell
.\gradlew.bat assembleDev
adb install -r -d app\build\outputs\apk\dev\app-dev.apk
adb shell su -c "kill $(adb shell pidof com.android.systemui)"   # LSPosed re-injects
adb shell cmd statusbar expand-settings
adb shell input swipe <volX> <volY> <volX> <volY> 900            # long press volume bar
```

Expected: ZTool panel (media / ring columns + mute, DND, media-output tiles), no
`ToggleSliderView: openBrightnessDetail` log, no `Failed to build QS tile view` error.
The same gesture on the brightness bar must still open the ROM brightness detail.

## Unrelated, still broken on this build

`NotificationCenterTransparency.hookNotificationShadeBlur` throws
`ClassCastException: java.lang.Boolean cannot be cast to java.lang.Float`
(`NotificationCenterTransparency.kt:104`) on every shade expansion — the shade-blur
argument type changed in A17.
