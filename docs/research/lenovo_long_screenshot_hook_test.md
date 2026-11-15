# Lenovo long screenshot hook experiments on Android 17

Tested on HA2899VJ (TB710FU, ZUXOS 2.5.10.102), November 15–16, 2026.
WeChat is excluded from these experiments. The preceding analysis is in
[the device layout report](lenovo_long_screenshot_device_layout.md).

**Retired on November 16, 2026.** The application-side repair requires injection
into third-party applications. The project does not permit this approach,
especially for QQ and WeChat, so it was abandoned. Both experimental hooks,
their registration/isolation logic and the added QQ/ZTool scope declarations
have been removed. The test device's QQ scope was removed and its existing
process stopped to unload the hook. The implementation described below is
historical and can be found in commit `6c27cffb`.

The existing AOSP long-screenshot setting now has `maxSdkVersion = 36`
(`Build.VERSION_CODES.BAKLAVA`). Android 17 and later show the standard
unsupported-version hint and disable the switch; the shared setting component
also writes an existing enabled value back to `false` when rendered.

The retirement change passed `assembleDebug` and `assembleDev`. The dev APK
was installed on HA2899VJ, and its packaged scope list excludes QQ and ZTool.
The Android 17 UI dump confirmed an unchecked, disabled AOSP long-screenshot
row with the standard unsupported-version message.

## Experimental implementation

`LenovoLongScreenshotTestHook` used `TEST_HOOK` and ran only in debug/dev
builds on SDK 37. Its explicit application targets were QQ and ZTool. It did
not change the AOSP screenshot toggle or force a successful ability result
without finding a scrollable target.

The Lenovo session's minimum width changes from `frame.width / 3` to
`min(frame.width, frame.height) / 3`. Its minimum height remains
`frame.height / 2`. On this landscape device the limits become 633 × 950 px;
the stock width limit was 1066 px. Downward scrollability, touch handling,
visibility and the visible intersection are still checked.

For ZTool, the hook enumerates the Compose accessibility semantics map and
selects the largest visible, downward-scrollable vertical `ScrollNode` backed
by `ScrollState`. Discovery no longer depends on the previous pointer position
or an `onTouchEvent` override. The session uses the semantics region's top and
height, and its scroll loop reads pixel displacement from that same state.
The hook changes `canScrollVertically` only inside the active Lenovo session.

Scrolling uses `ScrollState.dispatchRawDelta`, followed by Lenovo's existing
200 ms callback delay. The first prototype invoked accessibility `ScrollBy`;
that animated beyond the delay and produced partial deltas (346/381 px for
549 px requests). It was replaced before the final experiment. Lazy list/grid
semantics, reverse scrolling, generic Compose integration and arbitrary app
obfuscation are deliberately unsupported in this prototype.

## Runtime results

QQ's landscape contacts page, without an open chat, selected
`ContactsFPSPinnedHeaderExpandableListView`, with a visible rectangle of
`(0,915)-(960,1999)`. It passed the new threshold. The stock loop scrolled the
list, emitted repeated positive crop regions and eventually reported
`isEnd=true`. This confirms a real discovery failure caused by the width gate,
rather than a mandatory Lenovo SDK integration requirement.

ZTool's original status-bar settings page selected the Compose region
`(200,354)-(3200,1999)`. With synchronous scrolling, state offsets were
`0 → 549 → 1098 → 1647 → 1749`. The last step consumed only the remaining
102 px. Callback offsets matched each step, and the last callback reported
`isEnd=true`. Crop intervals were 902–1451 for complete steps and 1349–1451
for the final step, with the same region top (354) and bottom (1999).

The completed native tile list produced these diagnostic images:

| Page | Export dimensions | Visual verification |
| --- | --- | --- |
| ZTool status-bar settings | 3200 × 3749 px | Scrolling settings content reaches the bottom; the fixed left navigation rail repeats in inserted strips. |
| QQ landscape contacts, no chat open | 3200 × 10418 px | Contact rows remain continuous at the inspected seams and reach the final groups; the fixed right pane repeats in inserted strips. |

The host files are `ztool-full.png` and `qq-full.png` in the external test
directory below. Lenovo still crops full-width strips even when the selected
scrolling region occupies only one pane. Candidate discovery and displacement
tracking alone do not solve this fixed-pane duplication.

The device also logged `bind ScreenShot Edit Service error`. The ordinary
editor/save path is therefore a separate limitation of this experiment;
positive ability and update callbacks must not be described as proof that the
normal save UI works.

## Image verification harness

`LenovoScreenshotOutputTestHook` is restricted to development builds with a
different application ID. The normal ZTool APK does not activate this exporter.
The exact device APK has an `onScreenShotUpdate(int, int, boolean, int, int)`
callback; an older offline sample had a different parameter order.

After an end callback, the exporter waits one second for the queued tile updates,
then concatenates the Lenovo preview drawable's existing cropped bitmap list.
It does not calculate new crop coordinates or capture additional frames.
The preview's ordinary `getFinalBitmap` depends on animation progress and can
return a shortened image; the diagnostic export includes every completed tile.
PNG encoding runs off the main thread. Only the export bitmap is recycled.

Diagnostic files are placed in SystemUI's private files directory with the
`lenovo-screenshot-probe-` prefix. Raw images, logs, APKs and extracted host
sources stay outside Git under
`F:\AgentTempWorkzone\long_screenshot_a17\hook_test`.

## Historical test isolation and cleanup

LSPosed rejects a module targeting itself. ZTool's Compose test consequently
used a temporary module with application ID
`com.qimian233.ztool.screenshotprobe`, targeting ZTool and SystemUI. The probe
registration filtered out every module except the screenshot experiments.
QQ was targeted by the ordinary ZTool dev module during this test only.
An external Gradle init script changed the probe APK's application ID; no
production flavor was added. Scopes were managed through LSPosed's `lspctl` CLI.

Both final normal builds passed, and the normal dev APK was reinstalled on
HA2899VJ. The temporary probe was disabled and uninstalled, its four private
diagnostic PNGs were removed after copying the final images to the host, and
SystemUI was restarted to unload the exporter. The ordinary module's original
SystemUI scope was restored. The QQ scope was subsequently removed when the
experiment was abandoned. Neither experimental hook remains in the project.

## Interpretation

The known Lenovo points can be repaired in application processes. This is a
useful compatibility experiment, not a general replacement for AOSP capture.
It cannot become a project feature under the prohibition on third-party
application injection. The experiment also leaves multiple-region selection,
fixed-pane cropping and the ordinary editor/save pipeline unresolved.
Self-injection is unavailable to the ordinary ZTool module. WeChat's separate
failure is not addressed by these results.
