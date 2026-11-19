package com.qimian233.ztool.hook.modules.systemui.keyguard

internal data class OwnerInfoQuoteConfig(
    val userId: Int,
    val apiUrl: String,
    val regex: String,
    val deviceOwner: Boolean
)

internal data class OwnerInfoQuoteRequest(val config: OwnerInfoQuoteConfig, val generation: Long)

internal data class OwnerInfoQuoteRead(val content: String?, val request: OwnerInfoQuoteRequest?)

internal class OwnerInfoQuoteCache {
    private var config: OwnerInfoQuoteConfig? = null
    private var generation = 0L
    private var content: String? = null
    private var screenSessionId = 1L
    private var screenOffPending = false
    private var lastAttemptedSession: Long? = null
    private var lastAttempt: Long? = null
    private var inFlight: OwnerInfoQuoteRequest? = null

    @Synchronized
    fun onScreenOff() {
        screenOffPending = true
    }

    @Synchronized
    fun onScreenOn(now: Long) {
        advanceSessionIfNeeded(now)
    }

    @Synchronized
    fun read(
        config: OwnerInfoQuoteConfig,
        now: Long,
        isInteractive: Boolean = true
    ): OwnerInfoQuoteRead {
        select(config)
        if (!isInteractive) {
            screenOffPending = true
            return OwnerInfoQuoteRead(content, null)
        }
        advanceSessionIfNeeded(now)
        val request = if (inFlight == null && lastAttemptedSession != screenSessionId) {
            if (isDebounced(now)) {
                lastAttemptedSession = screenSessionId
                null
            } else {
                OwnerInfoQuoteRequest(config, generation).also {
                    inFlight = it
                    lastAttemptedSession = screenSessionId
                    lastAttempt = now
                }
            }
        } else null
        return OwnerInfoQuoteRead(content, request)
    }

    @Synchronized
    fun complete(
        request: OwnerInfoQuoteRequest,
        result: String?,
        now: Long,
        currentConfig: OwnerInfoQuoteConfig?
    ): Boolean {
        if (inFlight != request) return false
        select(currentConfig)
        inFlight = null
        if (request.generation != generation || request.config != config) return false
        lastAttempt = now
        if (!screenOffPending) {
            lastAttemptedSession = screenSessionId
        }
        if (result.isNullOrBlank() || result == content) return false
        content = result
        return true
    }

    private fun advanceSessionIfNeeded(now: Long) {
        if (!screenOffPending) return
        screenOffPending = false
        screenSessionId++
        if (inFlight == null && isDebounced(now)) {
            lastAttemptedSession = screenSessionId
        }
    }

    private fun isDebounced(now: Long): Boolean {
        val attempt = lastAttempt ?: return false
        return now - attempt < DEBOUNCE_INTERVAL_MS
    }

    private fun select(next: OwnerInfoQuoteConfig?) {
        if (config == next) return
        config = next
        generation++
        content = null
        lastAttemptedSession = null
        lastAttempt = null
    }

    private companion object {
        // Debounce rapid screen off/on toggles without retrying periodically during a single wake.
        const val DEBOUNCE_INTERVAL_MS = 5_000L
    }
}
