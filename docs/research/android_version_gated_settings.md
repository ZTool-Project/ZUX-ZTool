# Android-version gated settings rows — research and migration design

Status: research only. No production code was changed.

Scope: the generic `SettingItem` availability model and the four rows the user
identified for migration. Related gates (section-level, menu-level, hook-only) are
inventoried but explicitly out of scope.

## TL;DR

- The bounds are **SDK API levels** (`Build.VERSION.SDK_INT` values). Call sites write
  them with the codename constants: `Build.VERSION_CODES.VANILLA_ICE_CREAM` (35,
  Android 15), `Build.VERSION_CODES.BAKLAVA` (36, Android 16),
  `Build.VERSION_CODES.CINNAMON_BUN` (37, Android 17). Do not use marketing versions
  (15/16/17) or parse `Build.VERSION.RELEASE`.
- The three nullable bounds belong on `SettingItem`; the render funnel
  (`ZToolSettingItem`) is the single place that turns them into `enabled`, `checked`,
  `summary`, a ripple-only click, and the persistence side effect.
- Unsupported is now **destructive by design for boolean rows**: force the switch off
  and persist `false`. Preserving the old preference for a downgrade is not a
  requirement — downgrades either trigger an SPL downgrade (which wipes data) or are
  blocked by AVB rollback.
- Persistence makes hook-side version guards secondary, not irrelevant. The UI clears
  the preference when the page is composed; a user who receives an OTA and never opens
  that page still has the old value until then. Keep cheap hook guards as
  defense-in-depth.
- Four rows are in scope. Three already implement the disabled look by hand; the
  fourth (shade rebound) additionally gains a lower bound. Row 4's hint differs by
  violated bound and must be resolved per version.
- Miuix is the implementation trap: `BasicComponent` only attaches
  `Modifier.clickable` when `enabled && onClick != null`, so `enabled = false` yields
  no ripple at all. "Ripple only" needs an enabled component with disabled colors and
  a guarded callback (verified against miuix v0.9.3 source).

## 1. Confirmed migration scope

The user counted four rows. Bounds are SDK API levels, inclusive. Call sites should
use the codename constants, not raw numbers.

| # | Row | Screen | Current gate | New bounds | Hook-side guard today |
|---|-----|--------|--------------|------------|-----------------------|
| 1 | 使用原生通知图标 | `screens/systemui/statusbar/StatusBarSettingsScreen.kt:492` | `SDK_INT < 36` | `maxAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM` (35) | none (see §5.7) |
| 2 | 自定义锁屏时钟颜色 | `screens/systemui/lockscreen/LockScreenSettingsScreen.kt:317` | `SDK_INT >= 36` native | `maxAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM` (35) | `LockScreenClockColorHook` returns on `>= 36` |
| 3 | 媒体输出弹窗居中 | `screens/systemui/controlcenter/ControlCenterSettingsScreen.kt:472` | `SDK_INT >= 37` native | `maxAndroidVersion = Build.VERSION_CODES.BAKLAVA` (36) | `MediaOutputDialogCenterHook` returns on `>= 37` |
| 4 | 修复控制中心回弹延迟 | `screens/systemui/misc/SystemUiMiscSettingsScreen.kt:237` | `SDK_INT >= 37` fixed | `minAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM` (35), `maxAndroidVersion = Build.VERSION_CODES.BAKLAVA` (36) | `ShadeReboundFix` returns on `>= 37`; no lower guard |

Notes:

- The project already uses these codenames: `CustomGridSize` selects the API 36
  constructor with `BAKLAVA`, and `ShadeReboundFix` / `NotificationCenterTransparency`
  branch on `CINNAMON_BUN`. Verified against the installed Android 37 platform
  (`javap -constants android.os.Build$VERSION_CODES`):
  `VANILLA_ICE_CREAM = 35`, `BAKLAVA = 36`, `CINNAMON_BUN = 37`.
- Row 1 only needs `max = 35`; its current replacement summary is literally the
  default hint text, so it can pass `unsupportedHint = null` and rely on the default.
- Row 4 is a behavior change on Android 14 and below: the row was previously shown as
  a working switch there. The hook must gain a matching lower guard, otherwise a
  stored `true` preference still installs it until the page is opened.
- Rows 2–3 keep their feature-specific hints (`..._native_summary`) because they
  explain *why* the row is unavailable. Row 4's reason differs by bound: Android 17+
  is "ZUXOS 2.0 fixed it"; Android 14 and below is "not supported". Resolve the hint
  at the call site (see §6.4).
- The parameter names `minAndroidVersion` / `maxAndroidVersion` are retained per the
  request, but their unit is the SDK API level. If the naming is allowed to change,
  `minSdkVersion` / `maxSdkVersion` would be less ambiguous.

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

- `minAndroidVersion`, `maxAndroidVersion`: nullable SDK API levels.
- Both `null` → available on every version.
- Comparison is inclusive on both ends: API 35 is available with
  `maxAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM`; API 35 is available with
  `minAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM`.
- Suggested validation: if both are non-null and `min > max`, treat as unsupported
  and log in debug builds (do not crash release builds on a typo).

Comparison is direct:

```kotlin
val supported = (min == null || Build.VERSION.SDK_INT >= min) &&
    (max == null || Build.VERSION.SDK_INT <= max)
```

No marketing-version mapper is needed. `Build.VERSION_CODES.*` are just readable
`Int` aliases; the runtime value is always `Build.VERSION.SDK_INT`.

### 3.2 Unsupported state

When the current SDK level is outside the bounds:

| Aspect | Required behavior |
|---|---|
| `enabled` | `false` (combined with the row's own `enabled` via AND) |
| `checked` (Switch/Checkbox) | forced `false`, regardless of the stored preference |
| User click | callback is **not** invoked; the row still shows a press ripple |
| Programmatic one-shot | persist `false` once, so the hook cannot keep running |
| Summary | `unsupportedHint`, or the default hint when null/blank |
| Visibility | row stays visible (the request disables it, it does not hide it) |

Two different "callbacks" must be distinguished:

1. **User-triggered callback**: suppressed. The row is disabled and the tap only
   produces a ripple.
2. **Availability-triggered persistence**: when the row is first composed as
   unsupported and its stored value is `true`, the row invokes its setter with
   `false` once. This is not a user action and must not produce ripple/animation.

The "combined with the row's own `enabled`" part matters: rows disabled for other
reasons (dependency on a parent switch, in-progress operation) must keep their current
no-ripple disabled behavior and must not persist anything. Only version-unavailability
adds the ripple-only state and the one-shot reset.

### 3.3 Persistence side effect

This is the main change from the first draft of this research. All boolean rows that
become unavailable because of the SDK gate should clear their stored preference.

Rationale (from the user): a user who downgrades Android already goes through an SPL
downgrade, which wipes data; some devices cannot downgrade at all because AVB rollback
refuses the image. Preserving the old preference "for a later downgrade" is therefore
not a requirement, while leaving it `true` lets the hook keep running on the version
where the feature is declared unavailable.

Recommended placement: in `ZToolSettingItem`, per boolean variant, not in composition
directly:

```kotlin
is SettingItem.Switch -> {
    LaunchedEffect(item.key, supported, item.checked) {
        if (!supported && item.checked) item.onCheckedChange(false)
    }
    ZToolSwitchRow(
        checked = if (supported) item.checked else false,
        onCheckedChange = if (supported) item.onCheckedChange else {},
        enabled = item.enabled && supported,
        rippleOnly = !supported,
        summary = effectiveSummary,
        // ...
    )
}
```

Key details:

- The effect keys on `item.key`, `supported`, and `item.checked`. Keying only on
  `supported` is a bug: if the row composes before the UiState loads (`checked` still
  `false`), the effect would not rerun when the real `true` value arrives.
- `item.onCheckedChange(false)` is the original setter, not the user-suppressed
  callback. It updates the ViewModel and repository exactly like a user toggle.
- The `item.checked` guard avoids a write when the preference is already `false`.
- After the write, the row recomposes with `checked = false`; the effect's keys change
  to `(key, false, false)` and it does nothing. No loop.
- `Checkbox` gets the same treatment.

Limitations:

- This only covers rows that are actually composed. If the user never opens the
  settings page after an OTA, the old `true` value survives and the hook still sees
  it. That is why hook-side guards remain useful as defense-in-depth (§5.7), or a
  startup migration would be needed for a stronger guarantee.
- `Entry`, `Action`, `ColorPreview`, `Dropdown`, `Slider`, `TextInput`, and `Custom`
  have no boolean "off" value. They cannot auto-persist through this mechanism. If a
  future gated feature uses one of these, it needs an explicit reset callback or a
  repository-level check. The four migration targets are all switches, so this is not
  a blocker now. If the model needs to cover them generically, add an optional
  `onUnsupported: (() -> Unit)?` to `SettingItem` (a fourth, optional param) and have
  the funnel invoke it once; the boolean variants can default it to
  `onCheckedChange(false)`.

Alternative placement: clear the preference in each `Repository.loadState()` when
unsupported (the current shade-rebound code does this). It has the same page-open
timing, duplicates the version check in the data layer, and writes even when the row
is collapsed. The funnel approach is preferred for boolean rows; a repository-level
check is a reasonable defense for hooks that must not run at all.

### 3.4 Default hint

Text: `当前系统版本不支持此功能` / `Not supported on this system version`.

The Chinese string already exists as
`system_ui_status_bar_notification_icon_native_unsupported_summary`; the new generic
resource should replace it so there is one source of truth (see §5.6).

## 4. Whole-repo and Git-log inventory

Search performed:

```text
content search for "Build.VERSION.SDK_INT|VERSION_CODES." under app/src/main/java  # 54 hits
git log -S <each feature flag> --all
git log --grep "Android 17|ZUXOS 2.5|unsupported|disable.*switch"
git grep on master / android-16-archived for the same UI/data paths
```

The two branches carry the same frontend gates; no additional frontend gate was
found in the checked branches.

### 4.1 Row-level gates (in scope)

| Row | Repository field | Hook module | Boundary | Commit that introduced it |
|---|---|---|---|---|
| 使用原生通知图标 | `StatusBarSettingsRepository.nativeNotificationIconSupported` (`SDK_INT < 36`) | `NativeNotificationIcon` (no guard) | API 35 max | `36377374` |
| 自定义锁屏时钟颜色 | `LockScreenSettingsRepository.nativeClockColorAvailable` (`>= 36`) | `LockScreenClockColorHook` (`>= 36` return) | API 35 max | `d114e393` |
| 媒体输出弹窗居中 | `ControlCenterSettingsRepository.nativeMediaOutputDialogCenter` (`>= 37`) | `MediaOutputDialogCenterHook` (`>= 37` return) | API 36 max | `a3800d3b` |
| 修复控制中心回弹延迟 | `SystemUiMiscSettingsRepository.shadeReboundFixFixed` (`>= 37`) | `ShadeReboundFix` (`>= 37` return) | API 35–36 | `34239b05` |

On unsupported versions all four render as `enabled = false`, `checked = false`, an
empty user callback, and a replacement summary. Rows 2–4 do this by duplicating the
row in an `if/else` branch; row 1 inlines the same checks. The duplication is exactly
what the new model removes.

The persistence side effect is **not** present in any of the four today, with one
exception: `SystemUiMiscSettingsRepository.loadState()` writes
`saveShadeReboundFix(false)` when `SDK_INT >= 37`. That is the old upper-bound-only
version of the behavior; the generic mechanism should replace it and extend the same
reset to the new lower bound.

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

    /** Minimum SDK API level, inclusive; null = unbounded. */
    val minAndroidVersion: Int? get() = null

    /** Maximum SDK API level, inclusive; null = unbounded. */
    val maxAndroidVersion: Int? get() = null

    /** Replaces [summary] when the current SDK level is outside the bounds. */
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

### 5.2 Resolve once in the funnel

`ZToolSettingItem` computes the gate and passes only concrete values to the rows:

```kotlin
val sdk = Build.VERSION.SDK_INT
val supported = isAndroidVersionSupported(sdk, item.minAndroidVersion, item.maxAndroidVersion)
val effectiveEnabled = item.enabled && supported
val effectiveSummary = if (supported) item.summary else
    item.unsupportedHint?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.common_android_version_unsupported_hint)

// Switch/Checkbox only:
val effectiveChecked = if (supported) item.checked else false
val effectiveCallback: (Boolean) -> Unit = if (supported) item.onCheckedChange else {}
```

Pure helper, small enough to unit test:

```kotlin
internal fun isAndroidVersionSupported(sdkInt: Int, min: Int?, max: Int?): Boolean =
    (min == null || sdkInt >= min) && (max == null || sdkInt <= max)
```

### 5.3 Persistence side effect in the funnel

See §3.3. The effect lives in the `Switch` / `Checkbox` branches of
`ZToolSettingItem`, keyed on `(item.key, supported, item.checked)`. It calls the
original setter once with `false`; the user-facing callback remains suppressed.

Why not call the setter during composition: it would write on every recomposition and
could trigger a state-update loop. Why not call it from the row's click handler: the
row is not clickable for actions when unsupported, and the reset must happen even if
the user never taps.

Why not rely only on hooks: the hook could compute the same SDK level, but the bounds
would then have to be duplicated in every hook module, and a stale preference would
still install a module whose feature the UI already declared unavailable. The
persistence write is the bridge between the UI decision and the hook process; hook
guards remain as defense for the never-opened-page window.

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

| Variant | Forced `checked=false` | User click replaced | Hint replaces summary | Persist reset | Ripple-only |
|---|---|---|---|---|---|
| `Switch` | yes | yes (`onCheckedChange` no-op) | yes | yes (setter `false`) | yes |
| `Checkbox` | yes | yes | yes | yes (setter `false`) | yes |
| `Entry` | n/a | yes (`onClick` no-op) | yes | optional `onUnsupported` | yes |
| `Action` | n/a | yes | yes | optional `onUnsupported` | yes |
| `ColorPreview` | n/a | yes | yes | optional `onUnsupported` | yes |
| `Dropdown` | n/a | yes (popup must not open) | yes | optional `onUnsupported` | yes |
| `Slider` | n/a | slider disabled | yes | optional `onUnsupported` | no (sliders have no row ripple) |
| `TextInput` | n/a | field disabled | yes | optional `onUnsupported` | no |
| `Custom` | n/a | not enforceable | no | no | no |

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

### 5.7 Hook consistency after persistence

With the UI writing `false`, the hook sees the preference cleared once the user opens
the page. Hook-side version guards are therefore **secondary**:

- Existing upper guards (`LockScreenClockColorHook`, `MediaOutputDialogCenterHook`,
  `ShadeReboundFix`) can stay; they are cheap and cover the window before the page is
  opened.
- `ShadeReboundFix` still needs a lower guard (`< 35`) if the product wants the hook
  to be correct even when the page was never opened. This is defense, not the primary
  mechanism.
- `NativeNotificationIcon` currently has no guard and contains API 36+ paths. With
  persistence, an old `true` value is cleared when the status bar page is opened; if
  that is considered sufficient, the hook can be left alone. If the feature is known
  broken on 16/17, add the guard anyway — it is one line and protects the
  never-opened-page case.
- Do not duplicate the whole gate in every hook. Keep the UI as the source of truth
  for availability, and use hook guards only where a stale preference would cause a
  visible regression or crash.

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
    maxAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM,
    key = "status_bar_notification_icon_native"
)
```

The default hint supplies the unsupported text. Remove
`nativeNotificationIconSupported` from `StatusBarSettingsUiState` and
`StatusBarSettingsRepository`; the funnel's one-shot reset now clears the preference.

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
    maxAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM,
    unsupportedHint = stringResource(R.string.system_ui_lock_screen_clock_color_native_summary),
    key = "lock_screen_clock_color"
)
if (state.clockColorCustom && Build.VERSION.SDK_INT <= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
    // existing ZToolArgbColorTextFieldRow as SettingItem.Custom
}
```

The picker guard uses the same boundary as the switch. The funnel's one-shot reset
will clear `clockColorCustom` when the row is composed as unsupported, so the guard is
mostly a first-frame safety net (state may still be `true` before the effect runs).

### 6.3 Control center — media output dialog center

Same pattern as 6.2 with
`maxAndroidVersion = Build.VERSION_CODES.BAKLAVA` and
`unsupportedHint = stringResource(R.string.system_ui_media_output_dialog_center_native_summary)`.
Remove `nativeMediaOutputDialogCenter` from the UiState and repository.

### 6.4 System UI misc — shade rebound fix

The hint must depend on the violated bound: Android 17+ is "system already fixed
it"; Android 14 and below is "not supported". Resolve it at the call site:

```kotlin
SettingItem.Switch(
    title = stringResource(R.string.system_ui_misc_shade_rebound_fix_title),
    summary = stringResource(R.string.system_ui_misc_shade_rebound_fix_summary),
    checked = state.shadeReboundFix,
    onCheckedChange = onShadeReboundFixChanged,
    minAndroidVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM,
    maxAndroidVersion = Build.VERSION_CODES.BAKLAVA,
    unsupportedHint = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.BAKLAVA) {
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
in `loadState()` (the funnel now performs the reset for both bounds). Add the `< 35`
return to `ShadeReboundFix` (§5.7).

## 7. Files expected to change

Production (implementation phase, not this research):

- `ui/components/ZToolSettingsModel.kt` — interface members + 8 variant constructors +
  funnel resolution + one-shot reset in `Switch`/`Checkbox`.
- `ui/components/ZToolSettings.kt` — `rippleOnly` on `ZToolSwitchRow`,
  `ZToolCheckboxRow`, `ZListItem`, `ZToolPopupMenuSettingRow`; Miuix color handling.
- The four screens and their repositories/UiStates/ViewModels listed in §6.
- `res/values/strings.xml`, `res/values-en-rUS/strings.xml`.
- `docs/hook/add_frontend_item.md` — document the three params, the persistence
  side effect, and the `Custom` limitation.
- `app/src/test/java/com/qimian233/ztool/AndroidVersionGateTest.kt` (new; pure bounds
  helper only, no Android framework calls).

Not expected to change:

- `SearchIndex.kt` — rows keep their `key`, so the consistency test still passes; the
  search result will show the static summary, not the unsupported hint.
- `FeatureCatalog.kt` — cards are filtered by installed package, not Android version.
  Extending `FeatureAvailability` with a version bound is a separate follow-up.
- Hook-side version branches other than the four features.

## 8. Risks and open questions

1. **Parameter naming.** `minAndroidVersion` now holds an SDK API level, not an
   Android marketing version. Keep the requested names with KDoc, or rename to
   `minSdkVersion` / `maxSdkVersion` before the API spreads.
2. **Persistence timing.** The reset runs when the row is composed (page opened). A
   user who receives an OTA and never opens the page keeps the old `true` until then.
   Keep cheap hook guards, or add an app-start migration if that window matters.
3. **Non-boolean rows.** `Entry`, `Action`, `ColorPreview`, `Dropdown`, `Slider`,
   `TextInput`, and `Custom` cannot auto-reset. They need an explicit reset callback
   or a repository check if they are ever gated.
4. **Native icon hook mismatch.** Lower priority after persistence, but still a
   visible inconsistency: UI says unsupported on 16+, hook has API 36+ paths. Decide
   whether to add the guard or accept the never-opened-page case.
5. **Shade rebound lower boundary.** `min = 35` is new. Confirm the feature genuinely
   does not work on Android 14 and below; add the hook guard accordingly.
6. **Miuix ripple parity.** The enabled-component-with-disabled-colors approach is
   sound at the API level, but the press haptics and `HoldDownObserver` need a device
   check.
7. **Accessibility.** `semantics { disabled() }` on an otherwise clickable row may
   make TalkBack skip the row entirely. Verify; if it does, use a custom
   `onClickLabel`/role instead.
8. **`Custom` rows.** They cannot be gated. The four migration targets avoid the
   problem by using `Switch`; future rows must not assume `Custom` honors the params.
9. **Dependent rows.** The lock-screen color picker is the concrete case; any future
   gated switch with a visible dependent row needs the same `supported &&` guard.
10. **Search results.** Unsupported rows remain searchable and navigate to a disabled
    row. If the product wants a "not supported" badge in search, add a condition note
    to `SearchEntry` in a follow-up.
11. **`SettingItem` equality.** Adding constructor params changes generated
    `equals`/`copy`; no current code compares `SettingItem` instances, but verify
    before relying on it.
12. **String removal.** Deleting the old native-icon unsupported string requires
    updating both locales and confirming `check_unused_strings.py` reports zero
    missing/unused.

## 9. Verification plan

Unit tests (`app/src/test`):

- `isAndroidVersionSupported`: null/null, min-only boundary at equality, max-only
  boundary at equality, both bounds, min > max, and the four migration bounds with
  `sdkInt` 34/35/36/37.

Build and static checks:

- `.\gradlew.bat assembleDebug`
- `.\gradlew.bat testDebugUnitTest --tests "com.qimian233.ztool.SearchIndexConsistencyTest"`
- `python tools/check_unused_strings.py` (values / values-en-rUS in sync, no new
  UNUSED entries).

Manual matrix (at least one Material and one Miuix style):

| SDK / Android | Row 1 (max 35) | Row 2 (max 35) | Row 3 (max 36) | Row 4 (35–36) |
|---|---|---|---|---|
| 34 / 14 | on | on | on | **off** |
| 35 / 15 | on | on | on | on |
| 36 / 16 | **off** | **off** | on | on |
| 37 / 17 | **off** | **off** | **off** | **off** |

For each "off" cell verify:

- switch off and greyed, row ripple on tap, user callback not invoked;
- hint shown (row 4: generic on 14, fixed-summary on 17);
- after opening the page, `xposed_module_config` contains `false` for the key, and the
  hook log shows the module disabled / not installed;
- dependent rows (lock-screen picker) hidden;
- search entry still navigates.

Also verify the one-shot reset does not loop: open the page twice on an unsupported
version and confirm there is one write, not one per recomposition.

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
