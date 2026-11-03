package com.qimian233.ztool.data.update

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the release Atom feed through the first reachable mirror; see docs/research/update-delivery-model.md. */
class ReleaseFeedClient(
    private val feedUrls: List<String> = FEED_URLS,
    private val fetch: (String) -> String = ::fetchText
) {
    fun load(): List<ReleaseEntry> {
        var lastFailure: Exception? = null
        for (url in feedUrls) {
            try {
                // An empty result is a successful read: the feed may legitimately contain only
                // pre-migration tags until the first v* release is published.
                return ReleaseFeedParser.parse(fetch(url))
            } catch (e: Exception) {
                lastFailure = e
                Log.w(TAG, "Release feed failed via $url: ${e.message}")
            }
        }
        throw lastFailure ?: IOException("No release feed URL configured")
    }

    companion object {
        private const val TAG = "ReleaseFeedClient"

        private val FEED_URLS = listOf(
            "https://github.com/ZTool-Project/ZUX-ZTool/releases.atom",
            "https://gh-proxy.com/https://github.com/ZTool-Project/ZUX-ZTool/releases.atom",
            "https://gh-proxy.cc/https://github.com/ZTool-Project/ZUX-ZTool/releases.atom"
        )

        private fun fetchText(url: String): String {
            val cacheBusted = if (url.contains("?")) "$url&_t=${System.currentTimeMillis()}" else "$url?_t=${System.currentTimeMillis()}"
            val connection = URL(cacheBusted).openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                connection.setRequestProperty("User-Agent", "ZTool-UpdateCheck")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $responseCode")
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
