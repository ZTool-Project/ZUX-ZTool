# Removed freeform background keep-alive hooks

The freeform background keep-alive (`小窗后台保活`) feature was removed because it conflicted with existing keep-alive functionality (`TODOS.md`). This records the reverse-engineering findings and targets from the former launcher and `system_server` hooks (`PreferenceKeys.FREEFORM_KEEP_ALIVE_ENABLED` / `freeform_keep_alive_enabled`, `PreferenceKeys.FREEFORM_KEEP_ALIVE_PACKAGES` / `freeform_keep_alive_packages`).

## 1. Launcher Hook (`com.zui.launcher`, former `FreeformKeepAliveLauncherHook`)

- **Target**: `com.android.quickstep.views.RecentsView` batch clean-all method (`dismissAllUnlimitedTasks`, obfuscated as `P5` on current ZUI builds, located by `(java.util.ArrayList, com.android.launcher3.util.IntSet) -> void` signature, which ends by calling `OverviewUtilities.removeAllRunningAppProcesses`).
- **Mechanism**:
  - The batch method skips any task whose ID is present in the `IntSet protectedTaskIds` argument (the mechanism ZUI uses for user-locked cards) and passes the remaining list into the batch killer's survive set.
  - Iterated `ArrayList<Task>`, read `Task.key` -> `Task$TaskKey.baseIntent.component.packageName`, and added `Task$TaskKey.id` to `protectedTaskIds` via `IntSet.add(int)` when the package matched the whitelist.
  - Single-card manual swipe-dismiss (`removeTask` / `killUid`) was deliberately left untouched.
- **Pitfall**: Never inject package-name `String` objects into the `ArrayList<Task>` parameter — the `AsyncTask` throws a `ClassCastException` (`String cannot be cast to Task`) and crashes the launcher.

## 2. System Server Hook (`system_server`, former `FreeformKeepAliveSystemHook`)

- **Host behavior**: ZUI's freeform hide path (`com.android.server.wm.OvfWmHideShowController#bringToBack`) only calls `setAlwaysOnTop(false)` + `moveTaskToBack`, causing activities to stop and the process to drop to the cached OOM tier.
- **Preferred integration point**: `OomAdjuster.dynamicComputeAdj(String processName, String key, int pid, int uid, int curAdj, boolean z) -> int`, which runs inside `applyOomAdjLSP` before the computed adj is applied or frozen. Returning `200` (`PINNED_ADJ`) for whitelisted processes (`pkg` or `pkg:*`) with `computedAdj >= 700` (`PREVIOUS_APP_ADJ`) makes both the freeze check and the kernel write see `200` in the same pass.
- **Freeze bypass**: Hooked `com.android.server.am.CachedAppOptimizer.onOomAdjustChanged(int setAdj, int curAdj, ProcessRecord)` and returned `null` for whitelisted processes to suppress cached-app freezing.
- **Fallback post-apply hook**: After `applyOomAdjLSP` when `curAdj >= 700`:
  - Called Lenovo's `OomAdjuster.constructNewProtected(String, int pid, int uid, int adj=200, int durationMinutes=10080)` (guarded on `mAdjCustomizeHandler != null`, which exists when `LgsiFeatures "ZuiAdjCustomize"` is enabled, synchronized on `mAdjProtectedApps` `HashMap` if `!containsKey(pid)`).
  - Directly wrote `setCurAdj(200)`, `setSetAdj(200)`, and `com.android.server.am.ProcessList.setOomAdj(pid, uid, 200)`.
- **Android version split**:
  - **API <= 36 (`SDK_INT < Build.VERSION_CODES.CINNAMON_BUN`)**: `com.android.server.am.OomAdjuster` (`applyOomAdjLSP(ProcessRecord, boolean, long, long, int, boolean)`), `com.android.server.am.ProcessRecord` (`processName`, `uid`, `getPid()`, `mState` -> `com.android.server.am.ProcessStateRecord.{getCurAdj,setCurAdj,setSetAdj}`).
  - **API >= 37 (`SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN`)**: `com.android.server.am.psc.OomAdjuster` (`applyOomAdjLSP(ProcessRecordInternal, boolean)`), `com.android.server.am.psc.ProcessRecordInternal` (`processName`, `uid`, `getPid()`, `getCurAdj()`, `setCurAdj(int)`, `setSetAdj(int)`). `CachedAppOptimizer.onOomAdjustChanged` still took `com.android.server.am.ProcessRecord` with `processName` read from `ProcessRecordInternal`.
