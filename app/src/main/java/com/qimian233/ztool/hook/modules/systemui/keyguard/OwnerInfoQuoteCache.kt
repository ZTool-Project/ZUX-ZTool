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
    private var lastAttempt: Long? = null
    private var inFlight: OwnerInfoQuoteRequest? = null

    @Synchronized
    fun read(config: OwnerInfoQuoteConfig, now: Long): OwnerInfoQuoteRead {
        select(config)
        val attempt = lastAttempt
        val request = if (inFlight == null &&
            (attempt == null || now - attempt >= REFRESH_INTERVAL_MS)
        ) {
            OwnerInfoQuoteRequest(config, generation).also {
                inFlight = it
                lastAttempt = now
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
        if (result.isNullOrBlank() || result == content) return false
        content = result
        return true
    }

    private fun select(next: OwnerInfoQuoteConfig?) {
        if (config == next) return
        config = next
        generation++
        content = null
        lastAttempt = null
    }

    private companion object {
        // Minute ticks already refresh native indications; bound HTTP traffic to that cadence.
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}
