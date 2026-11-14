# Keyguard indication typography and rotation timing

Examined on 2026-11-15 through Jadx MCP: the loaded Lenovo SystemUI APK,
`versionName=17`, `versionCode=37`, SDK 37. This investigation follows
[the owner-info reader work](keyguard_owner_info_refresh.md). Findings below
come from host code/resources and repository hooks; no device timing trace
was collected. No production hooks or preferences were changed here.

## Typography is native

All three messages use `KeyguardIndicationTextView` in the bottom indication
area. `KeyguardIndicationRotateTextViewController.showIndication(int)` sets
`mUseOwnerInfoLayout` to `type == 0` before switching the message.

`KeyguardIndicationTextView.setNextIndication()` applies these native styles:

| Message | Style / resource | Size |
| --- | --- | --- |
| Owner info, type 0 | `TextAppearance.Keyguard.BottomArea.OwnerInfo`; `keyguard_owner_info_text_size` | 20dp |
| Charging, type 3 | `TextAppearance.Keyguard.BottomArea`, or its `DoubleShadow` child | 14sp |
| Swipe-to-unlock text | Same normal bottom-area style | 14sp |

The owner style also uses two lines, start text alignment and a maximum width
of 75% of the display; normal indications use the normal centered layout.
After applying the style and text, the view explicitly applies the owner
dimension again with `setTextSize(0, getDimensionPixelSize(...))`. Its unit
argument is pixels, because the dimension has already been converted.

The size values are in `res/values/dimens.xml` and `res/values/styles.xml`.
All 75 `values*/dimens.xml` and `values*/styles.xml` files in the loaded APK
were checked: none of the qualified files overrides these sizes/styles.
Runtime resource overlays were not inspected. At the default font scale,
20dp is approximately 1.43 times 14sp; dp and sp prevent treating that ratio
as constant across font-scale settings.

`OwnerInfoSystemUiHook` changes reader results and requests a native refresh.
It never sets text size, text appearance, owner layout, or rotation timing.
`SystemUIChargeWattsHook` and `SystemUIRealWatts` modify the result of
`computePowerIndication()` and also do not change these sizes. The larger
signature therefore follows the host's existing owner-info design.

## The exact swipe-to-unlock message

The user identified the text as `上滑解锁`. In the loaded APK:

| SystemUI string | Simplified Chinese text | Relevant path |
| --- | --- | --- |
| `lockscreen_storage_locked` | 上滑解锁 | `showActionToUnlock()` -> `showBiometricMessage(...)` |
| `dismissible_keyguard_swipe` | 向上滑动即可继续 | Force-dismissible branch, type 13 |
| `keyguard_unlock` | 向上滑动即可打开 | Biometric follow-up or persistent unlock message |

`showActionToUnlock()` uses `lockscreen_storage_locked` when the user cannot
skip the bouncer, and in some non-face-authenticated skip-bouncer cases.
Although the visible message is an action hint, the implementation routes it
through the biometric message machinery. With no existing biometric source,
it becomes the main message, type **11**. If an existing sourced biometric
message is retained, the source-less action hint can become its follow-up,
type **12**. Both have the timing settings below. Smali confirms the private
resource ID `0x7f1408f6` inside `showActionToUnlock()`.

Type 8 (`user_locked`) is separate: the central refresh reads a framework
string at `0x01040694` when the user is not unlocked or is in encrypted/lockdown
state. Its actual framework text cannot be resolved from this SystemUI APK.
Jadx's SDK-derived label for that ID is not evidence of its firmware text.
Do not assume every visually similar unlock hint is type 13, or every message
containing the word "unlock" is type 11.

## There are three different clocks

| Message / type | Ordinary rotation delay | Protection against an urgent replacement | Separate removal timeout |
| --- | --- | --- | --- |
| Owner info / 0 | 5000 ms | 0 ms (`mMinVisibilityMillis = null`) | None in the owner-info producer |
| Charging / 3 | 5000 ms | 0 ms (`mMinVisibilityMillis = null`) | Removed when its charging condition stops applying |
| 上滑解锁 / 11, possibly 12 | 5000 ms | 3000 ms | Biometric message pair scheduled for removal 6000 ms after publication |

These are scheduling values, not guaranteed fully visible durations. They
apply to the visible, non-dozing bottom indication area with multiple queued
messages. A single queued message does not need a next-rotation task.

### Ordinary rotation and urgent updates

`KeyguardIndicationRotateTextViewController.showIndication(int)` schedules
the next queued message after `max(mMinVisibilityMillis ?: 0, 5000)`.
It records `mLastIndicationSwitch` only when the **type** changes. Updating
text within the current type deducts the elapsed time instead of restarting
a full five-second interval. The next task shows the queue's first element;
the currently displayed type has been moved to its tail.

`updateIndication(int, KeyguardIndication, boolean)` uses its final parameter
as urgency. An urgent update to another type can replace the current one as
soon as the current message's minimum visibility has elapsed. With a null
minimum, replacement can be immediate. An ordinary update generally preserves
an already scheduled rotation. Explicit removal of the current type uses
`hideIndication()` -> `updateIndication(type, null, true)` and can advance the
queue even if a visibility minimum has not elapsed. Doze also clears display.

The native owner-info result uses `updateIndication(0, info, false)`.
Charging uses `updateIndication(3, info, refreshUrgency)`: battery callbacks
make it urgent on a newly connected wired charger, while ordinary refreshes
and the quote-completion refresh pass false. Biometric messages 11/12 are
reapplied with true and a 3000 ms minimum.

### Message lifetime

`showBiometricMessage(...)` schedules
`mHideBiometricMessageHandler.schedule(2, 6000L)` before updating the rotate
controller. The controller constructor connects that timeout to
`KeyguardIndicationController$$ExternalSyntheticLambda4`, class ID 1, whose
alarm calls `hideBiometricMessage()`. This clears both messages and removes
types 11/12. `AlarmTimeout.schedule` mode 2 replaces an existing alarm.

The six seconds start at message publication, not at its eventual appearance.
An identical message/source/follow-up returns early before rescheduling the
alarm. A central refresh that only calls `updateBiometricMessage()` also does
not restart that alarm. Extending a rotation interval alone therefore cannot
guarantee a longer lifetime for the action hint.

### Animation time

`KeyguardIndicationTextView.switchIndication()` animates when the message or
type changes, cancelling its previous animator. Its outgoing alpha animation
is 216 ms; incoming alpha has a 166 ms delay and an 833 ms duration. Translation
animations use 1000 ms. These transitions consume part of a rotation slot.
Changing charging text within type 3 can animate again while retaining the
original slot deadline. `SystemUIRealWatts` can produce different live numbers;
its 100 ms debounce is not a rotation interval or an independent refresh timer.

## Why charging can appear shortest

There is no charging-specific shorter ordinary rotation constant. There are
two concrete ways to reduce its observed readable time:

1. An urgent hint or authentication update replaces charging before five
   seconds, because charging has no minimum-visibility protection.
2. Charging numbers change while type 3 is active. The view transitions to
   the new text but the slot keeps its original deadline.

The current quote hook does not directly change these clocks, but its cache
completion calls `updateDeviceEntryIndication(false)`. That method first calls
`updateBiometricMessage()`, which reapplies an active type 11/12 with true.
If charging is showing then, the native urgent path can immediately replace
it. Native battery and other central refreshes take that same path. Passing
false only controls battery urgency; it does not make the entire refresh
non-urgent. This establishes a possible indirect effect of the new hook,
without proving that it caused the user's particular short charging slot.

## Available injection points

Per-message display timing is controllable in SystemUI. It belongs to the
active controller's `mRotateTextViewController`, not its owner-info reader.
Keep the existing `mLockPatternUtils` instance restriction for signature
reader interception; separately restrict timing interception to that related
rotate-controller instance.

- Hook `updateIndication(int, KeyguardIndication, boolean)` before native
  execution to supply a per-type `mMinVisibilityMillis`. A 5000 ms value
  protects the default slot against urgent updates; larger values extend
  the ordinary slot as well.
  A positive value below 5000 ms adds protection but does not shorten the
  ordinary five-second baseline. Explicit removals still take precedence.
- For independent ordinary dwell and urgent protection, or ordinary slots
  below five seconds, coordinate `showIndication(int)` and
  `scheduleShowNextIndication(long)`. Use the selected type and subtract
  elapsed time from its target duration. Preserve the distinction between
  normal rotation and a delayed urgent replacement; blindly replacing every
  scheduled delay would conflate them. Repeated text updates must not reset
  the slot forever.
- For the lifetime of `上滑解锁`, also handle its specific publication and
  hide timeout. Scope this by its native resource/message role rather than
  changing every biometric error or help message in types 11/12, or hooking
  `AlarmTimeout.schedule` globally. Changing only the rotation controller
  leaves the existing six-second removal in effect.
- If equal typography is desired later, apply a size override after native
  `setNextIndication()` for the active owner-info view. An earlier text-size
  change would be overwritten by the native owner-info dimension assignment.

## Remaining device verification

Use `assembleDev` / `app-dev.apk` for LSPosed devices. Record type, message,
urgency, current minimum, `mLastIndicationSwitch`, scheduling delay, central
refresh origin and actual animation/display timestamps. Compare a stable
native charging message, live wattage text, and quote completion while the
hint is active. This separates a shortened type slot from a shortened readable
animation interval and identifies which refresh caused any interruption.
