package com.qimian233.ztool.hook.modules.qq

import android.graphics.Rect
import android.util.Log
import android.view.View
import com.qimian233.ztool.BuildConfig
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Installs development-only Lenovo screenshot experiments for QQ and ZTool. */
class LenovoLongScreenshotTestHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.TEST_HOOK.name

    override fun getTargetPackages(): Array<String> = arrayOf(
        ScopeKeys.QQ.packageName, ScopeKeys.ZTOOL.packageName
    )

    private val regions = WeakHashMap<Any, ComposeRegion>()
    private val activeSession = ThreadLocal<Any>()

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (!BuildConfig.DEBUG || android.os.Build.VERSION.SDK_INT != 37) return
        val loader = param.defaultClassLoader
        val sessionClass = loader.loadClass(
            "android.app.LongScreenshotUtils\$LongScreenshotSessionImpl"
        )
        val screen = findField(sessionClass, "mScreenRect")
        val location = findField(sessionClass, "mMainScrollViewLocation")
        val top = findField(sessionClass, "mMainScrollViewTop")
        val main = findField(sessionClass, "mMainScrollView")
        val touchOverride = findMethod(sessionClass, "isOverrideOnTouchEvent", View::class.java)
        val hasTouchListeners = findMethod(View::class.java, "hasOnTouchListeners")

        hookWithId(findMethod(sessionClass, "isMainScrollView", View::class.java), "lenovo_test_candidate") { chain ->
            val session = chain.thisObject!!
            val view = chain.getArg(0) as View
            val frame = screen.get(session) as Rect
            val region = if (view.javaClass.name == COMPOSE_VIEW) {
                runCatching { findComposeRegion(view, frame) }.onFailure {
                    Log.e(TAG, "Compose semantics unavailable", it)
                }.getOrNull()
            } else null
            val bounds = region?.bounds ?: Rect().also { view.getGlobalVisibleRect(it) }
            // Landscape panes on HA2899VJ are 960 px wide on a 3200 px display.
            val minWidth = minOf(frame.width(), frame.height()) / 3
            val minHeight = frame.height() / 2
            val scrollable = region?.canScroll(1) ?: view.canScrollVertically(1)
            val handlesTouch = region != null || hasTouchListeners.invoke(view) == true ||
                touchOverride.invoke(session, view) == true
            val accepted = view.isShown && scrollable && handlesTouch &&
                bounds.intersect(frame) && bounds.width() >= minWidth && bounds.height() >= minHeight
            if (accepted) {
                view.getLocationOnScreen(location.get(session) as IntArray)
                top.setInt(session, bounds.top)
                if (region != null) regions[session] = region
            }
            if (scrollable || region != null) {
                Log.i(TAG, "candidate=${view.javaClass.name} bounds=$bounds min=$minWidth,$minHeight touch=$handlesTouch accepted=$accepted compose=${region?.offset()}")
            }
            accepted
        }

        for (name in listOf("scrollInit", "scrollView", "sendCallBack", "finishScroll")) {
            hookWithId(findMethod(sessionClass, name), "lenovo_test_context_$name") { chain ->
                val session = chain.thisObject!!
                val previous = activeSession.get()
                activeSession.set(session)
                try {
                    if (name == "sendCallBack") {
                        val view = main.get(session) as? View
                        Log.i(TAG, "callback view=${view?.javaClass?.name} offset=${regions[session]?.offset()} before=${findField(sessionClass, "mPreComputeScrollY").getInt(session)}")
                    }
                    chain.proceed()
                } finally {
                    activeSession.set(previous)
                    if (name == "finishScroll") regions.remove(session)
                }
            }
        }

        hookWithId(findMethod(sessionClass, "getMainScrollViewVisibleHeight"), "lenovo_test_height") { chain ->
            regions[chain.thisObject]?.bounds?.height() ?: chain.proceed()
        }
        hookWithId(findMethod(sessionClass, "checkIfNeedFakeTouchForScroll"), "lenovo_test_direct_scroll") { chain ->
            if (regions.containsKey(chain.thisObject)) false else chain.proceed()
        }
        hookWithId(findMethod(sessionClass, "scrollY", Int::class.javaPrimitiveType), "lenovo_test_compose_scroll") { chain ->
            val session = chain.thisObject!!
            val region = regions[session]
            if (region == null) chain.proceed() else {
                region.view.getLocationOnScreen(findField(sessionClass, "mMainScrollViewLastLocation").get(session) as IntArray)
                findField(sessionClass, "mPrevScrolledYChildView").set(session, null)
                findField(sessionClass, "mPreScrollY").setInt(session, 0)
                findField(sessionClass, "mIfNeedPreComputeScrollY").setBoolean(session, true)
                findField(sessionClass, "mPreComputeScrollY").setInt(session, region.offset())
                findField(sessionClass, "mPreComputeScrollExtent").setInt(session, region.bounds.height())
                val distance = chain.getArg(0) as Int
                Log.i(TAG, "Compose scroll requested=$distance accepted=${region.scroll(distance)} offset=${region.offset()}")
                null
            }
        }
        hookWithId(findMethod(sessionClass, "getViewComputeScrollY", View::class.java), "lenovo_test_offset") { chain ->
            regions[activeSession.get()]?.offset() ?: chain.proceed()
        }
        hookWithId(findMethod(sessionClass, "getViewComputeScrollExtent", View::class.java), "lenovo_test_extent") { chain ->
            regions[activeSession.get()]?.bounds?.height() ?: chain.proceed()
        }
        if (param.packageName == ScopeKeys.ZTOOL.packageName) {
            val compose = loader.loadClass(COMPOSE_VIEW)
            hookWithId(findMethod(compose, "canScrollVertically", Int::class.javaPrimitiveType), "lenovo_test_can_scroll") { chain ->
                val region = regions[activeSession.get()]
                if (region != null && region.view === chain.thisObject) region.canScroll(chain.getArg(0) as Int)
                else chain.proceed()
            }
        }
        Log.i(TAG, "Installed in ${param.packageName}; width=short-side/3, height=visible-frame/2")
    }

    private fun findComposeRegion(view: View, frame: Rect): ComposeRegion? {
        val loader = view.javaClass.classLoader!!
        val delegate = findField(view.javaClass, "composeAccessibilityDelegate").get(view)!!
        val nodes = invoke(delegate, "getCurrentSemanticsNodes")!!
        val values = findField(nodes.javaClass, "values").get(nodes) as Array<*>
        val metadata = findField(nodes.javaClass, "metadata").get(nodes) as LongArray
        val properties = loader.loadClass("androidx.compose.ui.semantics.SemanticsProperties")
        val vertical = invoke(properties.getField("INSTANCE").get(null)!!, "getVerticalScrollAxisRange")!!
        val getOrNull = loader.loadClass("androidx.compose.ui.semantics.SemanticsConfigurationKt")
            .declaredMethods.single { it.name == "getOrNull" && it.parameterCount == 2 }
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val candidates = mutableListOf<ComposeRegion>()
        for (index in values.indices) {
            if (index / 8 >= metadata.size - 1 ||
                ((metadata[index / 8] ushr ((index % 8) * 8)) and 255L) >= 128L) continue
            val node = values[index] ?: continue
            val semantics = invoke(node, "getSemanticsNode")!!
            val config = invoke(semantics, "getUnmergedConfig\$ui")!!
            val range = getOrNull.invoke(null, config, vertical) ?: continue
            val valueFunction = invoke(range, "getValue")!!
            val scrollNode = valueFunction.javaClass.declaredFields.firstOrNull {
                it.type.name == "androidx.compose.foundation.ScrollNode"
            }?.apply { isAccessible = true }?.get(valueFunction) ?: continue
            val state = findField(scrollNode.javaClass, "state").get(scrollNode) ?: continue
            if (state.javaClass.name != "androidx.compose.foundation.ScrollState" ||
                invoke(range, "getReverseScrolling") == true) continue
            val boundsValue = invoke(node, "getAdjustedBounds")!!
            val bounds = Rect(
                invoke(boundsValue, "getLeft") as Int, invoke(boundsValue, "getTop") as Int,
                invoke(boundsValue, "getRight") as Int, invoke(boundsValue, "getBottom") as Int
            )
            bounds.offset(origin[0], origin[1])
            if (!bounds.intersect(frame)) continue
            val region = ComposeRegion(view, bounds, range, state)
            if (region.canScroll(1)) candidates += region
        }
        return candidates.maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
    }

    private class ComposeRegion(val view: View, val bounds: Rect, val range: Any, val state: Any) {
        private val rawDelta = state.javaClass.getMethod("dispatchRawDelta", Float::class.javaPrimitiveType)
        fun offset(): Int = number(invoke(range, "getValue")!!).roundToInt()

        fun canScroll(direction: Int): Boolean {
            val value = number(invoke(range, "getValue")!!)
            val max = number(invoke(range, "getMaxValue")!!)
            val reversed = invoke(range, "getReverseScrolling") == true
            return if ((direction > 0) != reversed) value < max else value > 0f
        }

        // Accessibility ScrollBy animates beyond Lenovo's 200 ms capture delay.
        fun scroll(distance: Int): Float = (rawDelta.invoke(state, distance.toFloat()) as Number).toFloat()
    }

    companion object {
        private const val TAG = "ZToolLenovoScrollTest"
        private const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"

        private fun invoke(receiver: Any, name: String): Any? =
            receiver.javaClass.methods.single { it.name == name && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(receiver)

        private fun function(receiver: Any, vararg args: Any): Any? {
            val method: Method = receiver.javaClass.methods.first {
                it.name == "invoke" && it.parameterCount == args.size &&
                    it.parameterTypes.all { type -> type == Any::class.java }
            }
            return method.apply { isAccessible = true }.invoke(receiver, *args)
        }

        private fun number(function: Any): Float = (function(function) as Number).toFloat()
    }
}
