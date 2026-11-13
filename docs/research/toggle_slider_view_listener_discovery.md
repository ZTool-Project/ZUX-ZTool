# Resolving ToggleSliderView's progress listener without a hardcoded class number

`VolumeSliderPercentageHook` hooks `SeekBar.OnSeekBarChangeListener#onProgressChanged` inside
`com.android.systemui.settings.ToggleSliderView` to refresh the volume percentage label while
the user drags the control-center media slider. The listener is an **anonymous inner class**, so
its name is `com.android.systemui.settings.ToggleSliderView$<n>` where `<n>` is assigned by R8 and
changes on every build.

## Evidence that the number drifts

Measured on the ZUI `ZuiSystemUI.apk` of `Lenovo/TB710FU/TB710FU:17/CQ2A.260712.001/
ZUXOS_2.5.02.089_261030_PRC` (adb-pulled, dex string table contains every
`ToggleSliderView$…` class name):

| Class | Role in this build |
|---|---|
| `ToggleSliderView$2` | `ViewOutlineProvider` (rounded-outline clip) |
| `ToggleSliderView$5` | `SeekBar.OnSeekBarChangeListener` (the volume listener) |
| `ToggleSliderView$1` | `VolumeDialogController.Callbacks` |
| `ToggleSliderView$6` | `ContentObserver` (`$r8$classId`-merged) |
| `ToggleSliderView$10` | `BroadcastReceiver` (`$r8$classId`-merged) |
| `ToggleSliderView$11` | `ZuiBrightnessSliderListener` (holder) |
| `ToggleSliderView$12` | `View.OnTouchListener` |

The previously hardcoded `ToggleSliderView$2` therefore no longer names the listener: the
`getDeclaredMethod("onProgressChanged", SeekBar, int, boolean)` lookup fails and the label is not
refreshed during a drag (the other hooks — ctor, `updateVolumeSlider`, `setVolumeProgress`,
`refreshSeekBar`, `updateMusicSlider`, `registerVolumeObserver` — keep working because they target
named methods).

## Why not `getDeclaredClasses()`

Anonymous classes are not *member* classes; `Class.getDeclaredClasses()` only reports member
classes. Verified on a JDK: for an outer class with one member interface, two anonymous instances
and one local class, the call returns only the member interface. Any implementation that filters
`getDeclaredClasses()` by the `SeekBar.OnSeekBarChangeListener` interface would therefore silently
find nothing, and an implementation that walks `InnerClasses` metadata depends on an attribute R8
is free to strip.

## Primary mechanism: install-time field-type probe

The source-level structure that R8 cannot erase is that `ToggleSliderView` keeps the listener in a
field whose *declared type* is the anonymous class:

```java
public final AnonymousClass5 mMediaVolumeSeekListener;   // = ToggleSliderView$5
...
this.mMediaVolumeSeekListener = anonymousClass5;
...
this.mMediaVolumeSlider.setOnSeekBarChangeListener(anonymousClass5);
```

So `VolumeSliderPercentageHook.resolveProgressListenerClasses()` runs in `handleLoadPackage`:

1. load `com.android.systemui.settings.ToggleSliderView`;
2. read `getDeclaredFields()`, take each `Field.getType()` (a `NoClassDefFoundError` on an
   unrelated field type only drops that field);
3. keep concrete types (`!isInterface`) that are assignable to
   `SeekBar.OnSeekBarChangeListener` **and** declare `onProgressChanged(SeekBar, int, boolean)`;
4. prefer the candidates whose name is nested under the slider class (`ToggleSliderView$…`), which
   drops unrelated widgets the view holds — on this build `mBrightnessSlider` is a `ToggleSeekBar`
   (a genuine `SeekBar.OnSeekBarChangeListener` implementation) as well; if no candidate is nested,
   every match is used, so a listener moved out to a named class is still found;
5. hook every remaining match.

On this device (`ZUXOS_2.5.02.089_261030_PRC`) the probe logs
`Volume slider listener resolved online: com.android.systemui.settings.ToggleSliderView$5` and
installs two hooks only for that class.

Notes:

- No ZUI name is involved. The probe works even if the field is renamed, as long as some declared
  field of the class holds the listener type. It only requires that the field's declared type is the
  anonymous class rather than the framework interface; when R8 keeps the interface type, step 3
  finds nothing and the offline index takes over.
- A second matching field could exist (brightness listener on another build). Hooking it is
  harmless: the callback resolves the enclosing `ToggleSliderView` from the `SeekBar` parent chain
  and drops every drag whose `SeekBar` is not `mMediaVolumeSlider`.
- The classes are only *loaded* here, not initialized, so this is safe at process start.

## Fallback 1: offline DexIndex

`SystemUiDexIndexer.indexVolumeSliderPercentage()` (app side, DexKit) finds the method

```
void onProgressChanged(android.widget.SeekBar, int, boolean)
```

whose declaring class name starts with `com.android.systemui.settings.ToggleSliderView$`, and writes
it as `progressListenerClass` under module key `volume_slider_percentage` (schema v5). The hook reads
it with `DexIndexStore.string(...)` in `handleLoadPackage` — never inside a hook lambda — and falls
back to the full class name. `onProgressChanged` cannot be renamed by R8 because it implements a
framework interface method, so the signature is a stable anchor.

## Fallback 2: the pre-probe constant

If both mechanisms fail, the hook still tries the old hardcoded
`com.android.systemui.settings.ToggleSliderView$2`. That keeps builds where the numbering happens to
match working and costs nothing otherwise; failure is logged.

## Verification

1. `adb logcat -s ZToolXposedModule` right after SystemUI starts shows either
   `Volume slider listener resolved online: com.android.systemui.settings.ToggleSliderView$<n>` or
   `Volume slider listener resolved from DexIndex: …`, followed by
   `Hooked volume slider progress listener: …`.
2. Drag the media-slider in the control center with the feature enabled: the percentage label must
   follow the drag (this is the path that was broken before).
