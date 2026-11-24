package com.qimian233.ztool.hook.modules.systemui.statusbar

/** User-configured options for the unified status bar and shade clock hook. */
internal data class StatusBarClockOptions(
    val displaySeconds: Boolean = false,
    val displaySecondsLongPressToggle: Boolean = false,
    val redOneHour: Boolean = false,
    val customClock: Boolean = false,
    val customFormat: String = "",
    val textSizeEnabled: Boolean = false,
    val textSizeSp: Float = 16.0f,
    val letterSpacingEnabled: Boolean = false,
    val letterSpacing: Float = 0.1f,
    val textColorEnabled: Boolean = false,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textBold: Boolean = false
) {
    val enabled: Boolean
        get() = displaySeconds || redOneHour || customClock

    val effectiveCustomFormat: String
        get() = customFormat.takeIf { it.isNotBlank() }
            ?: StatusBarClockPolicy.DEFAULT_CUSTOM_CLOCK_FORMAT

    val effectiveShowSeconds: Boolean
        get() = displaySeconds ||
            (customClock && StatusBarClockPolicy.containsSecondsPattern(effectiveCustomFormat))

    val effectiveLongPressToggleSeconds: Boolean
        get() = displaySeconds && !customClock && displaySecondsLongPressToggle

    fun effectiveShowSeconds(runtimeSecondsHidden: Boolean): Boolean =
        if (effectiveLongPressToggleSeconds) !runtimeSecondsHidden else effectiveShowSeconds

    val needsTextHook: Boolean
        get() = customClock || redOneHour

    val effectiveCustomTextColorEnabled: Boolean
        get() = customClock && textColorEnabled && !redOneHour

    val effectiveTextSizeSp: Float
        get() = textSizeSp.takeIf { it.isFinite() && it > 0f } ?: 16.0f

    val effectiveLetterSpacing: Float
        get() = letterSpacing.takeIf { it.isFinite() && it >= 0f } ?: 0.1f
}

/** Pure rules for status bar clock seconds linkage and OnePlus Red 1 hour spans. */
internal object StatusBarClockPolicy {
    // Official OnePlus brand / OxygenOS clock red ("Never Settle" red).
    const val ONEPLUS_RED_COLOR: Int = 0xFFEB0028.toInt()
    const val DEFAULT_CUSTOM_CLOCK_FORMAT: String = "HH:mm"
    const val LONG_PRESS_TOGGLE_DURATION_MS: Long = 3_000L

    /** Returns true if [pattern] contains an unescaped SimpleDateFormat seconds token (`s` or `S`). */
    fun containsSecondsPattern(pattern: String): Boolean {
        var inQuote = false
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch == '\'') {
                if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                    i += 2
                    continue
                }
                inQuote = !inQuote
            } else if (!inQuote && (ch == 's' || ch == 'S')) {
                return true
            }
            i++
        }
        return false
    }

    /** Returns the first 1-2 digit ASCII numeric run in stock [text], corresponding to the hour field. */
    fun findStockHourRanges(text: CharSequence): List<IntRange> {
        var start = -1
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch in '0'..'9') {
                if (start == -1) start = i
            } else if (start != -1) {
                return if (i - start in 1..2) listOf(start until i) else emptyList()
            }
            i++
        }
        if (start != -1) {
            return if (text.length - start in 1..2) listOf(start until text.length) else emptyList()
        }
        return emptyList()
    }

    /** Returns sorted distinct character indices of `'1'` inside [hourRanges] within [text]. */
    fun findRedOneIndices(text: CharSequence, hourRanges: List<IntRange>): List<Int> {
        if (text.isEmpty() || hourRanges.isEmpty()) return emptyList()
        val indices = sortedSetOf<Int>()
        for (range in hourRanges) {
            val start = range.first.coerceAtLeast(0)
            val endInclusive = range.last.coerceAtMost(text.length - 1)
            if (start > endInclusive) continue
            for (i in start..endInclusive) {
                if (text[i] == '1') {
                    indices += i
                }
            }
        }
        return indices.toList()
    }
}
