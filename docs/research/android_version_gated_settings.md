# Android-version gated settings rows — research and migration design

Status: research only. No production code was changed.

Scope: the generic `SettingItem` availability model and the four rows the user
identified for migration. Related gates (section-level, menu-level, hook-only) are
inventoried but explicitly out of scope.

## TL;DR

- The three nullable bounds belong on `SettingItem`; the render funnel
  (`ZToolSettingItem`) is the single place that turns them into `enabled`, `checked`,
  `summary`, and a ripple-only click. Rows built through `SettingItem` need no
  per-screen gating code.
- Four rows are in scope. Three of them already implement the requested behavior by
  hand (`checked = false`, `enabled = false`, empty callback, replacement summary), so
  the generic API is a consolidation, not a UX change. The fourth (shade rebound)
  additionally gains a lower bound that the current code does not enforce.
- Bounds are **Android release majors** (`15`, `16`, `17`), not SDK API levels.
  Mapping used by the proposal: 15 → API 35, 16 → 36, 17 → 37. This matches the
  user's manual list; if API levels were intended instead, the helper is simpler but
  every call site must use 35/36/37.
- Existing UI/hook drift must be resolved in the same effort:
  - `NativeNotificationIcon`: UI caps at Android 15, but the hook has no version guard
    and contains API 36+ code paths. A stored `true` preference still installs it.
  - `ShadeReboundFix`: UI will now reject Android 14 and below, but the hook only
    guards the upper bound (`>= API 37`).
- Miuix is the main implementation trap: `BasicComponent` only attaches
  `Modifier.clickable` when `enabled && onClick != null`, so `enabled = false` yields
  no ripple at all. "Ripple only" requires an enabled component with disabled colors
  and a no-op callback (verified against miuix v0.9.3 source).

## 1. Confirmed migration scope

The user counted four rows. Bounds below are Android release majors, inclusive.

| # | Row | Screen | Current gate | New bounds | Hook-side guard today |
|---|-----|--------|--------------|------------|-----------------------|
| 1 | 使用原生通知图标 | `screens/systemui/statusbar/StatusBarSettingsScreen.kt:492` | `SDK_INT < 36` | `maxAndroidVersion = 15` | none (see §6) |
| 2 | 自定义锁屏时钟颜色 | `screens/systemui/lockscreen/LockScreenSettingsScreen.kt:317` | `SDK_INT >= 36` native | `maxAndroidVersion = 15` | `LockScreenClockColorHook` returns on `>= 36` |
| 3 | 媒体输出弹窗居中 | `screens/systemui/controlcenter/ControlCenterSettingsScreen.kt:472` | `SDK_INT >= 37` native | `maxAndroidVersion = 16` | `MediaOutputDialogCenterHook` returns on `>= 37` |
| 4 | 修复控制中心回弹延迟 | `screens/systemui/misc/SystemUiMiscSettingsScreen.kt:237` | `SDK_INT >= 37` fixed | `minAndroidVersion = 15, maxAndroidVersion = 16` | `ShadeReboundFix` returns on `>= 37`; no lower guard |

Notes:

- Row 1 only needs `max = 15`; its current replacement summary is literally the
  default hint text, so it can pass `unsupportedHint = null` and rely on the default.
- Row 4 is a behavior change on Android 14 and below: the row was previously shown as
  a working switch there. The hook must gain a matching lower guard, otherwise a
  stored `true` preference still installs it on unsupported versions.
- Rows 2–3 keep their feature-specific hints (`..._native_summary`) because they
  explain *why* the row is unavailable, which is more useful than the generic text.
  Row 4 is different: its reason differs by bound. The existing
  `..._fixed_summary` is only correct on Android 17+ ("ZUXOS 2.0 fixed it"); on
  Android 14 and below the generic "not supported" text is correct. Resolve the hint
  at the call site (see §6.4) rather than using one string for both bounds.

## 2. How the current settings UI is built

Relevant files:

- `ui/components/ZToolSettingsModel.kt` — `SettingItem` sealed interface (9 variants),
  `ZToolSettingsSection`, `ExpressiveSectionItems`, `ZToolSettingItem`.
- `ui/components/ZToolSettings.kt` — the actual row composables, with a Material path
  and a Miuix path.
- `ui/components/SettingHighlight.kt` — highlight wrapper; it only reads `item.key`.

`SettingItem` today carries exactly two availability-ish members:

```kotlin
sealed interface SettingItem {
    val key: String
    val enabled: Boolean
}
```

Every interactive variant (`Switch`, `Checkbox`, `Entry`, `Dropdown`, `Slider`,
`TextInput`, `ColorPreview`, `Action`) forwards `enabled` to its row composable.
`Custom` also declares `enabled`, but `ZToolSettingItem` ignores it and just renders
`item.content()` (`ZToolSettingsModel.kt:500-505`), so `Custom` has no availability
semantics at all.

Scale of the model (git grep, `app/src/main/java/com/qimian233/ztool`):

| Construction | Count |
|---|---:|
| `SettingItem.Switch(...)` | 186 |
| Other typed variants | 129 |
| of which `SettingItem.Custom(...)` | 54 |
| Total | 315 |

Direct row-composable calls that bypass `SettingItem` (counts include the definition
and internal delegation):

| Composable | Calls |
|---|---:|
| `ZToolSwitchRow` | 14 |
| `ZToolCheckboxRow` | 2 |
| `ZToolSliderRow` | 10 |
| `ZToolTextInputRow` | 14 |
| `ZToolArgbColorTextFieldRow` | 12 |
| `ZToolPopupMenuSettingRow` | 9 |
| `ZListItem` | 5 |

The four migration targets all use `SettingItem.Switch`, so the first migration is
contained. A complete "all rows" API should still cover the row composables, because
new rows are occasionally written directly (e.g. `ZToolPopupMenuSettingRow` in the
status bar notification-limit row).

Both frontend styles share `ZToolSettingItem`:

- Material3 Expressive: `MaterialExpressiveSettingsSection` → `ExpressiveSectionItems`
  → `ZToolSettingItem` → `ZToolSwitchRow` etc.
- Miuix: `ZToolSettingsSection` → `ZToolSettingItem` → the same rows, which take the
  `MiuixBasicComponent` branch.

Therefore a gate resolved in `ZToolSettingItem` covers both styles and all list
surfaces (`ZToolSettingsList`).

## 3. Requested semantics

### 3.1 Version bounds

- `minAndroidVersion`, `maxAndroidVersion`: nullable Android release majors.
- Both `null` → available on every version.
- Comparison is inclusive on both ends: Android 15 is available with
  `maxAndroidVersion = 15`; Android 15 is available with `minAndroidVersion = 15`.
- Suggested validation: if both are non-null and `min > max`, treat as unsupported
  and log in debug builds (do not crash release builds on a typo).

### 3.2 Unsupported state

When the current Android version is outside the bounds:

| Aspect | Required behavior |
|---|---|
| `enabled` | `false` (combined with the row's own `enabled` via AND) |
| `checked` (Switch/Checkbox) | forced `false`, regardless of the stored preference |
| Click callback | not invoked; the row still shows a press ripple |
| Summary | `unsupportedHint`, or the default hint when null/blank |
| Persistence | no write; the stored preference is preserved for a later supported version |
| Visibility | row stays visible (the request disables it, it does not hide it) |

The "combined with the row's own `enabled`" part matters: rows disabled for other
reasons (dependency on a parent switch, in-progress operation) must keep their current
no-ripple disabled behavior. Only version-unavailability adds the ripple-only state.

### 3.3 Default hint

Text: `当前系统版本不支持此功能` / `Not supported on this system version`.

The Chinese string already exists as
`system_ui_status_bar_notification_icon_native_unsupported_summary`; the new generic
resource should replace it so there is one source of truth (see §7).

## 4. Whole-repo and Git-log inventory

Search performed:

```text
rg "Build\.VERSION\.SDK_INT|VERSION_CODES\." app/src/main/java/com/qimian233/ztool   # 54 hits
git log -S <each feature flag> --all
git log --grep "Android 17|ZUXOS 2.5|unsupported|disable.*switch"
git grep on master / android-16-archived for the same UI/data paths
```

The two branches carry the same frontend gates; no additional frontend gate was
found in the checked branches.

### 4.1 Row-level gates (in scope)

| Row | Repository field | Hook module | Boundary | Commit that introduced it |
|---|---|---|---|---|
| 使用原生通知图标 | `StatusBarSettingsRepository.nativeNotificationIconSupported` (`SDK_INT < 36`) | `NativeNotificationIcon` (no guard) | Android 15 max | `36377374` |
| 自定义锁屏时钟颜色 | `LockScreenSettingsRepository.nativeClockColorAvailable` (`>= 36`) | `LockScreenClockColorHook` (`>= 36` return) | Android 15 max | `d114e393` |
| 媒体输出弹窗居中 | `ControlCenterSettingsRepository.nativeMediaOutputDialogCenter` (`>= 37`) | `MediaOutputDialogCenterHook` (`>= 37` return) | Android 16 max | `a3800d3b` |
| 修复控制中心回弹延迟 | `SystemUiMiscSettingsRepository.shadeReboundFixFixed` (`>= 37`) | `ShadeReboundFix` (`>= 37` return) | Android 15–16 | `34239b05` |

On unsupported versions all four render as `enabled = false`, `checked = false`, an
empty callback, and a replacement summary. Rows 2–4 do this by duplicating the row in
an `if/else` branch; row 1 inlines the same checks. The duplication is exactly what
the new model removes.

### 4.2 Adjacent gates (out of scope)

| Feature | Location | Gate | Why out of scope |
|---|---|---|---|
| ZUI 强制配置 section | `FreeformHubSettingsScreen.kt:717` + `SettingsDetailRepository.kt:47` + two UiStates | `SDK_INT >= 36` | Hides/replaces a whole section and its dependent actions; per-row disable would leave an empty "ZUI 强制配置" section on older versions |
| 软重启 menu entry | `HomeRoute.kt:234` | `SDK_INT < 34` | A `DropdownMenuItem`, not a `SettingItem` row |
| AOSP 滚动截屏 switch | `SystemUiMiscSettingsScreen.kt:228` | `enabled = !state.forceLongScreenshot` | A dependency gate, not a version gate; it is the existing precedent for "disabled row + note" and already has a ViewModel guard (`119114f9`) |
| 通知中心模糊强度 | `NotificationCenterTransparency.kt:28` | Hook skips only `hookQuickSettingsBackdropBlur` on `>= 37`; other blur hooks still install | Partial degradation, not a full unsupported feature; needs a product call |
| 亮度滑块百分比 | `BrightnessSliderPercentageHook.kt:46` | Hook skips only `hookSeekProgressChanges` on `>= 37` | The percentage path may still work through the remaining hooks; do not gate without device verification |

### 4.3 Not gates (excluded from the inventory)

The remaining `SDK_INT` hits are compatibility branches, capability checks, or
API-existence fallbacks. Representative examples:

- `HomeRepository` / `SystemInfoProbe`: `longVersionCode` on API 28+.
- `ZToolColorSchemeFactory`: dynamic color on API 31+.
- `BlurExt`: blur capability on API 33+.
- `SettingsRoute`: `ACTION_APP_LOCALE_SETTINGS` on API 33+.
- `ReduceBrightColorsTileService` / `MediaOutputTileService`: `startActivityAndCollapse`
  overload and the Android 17 broadcast.
- `EdgeBubbleView`: overlay window type on API 30+.
- Hook compatibility arms: `WorkModePortrait`, `CustomGridSize`, `LauncherNoLabelMode`,
  `NetworkSpeedHideSlowHook`, `VolumeSliderLongPressHook`, `PackageInstallerStyleHook`,
  `AllowDisplayDolbyHook`, `PermissionControllerHook`, etc. These select a different
  reflection target per API; they do not declare a feature unavailable.

### 4.4 Git history timeline

| Commit | Subject | Relevance |
|---|---|---|
| `7afeff62` | hide soft-reboot entry on Android 15+ | Older surviving version-gated UI entry (menu item) |
| `d114e393` | add lock screen clock color customization | Introduced the disabled "native summary" row |
| `a3800d3b` | bind media output dialog to the playing session | Introduced the native-centering row swap |
| `34239b05` | disable shade rebound fix switch on ZUXOS 2.5 | Introduced the fixed-summary row; its KDoc explicitly asks for frontend version gating |
| `36377374` | disable native notification icons on ZUXOS 1.5+ | Latest row gate; UI-only, hook left ungated |
| `119114f9` | disable AOSP scroll capture while forced on | Non-version precedent for disabled + explanatory note |
| `a8e4634d` / `43ff44c5` / `193177df` | Freeform Hub / settings detail state extraction | Introduced the section-level `showZuiForceConfig` |
| `33b46d8e` / `a5438bf9` | A17 hook skips | Partial hook-only degradations with no UI gate |

## 5. Proposed design

### 5.1 API shape

Add the three members to the interface with defaults, and override them in the eight
interactive variants. `Custom` is intentionally excluded from the constructor: it has
no enforceable enabled state today.

```kotlin
sealed interface SettingItem {
    val key: String
    val enabled: Boolean

    /** Android release major (15 = Android 15), inclusive; null = unbounded. */
    val minAndroidVersion: Int? get() = null

    /** Android release major (16 = Android 16), inclusive; null = unbounded. */
    val maxAndroidVersion: Int? get() = null

    /** Replaces [summary] when the current version is outside the bounds. */
    val unsupportedHint: String? get() = null
}

data class Switch(
    val title: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val summary: String? = null,
    val icon: ImageVector? = null,
    override val enabled: Boolean = true,
    override val minAndroidVersion: Int? = null,
    override val maxAndroidVersion: Int? = null,
    override val unsupportedHint: String? = null,
    override val key: String
) : SettingItem
```

Rationale for interface defaults: existing call sites compile unchanged (all 315
constructions), and `Custom` simply inherits `null` without pretending it can be gated.
If the team prefers a value object, the same three fields can live in a
`SettingAvailability` data class, but that deviates from the requested parameter names.

The row composables should also accept the three params (or a precomputed gate) so
direct callers are not second-class. Defaults keep current behavior.

### 5.2 Version representation and mapping

`minAndroidVersion` / `maxAndroidVersion` are release majors because that is what the
user's list uses (15/16). Internally compare API levels with a pure mapper:

```kotlin
/** Android release major for an SDK level; 0 when the level predates the mapping. */
internal fun androidVersionOf(sdkInt: Int): Int = when {
    sdkInt >= 35 -> sdkInt - 20          // 35 -> 15, 36 -> 16, 37 -> 17
    sdkInt == 34 -> 14
    sdkInt == 33 -> 13
    sdkInt == 32 || sdkInt == 31 -> 12   // 12 and 12L
    sdkInt == 30 -> 11
    sdkInt == 29 -> 10
    sdkInt == 28 -> 9
    sdkInt == 27 -> 8                    // 8.1 reports API 27
    else -> 0
}

internal fun isAndroidVersionSupported(
    current: Int,
    min: Int?,
    max: Int?
): Boolean = (min == null || current >= min) && (max == null || current <= max)
```

Place this in a pure Kotlin location (e.g. `utils/AndroidVersion.kt`), not in
`ui/components`, so hook modules can share the same mapper later. `Build` is only read
once at the call site (`androidVersionOf(Build.VERSION.SDK_INT)`), which keeps the
functions JVM-testable.

Alternative considered: parse `Build.VERSION.RELEASE` (`"15"`, `"16"`, `"8.1.0"`).
It matches the marketing version exactly, but is OEM-dependent and fails on strings
such as `"Android 15"`. The SDK mapping is deterministic. For the current bounds
(15–17), `sdkInt - 20` is exact.

### 5.3 Resolve once in the funnel

`ZToolSettingItem` computes the gate and passes only concrete values to the rows:

```kotlin
val current = androidVersionOf(Build.VERSION.SDK_INT)
val supported = isAndroidVersionSupported(
    current, item.minAndroidVersion, item.maxAndroidVersion
)
val effectiveEnabled = item.enabled && supported
val effectiveSummary = if (supported) item.summary else
    item.unsupportedHint?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.common_android_version_unsupported_hint)

// Switch/Checkbox only:
val effectiveChecked = if (supported) item.checked else false
val effectiveCallback: (Boolean) -> Unit = if (supported) item.onCheckedChange else {}
```

Then:

```kotlin
ZToolSwitchRow(
    checked = effectiveChecked,
    onCheckedChange = effectiveCallback,
    enabled = effectiveEnabled,
    rippleOnly = !supported,
    summary = effectiveSummary,
    // ...
)
```

`rippleOnly` is the only new row-level concept. It must be distinct from `enabled`
because non-version disabled rows (dependency gates, in-progress gates) must keep the
current no-ripple behavior.

### 5.4 Ripple-only disabled rows

This is the part that cannot be solved by passing `enabled = false` to the existing
rows. Both style paths need a small change.

Material path (`ZToolSwitchRow`, `ZListItem`, `ZToolCheckboxRow`,
`ZToolPopupMenuSettingRow`). Representative switch snippet; the other rows
substitute their own action inside the guard:

```kotlin
Row(
    modifier = modifier
        .clickable(enabled = enabled || rippleOnly) {
            if (enabled) onCheckedChange(!checked)
        }
        .semantics { if (!enabled) disabled() }
)
```

`clickable` enables the indication (ripple) while the lambda does nothing when
`enabled` is false; `disabled()` keeps accessibility honest. The text colors already
key off `enabled`, so the disabled look is preserved. Verify with TalkBack that the
semantics do not hide the ripple or announce the row as actionable.

Miuix path is the trap. `BasicComponent` (miuix v0.9.3, `basic/Component.kt`) builds
the clickable modifier as:

```kotlin
if (enabled && onClick != null) {
    Modifier.clickable(interactionSource, onClickLabel, role) { currentOnClick?.invoke() }
} else {
    Modifier
}
```

So `enabled = false` removes the clickable node entirely — no ripple. The proposal is
to keep the component enabled for pointer purposes and supply disabled colors plus a
guarded callback:

```kotlin
val clickable = enabled || rippleOnly
MiuixBasicComponent(
    enabled = clickable,
    onClick = { if (enabled) onCheckedChange(!checked) },
    titleColor = if (enabled) {
        BasicComponentDefaults.titleColor()
    } else {
        BasicComponentDefaults.titleColor(
            color = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
            disabledColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant
        )
    },
    summaryColor = /* same treatment */,
    modifier = modifier.semantics { if (!enabled) disabled() },
    // ...
)
```

The inner `MiuixSwitch` / checkbox stays `enabled = enabled`, so the control itself is
greyed and cannot be toggled. The row ripple is the only feedback. This needs a
device check because `BasicComponent`'s `HoldDownObserver` and the project's haptics
may react to the press.

Alternative rejected: overlay a transparent `Box(Modifier.matchParentSize().clickable {})`
over a disabled row. It produces a ripple with the wrong bounds, duplicates click
semantics, and would fire on top of nested controls.

### 5.5 Per-variant behavior

| Variant | Forced `checked=false` | Row click replaced | Hint replaces summary | Ripple-only required |
|---|---|---|---|---|
| `Switch` | yes | yes (`onCheckedChange` no-op) | yes | yes |
| `Checkbox` | yes | yes | yes | yes |
| `Entry` | n/a | yes (`onClick` no-op) | yes | yes |
| `Action` | n/a | yes | yes | yes |
| `ColorPreview` | n/a | yes | yes | yes |
| `Dropdown` | n/a | yes (popup must not open) | yes | yes |
| `Slider` | n/a | slider disabled | yes | no (sliders have no row ripple) |
| `TextInput` | n/a | field disabled | yes | no |
| `Custom` | n/a | not enforceable | no | no |

`Custom` is the one exception to "all rows". It is used for sliders, popup fields,
color pickers, decorative notes, and compound layouts (54 constructions). Gating it
would require hiding it at the call site or migrating it to a typed variant. The
research recommendation is to leave `Custom` out of the API and, for the four target
rows, use the typed `Switch` variant.

### 5.6 Default hint string

Add a shared resource, following `docs/strings/rename_strings_plan.md` grammar
(`common_<meaning>_<role>`):

```xml
<!-- values/strings.xml -->
<string name="common_android_version_unsupported_hint">当前系统版本不支持此功能</string>

<!-- values-en-rUS/strings.xml -->
<string name="common_android_version_unsupported_hint">Not supported on the current system version</string>
```

The existing feature-specific string
`system_ui_status_bar_notification_icon_native_unsupported_summary` has exactly the
same text and only one reference. After the status bar row passes
`unsupportedHint = null`, delete it from both locale files and run
`python tools/check_unused_strings.py`.

Keep the three explanatory strings:

- `system_ui_lock_screen_clock_color_native_summary`
- `system_ui_media_output_dialog_center_native_summary`
- `system_ui_misc_shade_rebound_fix_fixed_summary`

### 5.7 Persistence and hook consistency

The UI gate must be presentation-only:

- Do not call `onCheckedChange(false)` or write the preference when unsupported; a
  user who downgrades should get their old choice back.
- Do not copy the `SystemUiMiscSettingsRepository.loadState()` side effect
  (`saveShadeReboundFix(false)` on `>= 37`) into the new mechanism. That side effect
  currently clobbers the preference merely by opening the page. Removing it is a
  behavior change worth calling out in the implementation commit.
- The hook is the enforcement point. The UI being disabled does not stop
  `BaseHookModule.isEnabled()` from returning `true` for a stored preference. The
  four features need matching guards:
  - clock color and media-output center already have upper guards;
  - shade rebound needs a lower guard (`< 15`) in addition to its upper guard;
  - native notification icon needs a decision: either add a `> 15` guard, or accept
    that the hook still installs on Android 16/17 while the UI says unsupported. The
    A17 audit (`docs/research/systemui_a17_unchecked_todo_targets.md`) says
    `replaceTheSmallIcon` is gone on API 37, so the current UI cap is defensible.

A shared constants object (e.g. `data/keys/AndroidGates.kt`) for the four bounds would
prevent UI/hook drift, but it is optional for the first migration.

## 6. Migration sketch for the four rows

### 6.1 Status bar — native notification icon

Current (`StatusBarSettingsScreen.kt:492-507`) branches on
`state.nativeNotificationIconSupported` for summary, checked, callback, and enabled.

Target:

```kotlin
SettingItem.Switch(
    title = stringResource(R.string.system_ui_status_bar_notification_icon_native_title),
    summary = stringResource(R.string.system_ui_status_bar_notification_icon_native_summary),
    checked = state.nativeNotificationIcon,
    onCheckedChange = onNativeNotificationIconChanged,
    maxAndroidVersion = 15,
    key = "status_bar_notification_icon_native"
)
```

The default hint supplies the unsupported text. Remove
`nativeNotificationIconSupported` from `StatusBarSettingsUiState` and
`StatusBarSettingsRepository`; the hook needs the guard described in §5.7.

### 6.2 Lock screen — clock color

Current (`LockScreenSettingsScreen.kt:317-356`) swaps between a disabled "native"
row and the working switch + color picker.

Target: one switch row with the custom hint, plus a guarded picker row:

```kotlin
SettingItem.Switch(
    title = stringResource(R.string.system_ui_lock_screen_clock_color_title),
    summary = stringResource(R.string.system_ui_lock_screen_clock_color_summary),
    checked = state.clockColorCustom,
    onCheckedChange = onClockColorCustomChanged,
    maxAndroidVersion = 15,
    unsupportedHint = stringResource(R.string.system_ui_lock_screen_clock_color_native_summary),
    key = "lock_screen_clock_color"
)
if (state.clockColorCustom && clockColorSupported) {
    // existing ZToolArgbColorTextFieldRow as SettingItem.Custom
}
```

`clockColorSupported` is `isAndroidVersionSupported(androidVersionOf(Build.VERSION.SDK_INT), null, 15)`
computed in the section builder (or exposed by a small helper). Without this guard an
old `clockColorCustom = true` preference would show the picker under a disabled row.

### 6.3 Control center — media output dialog center

Same pattern as 6.2 with `maxAndroidVersion = 16` and
`unsupportedHint = stringResource(R.string.system_ui_media_output_dialog_center_native_summary)`.
Remove `nativeMediaOutputDialogCenter` from the UiState and repository.

### 6.4 System UI misc — shade rebound fix

The hint must depend on the violated bound: Android 17+ is "system already fixed
it"; Android 14 and below is "not supported". Resolve it at the call site with the
same mapper:

```kotlin
val current = androidVersionOf(Build.VERSION.SDK_INT)
SettingItem.Switch(
    title = stringResource(R.string.system_ui_misc_shade_rebound_fix_title),
    summary = stringResource(R.string.system_ui_misc_shade_rebound_fix_summary),
    checked = state.shadeReboundFix,
    onCheckedChange = onShadeReboundFixChanged,
    minAndroidVersion = 15,
    maxAndroidVersion = 16,
    unsupportedHint = if (current > 16) {
        stringResource(R.string.system_ui_misc_shade_rebound_fix_fixed_summary)
    } else {
        null // default "not supported on this version"
    },
    key = "system_ui_misc_shade_rebound_fix"
)
```

Alternatively, add `minUnsupportedHint` / `maxUnsupportedHint` to the model; that is
more API surface than the requested three params and is not necessary if call sites
can resolve the string themselves. The single-hint API assumes both bounds share a
reason; row 4 is the counterexample.

Remove the duplicated `if (state.shadeReboundFixFixed)` branch, the
`shadeReboundFixFixed` state field, and the forced `saveShadeReboundFix(false)` write
(see §5.7). Add the `< 15` return to `ShadeReboundFix`.

## 7. Files expected to change

Production (implementation phase, not this research):

- `ui/components/ZToolSettingsModel.kt` — interface members + 8 variant constructors +
  funnel resolution.
- `ui/components/ZToolSettings.kt` — `rippleOnly` on `ZToolSwitchRow`,
  `ZToolCheckboxRow`, `ZListItem`, `ZToolPopupMenuSettingRow`; Miuix color handling.
- `utils/AndroidVersion.kt` (new) — mapper and inclusive bounds check.
- The four screens and their repositories/UiStates/ViewModels listed in §6.
- `res/values/strings.xml`, `res/values-en-rUS/strings.xml`.
- `docs/hook/add_frontend_item.md` — document the three params and the `Custom`
  limitation.
- `app/src/test/java/com/qimian233/ztool/AndroidVersionTest.kt` (new).

Not expected to change:

- `SearchIndex.kt` — rows keep their `key`, so the consistency test still passes; the
  search result will show the static summary, not the unsupported hint.
- `FeatureCatalog.kt` — cards are filtered by installed package, not Android version.
  Extending `FeatureAvailability` with a version bound is a separate follow-up.
- Hook-side version branches other than the four features.

## 8. Risks and open questions

1. **Version unit confirmation.** The design assumes `15` means Android 15 (API 35),
   not SDK 35. If API levels were intended, every bound and helper simplifies, but
   the user-facing "Android version" wording becomes misleading.
2. **Native icon UI/hook mismatch.** The UI caps at Android 15 while the hook has no
   guard and contains API 36+ paths. Confirm whether the hook should be disabled on
   Android 16+ or the UI cap is stale.
3. **Shade rebound lower boundary.** `min = 15` is new. Confirm the feature genuinely
   does not work on Android 14 and below; add the hook guard accordingly.
4. **Miuix ripple parity.** The enabled-component-with-disabled-colors approach is
   sound at the API level, but the press haptics and `HoldDownObserver` need a device
   check. If it feels wrong, fall back to no ripple on Miuix and keep the Material
   behavior, but that violates the stated requirement.
5. **Accessibility.** `semantics { disabled() }` on an otherwise clickable row may
   make TalkBack skip the row entirely. Verify; if it does, use a custom
   `onClickLabel`/role instead.
6. **`Custom` rows.** They cannot be gated. The four migration targets avoid the
   problem by using `Switch`; future rows must not assume `Custom` honors the params.
7. **Dependent rows.** The lock-screen color picker is the concrete case; any future
   gated switch with a visible dependent row needs the same `supported &&` guard.
8. **Preference writes.** The current shade-rebound repository clears the preference
   on load; dropping that write changes stored state. Decide deliberately.
9. **Search results.** Unsupported rows remain searchable and navigate to a disabled
   row. If the product wants a "not supported" badge in search, add a condition note
   to `SearchEntry` in a follow-up.
10. **Android 12L mapping.** `androidVersionOf` maps API 31 and 32 both to 12; a
    future bound that needs to distinguish 12L cannot use this integer model.
11. **`SettingItem` equality.** Adding constructor params changes generated
    `equals`/`copy`; no current code compares `SettingItem` instances, but verify
    before relying on it.
12. **String removal.** Deleting the old native-icon unsupported string requires
    updating both locales and confirming `check_unused_strings.py` reports zero
    missing/unused.

## 9. Verification plan

Unit tests (`app/src/test`):

- `androidVersionOf`: 27→8, 28→9, 29→10, 30→11, 31→12, 32→12, 33→13, 34→14, 35→15,
  36→16, 37→17, 0→0.
- `isAndroidVersionSupported`: null/null, min-only boundary at equality, max-only
  boundary at equality, both bounds, min > max.

Build and static checks:

- `.\gradlew.bat assembleDebug`
- `.\gradlew.bat testDebugUnitTest --tests "com.qimian233.ztool.SearchIndexConsistencyTest"`
- `python tools/check_unused_strings.py` (values / values-en-rUS in sync, no new
  UNUSED entries).

Manual matrix (at least one Material and one Miuix style):

| Android version | Row 1 | Row 2 | Row 3 | Row 4 |
|---|---|---|---|---|
| 14 (API 34) | on | on | on | **off** |
| 15 (API 35) | on | on | on | on |
| 16 (API 36) | **off** | **off** | on | on |
| 17 (API 37) | **off** | **off** | **off** | **off** |

For each "off" cell verify: switch off and greyed, row ripple on tap, callback not
invoked, hint shown, no preference write (check `xposed_module_config` before/after),
dependent rows hidden, search entry still navigates.

## 10. References

Key source files:

- `app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettingsModel.kt`
- `app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettings.kt`
- `app/src/main/java/com/qimian233/ztool/data/systemui/StatusBarSettingsRepository.kt`
- `app/src/main/java/com/qimian233/ztool/data/systemui/LockScreenSettingsRepository.kt`
- `app/src/main/java/com/qimian233/ztool/data/systemui/ControlCenterSettingsRepository.kt`
- `app/src/main/java/com/qimian233/ztool/data/systemui/SystemUiMiscSettingsRepository.kt`
- `app/src/main/java/com/qimian233/ztool/screens/systemui/statusbar/StatusBarSettingsScreen.kt`
- `app/src/main/java/com/qimian233/ztool/screens/systemui/lockscreen/LockScreenSettingsScreen.kt`
- `app/src/main/java/com/qimian233/ztool/screens/systemui/controlcenter/ControlCenterSettingsScreen.kt`
- `app/src/main/java/com/qimian233/ztool/screens/systemui/misc/SystemUiMiscSettingsScreen.kt`

Hook guards:

- `app/src/main/java/com/qimian233/ztool/hook/modules/systemui/statusbar/NativeNotificationIcon.kt`
- `app/src/main/java/com/qimian233/ztool/hook/modules/systemui/keyguard/LockScreenClockColorHook.kt`
- `app/src/main/java/com/qimian233/ztool/hook/modules/systemui/qs/MediaOutputDialogCenterHook.kt`
- `app/src/main/java/com/qimian233/ztool/hook/modules/systemui/misc/ShadeReboundFix.kt`

Project docs:

- `docs/hook/add_frontend_item.md` (conditional rows and `Custom` rows)
- `docs/strings/rename_strings_plan.md` (string naming)
- `docs/research/systemui_a17_unchecked_todo_targets.md` (native icon targets on A17)

External:

- Miuix `BasicComponent` source (v0.9.3):
  https://github.com/compose-miuix-ui/miuix/blob/v0.9.3/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/Component.kt
- Miuix `BasicComponent` API docs:
  https://compose-miuix-ui.github.io/miuix/dokka/miuix-ui/top.yukonga.miuix.kmp.basic/-basic-component.html
