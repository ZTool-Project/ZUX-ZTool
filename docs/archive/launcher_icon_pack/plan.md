# Launcher icon pack via Xposed — implementation plan

Goal: replace ZUI Launcher app icons with a third-party ADW / CandyBar icon pack,
entirely through ZTool's LSPosed hooks (no ROM integration).

## Hook points (verified against ZUX Desktop 18.6.0.0927)

Base class `com.android.launcher3.icons.IconProvider`; the runtime singleton is
`LauncherIconProviderImpl`, which does not override any of these:

| Target | Purpose |
|---|---|
| `getIcon(ComponentInfo,int)` / `(ComponentInfo,int,boolean)` | folders, shortcuts |
| `getIcon(ApplicationInfo,int)` / `(ApplicationInfo,int,boolean)` | app drawer (package-only lookup) |
| `getIcon(LauncherActivityInfo,int,boolean)` | workspace shortcuts |
| `getZuiIcon(ActivityInfo,int)` | dock recents / recents tasks |
| `getStateForApp(ApplicationInfo)` | icon-cache freshness (`String` or `PersistedItemState`) |

`getApplicationInfoHash` is overridden by the Impl, so it is NOT hooked.

## Deliverables

1. `PreferenceKeys`: `LAUNCHER_ICON_PACK` (Bool), `LAUNCHER_ICON_PACK_PACKAGE` (String).
2. `hook/modules/launcher/IconPackResolver.kt`: appfilter parser + drawable resolution.
3. `hook/modules/launcher/LauncherIconPackHook.kt`: the six icon hooks + state suffix hook.
4. Launcher settings page: enable switch + pack picker row (Repository/UiState/ViewModel).
5. Strings (zh + en), SearchIndex entries.

## Verification

- `.\gradlew.bat assembleDebug`
- `.\gradlew.bat testDebugUnitTest --tests "com.qimian233.ztool.SearchIndexConsistencyTest"`
- On device: enable, pick pack, restart launcher, check drawer / folder / dock recents.
