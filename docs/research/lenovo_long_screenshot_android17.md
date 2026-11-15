# Lenovo long screenshot on Android 17

Investigated on 2026-11-15 with jadx 1.5.6, using the Lenovo Android 17
SystemUI open in Jadx and the matching local framework.jar.

## Conclusion

The stock Lenovo/Moto long-screenshot chain does **not require applications to
actively integrate a Lenovo SDK or register a vendor capture callback**. The
vendor Binder methods run framework code inside the foreground application:
ActivityThread delegates to android.app.LongScreenshotUtils, which discovers a
scrollable View and drives it automatically.

The user's subsequent device tests found that only a small number of popular
applications support the Lenovo channel; the remaining tested applications cannot
take long screenshots. The automatic framework mechanism therefore does not
establish adequate application compatibility on Android 17. These tests supersede
the initial assessment that restoring AOSP was only potentially worthwhile.

**Restoring standard AOSP scroll capture remains a required Android 17 adaptation.**
Leaving the chip on the stock Lenovo channel is a temporary failure recovery path,
not completion of this feature. The tests establish the compatibility problem,
but do not establish its exact cause: no application list, per-target discovery
logs or capture failure traces were supplied. They do not contradict the presence
of automatic View discovery or prove mandatory vendor SDK integration.

The current module's AOSP reroute cannot restore capture by changing a flag or
method name. The SystemUI executor and tile set are empty, the client keeps only
a constant, the controller has only a ratio getter, and the LongScreenshot result
class is absent. Restoring the standard path requires supplying the missing
capture implementation. The
framework's standard request/search/connection and View capture support still
exist, but an end-to-end WMS/session/image test has not been performed.

## Artifacts and evidence

Inputs under `F:\USER\Jadx项目\Lenovo\android-17`:

| Artifact | SHA-256 |
| --- | --- |
| `系统界面_17.apk` | `332291D2773D3BCEE26957CDBC4EEBCAD11B1238A4A98FC5669209390AA79D19` |
| `framework.jar` | `ADDDF3A8F876007F10481CB91047661B889076D134DEC22372164E50C66FDA70` |

The SystemUI manifest identifies package `com.android.systemui`, versionName 17,
versionCode 37 and min/target SDK 37. Conclusions apply to these artifacts, rather
than every Lenovo device running Android 17.

SystemUI sources and stub smali were inspected through Jadx MCP. Framework sources
were generated with Jadx CLI `--single-class`, `--show-bad-code` and explicit UTF-8
reads. LongScreenshotUtils and ScrollCaptureViewSupport completed without errors.
ActivityThread and ViewRootImpl each reported two decompilation errors elsewhere;
the cited capture methods have complete bodies, with no failed-method placeholder.

An independent APK DEX class-definition scan covered all three DEX files (41,728
class definitions). It confirms that
`com.android.systemui.screenshot.scroll.LongScreenshot` is absent, while the
stub classes and LongScreenshotData are present. This avoids treating a Jadx search
miss or an existing holder as proof of an implemented capture pipeline.

Source snapshots remain outside the repository in
`F:\AgentTempWorkzone\long_screenshot_a17`.

## Stock chain

```text
ScreenshotView.initApplicationThread()
  -> ActivityManager.getFocusedWindowApplicationThread() [reflection]
  -> foreground IApplicationThread.checkLongScreenshotAbility(listener)
  -> ActivityThread.ApplicationThread -> main-thread message
  -> ActivityThread.handleCheckLongScreenshotAbility()
  -> LongScreenshotUtils.checkLongScreenshotAbility()
  -> LongScreenshotSessionImpl.findMainScrollView()
  -> listener.onLongScreenshotAbility(0 or 1)
  -> ScreenshotView.mCanBeLongScreenshotMotoFlag

long-screenshot chip / Lenovo screenshot request
  -> QuickScreenshotImpl / QuickScreenshotOverlay
  -> LongScreenshotOverlay
  -> IApplicationThread.startLongScreenshot(listener, 8)
  -> ActivityThread.handleStartLongScreenshot()
  -> LongScreenshotUtils.startLongScreenshot()
  -> scroll -> onScreenshotUpdate(isEnd, top, bottom, viewTop, viewBottom)
  -> LongScreenshotOverlay.takeScreenshot(), crop, append
  -> IApplicationThread.continueLongScreenshot() or stopLongScreenshot()
```

`ScreenshotView.canLongScreenshot(Context)` also applies desktop/streaming,
region screenshot, multiwindow and package/activity restrictions before returning
the reported ability. Enabling the chip cannot make a rejected target start
working: the framework performs discovery again when creating the capture session.

`TakeScreenshotExecutorImpl` also checks the ability and dispatches the stock
quick-screenshot broadcast for Lenovo's direct long-screenshot request.

The application does not have to implement ILongScreenshotListener. ActivityThread
and LongScreenshotUtils are platform implementations loaded into its process;
the listener supplied by SystemUI transports ability, failure and region metadata.
SystemUI takes and stitches the screen bitmaps itself.

## Automatic discovery and its limitations

LongScreenshotUtils.LongScreenshotSessionImpl:

- Enumerates WindowManagerGlobal root views in reverse order, skips keyboard
  windows and recursively visits visible children before their parent.
- Accepts a candidate only if `canScrollVertically(1)` is true and it handles touch
  through a listener or an overridden onTouchEvent.
- Requires both actual size and visible intersection to be at least one third
  of the visible screen width and one half of its height.
- Reports ability 0 when no candidate is found, otherwise 1. A page already at its
  bottom can therefore fail the ability check even if it has content above it.
- Uses AbsListView.scrollListBy, View.scrollBy or synthetic MotionEvents dispatched
  to the decor view. It waits 200 ms before calculating scroll/update metadata.
- Measures movement using a child view's screen position, getScrollY, or public
  vertical scroll offset/extent methods; nonpositive movement reports failure.
- Its specialized class-name checks still name legacy support-library
  NestedScrollView, RecyclerView and CoordinatorLayout. AndroidX names do not match
  these helpers, although generic discovery and fake-touch fallback may still work.

This is automatic adaptation to the application's View/touch behavior. It does
not search ScrollCaptureCallback targets or the Compose semantics tree. Custom
rendering, small scroll regions, nested scrolling, animations and inaccurate
offsets are compatibility questions, not evidence of missing vendor SDK adoption.

## Standard ScrollCapture remains in the framework

`ViewRootImpl.W.requestScrollCapture()` dispatches to
`handleScrollCaptureRequest()`, which collects root callbacks, invokes
`rootView.dispatchScrollCaptureSearch()`, selects a target and constructs a
ScrollCaptureConnection for a connected response.

`com.android.internal.view.ScrollCaptureViewSupport` still implements search,
start, image requests and end; image requests use the View helper and renderer.
Thus the standard application-side protocol was not removed with SystemUI's driver.

The Compose library bundled in this SystemUI also contains standard capture code:
`ScrollCapture_androidKt.visitScrollCaptureCandidates()` traverses semantics nodes
and checks ScrollByOffset and VerticalScrollAxisRange. This demonstrates a distinct
target-discovery mechanism. Other applications' Compose versions and callbacks
must be checked independently before claiming a compatibility improvement.

## Implication for ZTool

`AospScrollCaptureHook.hasAospScrollPipeline()` requires client/controller fields
on the executor and a LongScreenshot class. Those requirements fail here, so its
capture reroute correctly skips this build.

| SystemUI class | Verified implementation |
| --- | --- |
| ScrollCaptureExecutor | Empty class, no fields/methods |
| ScrollCaptureClient | Only MATCH_ANY_TASK = -1 |
| ScrollCaptureController | Only getTargetTopSizeRatio(), returns 0.4f |
| ImageTileSet | Empty class, no fields/methods |
| LongScreenshot | Absent from APK class definitions |
| LongScreenshotData | AtomicReference holder; not a capture driver |

The existing capability guard correctly prevents the legacy driver from using
missing host classes. It must not be treated as the completed Android 17 adaptation:
an independent driver is needed when the host pipeline is absent. Simply forcing
the vendor ability flag neither adds a target nor recreates AOSP.

## Required implementation direction after device feedback

The user subsequently requested investigating Lenovo target discovery before
choosing the implementation route. The
[HA2899VJ layout investigation](lenovo_long_screenshot_device_layout.md) finds
concrete Compose predicate failures and a landscape WeChat pane-size rejection.
Repairing discovery is now an alternative under investigation; it must also
demonstrate correct scrolling and stitching before replacing the AOSP requirement.
The driver direction below remains the alternative when the stock path cannot
deliver adequate compatibility.

Use a module-owned Kotlin driver in the SystemUI scope to call the retained
standard framework protocol. Do not require the host's empty executor/controller,
missing SessionWrapper or synthetic lambda classes to become functional.

The current AospScrollCaptureHook also depends on
`ScrollCaptureClient$1` for its response listener, the host SessionWrapper for
capture callbacks, host controller methods for the tile loop, and LongScreenshot
for its result. Bypassing hasAospScrollPipeline alone will therefore fail further
down the chain.

Implementation sequence:

1. Verify the Android 17 WMS request signature and standard Binder callback
   contracts, then implement response and session callbacks owned by the module.
2. Request standard targets and drive startCapture, requestImage and endCapture;
   receive images through ImageReader with cancellation, timeouts and cleanup.
3. Implement tile placement, overlap handling, image stitching and export without
   depending on SystemUI's stub ImageTileSet or absent LongScreenshot.
4. Connect the existing chip to this driver. Verify crop/save/share handoff before
   reusing the retained LongScreenshotActivity; its presence alone is insufficient.
5. Verify on the LSPosed device using the dev APK. Confirm that applications failing
   through Lenovo can produce valid long images through standard capture, and
   check repeated sessions, cancellation and target-without-callback behavior.

Keep the legacy AOSP driver on builds with an intact host pipeline. A standard
target failure must remain distinguishable from successful capture; a stock Lenovo
fallback does not satisfy the Android 17 acceptance requirement. No claim is made
that standard capture supports every application or bypasses secure windows.

This task records findings only; no Hook, UI or preference behavior was changed,
and no APK was built or installed.
