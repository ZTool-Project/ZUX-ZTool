# SystemUI Status Bar Battery Indicator (`BatteryMeterView`, Android 17 / ZUX OS)

Reverse-engineered from `com.android.systemui` (`compileSdkVersion = 37`, `17-16.1.11.010`) via JADX.

## 1. Host View Hierarchy & Lifecycle (`com.android.systemui.battery.BatteryMeterView`)

`BatteryMeterView` extends `LinearLayout` (horizontal orientation, `Gravity.CENTER_VERTICAL | Gravity.START`) and is instantiated across the status bar, keyguard status bar, and Quick Settings header (`contentDescription == "header"` sets `mInHeaderStatusBar = true`).

### Child View Structure

- Index `0`: `mBatteryPercentViewContainer` (`FrameLayout`)
  - Contains `mBatteryIconView` (`ImageView`, `FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)`)
  - Contains `mBatteryPercentView` (`TextView`, inflated from `R.layout.battery_percentage_view_zui`) by default, overlaid on top of `mBatteryIconView`.
- Index `1` (dynamic, while charging): `mBatteryChargeIconView` (`ImageView`), added by `updateBatteryIcon(boolean)` when `mCharging && !getBypassChargingStatus()` and removed when not charging. Displays `R.drawable.stat_sys_battery_charge` (normal charge) or `R.drawable.stat_sys_battery_turbo_charge` (fast charge).

### Key State Fields on `BatteryMeterView`

| Field | Type | Semantics |
| --- | --- | --- |
| `mLevel` | `int` | Current battery level (`0..100`). |
| `mValidLevel` | `boolean` | Set to `true` after the first `onBatteryLevelDualChanged` callback. |
| `mBatteryStateUnknown` | `boolean` | When `true`, displays `R.drawable.stat_sys_battery_unknown`. |
| `mCharging` | `boolean` | `true` when `batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING (2)` or `BATTERY_STATUS_FULL (5)`. |
| `mPlugType` / `mPluggedIn` | `int` / `boolean` | `mPluggedIn = (mPlugType != 0)`. |
| `mChargeRate` | `int` | Charger rate reported by `ZuiBatteryManager.getChargerRate()`. Values `< 3` (`0`, `1`, `2`) indicate normal charging; **`mChargeRate >= 3` indicates fast/turbo charging** (consistent across `BatteryMeterView`, `ChargingAnimationController`, `KeyguardIndicationController`, and `PulseDisplayView`). |
| `mDualCharging` | `int` | `3` when dual-port charging is active on dual-USB devices. |
| `mIsPowerSave` | `boolean` | Power-save mode state (`mIsPowerSave \|\| mBatteryController.mPowerSave`). |
| `mIsBlackTheme` | `boolean` | `!mInHeaderStatusBar && darkIntensity == 1.0f` (light status bar background requiring dark icons). |
| `batteryHealth` | `int` | `Settings.System` `"longtime_charging_protect_enable"` (`1` = enabled). |
| `mGlobalBypassChargingStatus` | `boolean` | Global bypass-charging protection toggle. |
| `BATTERY_PROTECTED_MIN_LEVEL` | `int` | Minimum level threshold for charging protection frame (default `40`). |

### Key Host Methods

- `onBatteryLevelDualChanged(int level, int plugType, int chargeRate, int dualCharging, int batteryStatus, boolean charging)`: Updates state fields, then calls `updatePercentText()` and `updateBatteryIcon(false)`.
- `updateBatteryIcon(boolean forceUpdate)` / `updateBatteryIconOTG(boolean forceUpdate)`: Selects a bucketed `stat_sys_battery_*` resource (5% increments: `00, 05, 10, ..., 100`), assigns it to `mBatteryIconView`, and manages `mBatteryChargeIconView`.
- `onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint)`: Computes `mIsBlackTheme`, updates text/charge-icon colors, and calls `updateBatteryIcon(true)`.
- `onPowerSaveChanged(boolean isPowerSave)`: Updates `mIsPowerSave` and calls `updateBatteryIcon(false)`.
- `updateShowPercent()` / `updatePercentText()`: Controls `mBatteryPercentView` visibility and text.
- `scaleBatteryMeterViews()`: Scales `mBatteryPercentViewContainer` by `R.dimen.status_bar_icon_scale_factor` and resets `mBatteryPercentView.getLayoutParams().width = MATCH_PARENT` and `height = MATCH_PARENT`.

## 2. Stock ZUI Battery VectorDrawable Geometry (`res/drawable/stat_sys_battery_*.xml`)

All stock battery level drawables (`stat_sys_battery_inner_*`, `stat_sys_battery_pure_*`, `stat_sys_battery_power_save_*`, `stat_sys_battery_*_health_*`) are `VectorDrawable` assets sharing the exact same coordinate system:

- Intrinsic dimensions: `26.4dp × 26.8dp`
- Viewport: `viewportWidth = 66.0`, `viewportHeight = 67.0`
- **Group 1 — Outer Rounded Frame** (`Style.STROKE`, `strokeWidth = 4.0`, bounds `x: 3..60`, `y: 18..50`):
  - Path: `M11.256,18L51.744,18C54.744,18 55.86,18.242 56.964,18.832C57.914,19.34 58.66,20.086 59.168,21.036C59.758,22.14 60,23.256 60,26.256L60,41.744C60,44.744 59.758,45.86 59.168,46.964C58.66,47.914 57.914,48.66 56.964,49.168C55.86,49.758 54.744,50 51.744,50L11.256,50C8.256,50 7.14,49.758 6.036,49.168C5.086,48.66 4.34,47.914 3.832,46.964C3.242,45.86 3,44.744 3,41.744L3,26.256C3,23.256 3.242,22.14 3.832,21.036C4.34,20.086 5.086,19.34 6.036,18.832C7.14,18.242 8.256,18 11.256,18Z`
- **Group 2 — Right Terminal Cap** (`Style.FILL`, bounds `x: 62..65`, `y: 28..40`):
  - Path: `M62,28L64.073,31.015C64.677,31.893 65,32.934 65,34C65,35.066 64.677,36.107 64.073,36.985L62,40L62,40L62,28Z`
- **Group 3 — Inner Level Fill Bar** (`Style.FILL`, bounds `x: 8..(8 + 47 * level / 100)`, `y: 23..45`):
  - Full width at `100%` is `47.0` (`x: 8.0 -> 55.0`), height is `22.0` (`y: 23.0 -> 45.0`).
  - Corner radius is `min(3.85f, fillWidth / 2f)` (matching `05%` width `2` r=`1`, `10%` width `4` r=`2`, `15%` width `7` r=`3.5`, `20%..100%` r=`3.85`).

### Stock Color & Alpha Matrix

| Element / State | Light Status Bar (`mIsBlackTheme = true`) | Dark Status Bar (`mIsBlackTheme = false`) |
| --- | --- | --- |
| Frame & Cap (Normal) | `#000000`, `alpha = 0.7` (`179`) | `#FFFFFF`, `alpha = 0.8` (`204`) |
| Frame & Cap (Health / Bypass Protect) | `#18AD74`, `alpha = 1.0` (`255`) | `#18AD74`, `alpha = 1.0` (`255`) |
| Fill (Normal `16..100%`, inner percent shown) | `#000000`, `alpha = 0.2` (`51`) | `#FFFFFF`, `alpha = 0.2` (`51`) |
| Fill (Normal `16..100%`, pure / no inner percent) | `#000000`, `alpha = 0.8` (`204`) | `#FFFFFF`, `alpha = 0.9` (`230`) |
| Fill (Low battery `1..15%`, not charging) | `#FF0000`, `alpha = 1.0` (`255`) | `#FF0000`, `alpha = 1.0` (`255`) |
| Fill (Power save, not charging) | `#CC8E47`, `alpha = 1.0` (`255`) | `#CC8E47`, `alpha = 1.0` (`255`) |

## 3. ZTool Custom Drawable Architecture

Instead of mutating the shared static `VectorDrawable` resources (which lack a fill `<path>` at `0%` and cannot be replaced with alternative shapes), `SystemUIBatteryHook` uses a layered rendering pipeline:

1. **`BatteryIndicatorPolicy.kt`** (pure Kotlin, JVM-unit-testable):
   - Holds `BatteryIndicatorStyle`, `BatteryIndicatorOptions`, `BatteryIndicatorState`, and `BatteryResolvedColors`.
   - `resolveBatteryColors(state, options)` computes frame and fill colors/alphas for normal, low-battery, power-save, health-frame, and charging-speed states (`#34C759` green for normal charging `mChargeRate < 3`, `#BF5AF2` bright purple for fast charging `mChargeRate >= 3`).
2. **`ZToolBatteryDrawable.kt`**:
   - Custom `Drawable` backed by a pluggable `BatteryShapeRenderer`.
   - `DefaultFramedBatteryRenderer` reproduces the stock `66 × 67` viewport outer frame, right terminal cap, and continuous `1%`-granularity rounded level bar.
   - Future styles (frameless capsule, vertical, ring, pie, segmented) implement `BatteryShapeRenderer` without changing hook plumbing.
3. **`SystemUIBatteryHook.kt`**:
   - Activates whenever `readOptions().enabled` is true.
   - Replaces `mBatteryIconView`'s drawable with `ZToolBatteryDrawable` after stock icon updates (falling back to stock drawable for `level == 0 && !isCharging`, unknown battery state, or virtual battery).
   - Manages external vs. internal `mBatteryPercentView` placement and guards `LayoutParams` in `scaleBatteryMeterViews()`.
