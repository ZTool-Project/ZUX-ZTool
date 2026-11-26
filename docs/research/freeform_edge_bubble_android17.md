# Freeform Edge Bubble — Android 17 (API 37 / ZUXOS 2.5) Adaptation & WeChat Investigation

## 1. SystemUI Drag-Bounds Clamping Path (`ZuiSystemUI_261118_jadx`)

On Android <= 16, `DesktopModeWindowDecoration.calculateValidDragArea()` computed `mLastValidDragArea` for `DesktopModeWindowDecoration`.

On Android 17 (ZUXOS 2.5):
- `DesktopModeWindowDecoration` no longer defines `calculateValidDragArea()` (when used without the refactor flag, `mLastValidDragArea` is inlined into `relayout(...)`).
- Window decorations extend `WindowDecoration2` (`OvWindowDecoration` for tablet freeform mode via `OvShellWinDecorUtils.isOnOvNormalMode`, `DefaultWindowDecoration` for Desktop/PC Mode).
- `WindowDecoration2.relayout(...)` caches `captionController.calculateValidDragArea()` into `lastValidDragArea`, which `WindowDecorationWrapper.getValidDragArea()` returns to `DesktopModeTouchEventListener.handleMotionEvent` before `DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(bounds, validDragArea)`.
- Active `CaptionController` subclasses overriding drag-area computation:
  - `com.android.wm.shell.windowdecor.caption.OvTabletCaptionHandleController`: `calculateValidDragArea()` picks `taskInfo.scaleSurfaceBounds.width()` when `taskInfo.scaleX > 0.0f` and non-empty (else `configuration.windowConfiguration.getBounds().width()`), then delegates to `calculateValidDragAreaForTaskWidth(int)`. `MultiDisplayVeiledResizeTaskPositioner.onDragPositioningEnd` also calls `calculateValidDragAreaForTaskWidth(int)` directly.
  - `com.android.wm.shell.windowdecor.caption.OvDesktopModeAppHeaderController`: overrides `calculateValidDragArea()`, using `scaleSurfaceBounds.width()` when `scaleX > 0.0f`.
  - `com.android.wm.shell.windowdecor.caption.AppHeaderController`: overrides `calculateValidDragArea()`.
- `OvfScaleTaskPositionerDecorator.onDragPositioningEnd` (for scaled freeform moves when `mLastScale > 0.0f` outside desktop mode) calls `captionController.calculateValidDragArea()`.

## 2. system_server Audit (`services_jadx`)

- `ActivityTaskManagerService`: static `getInstance()` is absent; `LocalServices.getService(ActivityTaskManagerInternal.class)` fallback resolves the ATMS instance via `LocalService`'s `this$0` field. `mGlobalLock` and `mRootWindowContainer` are unchanged.
- `RootWindowContainer`: `anyTaskForId(int, int)`, `getAllRootTaskInfos(int)`, and `resumeFocusedTasksTopActivities()` are unchanged.
- `ATMS.getOvfMgr()`, `ATMS.getOvcWmManager()`, `OvcWmManager.getFreeformManager()`, `OvfWmFreeformManager.getHideShowCtrl()`, `OvfWmHideShowController.bringToBack(Task)`, and `bringToFront(Task, String, TransitionController, Transition)` are unchanged.
- `OvFreeformService.checkPermission(String)` is unchanged.
- `Task` extends `OvfBaseTask`:
  - `Task.getBounds()` (`OvfBaseTask.getBounds()`) returns `ConfigurationContainer.getBounds()` (the logical unscaled bounds), **not** `mScaleSurfaceBounds`.
  - `OvfBaseTask.getBounds(boolean)` returns `mScaleSurfaceBounds` when `z && hasScaleSurface() && !mScaleSurfaceBounds.isEmpty()`, `mHangingBounds` when hanging, and `super.getBounds(z)` otherwise.
  - `Task.setBounds(Rect)` overrides `ConfigurationContainer.setBounds(Rect)` and **returns `0` immediately** when `hasUniformScaleSurface()` is true (`mScaleX != 0.0f && !isInit`).
  - Moving a scaled task updates `OvfBaseTask.mScaleSurfaceBounds` via `OvfBaseTask.setScaleTaskSurface(Rect, Rect, float, float, boolean)` without calling `setBounds(Rect)`.

## 3. Why WeChat (`com.tencent.mm`) Failed Edge-Bubble Docking

- In `framework-res.apk` (`res/xml/ov_common_packages.xml`), WeChat is configured with:
  `<c fl_f_flag="512|2048|32768" i_c_p_k="4" i_f_drag_mode="0" p="com.tencent.mm">`
- `i_f_drag_mode="0"` (`OvcSupportInfo.DRAG_MODE_UNIFORM_SCALE`) causes:
  1. `OvfWmLayoutPolicy.scaleInitTaskBounds` to launch WeChat with `mScaleX > 0.0f` and visual bounds stored in `mScaleSurfaceBounds`.
  2. `DesktopModeWindowDecorViewModel` to wrap WeChat's positioner in `OvfScaleTaskPositionerDecorator` (`OvfUniformScaleDragExecutor`).
- On drag-end (`ctrlType == 0`), both `OvfScaleTaskPositionerDecorator.onDragPositioningEnd` and `DesktopModeTouchEventListener.handleMotionEvent` commit WeChat's new position via `TaskOrganizer.scaleTaskSurface(...)` (`OvfBaseTask.setScaleTaskSurface`), **not** `WindowContainerTransaction.setBounds` / `Task.setBounds`.
- Consequently:
  1. `FreeformEdgeBubbleHook.collectFreeformTaskInfos` called `task.getBounds()`, always seeing the unmoved logical `windowConfiguration.bounds` so `EdgeBubbleService` polling never detected WeChat at the edge.
  2. `installEdgeDetectionHook` only hooked `WindowContainer.setBounds(Rect)`, missing `setScaleTaskSurface`.
  3. `doMinimize` saved `task.getBounds()` instead of `mScaleSurfaceBounds`.
  4. `doRestore` called `task.setBounds(target)`, which is a no-op when `hasUniformScaleSurface()` is true and never updated `mScaleSurfaceBounds`.
  5. `FreeformEdgeBubbleDragLimitHook` (on legacy path) used `windowConfiguration.getBounds().width()` instead of `scaleSurfaceBounds.width()`.

## 4. Non-Transition Restore Z-Ordering Over Fullscreen Tasks

- Docking a freeform task via `Task.moveTaskToBack` runs a `TRANSIT_TO_BACK` Shell Transition whose finish transaction hides the task `SurfaceControl` and places it low in `TaskDisplayArea` (`mTmpNormalChildren`, e.g., `z = 1` above Home `z = 0`). Opening another app fullscreen afterward assigns that fullscreen task a higher SurfaceFlinger layer (`z >= 2`).
- `FreeformEdgeBubbleHook.doRestore` calls `OvfWmHideShowController.bringToFront(task, "ztool_freeform_bubble", null, null)` without creating a `Transition`. Although `bringToFront` sets `alwaysOnTop = true`, reorders `TaskDisplayArea.mChildren`, and resumes the task, `TaskDisplayArea.assignRootTaskOrdering(SurfaceControl.Transaction)` only reassigns SurfaceFlinger layers when `transitionController.mBuildingTransitionLayers || !transitionController.isShellTransitionsEnabled()`.
- Consequently, over Launcher (`z = 0 < 1`) the restored task appeared immediately, whereas over another fullscreen app (`z >= 2`) the task's `SurfaceControl` stayed obscured behind the fullscreen app until an extra touch/transition forced layer reassignment.
- Fix in `FreeformEdgeBubbleHook.syncRestoredTaskSurfaceOrdering`: inside `globalLock`, invoke `RootWindowContainer.ensureActivitiesVisible()`, call `Transition.assignLayersForFinishTransaction(displayArea, displayArea.getPendingTransaction())` (setting `mBuildingTransitionLayers` / `mBuildingFinishLayers` briefly so `assignRootTaskOrdering` runs and keeps `WindowContainer.mLastLayer` in sync), `pendingTx.setVisibility(rootTaskSurface, true)`, and `displayArea.scheduleAnimation()`.
