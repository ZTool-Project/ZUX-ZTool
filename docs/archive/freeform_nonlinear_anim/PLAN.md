# Freeform Nonlinear Animation Implementation Plan

## Checklist

- [x] **Step 1: Shared Preference Key & IPC Contract**
  - [x] Add `SYSTEM_UI_FREEFORM_NONLINEAR_ANIM` to `PreferenceKeys.kt` and register in `booleanKeys`
  - [x] Create `app/src/main/java/com/qimian233/ztool/data/freeform/FreeformAnimContract.kt`
- [x] **Step 2: Nonlinear Physics Curves, Geometry Math & Unit Tests**
  - [x] Create `app/src/main/java/com/qimian233/ztool/hook/modules/systemui/FreeformAnimCurves.kt`
  - [x] Create `app/src/test/java/com/qimian233/ztool/hook/modules/systemui/FreeformAnimCurvesTest.kt`
- [x] **Step 3: SystemUI Hook (`FreeformNonlinearAnimationHook.kt`) + Registration**
  - [x] Part A: Open / Close / Minimize nonlinear animations (`OvfTransitionAnimation`, `OvfScaleTransitionUtils`)
  - [x] Part B: Edge-dock shrink animation + below-threshold snap-back (`DesktopModeTouchEventListener`)
  - [x] Part C: Restore-hint broadcast receiver in SystemUI
  - [x] Part D: Bubble-to-freeform restore expand animation (primary `DefaultTransitionHandler` + fallback `FreeformTaskListener`)
  - [x] Register in `HookManager.registerSystemUIModules()`
- [x] **Step 4: `system_server` Coordination (`FreeformEdgeBubbleHook.kt`)**
  - [x] Allow `com.android.systemui` UID in `CommandReceiver.isAllowedCaller`
  - [x] Support `restore_*`, `center_y_ratio`, `notify_bubble` in `CMD_MINIMIZE_TASK` / `doMinimize`
  - [x] Move `moveTaskBounds` before `bringToFront` / `moveToFront` in `doRestore`
- [x] **Step 5: App-Side Bridge & Bubble View (`FreeformBubbleBridge.kt`, `EdgeBubbleView.kt`, `EdgeBubbleService.kt`)**
  - [x] Add `notifySystemUiRestoreHint` and `centerYRatio` event callback in `FreeformBubbleBridge.kt`
  - [x] Add `currentBubbleScreenRect()`, `playRestoreExitAnimation()`, and updated `Listener.onBubbleRestore` in `EdgeBubbleView.kt`
  - [x] Wire restore hint + exit animation + `cy_<taskId>` + idempotent `removeBubble` in `EdgeBubbleService.kt`
- [x] **Step 6: Frontend Switch, Search Index & i18n Strings**
  - [x] Wire `SystemUiMiscSettingsRepository.kt` & `SystemUiMiscSettingsViewModel.kt`
  - [x] Wire `FreeformHubRepository.kt` & `FreeformHubViewModel.kt`
  - [x] Add `SettingItem.Switch` in `FreeformHubSettingsScreen.kt`
  - [x] Add `SearchIndex.kt` entry
  - [x] Add Chinese and English strings in `res/values/strings.xml` and `res/values-en-rUS/strings.xml`
- [x] **Verification & Completion**
  - [x] `python tools/check_unused_strings.py`
  - [x] `.\gradlew.bat testDebugUnitTest --tests "com.qimian233.ztool.SearchIndexConsistencyTest" --tests "com.qimian233.ztool.hook.modules.systemui.FreeformAnimCurvesTest"`
  - [x] `.\gradlew.bat assembleDebug`
  - [x] `git diff --check`
  - [x] Archive plan to `docs/archive/freeform_nonlinear_anim/PLAN.md` and commit
