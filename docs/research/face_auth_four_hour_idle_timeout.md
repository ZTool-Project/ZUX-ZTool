# Four-hour face authentication idle timeout (Android 17)

Investigated on 2026-11-15 using the Android 17 Lenovo artifacts in
`F:\USER\Jadx项目\Lenovo\android-17` and jadx 1.5.6.

## Conclusion

The four-hour non-strong biometric idle timeout is implemented in
`com.android.server.locksettings.LockSettingsStrongAuth` in `services.jar`,
and its state reaches the SystemUI face authentication gate. The timer and
the authoritative allowed/disallowed state belong to system_server.

This is not a face-only timeout: it applies to non-strong biometrics. The
device's runtime face sensor strength still needs to be checked before
attributing an observed face authentication restriction to this exact path.
Strong biometric authentication can restore eligibility, as can successful
primary credential authentication. Four hours does not itself set a global
"PIN only" strong-auth flag.

## Artifacts and verification

- `services.jar` SHA-256:
  `CBBA40FF4E38A193186931655B022F7CAF644C0ECA9584277DF752C40C412FE9`.
- Supplementary artifacts: `framework.jar` and `系统界面_17.apk` from the
  same directory.
- Jadx MCP field listings, method references and smali were inspected.
  `get_class_source` returned an unrelated class even after clearing its
  cache; code searches also missed classes subsequently found by CLI.
  Those source/search results must not be used as negative evidence.
- CLI decompilation with `--no-replace-consts` exposes the literal timeout.
  Targeted Java output was checked against the original smali for the timer,
  callback and reset paths. Some CLI runs returned errors in other methods;
  these do not establish that all code in the artifacts decompiled correctly.
- Decompiled output stays outside the repository in the temporary directory
  `ztool-face-timeout-20261115`; only research notes are committed.

Reproduce the decisive class export:

```powershell
& 'D:\Program Files\Jadx\bin\jadx.bat' `
  --single-class com.android.server.locksettings.LockSettingsStrongAuth `
  --single-class-output "$env:TEMP\LockSettingsStrongAuth.java" `
  --no-replace-consts --no-res `
  'F:\USER\Jadx项目\Lenovo\android-17\services.jar'
```

## Constants and scheduling

The class initializer contains:

```text
DEFAULT_NON_STRONG_BIOMETRIC_TIMEOUT_MS = DEBUG_TIMEOUT ? 300000L : 86400000L
DEFAULT_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_MS = DEBUG_TIMEOUT ? 120000L : 14400000L
```

The normal idle value is confirmed by smali:

```smali
const-wide/32 v0, 0xdbba00
sput-wide v0, Lcom/android/server/locksettings/LockSettingsStrongAuth;->DEFAULT_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_MS:J
```

`0xDBBA00 = 14,400,000 ms = 14,400 s = 240 min = 4 h`.
`DEBUG_TIMEOUT` requires both `Build.IS_DEBUGGABLE` and debug-level logging
for the tag `LockSettingsStrongAuth_Timeout`; its idle value is two minutes.

The scheduling path is:

```text
LockPatternUtils.scheduleNonStrongBiometricIdleTimeout(userId) [framework.jar]
  -> ILockSettings Binder
  -> LockSettingsService.scheduleNonStrongBiometricIdleTimeout(userId)
     checks biometric permission, forwards to mStrongAuth
  -> LockSettingsStrongAuth.scheduleNonStrongBiometricIdleTimeout(userId)
     sends Handler message 9
  -> handleScheduleNonStrongBiometricIdleTimeout(userId)
     deadline = Injector.getNextAlarmTimeMs(DEFAULT_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_MS)
     cancels any existing idle alarm for this user
     AlarmManager.setExact(2, deadline, tag, listener, mHandler)
```

`Injector.getNextAlarmTimeMs` adds the duration to
`SystemClock.elapsedRealtime()`. Alarm type 2 is `ELAPSED_REALTIME_WAKEUP`.
The tag is `LockSettingsPrimaryAuth.nonStrongBiometricIdleTimeoutForUser`.

The deadline is four hours after the scheduling call. This investigation
does not identify the external caller/event that invokes the framework API,
so "four hours since the last face use" is not established from this method
alone. The only direct caller of the StrongAuth scheduling API inside
services.jar is the LockSettingsService Binder wrapper.

## Expiry, propagation and recovery

`LockSettingsStrongAuth$NonStrongBiometricIdleTimeoutAlarmListener.onAlarm()`
calls `setIsNonStrongBiometricAllowed(false, mUserId)`. A changed value is
stored in `mIsNonStrongBiometricAllowedForUser` and broadcast through
`IStrongAuthTracker.onIsNonStrongBiometricAllowedChanged(allowed, userId)`.

In framework.jar, `LockPatternUtils.StrongAuthTracker` caches this value.
Its `isBiometricAllowedForUser(isStrongBiometric, userId)` checks the normal
strong-auth flags and additionally requires
`isNonStrongBiometricAllowedAfterIdleTimeout(userId)` when
`isStrongBiometric` is false. Strong biometrics bypass this extra idle check.

In SystemUI:

- `com.android.systemui.keyguard.data.repository.BiometricSettingsRepositoryImpl`
  registers a StrongAuthTracker and exposes `isStrongBiometricAllowed` and
  `isNonStrongBiometricAllowed`. The face sensor's `SensorStrength` selects
  which stream contributes to `isFaceAuthCurrentlyAllowed`.
- `com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl`
  includes `isFaceAuthCurrentlyAllowed` among the conditions of `canRunFaceAuth`.
- `KeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(FACE)` also passes
  `SystemUIDeviceEntryFaceAuthInteractor.isFaceAuthStrong()` into the
  framework tracker. That strength comes from the runtime sensor properties.

Recovery is explicit in services.jar:

- `handleStrongBiometricUnlock(userId)` cancels the non-strong fallback and
  idle alarms and sets non-strong biometric eligibility to true.
- `handleScheduleStrongAuthTimeout(userId)`, reached after
  `reportSuccessfulStrongAuthUnlock`, performs the same cancellation/reset
  while rescheduling the primary strong-auth timeout.
- A non-strong biometric success instead reaches
  `handleScheduleNonStrongBiometricTimeout`: it starts the separate 24-hour
  fallback alarm if absent and cancels the idle alarm. It does not itself
  schedule a new four-hour idle alarm.

The 24-hour callback is different:
`NonStrongBiometricTimeoutAlarmListener.onAlarm()` calls
`requireStrongAuth(128, userId)` (`STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT`).
The four-hour callback only flips the non-strong eligibility boolean.
`LockSettingsStrongAuthDelegate` in this artifact has an empty constructor
and an empty `strongAuthTimeoutOnAlarm`; it does not implement another gate.

## Other four-hour search hits

CLI source searches for exact `14400000`, `14400` and `Duration.ofHours(4)`
also found these unrelated uses:

| Class or area | Use |
| --- | --- |
| `TrustManagerService.handleScheduleTrustedTimeout` | Trust timeout; separate from the biometric idle state |
| `BackupManagerConstants` | Key/value backup interval |
| `AppFGSTracker` | Background foreground-service duration thresholds |
| `QuotaController`, `DeviceIdlenessTracker`, `AppStandbyController` | Job quotas, idle thresholds and app standby timing |
| `GnssLocationProvider`, `NtpNetworkTimeHelper` | Backoff limits |
| `GnssTimeUpdateService` | GNSS time update alarm interval |
| `NotificationUsageStats`, `EventHistoryImpl`, `PowerStatsDataStorage` | Statistics/event maintenance and file rotation |
| `ProfcollectForwardingService`, `RemoteOnDeviceIntelligenceService` | Profiling interval and service timeout |
| `PacProxyService` | PAC retry delay sequence in seconds |

Jadx may render unrelated equal-valued literals as
`BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS`.
That rendering does not mean lock settings reads the backup configuration.

## Implication for a future hook

The specific system_server investigation targets are
`handleScheduleNonStrongBiometricIdleTimeout(int)` and
`LockSettingsStrongAuth$NonStrongBiometricIdleTimeoutAlarmListener.onAlarm()`.
Keeping the old SystemUI delegate's timestamps fresh cannot prevent the
system_server idle callback from changing eligibility on this build.

Before implementing a bypass, verify the sensor strength and the transition
of the non-strong allowed state on-device, and identify the external
scheduling event. A scheduling-only change also needs to account for an
already queued alarm or an already expired false state. Broadly clearing
strong-auth flags would change separate lockout/credential policies and is
not equivalent to targeting this idle timeout. No hook behavior was changed
as part of this investigation.
