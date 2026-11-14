# Doze brightness ownership in the Android 17 SystemUI sample

## Verdict and evidence boundary

Inspected on 2026-11-15 through the connected JADX instance. The loaded manifest
identifies `com.android.systemui`, version name `17`, version code `37`, and
compile/min/target SDK `37`. Its decompiled source marker is
`go/retraceme 18fb1fa2e53ac1eb6d3aabb5731499e5a83dbe8bb2e2198b13e7fc962547dfa7`.
This marker is not an APK checksum or a verified device build number.

**SystemUI's decision owner is `com.android.systemui.doze.DozeScreenBrightness`.**
It calculates a float brightness and calls `DozeMachine.Service`.
`DozeService` submits that value to its framework `DreamService` superclass.
`DisplayManager` supplies the default brightness and the preferred sensor mapping;
`AlwaysOnDisplayPolicy` supplies a fallback mapping and dimming policy.

Actual panel output is applied by the platform display/power stack. Only the
SystemUI portion was verified against this vendor APK. The AOSP references below
explain the downstream architecture; they do not establish Lenovo's exact
framework implementation or the device's effective configuration.

No device measurement was performed. No hook implementation was changed.

## Verified SystemUI call chain

```text
DozeMachine transitions / DozeScreenState.applyScreenState(3)
    or DozeScreenBrightness.onSensorChanged()
  -> DozeScreenBrightness.resetBrightnessToDefault()
     or updateBrightnessAndReady(boolean)
  -> DozeMachine.Service.setDozeScreenBrightness(float)
  -> optional display-state adapters (brightness forwarded by Delegate)
  -> DozeBrightnessHostForwarder.setDozeScreenBrightness(float)
  -> DozeService.setDozeScreenBrightness(float)
  -> background executor / $r8$lambda$WyOLEbIwvU4m6kUdHMSex0W1n8s
  -> DreamService.setDozeScreenBrightness(float) [vendor framework boundary]
```

The wrapped-service construction is in
`DaggerReferenceGlobalRootComponent.DozeComponentImpl.SwitchingProvider.get()`,
case `1`. Case `8` constructs and populates `DozeScreenBrightness`.
`DozeComponentImpl.partArray()` places brightness before screen state in the
machine's parts list. `DozeScreenState.applyScreenState(3)` also requests a
brightness update after setting the display to DOZE.

The forwarder additionally assigns
`NotificationShadeWindowControllerImpl.mScreenBrightnessDoze = brightness`.
The window controller's `apply()` uses that value as
`LayoutParams.screenBrightness` when `forceDozeBrightness` is set, subject to
the ZUI pulse-brightness branch. This is a window-brightness consumer of the same
value, not a separate sensor policy. The forwarder does not immediately call
the window controller's `apply()`.

## Where the brightness value comes from

| Value | Source verified in Dagger case 8 |
| --- | --- |
| `mDefaultDozeBrightness` | `DisplayManager.getDefaultDozeBrightness(context.getDisplayId())` |
| `mSensorToBrightness` | First `DisplayManager.getDozeBrightnessSensorValueToBrightness(context.getDisplayId())`; only when null, convert `AlwaysOnDisplayPolicy.screenBrightnessArray` with `BrightnessSynchronizer.brightnessIntToFloat()` |
| `mScreenBrightnessDim` | `AlwaysOnDisplayPolicy.dimBrightness` |
| `mScreenBrightnessMinimumDimAmount` | Framework float resource; the resolved symbolic resource name in JADX is not reliable |
| Scrim arrays | `AlwaysOnDisplayPolicy.dimmingScrimArray` and `wallpaperDimmingScrimArray` |

`resetBrightnessToDefault()` sends:

```text
clampToDimBrightnessForScreenOff(
    min(mDefaultDozeBrightness, DisplayManager.getBrightness(0)))
```

It also resets AOD dimming scrim alpha to `0`. The default path limits the value
to current display brightness even when automatic brightness is enabled.

The sensor callback treats `SensorEvent.values[0]` as an integer **bucket index**,
not as raw lux. `updateBrightnessAndReady()` selects the debug bucket if present,
otherwise `mLastSensorValue`, then reads `mSensorToBrightness[bucket]`. A negative
or out-of-range value does not submit a new brightness. For a valid value:

- `screen_brightness_mode == 1`: use the mapped float brightness.
- Otherwise: cap it with `DisplayManager.getBrightness(0)`.
- Apply `clampToDimBrightnessForScreenOff()` and submit the result.

The clamp was verified in smali because JADX's Java reconstruction contains
misleading loop/control flow. It applies only when all of these hold:

- An off animation should play, or `mWakefulness == 3`.
- `mState == INITIALIZED`.
- `mLastSleepReason == 2`.

The clamped value is `max(0, min(value - minimumDimAmount, dimBrightness))`.
This is an initialization-time dim constraint, not an unconditional AOD ceiling.

## APK resources and the missing-sensor branch

The loaded APK's `res/values/arrays.xml` and `strings.xml` contain:

| Resource | APK value |
| --- | --- |
| `config_doze_brightness_sensor_to_brightness` | `[-1, 2, 5, 27, 28]` |
| `config_doze_brightness_sensor_to_scrim_opacity` | `[-1, 0, 0, 0, 0]` |
| `config_doze_brightness_sensor_to_wallpaper_scrim_opacity` | `[-1, 0, 0, 0, 0]` |
| `doze_brightness_sensor_name_posture_mapping` | Empty array |
| `doze_brightness_sensor_type` | Empty string |

The integer brightness array is only a fallback; it is converted to floats and
is bypassed when DisplayManager provides a mapping. Its entries are not nits.

`RootDozeModule_Companion_ProvidesBrightnessSensorsFactory.providesBrightnessSensors()`
uses the empty posture mapping to call
`DozeSensors.findSensor(sensorManager, sensorType, null)`.
`findSensor()` returns null immediately when both type and name are empty; it
does not automatically pick the ordinary ambient-light sensor.

**Inference, conditional on no runtime resource overlay:** this sample creates
an empty sensor Optional, `setLightSensorEnabled(true)` cannot register a
listener, and normal sensor-driven brightness updates do not occur. AOD then
retains the value submitted by the default/reset path. If the initialization
clamp ran, that lower value can persist until a subsequent brightness submission.
Neither the effective overlay nor that runtime branch was measured here.

`AlwaysOnDisplayPolicy.SettingsObserver.update()` parses
`Settings.Global.always_on_display_constants`. It accepts `screen_brightness_array`,
`dimming_scrim_array`, and `wallpaper_dimming_scrim_array`, with the APK resources
as defaults. Editing those constants alone cannot fix an absent sensor. The
brightness controller captures the policy arrays during construction; the
observer does not directly rebind its captured arrays.

## Perceived brightness and useful investigation points

The same controller separately calls `DozeServiceHost.setAodDimmingScrim(alpha)`.
For a valid sensor bucket, it selects the larger ordinary/wallpaper scrim opacity
(wallpaper opacity is considered only for an ambient-capable wallpaper) and
divides by `255`. The host updates the front scrim in the applicable AOD/PULSING
state and stores the alpha in `ScrimState.AOD` and `ScrimState.PULSING`.
Without a brightness sensor it uses zero scrim opacity on a forced update.
The APK's valid bucket opacity entries are all zero, so these base resources
do not themselves demonstrate additional darkening.

`ForceNativeAod.kt` currently overrides only `DozeParameters.getAlwaysOn()` and
`AmbientDisplayConfiguration.alwaysOnEnabled(int)`. It does not replace the
brightness policy, add a sensor, or bypass the brightness caps.

For a future brightness hook, this sample's common submission point is
`DozeBrightnessHostForwarder.setDozeScreenBrightness(float)`: changing its argument
keeps both the framework submission and the stored window brightness consistent.
Changing only `DozeService`'s argument leaves the forwarder's stored value intact.
Changing only `mDefaultDozeBrightness` still encounters the current-brightness cap
and the initialization clamp. These are candidate points, not device-tested fixes.

To identify the actual cause of dim AOD on the device, inspect DozeService's dump
(`DozeScreenBrightness.dump()` exposes `registered`, `sensorToBrightness`,
`mDefaultDozeBrightness`, `screenBrightnessDim`, and `mLastSensorValue`), effective
sensor resources/overlays, current display brightness, and the Doze brightness
submission logs. The dump's default field alone is not the final submitted value.

## AOSP downstream reference, separate from the vendor evidence

Official AOSP main sources were read on 2026-11-15:

- [DreamService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/dreams/DreamService.java): its float setter is named `setDozeScreenBrightnessFloat` in the inspected AOSP source, unlike the vendor call above. `updateDoze()` forwards brightness through `IDreamManager.startDozing` or its one-way counterpart while dozing.
- [DreamManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/dreams/DreamManagerService.java): a valid active dream forwards its brightness to `PowerManagerInternal.setDozeOverrideFromDreamManager()`.
- [PowerManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/PowerManagerService.java): records the Doze brightness override and updates power state, preferring a valid float value over integer conversion.

This supports the architectural distinction: SystemUI chooses and requests brightness,
while system_server and the display stack apply it. Establishing the device's
exact downstream clamps or physical panel brightness requires its framework and
display configuration, beyond this SystemUI APK.
