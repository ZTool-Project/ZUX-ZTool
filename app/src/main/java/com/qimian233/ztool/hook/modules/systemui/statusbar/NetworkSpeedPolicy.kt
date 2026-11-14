package com.qimian233.ztool.hook.modules.systemui.statusbar

import java.text.DecimalFormat

internal data class NetworkSpeedOptions(
    val size: Boolean = false,
    val doubleLayer: Boolean = false,
    val customRefresh: Boolean = false,
    val hideSlow: Boolean = false,
    val intervalSeconds: Float = 3f,
    val thresholdKb: Float = 10f,
    val hideBoth: Boolean = false,
) {
    val enabled: Boolean get() = size || needsHandler
    val needsHandler: Boolean get() = doubleLayer || customRefresh || hideSlow
    val needsText: Boolean get() = size || doubleLayer
    val intervalMs: Long get() = if (customRefresh) {
        (intervalSeconds.takeIf { it.isFinite() } ?: 3f)
            .toDouble().times(1000).toLong().coerceIn(500L, 60_000L)
    } else 3000L

    fun isSlow(rate: NetworkSpeedRate): Boolean {
        val threshold = (thresholdKb.takeIf { it.isFinite() } ?: 10f).coerceAtLeast(0f) * 1024.0
        return hideSlow && rate.down < threshold && (!hideBoth || rate.up < threshold)
    }
}

internal data class NetworkSpeedSnapshot(val rx: Long, val tx: Long, val atMs: Long) {
    val total: Long get() = rx + tx
}

internal data class NetworkSpeedRate(val down: Double, val up: Double) {
    val nativeRate: Int get() = (down + up).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()

    companion object {
        val ZERO = NetworkSpeedRate(0.0, 0.0)
    }
}

internal data class NetworkSpeedFrame(
    val generation: Long,
    val snapshot: NetworkSpeedSnapshot?,
    val rate: NetworkSpeedRate?,
    val warmup: Boolean,
)

internal class NetworkSpeedSession {
    var generation = 0L
        private set
    var lastRate: NetworkSpeedRate? = null
        private set
    var latestFrame: NetworkSpeedFrame? = null
        private set
    var slow = false
    private var baseline: NetworkSpeedSnapshot? = null

    fun reset() {
        generation++
        baseline = null
        lastRate = null
        latestFrame = null
    }

    fun update(snapshot: NetworkSpeedSnapshot?, options: NetworkSpeedOptions): NetworkSpeedFrame {
        if (!options.hideSlow) slow = false
        val frame = sample(snapshot)
        latestFrame = frame
        accept(frame, options)
        return frame
    }

    fun sample(snapshot: NetworkSpeedSnapshot?): NetworkSpeedFrame {
        val previous = baseline
        baseline = snapshot
        if (snapshot == null) return NetworkSpeedFrame(generation, null, null, false)
        if (previous == null) return NetworkSpeedFrame(generation, snapshot, NetworkSpeedRate.ZERO, true)
        val elapsed = snapshot.atMs - previous.atMs
        val rx = snapshot.rx - previous.rx
        val tx = snapshot.tx - previous.tx
        val rate = if (elapsed > 0 && rx >= 0 && tx >= 0) {
            NetworkSpeedRate(rx.toDouble() * 1000 / elapsed, tx.toDouble() * 1000 / elapsed)
        } else null
        return NetworkSpeedFrame(generation, snapshot, rate, false)
    }

    fun accept(frame: NetworkSpeedFrame, options: NetworkSpeedOptions): Boolean {
        if (frame.generation != generation) return false
        val rate = frame.rate ?: return false
        if (!frame.warmup) {
            lastRate = rate
            slow = options.isSlow(rate)
        }
        return true
    }
}

internal data class NetworkSpeedLabel(val number: String, val unit: String)

internal data class NetworkSpeedSegment(val start: Int, val end: Int, val scale: Float)
internal data class NetworkSpeedText(val value: String, val segments: List<NetworkSpeedSegment>)

internal fun doubleLayerSpeedText(rate: NetworkSpeedRate): NetworkSpeedText {
    val segments = mutableListOf<NetworkSpeedSegment>()
    val value = buildString {
        listOf("▴ " to rate.up, "▾ " to rate.down).forEachIndexed { index, (arrow, speed) ->
            if (index > 0) append('\n')
            append(arrow)
            val label = networkSpeedLabel(speed)
            val start = length
            append(label.number)
            segments += NetworkSpeedSegment(start, length, 1.3f)
            append(' ')
            val unitStart = length
            append(label.unit)
            segments += NetworkSpeedSegment(unitStart, length, 0.9f)
        }
    }
    return NetworkSpeedText(value, segments)
}

internal fun networkSpeedLabel(bytesPerSecond: Double): NetworkSpeedLabel {
    val divisor: Double
    val unit: String
    when {
        bytesPerSecond >= 1_073_741_824 -> {
            divisor = 1_073_741_824.0
            unit = "G/s"
        }
        bytesPerSecond >= 1_048_576 -> {
            divisor = 1_048_576.0
            unit = "M/s"
        }
        bytesPerSecond >= 1024 -> {
            divisor = 1024.0
            unit = "K/s"
        }
        else -> {
            divisor = 1.0
            unit = "B/s"
        }
    }
    val scaled = bytesPerSecond.coerceAtLeast(0.0) / divisor
    val pattern = when {
        scaled >= 100 -> "0"
        scaled >= 10 -> "0.0"
        else -> "0.00"
    }
    return NetworkSpeedLabel(DecimalFormat(pattern).format(scaled), unit)
}
