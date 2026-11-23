# ZUI Launcher Big Folder External Title Analysis

Jadx analysis of `com.zui.launcher` (Android 17 / ZUI 18.6+) comparing collapsed `1x1` small folders / desktop icons with stock `spanX > 1 || spanY > 1` big folders (`com.android.launcher3.folder.FolderIcon`, `PreviewBackground`, `FolderAnimationManager`, `Folder`, `ShortcutAndWidgetContainer`).

## 1. Small Folder / App Icon vs Stock Big Folder Geometry

- **Container top padding (`ShortcutAndWidgetContainer.measureChild`)**:
  - Normal `1x1` icons and `1x1` `FolderIcon` receive `paddingTop = WorkspaceProfile.cellYPaddingPx` (or `max(0, (cellHeight - cellContentHeight) / 2)` when `cellYPaddingPx < 0` or outside Workspace).
  - Big folders (`qh.f.f(info.spanX, info.spanY)` == `spanX > 1 || spanY > 1`) explicitly force `paddingTop = 0`.
- **Visual vertical envelope of a `1x1` cell row**:
  - Small folder background spans `[cellPadTop + folderIconOffsetYPx, cellPadTop + folderIconOffsetYPx + folderIconSizePx]`.
  - App icon and small folder labels start at `cellPadTop + iconSizePx + iconDrawablePaddingPx` (both via `BubbleTextView` drawable+padding and `FolderIcon.setFolderNameMargin`: `lp.topMargin = iconSizePx + iconDrawablePaddingPx`).
  - Therefore the visual gap between the solid folder background bottom and the label top is:
    `labelGap = (iconSizePx + iconDrawablePaddingPx) - (folderIconOffsetYPx + folderIconSizePx)`.
- **Big folder (`slotH = spanY * cellHeight + (spanY - 1) * borderY`)**:
  - Stock `PreviewBackground.setup` sets `basePreviewOffsetY = widgetPadding.top` and `previewSizeY = cellHeightBig * spanY - widgetPadding.top - widgetPadding.bottom`. Because `widgetPadding.bottom` is only ~16dp, placing an external label below `basePreviewOffsetY + previewSizeY` overflows `[0, slotH]` and collides with the row below.
  - Matching ZUI's own legacy external-label dead code (`FolderIcon.onBeforeResize` and `FolderIcon.setFolderNameMargin`), aligning the background vertical region to `regionTop = cellPadTop + folderIconOffsetYPx` and `regionBottom = min(lastRowTop + regionTop + folderIconSizePx, slotH - textH - labelGap)` keeps the top edge, bottom edge, and bottom external label baseline aligned with neighboring `1x1` icons while preventing bottom clipping.

## 2. Host Big Folder Label Overrides on `FolderIcon`

1. `getBigFolderItemPaddingTop()`: Stock returns half of `(ceil(fontMetrics.bottom - fontMetrics.top) + mFolderNameTopMargin + mFolderNameBottomMargin)` to shift preview items below the top title. Returning `0` centers the preview grid in both stock big folders and `BigFolderAlignHook`.
2. `setNameSize()`: Stock calls `mFolderName.setTextSize(0, mFolderNameTextSize)` (`R.dimen.folder_label_text_size`) for big folders instead of `WorkspaceProfile.iconTextSizePx` (desktop icon label size).
3. `setFolderNameScale()`: Stock scales `mFolderName` by `mFolderNameTextSizeOrigin / mFolderNameTextSize`. External labels use `scaleX = 1.0f`, `scaleY = 1.0f`.
4. `setFolderIconMargin()`: Stock computes large horizontal margins from `mBackground.getBigBounds(rectF)` and `bigFolderNameHMargin` to squeeze the scaled title inside the card. Small folders use `leftMargin = 0`, `rightMargin = 0`.
5. `setFolderNameTextColor(int i10)`: Stock ignores the wallpaper-adaptive color argument `i10` for big folders and hardcodes `GraphicsUtils.getAttrColor(R.attr.folder_text_color, getContext())`. External labels must apply `i10` directly and call `mFolderName.invalidate()` (called from `WorkspaceLayoutManager.addInScreen` and `FolderIcon.setTextColor` on wallpaper changes).
6. `setFolderNameMargin()`: Called in `inflateIcon` and every `dispatchDraw`. Stock sets `lp.topMargin = mFolderNameTopMargin + widgetPadding.top` (and `BigFolderAlignHook` syncs it to `basePreviewOffsetY + mFolderNameTopMargin`). For external bottom placement:
   `topMargin = bgBottom + labelGap - icon.paddingTop`, with `icon.clipChildren = false` so text descenders/shadows are never clipped.

## 3. Open/Close & Resize Animations

- **Open/Close title animation (`FolderAnimationManager.createAnimatorSet` & `Folder.ENBALE_NAMR_ANIM`)**:
  - When `Folder.ENBALE_NAMR_ANIM == true`, `com.android.launcher3.folder.h0` morphs `Folder`'s header from `FolderIcon.getFolderNameTopMargin()` (top inside).
  - Setting `Folder.ENBALE_NAMR_ANIM = false` switches `FolderAnimationManager` to alpha-fade `Folder.getFolderNameView()`, matching small folders (and paired with fading `Folder.mFolderNameAnimDelegate` over `120L` ms on close).
  - Note: Do not block `FolderIcon.setTextVisible(false)`, because `Folder` temporarily hides the icon label while recording wallpaper blur `RenderNode` snapshots as well as during open transitions.
- **Resize animation (`FolderIcon.onBeforeResize`, `onAfterResize`, synthetic `com.android.launcher3.folder.y1`)**:
  - Stock `y1.onAnimationUpdate` translates `mFolderName.translationY = pb.offsetT * frac` (anchored to top edge) and `translationX = offsetL/offsetR * frac / 2f`.
  - When the label sits outside the bottom edge, `translationY` must track `pb.offsetB * frac` (bottom edge delta) and `translationX` should track `(pb.offsetL + pb.offsetR) * frac / 2f`.

## 4. Cooperation with `BigFolderAlignHook` & `LauncherNoLabelMode`

- When `preference_key = launcher_no_label_mode` is enabled, `BigFolderExternalTitleHook` disables itself (`BigFolderExternalTitleSupport.isActive = false`) so desktop backgrounds never reserve unused label space.
- `BigFolderExternalTitleHook` hooks `PreviewBackground.setup`, `FolderIcon.setFolderNameMargin`, `onBeforeResize`, and `onAfterResize` at `PRIORITY_DEFAULT - 10` (inner).
- `BigFolderAlignHook` (at `PRIORITY_DEFAULT`, outer) calls `BigFolderExternalTitleSupport.computeVerticalRegion(..., reserveBottomLabel = externalTitle)` unconditionally in `hookSetupGeometry` and `onBeforeResize`, aligning the big folder card's top and bottom edges with neighboring `1x1` workspace icons even when `BigFolderExternalTitleHook` is OFF (preventing the card from sinking into the bottom row's label band). When `BigFolderExternalTitleHook` is ON, `BigFolderAlignHook` also skips its internal top-label margin overwrite.
