# Lenovo scroll-target discovery: HA2899VJ layout investigation

Investigated on 2026-11-15 through wired ADB and an authorized root shell.
The user confirmed that the foreground ZTool page was a failing capture scene,
and reported that WeChat/QQ capture works in portrait but fails in landscape.

## Findings

ZTool is rejected by two independent discovery gates in the Lenovo framework:

1. AndroidComposeView.canScrollVertically(1) returns false when its most recent
   pointer-down position is outside the scrollable Compose semantics region.
2. AndroidComposeView has neither an OnTouchListener nor an override of
   onTouchEvent. It handles Compose input through dispatchTouchEvent, which the
   Lenovo predicate does not check.

The accessibility dump's android.widget.ScrollView is a virtual semantics node,
not an actual ScrollView in the View tree visited by LongScreenshotUtils. Its
presence and scrollable=true therefore do not mean the Lenovo finder will see it.
The existing generic mechanism does not adequately adapt to this Compose host.

WeChat's retained landscape layout provides a separate geometric failure case:
its conversation-list pane is 960 pixels wide while the framework's visible
display frame is based on the 3200-pixel display. The minimum width is 1066.
That candidate fails the width predicate independently of its scroll capability.
This establishes a rejection of that pane, not every possible WeChat chat page.
QQ had exited before its targeted View dump; a historical screenshot log reports
ability 0, but its precise failed predicate remains unverified.

These findings justify investigating repairs to Lenovo discovery alongside the
independent AOSP driver. They do not establish that modifying discovery alone
will produce correct long images: scrolling, distance measurement and stitching
must also be verified.

## Device and exact inputs

| Item | Observed value |
| --- | --- |
| ADB serial | HA2899VJ |
| Device | Lenovo TB710FU |
| Build | ZUXOS_2.5.10.102_261112_PRC, Android 17 |
| Display | 3200 x 2000 in landscape, density 400 |
| Root | uid=0, KernelSU context |
| Foreground at initial capture | com.qimian233.ztool/.LauncherAliasAlt |
| Installed ZTool version | v1.0.0-dev.05bdc5c0, DEBUGGABLE |
| Initial ZTool PID | 6597 |

Exact pulled artifacts:

- Device `/system/framework/framework.jar` SHA-256:
  `FA5B0049A30C0887592B70A33E693C2DB75A2737F42F7708B69010AFEEF8392D`.
- Installed ZTool `base.apk` SHA-256:
  `52D239C07BFDDC06F5DE96C4F9894C2CB09321C7CD74C7667BA1D8654E6EE4CF`.

The live framework differs from the earlier offline sample's SHA-256. The live
jar was pulled and LongScreenshotUtils was decompiled again with jadx 1.5.6;
the relevant predicates and scrolling logic remain present. Its decompilation
completed without errors. ViewRootImpl and the installed Compose classes reported
errors in other methods; the cited methods have complete decompiled bodies.

Raw dumps, APK/JAR inputs and generated sources remain outside Git at
`F:\AgentTempWorkzone\long_screenshot_a17\HA2899VJ_initial`.
No conversation text or complete device UI dump is included in this document.

## Collection and distinction between trees

The initial scene was captured using:

```text
adb -s HA2899VJ shell uiautomator dump /data/local/tmp/ztool_longshot_ui.xml
adb -s HA2899VJ pull /data/local/tmp/ztool_longshot_ui.xml <local-output>/ui.xml
adb -s HA2899VJ shell su -c "dumpsys -t 20 activity com.qimian233.ztool"
adb -s HA2899VJ shell dumpsys gfxinfo com.qimian233.ztool
adb -s HA2899VJ shell su -c "dumpsys window"
```

All project/source text was decoded explicitly as UTF-8. A broad `activity top`
dump timed out while dumping other tasks, so the targeted activity dump was used
for ZTool rather than interpreting the incomplete output as a missing View tree.

The accessibility hierarchy contains 85 nodes and reports rotation 1:

```text
ComposeView [0,0][3200,2000]
  ... virtual semantics nodes ...
  android.widget.ScrollView scrollable=true [200,354][3200,1999]
```

The actual View hierarchy has nine attached Views and one ViewRoot:

```text
DecorView 3200 x 2000
  LinearLayout
    FrameLayout android:id/content
      ComposeView 3200 x 2000
        AndroidComposeView 3200 x 2000
          View 0 x 0
          AndroidViewsHandler 3200 x 2000
```

The virtual scroll region is 3000 x 1645 and would pass a size check. It is not a
child View available to findMainScrollView. IME insets explicitly report
visible=false; a retained keyboard Window with a surface is not proof of a
visible keyboard.

## Lenovo predicate and measured runtime values

The live LongScreenshotUtils recursively visits visible ViewGroup children in
reverse order, then tests their parent. A View must pass all of these gates:

```text
canScrollVertically(1)
hasOnTouchListeners() OR onTouchEvent is overridden relative to View
width >= visibleDisplayFrame.width / 3
height >= visibleDisplayFrame.height / 2
visible intersection also meets both size thresholds
```

The installed AndroidComposeView delegates canScrollVertically to its
accessibility delegate using lastDownPointerPosition. That delegate requires the
main thread, a valid pointer position inside a scrolling semantics node's adjusted
bounds, and remaining scroll range in the requested direction.

A JDWP breakpoint at LongScreenshotSessionImpl.isMainScrollView captured the
actual AndroidComposeView while the method ran on the application's main thread:

| Measurement | Value |
| --- | --- |
| canScrollVertically(1), original state | false |
| lastDownPointerPosition, original packed long | 4719768012558538496 |
| Decoded original x/y | approximately (15.999, 102.795) |
| hasOnTouchListeners() | false |
| isOverrideOnTouchEvent(view) | false |
| Session mScreenRect | Rect(0, 98 - 3200, 1999) |
| Minimum width/height from that rect | 1066 / 950 |

The original pointer lies outside the virtual scrolling bounds, which start at
x=200 and y=354. As a temporary diagnostic control, the old process's packed
pointer field was set to 4956211391070076928, representing (1600,1000).
canScrollVertically(1) then returned true. This isolates the pointer-sensitive
first gate; the independent touch-handler gate still rejects the same host.
No complete long-screenshot session was successfully demonstrated by this control.

## Landscape WeChat and QQ comparison

Targeted dumps of the retained WeChat activities show two embedded TaskFragments:

| Pane | Activity/root | Relevant layout |
| --- | --- | --- |
| Left | LauncherUI, 960 x 2000 | ConversationListView, 960 x 1999 |
| Right | EmptyActivity, 2240 x 2000 | Empty pane; not a verified scrolling chat |

ViewRootImpl.getWindowVisibleDisplayFrame uses mTmpFrames.displayFrame and visible
insets. The WeChat dump reports displayFrame=(0,0)-(3200,2000); the inspected
sandboxing code offsets this rectangle rather than reducing its width to the pane.
The left pane's width 960 is consequently below 3200/3=1066. Portrait's display
width is 2000, giving a lower threshold of 666, and the UI topology changes;
the user's portrait success is consistent with this geometric difference.
Portrait geometry and the exact failed chat page were not independently dumped.

QQ's historical SystemUI log at 20:26:48 identifies
com.tencent.mobileqq.activity.SplashActivity and onLongScreenshotAbility=0.
The targeted later QQ activity dump reports PID not running. A class-name or
geometric cause cannot be concluded from its ability result alone.

## Repair direction and remaining verification

For ordinary View layouts, test replacing the full-display size threshold with
one based on the relevant application window/pane, while retaining a useful
minimum visible area. Embedded-activity targeting and coordinate spaces also need
checking; choosing an empty right pane must not hide a usable left-pane target.

For Compose, merely accepting dispatchTouchEvent overrides is insufficient.
Discovery must find a usable scrolling semantics region independently of the
last touch, and scrolling must be directed to that region. The Lenovo capture
loop repeatedly rechecks canScrollVertically and computes displacement using
child Views/getScrollY/scroll metrics, so the subsequent loop can still fail even
after a forced ability result.

A repair should first demonstrate target selection, actual scrolling and valid
displacement on this failing ZTool scene, then validate landscape/portrait and
multiple panes. Standard AOSP capture remains an alternative with explicit
region/ScrollCapture callbacks. This investigation changes no Hook behavior.

## Diagnostic incident and cleanup

The interactive debugger breakpoint was held too long. The device event log
records an input ANR for PID 6597 at 20:36:03, followed by a user-requested kill
at 20:42:33. The app restarted as PID 16074. This was an investigation error,
not evidence that the stock capture code itself caused an ANR.

The old process disconnected before the temporary pointer field could be restored;
its termination discarded that instance and all debugger breakpoints. The ADB
forward tcp:9402 was removed and the foreground ZTool window was verified again.
No diagnostic APK or Hook was installed. Future runtime diagnostics must avoid
holding an input-facing application thread at an interactive breakpoint.
