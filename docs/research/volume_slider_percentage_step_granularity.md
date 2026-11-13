# Volume-slider percentage granularity: ZUI 17.5.24 (Android 16) vs ZUXOS 2.5 (Android 17)

The control-center media-volume label changes in 1% steps while dragging on the Android 16 ZUI
build, but only in ~6.7% steps on the Android 17 build. This note records the A16-vs-A17 comparison
and the actual cause, which is **not** in SystemUI.

APKs compared: `F:\USER\Jadx项目\Lenovo\android-16\系统界面_17.5.24.apk` (jadx CLI) and the A17
`ZuiSystemUI.apk` (`ZUXOS_2.5.02.089_261030_PRC`, jadx MCP). Framework side: `services.jar` from
both dumps (`com.android.server.audio.AudioService`).

## The slider data path is unchanged

| Piece | A16 (17.5.24) | A17 |
|---|---|---|
| `ToggleSliderView` ctor | `mMediaVolumeSlider.setMax(150000); setMin(0)` | same |
| raw → level | `ceil(progress / 10000)` | same |
| level → bar | `setVolumeProgress(level)` → `setProgress(level * 10000)` | same |
| drag listener body | `setStreamVolume(3, ceil(progress/10000), 0)` | same (via `mController.setStreamVolume`) |
| `zui.widget.SeekBarNps` drag | `progress = start + (int)(dx * max/width)`; applied through `ProgressBar.setProgressExternal(p, fromUser=true, animate=false)` | same |
| slider max during drag | 150000, continuous progress | same |
| percentage TextView in layout | none (hook adds one) | none (hook adds one) |

Two A17-side differences that do **not** affect the number:

- the volume slider is now a `ToggleSeekBar` (A16: plain `zui.widget.SeekBarNps`) and
  `ToggleSeekBar.onProgressChanged` clamps progress to `mMaxProgressValue`, which the new
  `ToggleSliderView.updateMediaVolumeSliderLimit()` sets to `limitVolume * 10000` (safe-volume
  limit) or 150000. It caps the reachable level, not the label granularity;
- `AudioService` replaced `AudioSystem.initStreamVolume()` with `initMinMaxForVolumeGroup()` and
  added `AudioManager.clearVolumeCache("getStreamMaxVolume")` in `createStreamStates()`. Both
  frameworks still start from `MAX_STREAM_VOLUME[STREAM_MUSIC] = 15`, overridable by
  `ro.config.media_vol_steps`.

## What actually decides the granularity

`VolumeSliderPercentageHook` used the slider position (`round(progress * 100 / max)`, 1% per pixel
step) until commit `3060ffa6` (2026-10-11) replaced it with the MediaOutputDialog number:

```kotlin
(level * 100 / maxLevel)      // AudioManager.getStreamVolume/getStreamMaxVolume(STREAM_MUSIC)
```

That formula is quantized by the stream step count: `100 / 15 = 6.67` % per step. On the tablet
`cmd media_session volume --stream 3 --get` reports `range [0..15]`, hence 26%, 33%, 40%, ...

The finer `FineVolumeSteps` path (150 steps) would bring the granularity back to ~0.67% under the
same formula, but it does not run on the tablet: `AudioService.createStreamStates()` lives in
system_server, and the LSPosed log only shows this module loading in `com.android.systemui` and
`com.android.settings` — the module's `android` scope is not enabled there. With fine volume steps
active, `FineVolumeStepsSliderHook` also raises the bar max to `150 * 10000`, so both the bar and
the settled number follow 1%-ish steps.

## Fix chosen here

The label now uses two sources (see `resolvePercent`):

- while the slider is held (a drag callback with `fromUser=true`, or `SeekBar.isPressed`) it reports
  the **bar position**, normalized onto the 0..100000 stock range → 1% granularity, the A16 feel;
- otherwise it reports the **applied stream level** (`level * 100 / maxLevel`) → the number
  MediaOutputDialog shows, which was the point of `3060ffa6`.

Consequence: after release the number snaps from the finger position to the applied step (the drag
callback's value followed the finger). That is intended: the bar may sit between two stream steps
while the volume already holds the ceil'd level.

## Verification

1. `adb shell cmd media_session volume --stream 3 --get` — shows the current step range (15 = stock,
   150 = fine volume steps active).
2. Drag the QS media slider: the label must move in 1% increments while the finger is down and
   settle on the applied level after release.
