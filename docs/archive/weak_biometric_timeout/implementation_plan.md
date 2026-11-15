# Weak biometric timeout bypass implementation

## Scope

Add the Android 17+ system_server bypass and keep the existing Android 16-and-earlier
SystemUI bypass complementary. Preserve existing preferences and unrelated user edits.

## Tasks

- [x] Add and register the framework Hook with a separate preference key.
- [x] Wire the framework Repository, UiState, ViewModel, setting row and search entry.
- [x] Gate both rows and both Hook implementations at the Android 16/17 boundary.
- [x] Update Chinese/English titles, summaries and the SystemUI unsupported hint.
- [x] Record implementation behavior and legacy reverse-engineering notes.
- [x] Review changed Kotlin comments, verify resources, build and run search consistency tests.
- [x] Archive this completed plan and prepare the reviewed implementation for commit.

## Validation

- `assembleDebug`: passed.
- `SearchIndexConsistencyTest`: 2 tests passed.
- `AndroidVersionGateTest`: 5 tests passed.
- `check_unused_strings.py`: locale missing/extra counts are 0/0; no new unused entries.
  The existing unused `search_title` is outside this change.
- Changed Kotlin KDoc blocks satisfy the eight-line cap.
- The system_server target is already declared in scope.list.
- Device expiry verification was not performed; the new Hook suppresses future callbacks,
  and existing expired restrictions still clear through successful primary authentication.

## Expected files

PreferenceKeys, HookManager, new framework Hook, existing SystemUI Hook,
FrameworkSettingsRepository, FrameworkSettingsViewModel, FrameworkSettingsScreen,
SystemUiMiscSettingsScreen, SearchIndex, both string locales, research notes and this plan.
