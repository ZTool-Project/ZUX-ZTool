package com.qimian233.ztool.data.hotreload

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What triggered a module hot reload inside ZTool. */
enum class HotReloadTrigger {
    /** User-triggered hot reload from inside ZTool. */
    MANUAL
}

/**
 * Record of an in-app hot reload request.
 *
 * @param recordedAtElapsedRealtime Monotonic timestamp from [SystemClock.elapsedRealtime].
 */
data class HotReloadNotice(
    val trigger: HotReloadTrigger,
    val recordedAtElapsedRealtime: Long
)

/** Process-scoped channel delivering hot reload notices to the home card. */
object HotReloadNoticeBus {

    private val _pending = MutableStateFlow<HotReloadNotice?>(null)

    /** Pending hot reload notice, or null after consumption. */
    val pending: StateFlow<HotReloadNotice?> = _pending.asStateFlow()

    internal fun consume() {
        _pending.value = null
    }

    /** Publishes a hot reload notice for the home card. */
    fun publish(trigger: HotReloadTrigger) {
        _pending.value = HotReloadNotice(trigger, SystemClock.elapsedRealtime())
    }
}
