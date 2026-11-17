# Status Bar Battery Indicator — Architecture & Task Checklist

> [!IMPORTANT]
> **Archiving Instruction**: Once all checklist items in this document are completed and checked off (`[x]`), move this file to `docs/archive/battery_indicator/battery_indicator_checklist.md` (following the workflow in `AGENTS.md`).

## 1. Architecture Overview

The SystemUI status bar battery indicator module (`com.android.systemui`, target class `com.android.systemui.battery.BatteryMeterView`) uses a **custom `Canvas`-drawn `Drawable` architecture** instead of mutating the host's static 5%-stepped `VectorDrawable` resources. Full reverse-engineering notes and host geometry contracts live in `docs/research/systemui_battery_indicator.md`.

### Layered Design

1. **Pure Policy Layer** (`app/src/main/java/com/qimian233/ztool/hook/modules/systemui/statusbar/BatteryIndicatorPolicy.kt`)
   - Pure Kotlin with zero Android or `libxposed` dependencies so all state and color rules are directly covered by JVM unit tests (`BatteryIndicatorPolicyTest.kt`).
   - Defines:
     - `BatteryIndicatorStyle`: enum of indicator shapes (currently `DEFAULT_FRAMED`; extensible to `FRAMELESS`, `VERTICAL`, `RING`, `PIE`, `SEGMENTED`).
     - `BatteryIndicatorOptions`: user-configured feature switches and style selection; exposes `val enabled: Boolean` for multi-switch Hook activation.
     - `BatteryIndicatorState`: snapshot of host `BatteryMeterView` state (`level`, `isCharging`, `chargeRate`, `isPowerSave`, `isBlackTheme`, `isHealthFrameActive`, `systemShowPercent`).
     - `BatteryResolvedColors`: resolved opaque ARGB colors and `0..255` alphas for the container/frame and the level fill.
   - `BatteryIndicatorPolicy.resolveColors(state, options)` computes frame and fill colors/alphas across charging speeds (`mChargeRate >= 3` fast charging vs. `< 3` normal charging), power-save mode, low-battery state, health/bypass protection frame, and light/dark status bar themes.
   - `BatteryIndicatorPolicy.shouldUseStockDrawable(...)` preserves the stock `VectorDrawable` for `0%` non-charging (red exclamation warning / OOBE delay), unknown battery state, and virtual battery mode.

2. **Custom Drawable & Pluggable Shape Renderer Layer** (`app/src/main/java/com/qimian233/ztool/hook/modules/systemui/statusbar/ZToolBatteryDrawable.kt`)
   - `ZToolBatteryDrawable` extends `android.graphics.drawable.Drawable` (`26.4dp × 26.8dp` intrinsic size matching stock `stat_sys_battery_*.xml`), caches `BatteryIndicatorState` and `BatteryIndicatorOptions`, and delegates drawing to a `BatteryShapeRenderer`.
   - `BatteryShapeRenderer` is the strategy interface for all battery indicator shapes:
     - `DefaultFramedBatteryRenderer`: reproduces the stock `66 × 67` viewport stroked outer frame (`strokeWidth = 4f`), right terminal cap, and a continuous `1%`-granularity rounded level bar (`x: 8..55`, `y: 23..45`, `cornerRadius = min(3.85f, width / 2f)`).
     - Future indicator styles only need to implement `BatteryShapeRenderer` and register a branch in `ZToolBatteryDrawable.rendererForStyle(style)`.

3. **Hook Coordinator Layer** (`app/src/main/java/com/qimian233/ztool/hook/modules/systemui/statusbar/SystemUIBatteryHook.kt`)
   - Registered under `PreferenceKeys.SYSTEMUI_BATTERY_PERCENTAGE.name` and overrides `isEnabled(): Boolean = readOptions().enabled` so any active battery sub-feature enables the module.
   - Caches reflected `BatteryMeterView` fields and methods in a `Host` descriptor during `handleLoadPackage`.
   - Hooks `BatteryMeterView` constructor, `updateBatteryIcon`, `updateBatteryIconOTG`, `onBatteryLevelDualChanged`, `onDarkChanged`, `onPowerSaveChanged`, `updateShowPercent`, `updatePercentText`, and `scaleBatteryMeterViews`.
   - Attaches and updates `ZToolBatteryDrawable` on `mBatteryIconView` (keeping a `WeakHashMap` reference to the last stock drawable for fallback states) while letting stock `BatteryMeterView` continue managing `mBatteryChargeIconView` (the charging bolt icon).
   - Manages `mBatteryPercentView` placement when external percentage is enabled and guards `WRAP_CONTENT` layout parameters against `scaleBatteryMeterViews()` resets.

4. **Frontend Persistence, ViewModel, UI & Search Index**
   - Keys registered in `PreferenceKeys.kt`.
   - Persistence in `StatusBarSettingsRepository.kt`, state exposed via `StatusBarSettingsViewModel.kt`, Compose rows in `StatusBarSettingsScreen.kt` (`电池` section), search entries in `SearchIndex.kt`, and bilingual strings in `res/values/strings.xml` + `res/values-en-rUS/strings.xml`.

---

## 2. Completed Work

- [x] Reverse-engineer `com.android.systemui.battery.BatteryMeterView`, `ZuiBatteryManager` charger rate constants (`mChargeRate >= 3` for fast/turbo charging), and stock `stat_sys_battery_*.xml` vector geometry; document findings in `docs/research/systemui_battery_indicator.md`.
- [x] Implement pure policy layer (`BatteryIndicatorPolicy.kt`) with unit tests (`BatteryIndicatorPolicyTest.kt`) covering charging speed colors, inner/external percentage fill alpha, low battery, power-save mode, health frame, and stock fallback rules.
- [x] Implement `ZToolBatteryDrawable` and `BatteryShapeRenderer` with `DefaultFramedBatteryRenderer` (`66 × 67` viewport, `1%`-continuous level bar).
- [x] Refactor `SystemUIBatteryHook.kt` onto the custom `Drawable` pipeline with multi-switch `isEnabled()` activation and cached reflection handles.
- [x] Migrate **External Battery Percentage** (`PreferenceKeys.SYSTEMUI_BATTERY_PERCENTAGE`) onto the new architecture and guard `mBatteryPercentView` `LayoutParams` in `scaleBatteryMeterViews()`.
- [x] Implement **Charging Speed Coloring** (`PreferenceKeys.SYSTEMUI_BATTERY_CHARGING_COLOR`: `#34C759` green for normal charging, `#BF5AF2` bright purple for fast charging) across Hook, Repository, ViewModel, Compose UI (`status_bar_battery_charging_color`), `SearchIndex`, and bilingual `strings.xml`.

---

## 3. Pending Tasks (Todo Checklist)

### 3.1 Bold Battery Percentage
- [ ] Add a `PreferenceKey` (e.g., `SYSTEMUI_BATTERY_PERCENT_BOLD`) and wire it into `BatteryIndicatorOptions`.
- [ ] Support toggling bold vs. normal typeface on `mBatteryPercentView` independently for both internal (inside icon) and external (right of icon) percentage modes in `SystemUIBatteryHook`.
- [ ] Add the frontend switch in `StatusBarSettingsRepository`, `StatusBarSettingsViewModel`, `StatusBarSettingsScreen`, `SearchIndex`, and bilingual `strings.xml`.

### 3.2 Frameless Battery Indicator (`BatteryIndicatorStyle.FRAMELESS` & `IOS_STYLE`)
- [x] Add `FRAMELESS` and `IOS_STYLE` to `BatteryIndicatorStyle` and define container background alpha/color rules in `BatteryIndicatorPolicy` (translucent dark/dim filled containers replacing the bright stroked border).
- [x] Implement `FilledContainerBatteryRenderer`, `FramelessBatteryRenderer` (pure capsule), and `IosBatteryRenderer` (horizontal iOS battery with seamless rounded-rect terminal cap filled only at 100%) in `ZToolBatteryDrawable.kt` with edge-flush `SRC_IN` `1%`-continuous level fill and light inner percentage text.
- [x] Wire style selection in `PreferenceKeys`, Repository, ViewModel, Compose UI, `SearchIndex`, and unit tests.

### 3.3 Alternative Battery Indicator Shapes (`BatteryShapeRenderer` Implementations)
- [ ] **Vertical battery icon (`BatteryIndicatorStyle.VERTICAL`)**: implement `VerticalBatteryRenderer` with top terminal cap, vertical frame/container, and bottom-to-top continuous level fill.
- [ ] **Circular ring indicator (`BatteryIndicatorStyle.RING`)**: implement `RingBatteryRenderer` using a circular background track and `canvas.drawArc(...)` sweep proportional to `clampedLevel`.
- [ ] **Pie chart indicator (`BatteryIndicatorStyle.PIE`)**: implement `PieBatteryRenderer` using a circular container and filled sector `canvas.drawArc(..., useCenter = true, ...)` proportional to `clampedLevel`.
- [ ] **Classic segmented battery indicator (`BatteryIndicatorStyle.SEGMENTED`)**: implement `SegmentedBatteryRenderer` with discrete rectangular level cells separated by gaps inside the battery frame.
- [ ] Add frontend style selector (dropdown / popup menu row) in `StatusBarSettingsScreen` and register corresponding `SearchIndex` and bilingual string entries.

### 3.4 Power-Save & High-Performance Mode Colors
- [ ] Inspect ZUI high-performance / beast mode state source in SystemUI (e.g., `Settings.System` / `Settings.Global` key or controller state) and document findings in `docs/research/systemui_battery_indicator.md`.
- [ ] Extend `BatteryIndicatorState`, `BatteryIndicatorOptions`, and `BatteryIndicatorPolicy.resolveColors(...)` to support dedicated or custom colors for Power-Save mode and High-Performance mode (with well-defined priority relative to charging speed colors).
- [ ] Add unit test cases in `BatteryIndicatorPolicyTest.kt` for power-save and high-performance color resolution.
- [ ] Wire frontend switches (and optional color configuration) in Repository, ViewModel, `StatusBarSettingsScreen`, `SearchIndex`, and bilingual `strings.xml`.

### 3.5 On-Device Verification & Archiving
- [ ] Build `.\gradlew.bat assembleDev`, install `app-dev.apk` on the target ZUX OS / Android 17 device, and verify all battery styles, percentage placements, charging speed colors, power-save/performance colors, light/dark status bar transitions, and QS header rendering.
- [ ] Once all items above are checked off, move this document to `docs/archive/battery_indicator/battery_indicator_checklist.md` and commit.
