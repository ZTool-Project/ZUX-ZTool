package com.qimian233.ztool.hook.modules.systemui.statusbar

import android.annotation.SuppressLint
import android.net.TrafficStats
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Shares the host network-speed message pipeline across all network-speed settings. */
// See docs/research/network_speed_refactor_plan.md.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class SystemUINetworkSpeedHook : AppHookModule() {
    private data class Host(
        val viewClass: Class<*>,
        val handlerClass: Class<*>,
        val ownerField: Field,
        val enabledField: Field,
        val restartField: Field?,
        val handleMessage: Method,
        val updateStatus: Method,
    )

    private data class Pending(val session: NetworkSpeedSession, val frame: NetworkSpeedFrame)
    private data class Sampling(
        val handler: Handler,
        val session: NetworkSpeedSession,
        val frame: NetworkSpeedFrame,
        val options: NetworkSpeedOptions,
    )
    private data class Display(
        val view: TextView,
        val options: NetworkSpeedOptions,
        val rate: NetworkSpeedRate?,
    )

    private class SpeedSpan(scale: Float) : RelativeSizeSpan(scale)

    private val sessions = Collections.synchronizedMap(WeakHashMap<TextView, NetworkSpeedSession>())
    private val pending = Collections.synchronizedMap(WeakHashMap<Message, Pending>())
    private val sampling = ThreadLocal<Sampling>()
    private val display = ThreadLocal<Display>()
    private var installed = NetworkSpeedOptions()
    private var snapshotAt = Long.MIN_VALUE
    private var cachedSnapshot: NetworkSpeedSnapshot? = null

    override fun getModuleName(): String = PreferenceKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun isEnabled(): Boolean = readOptions().enabled

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val requested = readOptions()
        val viewClass = VIEW_CLASSES.firstNotNullOfOrNull { name ->
            runCatching { param.defaultClassLoader.loadClass(name) }.getOrNull()
        } ?: error("NetworkSpeedView not found")
        var textReady = false
        if (requested.needsText) {
            textReady = installGroup("text") { handles -> installText(viewClass, handles) }
        }
        var handlerReady = false
        var refreshReady = false
        var hideReady = false
        if (requested.needsHandler) {
            val host = runCatching { resolveHost(viewClass, param.defaultClassLoader) }
                .onFailure { logger.error("Network speed handler resolution failed", it) }.getOrNull()
            if (host != null) handlerReady = installGroup("handler") { handles -> installPipeline(host, handles) }
            if (handlerReady && host != null) {
                installGroup("counter cache") { handles ->
                    handles += hookWithId(viewClass.getDeclaredMethod("getCurrentTotalBytes"), "network_speed_total") { chain ->
                        sampling.get()?.frame?.snapshot?.total ?: chain.proceed()
                    }
                }
                if (requested.customRefresh) {
                    refreshReady = installGroup("refresh") { handles -> installRefresh(host, handles) }
                }
                if (requested.hideSlow) {
                    hideReady = installGroup("hide slow") { handles -> installVisibility(host, handles) }
                }
            }
        }
        installed = requested.copy(
            size = requested.size && textReady,
            doubleLayer = requested.doubleLayer && textReady && handlerReady,
            customRefresh = requested.customRefresh && refreshReady,
            hideSlow = requested.hideSlow && hideReady,
        )
        logger.info("Network speed installed: size=${installed.size}, doubleLayer=${installed.doubleLayer}, " +
            "refresh=${installed.customRefresh}, hideSlow=${installed.hideSlow}")
    }

    private fun installGroup(name: String, install: (MutableList<XposedInterface.HookHandle>) -> Unit): Boolean {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        return try {
            install(handles)
            true
        } catch (failure: Throwable) {
            handles.forEach { runCatching { it.unhook() } }
            logger.error("Network speed $name hooks unavailable", failure)
            false
        }
    }

    private fun resolveHost(viewClass: Class<*>, loader: ClassLoader): Host {
        val indexed = DexIndexStore.string(
            xposed, ScopeKeys.SYSTEM_UI.packageName,
            DexIndexConstants.ModuleKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER,
            DexIndexConstants.Keys.HANDLER_INNER_CLASS,
        )
        val candidates = buildList {
            if (indexed != null) runCatching { loader.loadClass(indexed) }.getOrNull()?.let(::add)
            viewClass.declaredFields.forEach { field ->
                runCatching { field.type }.getOrNull()?.let(::add)
            }
            for (index in 1..10) {
                runCatching { loader.loadClass("${viewClass.name}$$index") }.getOrNull()?.let(::add)
            }
        }
        val handlerClass = candidates.distinct().firstOrNull { candidate ->
            Handler::class.java.isAssignableFrom(candidate) && runCatching {
                candidate.getDeclaredMethod("handleMessage", Message::class.java)
                viewClass.isAssignableFrom(candidate.getDeclaredField($$"this$0").type)
            }.getOrDefault(false)
        } ?: error("NetworkSpeedView Handler not found")
        return Host(
            viewClass, handlerClass,
            handlerClass.getDeclaredField($$"this$0").apply { isAccessible = true },
            findField(viewClass, "mShowNetworkRealtimeSpeedState"),
            runCatching { findField(viewClass, "mIsScreenOff") }.getOrNull(),
            handlerClass.getDeclaredMethod("handleMessage", Message::class.java),
            viewClass.getDeclaredMethod("updateNetworkSpeedViewStatus"),
        )
    }

    private fun installPipeline(host: Host, handles: MutableList<XposedInterface.HookHandle>) {
        val sendMessage = findMethod(host.handlerClass, "sendMessage", Message::class.java)
        val detach = host.viewClass.getDeclaredMethod("onDetachedFromWindow")
        handles += hookWithId(sendMessage, "network_speed_send_rate") { chain ->
            val context = sampling.get()
            val message = chain.args[0] as Message
            if (context == null || context.handler !== chain.thisObject || message.what != RATE) {
                return@hookWithId chain.proceed()
            }
            pending[message] = Pending(context.session, context.frame)
            var sent = false
            try {
                chain.proceed().also { sent = it == true }
            } finally {
                if (!sent) pending.remove(message)
            }
        }
        handles += hookWithId(host.updateStatus, "network_speed_status") { chain ->
            val view = chain.thisObject as TextView
            val session = session(view)
            session.reset()
            val result = chain.proceed()
            applyVisibility(host, view, session, options())
            result
        }
        handles += hookWithId(detach, "network_speed_detach") { chain ->
            val view = chain.thisObject as TextView
            try {
                chain.proceed()
            } finally {
                sessions.remove(view)?.reset()
            }
        }
        handles += hookWithId(host.handleMessage, "network_speed_handle_message") { chain ->
            val handler = chain.thisObject as Handler
            val view = host.ownerField.get(handler) as? TextView ?: return@hookWithId chain.proceed()
            val message = chain.args[0] as Message
            val options = options()
            val session = session(view)
            if (!options.hideSlow) session.slow = false
            when (message.what) {
                SAMPLE -> {
                    if (!options.needsHandler) {
                        session.reset()
                        val result = chain.proceed()
                        applyVisibility(host, view, session, options)
                        return@hookWithId result
                    }
                    if (host.restartField?.getBoolean(view) == true) session.reset()
                    val frame = session.sample(readSnapshot())
                    val previous = sampling.get()
                    sampling.set(Sampling(handler, session, frame, options))
                    try {
                        chain.proceed()
                    } finally {
                        restore(sampling, previous)
                    }
                }
                RATE -> {
                    val queued = pending.remove(message)
                    if (queued != null && (queued.session !== session || queued.frame.generation != session.generation)) {
                        return@hookWithId null
                    }
                    val frame = queued?.frame
                    val accepted = frame != null && session.accept(frame, options)
                    val rate = if (accepted && options.needsHandler) {
                        if (frame.warmup) session.lastRate ?: NetworkSpeedRate.ZERO else frame.rate
                    } else null
                    if (rate != null) message.arg1 = rate.nativeRate
                    val previous = display.get()
                    display.set(Display(view, options, rate))
                    val result = try {
                        chain.proceed()
                    } finally {
                        restore(display, previous)
                    }
                    applyVisibility(host, view, session, options)
                    result
                }
                else -> chain.proceed()
            }
        }
    }

    private fun installRefresh(host: Host, handles: MutableList<XposedInterface.HookHandle>) {
        val method = findMethod(Handler::class.java, "sendEmptyMessageDelayed",
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        handles += hookWithId(method, "network_speed_rearm") { chain ->
            val context = sampling.get()
            if (context != null && context.handler === chain.thisObject && host.handlerClass.isInstance(chain.thisObject) &&
                chain.args[0] == SAMPLE && chain.args[1] == NATIVE_INTERVAL && context.frame.rate != null) {
                chain.proceed(arrayOf<Any>(SAMPLE, context.options.intervalMs))
            } else chain.proceed()
        }
    }

    private fun installVisibility(host: Host, handles: MutableList<XposedInterface.HookHandle>) {
        handles += hookWithId(host.viewClass.getDeclaredMethod("isIconVisible"), "network_speed_icon_visible") { chain ->
            val original = chain.proceed() as Boolean
            val view = chain.thisObject as TextView
            original && !(options().hideSlow && sessions[view]?.slow == true)
        }
    }

    private fun applyVisibility(host: Host, view: TextView, session: NetworkSpeedSession, options: NetworkSpeedOptions) {
        if (!installed.hideSlow) return
        val visible = host.enabledField.getBoolean(view) && !(options.hideSlow && session.slow)
        val target = if (visible) TextView.VISIBLE else TextView.GONE
        if (view.visibility != target) {
            view.visibility = target
            view.requestLayout()
        }
    }

    private fun installText(viewClass: Class<*>, handles: MutableList<XposedInterface.HookHandle>) {
        val setText = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
        handles += hookWithId(setText, "network_speed_set_text") { chain ->
            if (!viewClass.isInstance(chain.thisObject)) return@hookWithId chain.proceed()
            val original = chain.args[0] as? CharSequence ?: return@hookWithId chain.proceed()
            val context = display.get()?.takeIf { it.view === chain.thisObject }
            val options = context?.options ?: options()
            val rate = context?.rate ?: if (context == null && original.toString().substringBefore('\n') == "0.00") {
                NetworkSpeedRate.ZERO
            } else null
            val text = if (options.doubleLayer && rate != null) doubleLayerText(rate, options.size)
                else nativeText(original, options.size)
            chain.proceed(arrayOf<Any>(text))
        }
    }

    private fun nativeText(original: CharSequence, size: Boolean): CharSequence {
        if (!size && original !is Spanned) return original
        val text = SpannableStringBuilder(original)
        text.getSpans(0, text.length, SpeedSpan::class.java).forEach(text::removeSpan)
        val newline = text.indexOf('\n')
        if (size && newline > 0 && newline == text.lastIndexOf('\n') && newline < text.lastIndex) {
            addSpan(text, 1.3f, 0, newline)
            addSpan(text, 0.9f, newline + 1, text.length)
        }
        return text
    }

    private fun doubleLayerText(rate: NetworkSpeedRate, size: Boolean): CharSequence {
        val formatted = doubleLayerSpeedText(rate)
        val text = SpannableStringBuilder(formatted.value)
        if (size) formatted.segments.forEach { segment ->
            addSpan(text, segment.scale, segment.start, segment.end)
        }
        // Preserve the former constructor's 10% adjustment without mutating host text size.
        addSpan(text, 1.1f, 0, text.length)
        return text
    }

    private fun addSpan(text: SpannableStringBuilder, scale: Float, start: Int, end: Int) {
        text.setSpan(SpeedSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun session(view: TextView): NetworkSpeedSession = synchronized(sessions) {
        sessions.getOrPut(view) { NetworkSpeedSession() }
    }

    @Synchronized
    private fun readSnapshot(): NetworkSpeedSnapshot? {
        val now = SystemClock.elapsedRealtime()
        if (snapshotAt == now) return cachedSnapshot
        cachedSnapshot = runCatching {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val localRx = LOOPBACK_RX?.invoke(null, "lo") as? Long ?: -1L
            val localTx = LOOPBACK_TX?.invoke(null, "lo") as? Long ?: -1L
            if (rx < 0 || tx < 0 || localRx < 0 || localTx < 0 || localRx > rx || localTx > tx) null
            else NetworkSpeedSnapshot(rx - localRx, tx - localTx, now)
        }.getOrNull()
        snapshotAt = now
        return cachedSnapshot
    }

    private fun options(): NetworkSpeedOptions = readOptions().let { current ->
        current.copy(size = current.size && installed.size, doubleLayer = current.doubleLayer && installed.doubleLayer,
            customRefresh = current.customRefresh && installed.customRefresh, hideSlow = current.hideSlow && installed.hideSlow)
    }

    private fun readOptions(): NetworkSpeedOptions = runCatching {
        val prefs = remotePreferences
        NetworkSpeedOptions(
            size = prefs.getBoolean(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_SIZE.name, PreferenceKeys.SYSTEMUI_NETWORK_SPEED_SIZE.default),
            doubleLayer = prefs.getBoolean(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER.name, PreferenceKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER.default),
            customRefresh = prefs.getBoolean(PreferenceKeys.CUSTOM_NETWORK_SPEED_REFRESH_INTERVAL.name, PreferenceKeys.CUSTOM_NETWORK_SPEED_REFRESH_INTERVAL.default),
            hideSlow = prefs.getBoolean(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_HIDE_SLOW.name, PreferenceKeys.SYSTEMUI_NETWORK_SPEED_HIDE_SLOW.default),
            intervalSeconds = prefs.getFloat(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_REFRESH_INTERVAL.name, PreferenceKeys.SYSTEMUI_NETWORK_SPEED_REFRESH_INTERVAL.default),
            thresholdKb = prefs.getFloat(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_HIDE_THRESHOLD.name, PreferenceKeys.SYSTEMUI_NETWORK_SPEED_HIDE_THRESHOLD.default),
            hideBoth = prefs.getBoolean(PreferenceKeys.SYSTEMUI_NETWORK_SPEED_HIDE_BOTH.name, PreferenceKeys.SYSTEMUI_NETWORK_SPEED_HIDE_BOTH.default),
        )
    }.getOrDefault(NetworkSpeedOptions())

    private fun <T> restore(local: ThreadLocal<T>, previous: T?) {
        if (previous == null) local.remove() else local.set(previous)
    }

    private companion object {
        val VIEW_CLASSES = arrayOf("com.android.systemui.statusbar.view.NetworkSpeedView", "com.android.systemui.zui.NetworkSpeedView")
        val LOOPBACK_RX = runCatching { TrafficStats::class.java.getDeclaredMethod("getRxBytes", String::class.java) }.getOrNull()
        val LOOPBACK_TX = runCatching { TrafficStats::class.java.getDeclaredMethod("getTxBytes", String::class.java) }.getOrNull()
        const val SAMPLE = 10
        const val RATE = 1
        // Native periodic rearm; the 50 ms status-update kick must pass through.
        const val NATIVE_INTERVAL = 3000L
    }
}
