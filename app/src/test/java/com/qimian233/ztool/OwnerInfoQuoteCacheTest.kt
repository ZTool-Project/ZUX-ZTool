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
    fun refreshAndNetworkFailureKeepTheLastValidQuoteAcrossWakes() {
        val cache = OwnerInfoQuoteCache()
        cache.complete(cache.read(config, 0).request!!, "old", 100, config)
        assertNull(cache.read(config, 60_100).request)
        cache.onScreenOff()
        cache.onScreenOn(5_100)
        val refresh = cache.read(config, 5_101)
        assertEquals("old", refresh.content)
        assertFalse(cache.complete(refresh.request!!, null, 5_200, config))
        assertEquals("old", cache.read(config, 65_200).content)
        assertNull(cache.read(config, 65_200).request)
        cache.onScreenOff()
        cache.onScreenOn(10_200)
        assertNotNull(cache.read(config, 10_201).request)
    }

    @Test
    fun emptyFailureRetriesOnlyOnNextEligibleWakeWithoutCachingAnErrorMessage() {
        val cache = OwnerInfoQuoteCache()
        assertFalse(cache.complete(cache.read(config, 0).request!!, "  ", 100, config))
        assertNull(cache.read(config, 101).content)
        assertNull(cache.read(config, 101).request)
        assertNull(cache.read(config, 60_100).request)
        cache.onScreenOff()
        cache.onScreenOn(5_100)
        assertNotNull(cache.read(config, 5_101).request)
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
        cache.onScreenOff()
        cache.onScreenOn(5_100)
        val next = cache.read(config, 5_101).request!!
        assertFalse(cache.complete(next, "same", 5_200, config))
        assertEquals("same", cache.read(config, 5_201).content)
    }

    @Test
    fun singleWakeNeverLaunchesAdditionalRequestsOverLongDurations() {
        val cache = OwnerInfoQuoteCache()
        val request = cache.read(config, 0).request!!
        assertNull(cache.read(config, 60_000).request)
        assertTrue(cache.complete(request, "first", 100, config))
        for (time in listOf(60_000L, 120_000L, 600_000L)) {
            val read = cache.read(config, time)
            assertEquals("first", read.content)
            assertNull(read.request)
        }
    }

    @Test
    fun interactiveReadBeforeScreenOnBroadcastAdvancesOnceWithoutDuplicate() {
        val cache = OwnerInfoQuoteCache()
        assertTrue(cache.complete(cache.read(config, 0).request!!, "first", 100, config))
        cache.onScreenOff()
        val second = cache.read(config, 5_100, isInteractive = true)
        assertEquals("first", second.content)
        assertNotNull(second.request)
        cache.onScreenOn(5_120)
        assertNull(cache.read(config, 5_121).request)
        assertTrue(cache.complete(second.request!!, "second", 5_300, config))
        assertNull(cache.read(config, 5_301).request)
    }

    @Test
    fun rapidScreenToggleWithinDebounceIsSuppressedAndDoesNotFireLaterInSameWake() {
        val cacheA = OwnerInfoQuoteCache()
        assertTrue(cacheA.complete(cacheA.read(config, 0).request!!, "first", 100, config))
        cacheA.onScreenOff()
        cacheA.onScreenOn(5_099)
        assertNull(cacheA.read(config, 5_100).request)
        assertNull(cacheA.read(config, 65_100).request)
        cacheA.onScreenOff()
        cacheA.onScreenOn(66_000)
        assertNotNull(cacheA.read(config, 66_001).request)

        val cacheB = OwnerInfoQuoteCache()
        assertTrue(cacheB.complete(cacheB.read(config, 0).request!!, "first", 100, config))
        cacheB.onScreenOff()
        assertNull(cacheB.read(config, 5_099, isInteractive = true).request)
        cacheB.onScreenOn(5_120)
        assertNull(cacheB.read(config, 65_120).request)
        cacheB.onScreenOff()
        assertNotNull(cacheB.read(config, 66_000, isInteractive = true).request)
    }

    @Test
    fun nonInteractiveReadsReturnCacheWithoutRequestAndArmNextWake() {
        val cache = OwnerInfoQuoteCache()
        assertTrue(cache.complete(cache.read(config, 0).request!!, "cached", 100, config))
        val nonInteractive = cache.read(config, 6_000, isInteractive = false)
        assertEquals("cached", nonInteractive.content)
        assertNull(nonInteractive.request)
        val nextWake = cache.read(config, 6_100, isInteractive = true)
        assertEquals("cached", nextWake.content)
        assertNotNull(nextWake.request)
    }

    @Test
    fun inFlightCompletionAcrossScreenOffRespectsNextWake() {
        val cacheDuringOff = OwnerInfoQuoteCache()
        val reqOff = cacheDuringOff.read(config, 0).request!!
        cacheDuringOff.onScreenOff()
        assertTrue(cacheDuringOff.complete(reqOff, "q1", 1_000, config))
        cacheDuringOff.onScreenOn(6_000)
        assertNotNull(cacheDuringOff.read(config, 6_001).request)

        val cacheAfterWake = OwnerInfoQuoteCache()
        val reqWake = cacheAfterWake.read(config, 0).request!!
        cacheAfterWake.onScreenOff()
        cacheAfterWake.onScreenOn(6_000)
        assertNull(cacheAfterWake.read(config, 6_001).request)
        assertTrue(cacheAfterWake.complete(reqWake, "q1", 6_200, config))
        val afterCompletion = cacheAfterWake.read(config, 6_201)
        assertEquals("q1", afterCompletion.content)
        assertNull(afterCompletion.request)
        assertNull(cacheAfterWake.read(config, 66_200).request)
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
