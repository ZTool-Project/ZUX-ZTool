# Lenovo long screenshot on Android 17

Investigated on 2026-11-15 with jadx 1.5.6, using the Lenovo Android 17
SystemUI open in Jadx and the matching local framework.jar.

## Conclusion

The stock Lenovo/Moto long-screenshot chain does **not require applications to
actively integrate a Lenovo SDK or register a vendor capture callback**. The
vendor Binder methods run framework code inside the foreground application:
ActivityThread delegates to android.app.LongScreenshotUtils, which discovers a
scrollable View and drives it automatically.

Consequently, mandatory vendor integration is not a reason to enable AOSP scroll
capture on this build. There is still a potential compatibility reason: Lenovo's
View-tree heuristics can reject or mismeasure interfaces that expose useful
capture targets through standard ScrollCapture callbacks or Compose semantics.
That advantage needs testing against specific applications; this investigation
does not establish that all Compose pages fail or that AOSP captures every page.

The current module's AOSP reroute cannot restore capture by changing a flag or
method name. The SystemUI executor and tile set are empty, the client keeps only
a constant, the controller has only a ratio getter, and the LongScreenshot result
class is absent. Restoring
the standard path requires supplying the missing capture implementation. The
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

Preserve the existing capability guard. If actual applications fail on the stock
path, record candidate discovery and movement failures first. For applications
with standard capture targets, a separate implementation of standard session
management, image collection, stitching/export and UI handoff may be worthwhile.
Simply forcing the vendor ability flag neither adds a target nor recreates AOSP.

This task records findings only; no Hook, UI or preference behavior was changed,
and no APK was built or installed.
