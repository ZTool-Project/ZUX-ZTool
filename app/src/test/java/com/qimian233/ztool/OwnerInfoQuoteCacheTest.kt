package com.qimian233.ztool

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.hook.modules.systemui.keyguard.OwnerInfoQuoteCache
import com.qimian233.ztool.hook.modules.systemui.keyguard.OwnerInfoQuoteConfig
import com.qimian233.ztool.hook.modules.systemui.keyguard.OwnerInfoQuoteRead
import com.qimian233.ztool.hook.modules.systemui.keyguard.parseOwnerInfoQuote
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerInfoQuoteCacheTest {
    private val config = OwnerInfoQuoteConfig(0, "https://example.test/quote",
        PreferenceKeys.REGULAR.default, false)

    @Test
    fun firstReadFallsBackAndConcurrentReadsShareOneRequest() {
        val cache = OwnerInfoQuoteCache()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map {
                executor.submit<OwnerInfoQuoteRead> {
                    start.await()
                    cache.read(config, 0)
                }
            }
            start.countDown()
            val reads = results.map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(reads.all { it.content == null })
            assertEquals(1, reads.count { it.request != null })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun completionRefreshReadsCacheWithoutLaunchingAnotherRequest() {
        val cache = OwnerInfoQuoteCache()
        val request = cache.read(config, 0).request!!
        assertTrue(cache.complete(request, "句子", 200, config))
        val read = cache.read(config, 200)
        assertEquals("句子", read.content)
        assertNull(read.request)
    }

    @Test
    fun refreshAndNetworkFailureKeepTheLastValidQuote() {
        val cache = OwnerInfoQuoteCache()
        cache.complete(cache.read(config, 0).request!!, "old", 100, config)
        val refresh = cache.read(config, 60_100)
        assertEquals("old", refresh.content)
        assertFalse(cache.complete(refresh.request!!, null, 60_200, config))
        assertEquals("old", cache.read(config, 60_201).content)
        assertNull(cache.read(config, 120_199).request)
        assertNotNull(cache.read(config, 120_200).request)
    }

    @Test
    fun emptyFailureRetriesAfterCooldownWithoutCachingAnErrorMessage() {
        val cache = OwnerInfoQuoteCache()
        assertFalse(cache.complete(cache.read(config, 0).request!!, "  ", 100, config))
        assertNull(cache.read(config, 101).content)
        assertNull(cache.read(config, 101).request)
        assertNotNull(cache.read(config, 60_100).request)
    }

    @Test
    fun switchingUsersRejectsAnOldResponseAndStillAllowsOnlyOneRequest() {
        val cache = OwnerInfoQuoteCache()
        val old = cache.read(config, 0).request!!
        val next = config.copy(userId = 10)
        val pending = cache.read(next, 1)
        assertNull(pending.content)
        assertNull(pending.request)
        assertFalse(cache.complete(old, "wrong user", 100, next))
        assertNotNull(cache.read(next, 101).request)
    }

    @Test
    fun configurationChangesAndDisablementDiscardPendingContent() {
        for (next in listOf(config.copy(apiUrl = "https://other.test"),
            config.copy(regex = "(other)"), config.copy(deviceOwner = true), null)) {
            val cache = OwnerInfoQuoteCache()
            val request = cache.read(config, 0).request!!
            assertFalse(cache.complete(request, "obsolete", 100, next))
            assertNull(cache.read(next ?: config, 101).content)
        }
    }

    @Test
    fun returningToTheSameConfigurationDoesNotResurrectAnOldRequest() {
        val cache = OwnerInfoQuoteCache()
        val request = cache.read(config, 0).request!!
        cache.read(config.copy(userId = 10), 1)
        cache.read(config, 2)
        assertFalse(cache.complete(request, "obsolete", 100, config))
        assertNull(cache.read(config, 101).content)
    }

    @Test
    fun duplicateOrUnchangedCompletionsDoNotCauseRefreshLoops() {
        val cache = OwnerInfoQuoteCache()
        val request = cache.read(config, 0).request!!
        assertTrue(cache.complete(request, "same", 100, config))
        assertFalse(cache.complete(request, "different", 101, config))
        assertFalse(cache.complete(request, null, 102, null))
        assertEquals("same", cache.read(config, 103).content)
        val next = cache.read(config, 60_100).request!!
        assertFalse(cache.complete(next, "same", 60_200, config))
        assertEquals("same", cache.read(config, 60_201).content)
    }

    @Test
    fun defaultPatternDecodesChineseUnicodeAndJsonEscapes() {
        val json = """{"content":"\u4f60\u597d\n\"quoted\" \\ \/ \ud83d\ude00"}"""
        assertEquals("你好\n\"quoted\" \\ / 😀", parseOwnerInfoQuote(json, config.regex))
        assertEquals("中文", parseOwnerInfoQuote("""{"content":"中文"}""", config.regex))
    }

    @Test
    fun unmatchedEmptyAndMalformedResponsesNeverBecomeSignatures() {
        assertNull(parseOwnerInfoQuote("""{"error":"offline"}""", config.regex))
        assertNull(parseOwnerInfoQuote("""{"content":""}""", config.regex))
        assertNull(parseOwnerInfoQuote("""{"content":"\uZZZZ"}""", config.regex))
        assertNull(parseOwnerInfoQuote("""{"content":"\q"}""", config.regex))
        assertNull(parseOwnerInfoQuote("quote", "quote"))
    }

    @Test
    fun customPatternStillSelectsTheFirstCaptureGroup() {
        assertEquals("custom", parseOwnerInfoQuote("value:custom", "value:(.*)"))
    }
}
