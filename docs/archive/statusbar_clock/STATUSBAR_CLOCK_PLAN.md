# Status Bar Clock Dispatch Unification & OnePlus Red "1" Plan

## Scope
- Unify `StatusBarClockSecondsHook` and `CustomStatusBarClock` into a single `SystemUIClockHook` owner backed by pure-Kotlin `StatusBarClockPolicy`.
- Add independent `STATUSBAR_CLOCK_RED_ONE` switch highlighting `'1'` digits in the hour field with OnePlus classic red (`#EB0028`).
- Disable custom text color controls in UI when Red 1 is active, and ignore custom text color in the hook when Red 1 is enabled.
- Auto-enable second ticking when custom clock format contains an unescaped `s` or `S`.
- Extract exact hour ranges via `SimpleDateFormat.formatToCharacterIterator` in `CustomDateFormatter`.

## Checklist
- [x] Create root plan checklist (`STATUSBAR_CLOCK_PLAN.md`)
- [x] Register `STATUSBAR_CLOCK_RED_ONE` in `PreferenceKeys.kt` and `booleanKeys`
- [x] Extend `CustomDateFormatter.kt` with `FormattedDateText` and `formatWithFields(...)`
- [x] Create `StatusBarClockPolicy.kt` (options, seconds pattern detection, stock hour range & red-1 index resolution)
- [x] Create `SystemUIClockHook.kt`, delete `StatusBarClockSecondsHook.kt` & `CustomStatusBarClock.kt`, and update `HookManager.kt`
- [x] Add Chinese and English string resources in `values/strings.xml` and `values-en-rUS/strings.xml`
- [x] Wire `StatusBarSettingsRepository.kt`, `StatusBarSettingsViewModel.kt`, `StatusBarSettingsScreen.kt`, and `SearchIndex.kt`
- [x] Create `StatusBarClockPolicyTest.kt`
- [x] Run `assembleDebug`, unit tests (`StatusBarClockPolicyTest`, `SearchIndexConsistencyTest`), and `check_unused_strings.py`
- [x] Archive this checklist to `docs/archive/statusbar_clock/STATUSBAR_CLOCK_PLAN.md`, verify `git diff --check`, and commit
