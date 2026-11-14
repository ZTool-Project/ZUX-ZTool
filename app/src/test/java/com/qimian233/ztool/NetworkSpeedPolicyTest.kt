package com.qimian233.ztool

import com.qimian233.ztool.hook.modules.systemui.statusbar.NetworkSpeedOptions
import com.qimian233.ztool.hook.modules.systemui.statusbar.NetworkSpeedRate
import com.qimian233.ztool.hook.modules.systemui.statusbar.NetworkSpeedSession
import com.qimian233.ztool.hook.modules.systemui.statusbar.NetworkSpeedSnapshot
import com.qimian233.ztool.hook.modules.systemui.statusbar.networkSpeedLabel
import com.qimian233.ztool.hook.modules.systemui.statusbar.doubleLayerSpeedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSpeedPolicyTest {
    @Test
    fun fractionalSecondSamplingUsesTheActualWindow() {
        val session = NetworkSpeedSession()
        session.sample(NetworkSpeedSnapshot(1000, 2000, 100))
        val rate = session.sample(NetworkSpeedSnapshot(1512, 2256, 600)).rate!!
        assertEquals(1024.0, rate.down, 0.0)
        assertEquals(512.0, rate.up, 0.0)
        assertEquals(1536, rate.nativeRate)
    }

    @Test
    fun delayedLooperUsesElapsedTimeRatherThanRequestedInterval() {
        val session = NetworkSpeedSession()
        session.sample(NetworkSpeedSnapshot(0, 0, 100))
        val rate = session.sample(NetworkSpeedSnapshot(9000, 3000, 6100)).rate!!
        assertEquals(1500.0, rate.down, 0.0)
        assertEquals(500.0, rate.up, 0.0)
    }

    @Test
    fun largeByteDeltasDoNotOverflowBeforeDivision() {
        val session = NetworkSpeedSession()
        session.sample(NetworkSpeedSnapshot(0, 0, 100))
        val rate = session.sample(NetworkSpeedSnapshot(6_000_000_000, 3_000_000_000, 3100)).rate!!
        assertEquals(2_000_000_000.0, rate.down, 0.0)
        assertEquals(Int.MAX_VALUE, rate.nativeRate)
    }

    @Test
    fun unavailableCountersDoNotInventASlowDecision() {
        val session = NetworkSpeedSession()
        session.sample(NetworkSpeedSnapshot(0, 0, 100))
        session.accept(session.sample(NetworkSpeedSnapshot(100, 0, 1100)), NetworkSpeedOptions(hideSlow = true))
        assertTrue(session.slow)
        val unavailable = session.sample(null)
        assertFalse(session.accept(unavailable, NetworkSpeedOptions(hideSlow = true)))
        assertTrue(session.slow)
        val recovered = session.sample(NetworkSpeedSnapshot(10_000_000, 0, 2100))
        assertTrue(recovered.warmup)
        assertEquals(NetworkSpeedRate.ZERO, recovered.rate)
    }

    @Test
    fun resetCountersAndInvalidClockRebaseWithoutNegativeRates() {
        val session = NetworkSpeedSession()
        session.sample(NetworkSpeedSnapshot(1000, 1000, 100))
        assertNull(session.sample(NetworkSpeedSnapshot(500, 500, 600)).rate)
        assertNull(session.sample(NetworkSpeedSnapshot(600, 600, 600)).rate)
        assertEquals(200.0, session.sample(NetworkSpeedSnapshot(700, 700, 1100)).rate!!.down, 0.0)
    }

    @Test
    fun lifecycleResetRejectsQueuedFramesFromTheOldGeneration() {
        val session = NetworkSpeedSession()
        session.sample(NetworkSpeedSnapshot(0, 0, 100))
        val queued = session.sample(NetworkSpeedSnapshot(500, 0, 600))
        session.reset()
        assertFalse(session.accept(queued, NetworkSpeedOptions(hideSlow = true)))
        assertNull(session.lastRate)
        assertTrue(session.sample(NetworkSpeedSnapshot(1000, 0, 1100)).warmup)
    }

    @Test
    fun warmupDoesNotHideTheIndicator() {
        val session = NetworkSpeedSession()
        assertTrue(session.accept(session.sample(NetworkSpeedSnapshot(0, 0, 100)), NetworkSpeedOptions(hideSlow = true)))
        assertFalse(session.slow)
    }

    @Test
    fun uploadOnlyTrafficRespectsBothDirectionPolicyInEitherDisplayMode() {
        for (doubleLayer in listOf(false, true)) {
            val options = NetworkSpeedOptions(doubleLayer = doubleLayer, hideSlow = true, thresholdKb = 10f)
            val upload = NetworkSpeedRate(0.0, 20_480.0)
            assertTrue(options.isSlow(upload))
            assertFalse(options.copy(hideBoth = true).isSlow(upload))
        }
    }

    @Test
    fun thresholdsAreStrictAndZeroNeverHidesValidRates() {
        val options = NetworkSpeedOptions(hideSlow = true, thresholdKb = 10f)
        assertFalse(options.isSlow(NetworkSpeedRate(10_240.0, 0.0)))
        assertTrue(options.isSlow(NetworkSpeedRate(10_239.0, 0.0)))
        assertFalse(options.copy(thresholdKb = 0f).isSlow(NetworkSpeedRate.ZERO))
        assertFalse(options.copy(hideSlow = false).isSlow(NetworkSpeedRate.ZERO))
    }

    @Test
    fun storedIntervalDoesNotActivateCustomRefreshForDoubleLayer() {
        val options = NetworkSpeedOptions(doubleLayer = true, intervalSeconds = 0.5f)
        assertEquals(3000L, options.intervalMs)
        assertEquals(500L, options.copy(customRefresh = true).intervalMs)
    }

    @Test
    fun invalidIntervalsFallbackAndOutOfRangeValuesClamp() {
        val options = NetworkSpeedOptions(customRefresh = true)
        assertEquals(3000L, options.copy(intervalSeconds = Float.NaN).intervalMs)
        assertEquals(3000L, options.copy(intervalSeconds = Float.POSITIVE_INFINITY).intervalMs)
        assertEquals(500L, options.copy(intervalSeconds = -1f).intervalMs)
        assertEquals(60_000L, options.copy(intervalSeconds = 100f).intervalMs)
    }

    @Test
    fun parameterKeysAndSizeOnlyDoNotActivateTheHandler() {
        val parameters = NetworkSpeedOptions(hideBoth = true, thresholdKb = 5f, intervalSeconds = 0.5f)
        assertFalse(parameters.enabled)
        val size = parameters.copy(size = true)
        assertTrue(size.enabled)
        assertTrue(size.needsText)
        assertFalse(size.needsHandler)
    }

    @Test
    fun doubleLayerUnitsAndPrecisionKeepTheExistingBoundaries() {
        assertEquals("B/s", networkSpeedLabel(1023.0).unit)
        assertEquals("K/s", networkSpeedLabel(1024.0).unit)
        assertEquals("M/s", networkSpeedLabel(1_048_576.0).unit)
        assertEquals("G/s", networkSpeedLabel(1_073_741_824.0).unit)
        assertEquals("0.00", networkSpeedLabel(0.0).number)
        assertEquals("10.0", networkSpeedLabel(10_240.0).number)
        assertEquals("100", networkSpeedLabel(102_400.0).number)
    }

    @Test
    fun doubleLayerSizeSegmentsCoverNumbersAndUnitsOnBothLines() {
        val formatted = doubleLayerSpeedText(NetworkSpeedRate(1024.0, 1_048_576.0))
        assertEquals("▴ 1.00 M/s\n▾ 1.00 K/s", formatted.value)
        assertEquals(listOf("1.00", "M/s", "1.00", "K/s"), formatted.segments.map {
            formatted.value.substring(it.start, it.end)
        })
        assertEquals(listOf(1.3f, 0.9f, 1.3f, 0.9f), formatted.segments.map { it.scale })
    }

    @Test
    fun samplingRecoversFromSlowTrafficWithoutAcceptingADisplayMessage() {
        for (doubleLayer in listOf(false, true)) {
            val options = NetworkSpeedOptions(hideSlow = true, doubleLayer = doubleLayer)
            val session = NetworkSpeedSession()
            session.update(NetworkSpeedSnapshot(0, 0, 100), options)
            session.update(NetworkSpeedSnapshot(0, 0, 600), options)
            assertTrue(session.slow)
            val recovered = session.update(NetworkSpeedSnapshot(10_240, 0, 1100), options)
            assertFalse(session.slow)
            assertEquals(20_480.0, session.lastRate!!.down, 0.0)
            assertEquals(recovered, session.latestFrame)
            session.update(NetworkSpeedSnapshot(10_240, 0, 1600), options)
            assertTrue(session.slow)
        }
    }

    @Test
    fun disablingHideSlowRecoversAtTheNextSample() {
        val options = NetworkSpeedOptions(hideSlow = true, customRefresh = true)
        val session = NetworkSpeedSession()
        session.update(NetworkSpeedSnapshot(0, 0, 100), options)
        session.update(NetworkSpeedSnapshot(0, 0, 600), options)
        assertTrue(session.slow)
        session.update(null, options.copy(hideSlow = false))
        assertFalse(session.slow)
        session.reset()
        assertNull(session.latestFrame)
    }
}
