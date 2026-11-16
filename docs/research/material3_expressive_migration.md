# Material 3 Expressive adapter migration

Verified on 2026-11-16 against Google's Maven metadata and AndroidX sources.
The newest available version is 1.5.0-rc01; 1.5.0 final is not published.
The user explicitly approved the RC instead of the original stable-only constraint.

Sources:
- https://developer.android.com/jetpack/androidx/releases/compose-material3
- https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0-rc01/material3-android-1.5.0-rc01-sources.jar

## Adapter decisions

- Keep the existing Compose BOM and explicitly pin Material 3 to 1.5.0-rc01.
- MaterialExpressiveTheme uses expressive motion for the Material style and standard
  motion for Miuix. Existing palette, dynamic/manual color and AMOLED processing remain.
- ShortNavigationBar measures its own children; its content has no RowScope. The shared
  adapters use receiver-free content, while a local supplies Miuix's original RowScope.
  System navigation insets belong to the Material bar and are not applied twice.
- WideNavigationRail also measures individual items. Its state follows ZTool's hoisted
  expanded state, and the host reserves the measured rail width in Material mode.
  The collapsed Material width starts at the official 96dp token. Miuix keeps 80dp.
- Button and icon-button shapes use the official press morphing overloads. Miuix's
  existing icon buttons keep a fixed pressed shape. Small extended and medium icon FABs
  provide explicit size tiers; their sizes follow AndroidX tokens, rather than assuming
  that Medium means the previous Miuix 64dp size.
- Indeterminate shared loading uses LoadingIndicator; determinate Material indexing uses
  LinearWavyProgressIndicator. The Miuix determinate branch retains its previous indicator.
- The RC exposes SelectableDropdownMenuItem; its old selected DropdownMenuItem overload
  is hidden. Menu groups scroll within the existing 360dp limit and retain supporting text.
- SegmentedListItem owns Material row shapes and interactions. Group positions travel via
  a modifier element for custom section content and a composition local for typed items.
  Slider, text-input and custom content use a passive segmented container. Nested switches
  and checkboxes delegate toggling to the row, preserving a single accessibility target.
- Search anchors retain their existing keys and bounds registration. The highlight pulse
  tint is composed into opaque Material list colors, so it remains visible after migration.
- Existing DockedSearchBar and stateless Slider overloads remain functional but deprecated
  by this RC. Their state-based API migration is separate from the Expressive component change.

## Retained implementation notes

Long comments removed from touched Kotlin files are preserved below. They describe
existing startup, palette, navigation, highlighting and layout constraints; the migration
retains those behaviors.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Configuration changes listed in the manifest are handled in place instead of
recreating the Activity. Compose picks the new configuration up through
[androidx.compose.ui.platform.LocalConfiguration], but the system bar
appearance is imperative state that only [setupSystemBars] maintains, so it has
to be re-applied here — without this, toggling dark mode while the app is open
would leave the status/navigation icons on the previous polarity.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Decides whether the first-run flow should overlay this launch, based on
the per-page schema registry: FirstRun when nothing was ever accepted,
UpdateOnly when any registered page is stale (accepted below its current
schema version or not accepted at all). Registry bumps hence replay the
whole flow until per-page replay lands.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Re-enter the first-run (OOBE) flow, e.g. from the advanced settings
developer entry. The existing acceptance state is kept as-is.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Guards the one-shot startup maintenance against Activity recreation.
Process-scoped by virtue of being a companion property.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Cold start straight into Firstrun: seed the reveal provider with a
full-screen cover so the intro can uncover the welcome page.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Owned by the app shell: MainTabletShell draws the dim layer above the
navigation chrome, and the screens below publish into this instance.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

The main shell stays composed at all times; the Firstrun
flow overlays it instead of replacing it, so the NavHost
and its back stack survive OOBE replay and the user lands
back on whatever screen they came from (e.g. the advanced
settings route when opened from the developer entry).

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Restart the edge-bubble overlay service on app launch; EdgeBubbleBootReceiver
covers the boot / APK-update paths so opening ZTool is not required.
Off the main thread: the enable check reads RemotePreferences (a binder
call to LSPosed when the module is active) and queries the overlay
permission before starting the service.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Everything below is off the first-frame critical path: onCreate returns
without waiting for any of it. The tasks are independent and run
concurrently, except the shell work which is serialized.

Process-scoped, not Activity-scoped: these are one-shot maintenance tasks,
and MainActivity can be recreated for reasons the manifest cannot suppress
(locale change, "don't keep activities", a system-initiated recreation).
Re-running them per recreation would mean a root `su` round trip and
PackageManager component writes on every such event. The scope is the
Application's so a rotation landing mid-flight cannot cancel the work after
the flag below has already been claimed.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

A freshly launched task is not excluded from recents by default;
re-apply the persisted choice before the task gets snapshotted. Stays on the
main thread and runs per Activity: it must see the task this Activity lives in.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Component states survive app updates and a restored config can leave
the icon pointing at the wrong artwork (or at none); re-assert the
persisted selection. One pass, because the user-facing alias pair and
the debug-only LeakCanary alias share the same PackageManager read.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Root shell: copying the LSPosed log directory can take hundreds of
milliseconds, so it must never run on the main thread.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

NavHost installs navController.graph during its own composition, and it sits inside a
Scaffold subcomposition, so this effect can be launched during a pass in which the
graph is not installed yet — most visibly on the first composition after an Activity
recreation (configuration change). popUpTo below reads navController.graph and then
throws "You must call setGraph() before calling getGraph()".

navController.currentDestination is null exactly while the graph is missing, so the
body bails out instead of reading it. Keying on backStackReady re-runs the effect once
NavHost has installed the graph; keying on backStackEntry itself would instead make the
effect re-fire on every navigation and fight the back stack with a stale selectedRoute.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

When Miuix FloatingBottomBar is enabled, force BottomBar even in landscape
since the floating pill design works well in both orientations.

### app/src/main/java/com/qimian233/ztool/MainActivity.kt

Drawn above the navigation chrome so the docked search's dim layer also covers the
rail and the bottom bar; screens publish their search state through it.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Row bounds (in root coordinates) keyed by [SettingItem.key]; the scroll container
root reports its own bounds under [HighlightContainerMarker] so the controller can
convert to a scroll offset. Coordinates are re-read on demand, so values stay fresh
as layout shifts.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Live per-node coordinates. A LayoutCoordinates instance keeps updating itself
every frame, so offsets resolved at consumption time are always current —
immune to uiState-driven expansion shifts between report and consume.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Scroll offset that brings the row to the top of the content column, resolved
from LIVE coordinates read right now. Returns null while the row or the
container is absent or detached — never a stale/wrong-signed value. Detached
rows (conditional child collapsed after composing) are dropped on sight.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Suspends until both the container and the row have live coordinates, then
returns the offset. A row that never composes (conditional child behind an
OFF parent switch) times out to null — driving the parent fallback.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Place directly inside the settings list' scroll container (as the first child of the
content column): reports the container's root bounds so row offsets can be computed.
Draws and measures nothing.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Runs the land-and-highlight sequence for one navigation `?target=` id: waits for the
row to report its bounds, scrolls to it, keeps the pulse for two cycles, and falls
back to the parent key when the target never rendered (conditional child behind an
OFF parent switch). [onConsumed] lets the screen drop the stale target so back /
rotation does not replay the pulse.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Row wrapper used by the settings funnel: reports the row's bounds into the registry
and overlays the container-color double pulse while [LocalHighlightActiveId] equals
[highlightKey]. Content is rendered inside a Box so the overlay covers the whole row.
The pulse timeline runs only while the row is active; inactive rows pay one
CompositionLocal read.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Lookup seam between the highlight controller and the search index, keeping
ui/components free of a direct dependency on the search package. The real
implementation is installed from ZToolNavHost at composition root.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

The reporting frame may predate final layout; settle one frame, then read
the live coordinates.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

Debug-only index audit: once rows have composed, compare rendered keys against
the static index. No target needed; release builds short-circuit to a no-op.

### app/src/main/java/com/qimian233/ztool/ui/components/SettingHighlight.kt

uiState loads and animateContentSize keep shifting rows right after
landing; keep correcting until the target's live offset holds steady
for two polls (bounded), then pulse.

### app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettings.kt

The 28dp-tall Miuix switch would push single-line rows to 60dp; trim the
vertical inset for single-line rows so they stay at the 56dp baseline.

### app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettings.kt

The 40dp-wide value field would push single-line rows to 80dp; trim the
vertical inset for single-line rows so they stay at the 56dp baseline.

### app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettingsModel.kt

Horizontal gutter this list is meant to sit in inside its host, when the host
aligns it with other surfaces (the home screen lines its cards up on an 8dp
gutter, for example). Null keeps the list's own default insets, which is what
every full-page settings screen wants.

### app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettingsModel.kt

The expressive section draws every row as its own surface, so the
gutter has to become the section's content inset.

### app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettingsModel.kt

Miuix draws one surface per section, so the gutter has to be applied around
that surface instead of inside it.

### app/src/main/java/com/qimian233/ztool/ui/theme/ZToolTheme.kt

Miuix's Scaffold sizes itself to the largest available size and paints containerColor,
so applying it inside a wrap-content dialog window stretches that window past its
content (full available width, height down to the screen bottom) and leaves a solid
surface-colored band under the card. Dialog content therefore gets the theme only.


## Palette compatibility

When dynamic color is disabled, Miuix uses its own built-in palette. Shared Material
color roles are reconstructed from that palette; roles absent from Miuix map to the
closest available role. AMOLED post-processing still applies to dark shared surfaces.
The Miuix color mapping is remembered per resolved palette and dark-mode value.
Wallpaper seed reads occur only when dynamic color is enabled without a manual seed.

## Verification

- assembleDebug: passed.
- SearchIndexConsistencyTest: 2 tests passed, 0 failures/errors.
- debugRuntimeClasspath dependencyInsight: material3-android resolves to 1.5.0-rc01.
- No new strings, preferences, feature entries, or search identifiers.
- On-device visual and interaction verification was not performed. An LSPosed device
  must use assembleDev/app-dev.apk for that verification.
