# SystemUI (Android 17) — target audit for the unchecked hook TODOs

Scope: the seven unchecked items under `系统界面 → 状态栏 / 锁屏` in `TODOS.md`. For each
one, this note records the exact members the current hook expects and whether the bundled
SystemUI still provides them.

Build examined (jadx MCP, static analysis only):

| Property | Value |
| --- | --- |
| Package | `com.android.systemui` |
| `versionName` / `versionCode` | `17` / `37` |
| `compileSdk` / `minSdk` / `targetSdk` | 37 / 37 / 37 |
| Reference path | `F:\USER\Jadx项目\Lenovo\android-17\系统界面_17.apk` |

`Build.VERSION_CODES.CINNAMON_BUN == 37` in `android-37.0/android.jar`, so every
`SDK_INT >= CINNAMON_BUN` branch in the hooks takes its Android 17 arm.

## Verdict summary

| TODO | Hook | Verdict |
| --- | --- | --- |
| 隐藏通知过多时的圆点 | `NotificationOverflowDotHook` | **signature changed** — `setVisibleState` lost an argument (4 → 3) |
| 优化网速数字比例 | `SystemUINetworkSpeedSizeHook` | **class renamed** — `com.android.systemui.zui.NetworkSpeedView` is gone |
| 网速刷新时间自定义 | `NetworkSpeedRefresh` | **class renamed** — handler-name prefix no longer matches; silent no-op |
| 隐藏慢速 | `NetworkSpeedHideSlowHook` | targets intact — no rename, no signature change |
| 原生通知图标 | `NativeNotificationIcon` | **targets removed** — `replaceTheSmallIcon` no longer exists anywhere (1 of 6 sub-hooks is framework-side and unverifiable here) |
| 联想 AOD | `ForceLenovoAOD` | **field + constructor removed** — `ZuiDozeTriggers` degenerated into a data holder |
| 充电功率 | `SystemUIChargeWattsHook` / `SystemUIRealWatts` | targets intact — no rename, no signature change |

## 1. 隐藏通知过多时的圆点 — `setVisibleState` arity changed

`NotificationOverflowDotHook` selects the overload by name and arity 4, following
`docs/research/systemui_notification_overflow_dot.md` (written against the API 36 build).

Current `com.android.systemui.statusbar.StatusBarIconView` (class name unchanged) declares
only three overloads:

```text
setVisibleState(int)
setVisibleState(int, boolean)
setVisibleState(int, boolean, NotificationIconContainer$$ExternalSyntheticLambda0)
```

The API 36 overload `setVisibleState(int, boolean, Runnable, long)` is gone; the trailing
`long` delay was folded away and the completion callback is now a bare synthetic lambda
(`NotificationIconContainer$$ExternalSyntheticLambda0`, source type `Runnable`). The hook's
`declaredMethods.firstOrNull { name == "setVisibleState" && parameterTypes.size == 4 }` returns
null, so it logs `StatusBarIconView.setVisibleState(int, boolean, ..., long) not found` and
installs nothing.

Everything else in that path survived:

* DOT state `1` is still produced by
  `NotificationIconContainer$IconState.applyToView(View)`, which now calls
  `statusBarIconView.setVisibleState(this.visibleState, z3, null)`.
* `onDraw` still paints the circle from `mDotAppearAmount` / `mDotRadius`; `overflow_dot_radius`
  is still the radius source.
* `com.android.wm.shell.R$id.notificationIcons` still exists, so the container filter works.

Fix shape: match arity 3, keep rewriting `args[0]` from `1` to `2`; `args[2]` is the lambda, so
`chain.proceed(args)` must pass it through unchanged.

## 2. 优化网速数字比例 — `NetworkSpeedView` moved

`SystemUINetworkSpeedSizeHook` loads `com.android.systemui.zui.NetworkSpeedView` in
`handleLoadPackage`. That class does not exist in this APK (no class under
`com.android.systemui.zui` remains); the view now lives at
`com.android.systemui.statusbar.view.NetworkSpeedView`, and `IconManager.addHolder` is its
creator.

The rest of the hook's assumptions still hold:

* `NetworkSpeedView extends TextView` and writes its whole label through
  `setText(CharSequence)` (`"0.00\nK/s"`, `"<n>\nM/s"`, `"<n>\nG/s"`) — the `\n` test in
  `isNetworkSpeedText` still discriminates network-speed text.
* `TextView.setText(CharSequence)` is unchanged.

So the failure is exactly one renamed class: `loadClass` throws `ClassNotFoundException`, the
outer `catch` logs `Failed to hook SystemUI network speed display`, and the whole module
(including the double-layer-independent styling) never installs.

## 3. 网速刷新时间自定义 — handler class-name prefix no longer matches

`NetworkSpeedRefresh` does not load `NetworkSpeedView`; it hooks
`Handler.sendEmptyMessageDelayed(int, long)` and filters on
`handler.javaClass.name.startsWith("com.android.systemui.zui.NetworkSpeedView$")`.

Current state:

* The refresh loop still uses exactly that mechanism — `NetworkSpeedView.updateNetworkSpeedViewStatus()`
  calls `sendEmptyMessageDelayed(10, 50L)`, and message `10` re-arms itself with
  `sendEmptyMessageDelayed(10, 3000L)`.
* The handler instance is the field `mNetworkSpeedHandler` whose type is
  `com.android.systemui.statusbar.view.NetworkSpeedView$3`.

The hook therefore installs successfully (no exception, no warning) but the prefix test never
matches, which is exactly the reported "对原版失效": a silent no-op rather than a crash.

### 3.1 速率换算与间隔的耦合

`NetworkSpeedView` 不按真实时间算速率:消息 `10` 里直接
`messageObtainMessage.arg1 = ((int) currentTotalBytes) / 3`,即写死"采样窗口恒为 3000 ms"。
只改 `sendEmptyMessageDelayed` 的延迟会把读数按 `interval / 3 s` 缩放(改成 1 s 只剩真实值的 1/3)。

`NetworkSpeedRefresh` 现在的做法:

* 间隔改写只作用于 `delayMillis >= 1000` 的自续期调用;`updateNetworkSpeedViewStatus()`
  那次 `50L` 首帧原样放过(否则首帧要等一个完整间隔),同时清掉该 handler 的采样状态。
* 额外 hook `NetworkSpeedView$3.handleMessage(Message)`:消息 `what == 10` 入口用
  `SystemClock.elapsedRealtime()` 记采样时刻并算出与上一轮的窗口;`what == 1` 入口把 `arg1`
  按 `3000 / 实测窗口` 重标定后再交给原实现格式化。默认 3 s 时系数≈1,与原值等价。
* handler 类不再依赖 `$3` 这种编号:先读离线索引 `HANDLER_INNER_CLASS`,失败则按
  `NetworkSpeedView` 里唯一一个 `Handler` 子类字段的类型反查,新旧两个包名都试。
* 滑杆允许 0 s,而 `sendEmptyMessageDelayed(10, 0)` 会变成紧循环,所以实际间隔夹在 200 ms~60 s。

速率重标定尚未上机验证(编译通过),`SystemUINetworkSpeeddoublelayerHook` 的自算路径不受影响:
它自己用 `currentTime` 差值算速率,但 `timeDiff` 取整到秒,间隔小于 1 s 时 `timeDiff == 0` 会跳过更新。

## 4. 隐藏慢速 — every target still present

`NetworkSpeedHideSlowHook`:

| Target | State |
| --- | --- |
| `com.android.systemui.statusbar.view.NetworkSpeedView` (SDK ≥ 37 arm) | present |
| `updateNetworkSpeedViewStatus()` | present (`public final`, no args) |
| all `NetworkSpeedView` constructors | 3 present (`(Context)`, `(Context, AttributeSet)`, `(Context, AttributeSet, int)`) |
| `Settings.System.getIntForUser(ContentResolver, String, int, int)` | framework member, still the call form used by SystemUI |
| setting key `network_realtime_speed_state` | still read in `isIconVisible()` and `updateNetworkSpeedViewStatus()` |

Both native read sites use the 4-argument `getIntForUser(..., ActivityManager.getCurrentUser())`
overload, which the hook intercepts. No target in this module changed; the "不稳定" symptom is
behavioural, not a reflection failure.

## 5. 原生通知图标 — the `replaceTheSmallIcon` stage is gone

Six sub-hooks, checked individually:

| # | Target | State |
| --- | --- | --- |
| 1 | `com.android.systemui.utils.XSystemUtil.isCTSGTSTest()` | present (old `...util.XSystemUtil` is gone; the SDK ≥ 37 arm already targets `utils`) |
| 2 | `com.android.systemui.statusbar.NotificationShelf.updateResources$5()` | present |
| 3 | `com.android.systemui.util.QSUtil.replaceTheSmallIcon` | **missing** — `QSUtil` exists but declares no such method |
| 3' | `com.android.systemui.statusbar.NotificationListener.replaceTheSmallIcon` | **missing** |
| 4 | `com.android.systemui.notificationlist.notification.wrapper.NotificationHeaderViewWrapper` | present; `mIcon` is `ImageView`, `onContentUpdated(NotificationRowView)` present |
| 5 | `com.android.systemui.notificationlist.view.NotificationHeaderView` | present; `shouldShowIconBackground` gone, `updateIconBgVisibility()` present, `mIsChildInGroup` / `mShowLargeIconView` present (the hook's new-version branch is the one that runs) |
| 6 | `android.app.Notification$Builder.isCtsGtsTest()` | framework class, not part of this APK |

`replaceTheSmallIcon` occurs zero times in the whole APK — not as a method name, not as a
string — and no method name contains `SmallIcon` at all. The ZUI small-icon replacement stage
was removed rather than renamed, so sub-hook 3 has no substitute target in this build.
Sub-hooks 1, 2, 4 and 5 can still install; 6 needs a `framework.jar` check.

## 6. 联想 AOD — `ZuiDozeTriggers` became a data holder

`ForceLenovoAOD` does two things; the first one is dead.

* `com.android.systemui.doze.ZuiDozeTriggers` still exists, but it declares **no methods at all**
  (only `<clinit>`) and only these fields: `mContext`, `mDozeHost`, `mDozeLog`, `mDozeTriggers`,
  `mIsAodScheduled`, `mMainHandler`.
  `mIsGoingToStartAOD` occurs zero times in the APK, and the `(DozeTriggers, Context)`
  constructor the hook asks for does not exist — `getDeclaredConstructor` throws and the module
  logs `Failed to hook ZuiDozeTriggers`.
* The scheduling decision was inlined into
  `DozeTriggers.transitionTo(DozeMachine$State, DozeMachine$State)`, `DOZE_AOD` branch:
  `mZuiDozeMachine != null` → `XSystemUtil.isAodEnabled(context)` → power-save gate
  (`zui_power_save_disable_aod_notification_enabled`) → `ZuiDozeMachine.findSensor(...)` must
  return a motion sensor → `mIsAodScheduled = true`. `ZuiDozeTriggers` is now only the state bag
  that `DozeTriggers` constructs and mutates.

The second half still works and still covers both native gates:

* `android.os.SystemProperties.getInt(String, int)` — `XSystemUtil.isSupportAod()` reads
  `ro.config.aod.support` through it, so forcing `1` still passes.
* `Settings.System.getIntForUser(ContentResolver, String, int, int)` — `XSystemUtil.isAodEnabled()`
  reads `always_on_display` with `(resolver, key, 0, -2)`, which the hook fakes to `1`.

So the existing hook can satisfy both checks and still not start AOD, because the new code also
requires a resolvable motion sensor and a non-null `mZuiDozeMachine`. The picker activities the
TODO mentions are intact: `com.android.systemui.aod.setting.AoDSettingActivity` and
`AodPreviewActivity` are both exported with a `MAIN` filter, plus `AodSettingAdapter`,
`AodStyleItem` and `aod.utils.AodUtil`.

## 7. 充电功率 — every target still present

`SystemUIChargeWattsHook` and `SystemUIRealWatts` share the same targets; all of them survive:

| Target | State |
| --- | --- |
| `com.android.systemui.statusbar.KeyguardIndicationController` | present, same class name |
| `computePowerIndication()` | present (`public final`, no args, returns `String`), still called from `updateDeviceEntryIndication` |
| `mPowerPluggedIn`, `mChargingWattage`, `mChargingSpeed` | all three present |
| `KeyguardIndicationController$BaseKeyguardCallback` | present |
| `BaseKeyguardCallback.onRefreshBatteryInfo(BatteryStatus)` | present, still assigns `mChargingWattage = batteryStatus.maxChargingWattage` |
| `this$0` on the callback | present |
| `com.android.settingslib.fuelgauge.BatteryStatus.maxChargingWattage` | present |

Neither class name nor signature changed, so both hooks install. The remaining risk is data, not
reflection: `BatteryStatus.maxChargingWattage` is computed from the `max_charging_current` /
`max_charging_voltage` intent extras and is `-1` when the framework does not send them;
`SystemUIChargeWattsHook` only appends text for `chargingWattage > 0`, so a `-1` silently yields
no wattage line. `computePowerChargingStringIndication()` also still returns the plain
percentage string, so there is no duplicate/native wattage text to fight with.

## Method notes

* Names below `com.android.systemui.statusbar.notification.icon…` and
  `com.android.systemui.notificationlist…` are unobfuscated (`go/retraceme` mapping present), so
  plain `loadClass` + reflection remains acceptable for the surviving targets.
* jadx prints nested classes with `.` (`KeyguardIndicationController.BaseKeyguardCallback`,
  `NetworkSpeedView.AnonymousClass3`) but the runtime name keeps `$`; only the API-visible
  synthetic lambda keeps `$$` in its printed name.
