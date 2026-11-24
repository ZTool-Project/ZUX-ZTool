# ZUXOS SystemUI Live Activity Chips (DynamicBar L2 & L3)

Reverse-engineering notes from ZUXOS SystemUI APK (`com.android.systemui`), supporting `StatusBarChipsFilterHook`.

## 1. L2 Pipeline (`OngoingActivityChipsViewModel$ChipBundle`)

ZUXOS extends AOSP `statusbar.chips` with a 6-argument `OngoingActivityChipsViewModel$ChipBundle` constructor upstream of the status bar 2-slot overflow and dual-chip squish layout:

| Slot | Parameter | Runtime Key / Type | Category |
| --- | --- | --- | --- |
| `args[0]` | `screenRecord` | `OngoingActivityChipModel$Active` (`key = "ScreenRecord"`) | Screen Record |
| `args[1]` | `shareToApp` | `OngoingActivityChipModel$Active` (`key = "ShareToApp"`) | Share To App |
| `args[2]` | `castToOtherDevice` | `OngoingActivityChipModel$Active` (`key = "CastToOtherDevice"`) | Cast To Other Device |
| `args[3]` | `call` | `OngoingActivityChipModel$Active` (`key` starts with `"callChip-"`) | Ongoing Call |
| `args[4]` | `notifs` | `List<OngoingActivityChipModel$Active>` | Promoted Notifications |
| `args[5]` | `customization` | `List<OngoingActivityChipModel$Active>` | ZUXOS Customization Chips |

Disabled single-slot arguments (`args[0..3]`) are replaced with `OngoingActivityChipModel$Inactive(3)` (`shouldAnimate = true`). Items in `args[5]` are filtered by `Active.key`:

- `"CustomizationChip:media"` -> Media
- `"CustomizationChip:charging"` -> Charging
- `"CustomizationChip:flashlight"` -> Flashlight
- `"CustomizationChip:welcome_back"` / `"CustomizationChip:default"` -> Welcome Back
- `"CustomizationChip:bluetooth*"` / `"*headset*"` / `"bt"` -> Bluetooth Headset (defensive match)

### Unreached / Legacy Providers Observed in APK

- **ScreenRecord**: Bypassed when `ScreenCaptureRecordFeaturesInteractor.isLargeScreenScreencaptureEnabled()` returns `true` (`incomingChipBundle$1` drops `ScreenRecordChipViewModel.chip`).
- **WelcomeBack**: Only added via test/trigger helpers or unused providers; never active under normal flows in inspected builds.
- **BluetoothHeadset**: Provider updates its internal `_chip` flow without pushing to `CustomizationChipRepository.addChip(...)`.

## 2. L3 Expanded Cards

L3 cards are floating overlays triggered on chip tap or promoted notification arrival:

- **Promoted Notifications & Call**: `HeadsUpCoordinator$onPromotedNotificationChipTapEvent$1` and `StatusBarNotificationChipsInteractor$l3NotificationHandler$1` call suspend method `StatusBarNotificationChipsInteractor.onDynamicBarL3Show(NotificationEntry, Continuation)` which drives `DynamicBarL3Window.getOrCreateNotificationL3(onDismiss, onReady)`.
- **Flashlight**: `FlashlightChipProvider$$ExternalSyntheticLambda0` calls `DynamicBarL3Window.getOrCreateCustomizationL3(onDismiss, onReady)`.
- **Media**: `MediaChipInteractor.updateChipState()` publishes the L2 chip with a click handler that emits `_showL3Event`, driving `SharedNotificationContainerBinder` and `MediaL3ContainerView` via `MediaChipInteractor$MediaL3ViewState`.

Suppressing `onDynamicBarL3Show` (checking `NotificationEntry.mSbn` for `Notification.CallStyle` to distinguish Call from Notification), `DynamicBarL3Window.getOrCreateNotificationL3`, `DynamicBarL3Window.getOrCreateCustomizationL3`, and `MediaChipInteractor.updateChipState` + `MediaL3ViewState` prevents L3 overlays from appearing for any filtered category.
