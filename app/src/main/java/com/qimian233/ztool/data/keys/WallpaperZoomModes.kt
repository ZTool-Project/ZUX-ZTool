package com.qimian233.ztool.data.keys

/** Shared mode constants and overshoot encoding helpers for wallpaper zoom hooks. */
object WallpaperZoomModes {
    const val CONTINUOUS = "continuous"
    const val CONTINUOUS_BOUNCE = "continuous_bounce"
    const val GESTURE_START = "gesture_start"
    const val DISABLE = "disable"
    const val OVERSHOOT_ENCODING_SCALE = 2.0f

    val entries: List<String> = listOf(CONTINUOUS, CONTINUOUS_BOUNCE, GESTURE_START, DISABLE)

    fun normalize(raw: String?): String = if (raw in entries) raw!! else CONTINUOUS

    fun isContinuous(mode: String?): Boolean = mode == CONTINUOUS || mode == CONTINUOUS_BOUNCE

    fun encodeProgress(progress: Float): Float =
        (progress / OVERSHOOT_ENCODING_SCALE).coerceIn(0f, 1f)

    fun decodeProgress(encoded: Float): Float =
        encoded.coerceIn(0f, 1f) * OVERSHOOT_ENCODING_SCALE
}
