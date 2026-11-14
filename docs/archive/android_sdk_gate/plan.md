# Android SDK gate — implementation plan

## Goal

Add `minSdkVersion` / `maxSdkVersion` / `unsupportedHint` to `SettingItem`, resolve the
gate once in `ZToolSettingItem`, persist `false` for unsupported boolean rows, and
migrate the four existing version-gated rows to the new model.

## Scope

- `ui/components/ZToolSettingsModel.kt`: interface members, 8 variant constructors,
  funnel resolution, one-shot reset.
- `ui/components/ZToolSettings.kt`: `rippleOnly` support and disabled colors for
  Miuix/Material rows.
- `ui/components/AndroidVersionGate.kt` (new): inclusive bounds helper.
- Status bar / lock screen / control center / system UI misc screens and their
  repositories/UiStates.
- Default hint string in both locales; remove the old duplicate.
- `ShadeReboundFix`: lower SDK guard.
- Tests and docs.

## Steps

- [x] Add the SDK gate helper and unit tests.
- [x] Extend `SettingItem` and resolve the gate in `ZToolSettingItem`.
- [x] Implement ripple-only disabled rows in both frontend styles.
- [x] Migrate the four rows; remove obsolete support flags and the shade-rebound
      load-time write.
- [x] Add the shared hint string; remove the old native-icon hint.
- [x] Add the shade-rebound lower guard.
- [x] Update `docs/hook/add_frontend_item.md` and the research note.
- [x] Run `assembleDebug`, unit tests and `check_unused_strings.py`.
- [x] Move this plan to `docs/archive/android_sdk_gate/` and commit.

## Verification

- `.\gradlew.bat assembleDebug` — passed.
- `.\gradlew.bat testDebugUnitTest` — passed.
- `python tools/check_unused_strings.py` — values / values-en-rUS in sync; the only
  UNUSED entry is the pre-existing `search_title`.
- `git diff --check` — clean.

## Result

Four rows now use the shared gate. `SettingItem.Switch` / `Checkbox` rows persist
`false` once when composed outside their SDK bounds; all rows get ripple-only clicks
and an unsupported hint. `ShadeReboundFix` also refuses to run below Android 15.
