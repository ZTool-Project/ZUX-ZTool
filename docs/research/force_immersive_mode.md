# Global Immersive Mode on ZUX OS Android 17

## Inspected target

- Date: 2026-11-15.
- SystemUI loaded in the connected Jadx GUI: `com.android.systemui`, version 17,
  versionCode 37, compile/target SDK 37.
- Matching connected tablet: `TB710FU_CN_OPEN_USER_Q00019.0_A17_ZUXOS_2.5.10.102_ST_261112`.
- Read-only acquisition: `adb pull /system/framework/services.jar` to a temporary
  directory, then Jadx CLI single-class decompilation of InsetsPolicy,
  DisplayPolicy, and WindowState. Jadx reported method decompilation errors;
  the target selection methods and ControlTarget constructor used here were readable.
- Before changes, `dumpsys window displays` showed both bars SHOWING and
  `mAllowsSystemBarRemoteInsetsController=false`.

## Why the previous hooks failed

`CommandQueue.onSystemBarAttributesChanged(int, int, AppearanceRegion[], boolean,
int, int, String, LetterboxDetails[])` queues a message; its handler dispatches
attributes to registered callbacks. Argument 5 is a visibility report, not a hide
command. Setting it to zero changes SystemUI's view of the host window, without
changing surface ownership or the Insets delivered to apps.

The inspected `navigationbar.views.NavigationBar` and `TaskbarDelegate` callbacks
use appearance and behavior but do not consume requestedVisibleTypes.
`StatusBarModePerDisplayRepositoryImpl$commandQueueCallback$1` stores that value in
its status bar attributes flow. These paths do not issue a window-manager hide request.

`CommandQueue.setWindowState(int, int, int)` is also a queued notification.
`InsetsPolicy.BarWindow.updateVisibility` produces the real state from the control
target and sends it through StatusBarManagerInternal. Rewriting SHOWING to HIDDEN
at the receiving side falsifies that state, including transient reveals.

`DisplayInsetsController` registers a remote display Insets controller. Shell's
`DisplayImeController.PerDisplay.setVisibleDirectly` calls the four-argument
`updateDisplayWindowRequestedVisibleTypes`, with an IME-only mask. Remote system
bar control is disallowed on this tablet, so repurposing that registration alone
would not grant bar ownership.

## Actual control path and repair

The inspected InsetsPolicy has:

- `getStatusControlTargetInner(WindowState, boolean fake)`.
- `getNavControlTargetInner(WindowState, boolean fake)`.
- `mHidingPermanentControlTarget`, `mShowingTransientControlTarget`, and
  `mShowingTransientTypes`.

The outer selectors can replace an application target with a remote target.
Selecting a native hiding target inside the inner methods avoids that conversion.
`updateBarControlTarget` passes the resulting targets to
`InsetsStateController.onBarControlTargetChanged`, updates BarWindow visibility,
and dispatches the resulting real Insets and surface controls.

`ControlTarget(InsetsPolicy, false, true)` is the built-in hiding target. Its
constructor removes systemBars from its requested-visible mask and calls its own
InsetsController.hide(systemBars). The target's canShowTransient returns true.
Using this target hides the actual surfaces, including when apps request bars again.
It also overrides the normal forced-show branches for keyboard, split-screen,
navigation focus, notification shade, and lockscreen. Keyboard visibility itself
is not changed: only status/navigation ownership is redirected.

Real targets use the native showing-transient target while their bar bit appears
in mShowingTransientTypes. Fake targets use the hiding target, so apps retain the
hidden bar Insets during an edge reveal. A native forcibly-transient result is
also preserved. After hideTransient clears the showing mask, the selection returns
to the hiding target; the host manages control handoff and animation completion.
DisplayPolicy.requestTransientBars accepts the hiding target and calls showTransient;
SystemUI's existing auto-hide path remains responsible for hiding the reveal.

The SystemUI hook now only synchronizes the attribute report: clear the status and
navigation bits (1 and 2), preserve IME/caption/gesture bits, and report behavior 2
(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE). The old setWindowState hook is removed.
Both callbacks check isEnabled before overriding host behavior. Original WindowState
requests, global settings, and host LayoutParams are not persisted or rewritten.

This implementation deliberately targets the inspected Android 17 API. Older
InsetsPolicy versions lack the native hiding target; reflection failures are logged
by the module dispatcher, and the frontend switch is gated to SDK 37.
The [AOSP InsetsPolicy source](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/wm/InsetsPolicy.java)
corroborates the notification/control distinction but has different target names;
it must not substitute for the inspected vendor implementation.

## Frontend and activation

The existing `PreferenceKeys.FORCE_IMMERSIVE_MODE` remains false by default and is
shared by both hooks. The searchable switch is under System framework > Screen and
display. Repository/ViewModel persistence uses the existing module preference file.
The Framework feature's fixed scopes include SYSTEM_UI as well as ANDROID_SYSTEM
and SYSTEM_SERVER, so scope requests cover the companion hook.

Enable the switch, ensure Android/system and SystemUI are enabled in LSPosed, and
reboot for the initial installation into system_server. Runtime checks let an
installed hook stop overriding on subsequent policy/attribute events when disabled;
the switch does not promise an immediate traversal. Restarting SystemUI alone cannot
install a previously disabled system_server hook. Existing hot reload removes old
hook handles after replay, including the removed legacy setWindowState hook.

## Verification

Automated verification covers compilation, status/navigation-only mask clearing,
independent transient bits, fake-target layout stability, and the auto-hide return
to hiding. Search and locale checks cover the frontend entry.

The device was inspected read-only. No APK installation, preference edit, reboot,
or live hook activation was performed during this repair. Remaining on-device checks:

1. Install app-dev.apk (not debug) on the LSPosed device, enable the switch/scopes,
   and reboot. Verify both installation log messages.
2. Check launcher, a non-immersive app, app switching, and rotation. Dumpsys should
   report HidingPermanentControlTarget for both bars and hidden bar sources.
3. Swipe from an edge: both visible surfaces and SHOWING notifications should occur
   temporarily, while the app's layout remains stable; verify native auto-hide.
4. Open the keyboard, notification shade, lockscreen/bouncer, split-screen, and
   desktop/freeform modes. Check keyboard input, panel access, navigation gestures,
   taskbar access, and display cutout/content layout on this vendor build.
5. Disable the switch, trigger a focus change, and confirm normal policy returns.
   Reboot with it disabled to verify the original behavior without installed hooks.
