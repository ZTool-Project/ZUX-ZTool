package com.qimian233.ztool

import com.qimian233.ztool.hook.modules.systemui.misc.CustomDateFormatter
import com.qimian233.ztool.hook.modules.systemui.statusbar.StatusBarClockOptions
import com.qimian233.ztool.hook.modules.systemui.statusbar.StatusBarClockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatusBarClockPolicyTest {

    @Test
    fun stockClockHourRangesAndRedOneIndices() {
        assertEquals(listOf(1), stockRedOneIndices("01:00"))
        assertEquals(listOf(0, 1), stockRedOneIndices("11:21"))
        assertEquals(listOf(1), stockRedOneIndices("21:06"))
        assertEquals(listOf(0), stockRedOneIndices("10:11"))
        assertEquals(emptyList<Int>(), stockRedOneIndices("09:11"))
        assertEquals(listOf(0, 1), stockRedOneIndices("11:21:15"))
        assertEquals(listOf(2), stockRedOneIndices("上午1:05"))
        assertEquals(listOf(2, 3), stockRedOneIndices("下午11:21"))
        assertEquals(listOf(0, 1), stockRedOneIndices("11:21 AM"))
        assertEquals(listOf(0, 1), stockRedOneIndices("11.21"))
        assertEquals(listOf(1, 2), stockRedOneIndices("\u200E11:21"))
        assertEquals(emptyList<Int>(), stockRedOneIndices(""))
    }

    @Test
    fun customFormatterExtractsExactHourRanges() {
        val hm0100 = CustomDateFormatter.formatWithFields("HH:mm", dateOf(hour = 1, minute = 0), Locale.US)
        assertEquals("01:00", hm0100.text)
        assertEquals(listOf(1), StatusBarClockPolicy.findRedOneIndices(hm0100.text, hm0100.hourRanges))

        val hm1121 = CustomDateFormatter.formatWithFields("HH:mm", dateOf(hour = 11, minute = 21), Locale.US)
        assertEquals("11:21", hm1121.text)
        assertEquals(listOf(0, 1), StatusBarClockPolicy.findRedOneIndices(hm1121.text, hm1121.hourRanges))

        val hm2106 = CustomDateFormatter.formatWithFields("HH:mm", dateOf(hour = 21, minute = 6), Locale.US)
        assertEquals("21:06", hm2106.text)
        assertEquals(listOf(1), StatusBarClockPolicy.findRedOneIndices(hm2106.text, hm2106.hourRanges))

        val hm0911 = CustomDateFormatter.formatWithFields("HH:mm", dateOf(hour = 9, minute = 11), Locale.US)
        assertEquals("09:11", hm0911.text)
        assertEquals(emptyList<Int>(), StatusBarClockPolicy.findRedOneIndices(hm0911.text, hm0911.hourRanges))

        val compact = CustomDateFormatter.formatWithFields(
            "hhmmss",
            dateOf(hour = 11, minute = 21, second = 15),
            Locale.US
        )
        assertEquals("112115", compact.text)
        assertEquals(listOf(0, 1), StatusBarClockPolicy.findRedOneIndices(compact.text, compact.hourRanges))

        val period13 = CustomDateFormatter.formatWithFields(
            "a hhmmss",
            dateOf(hour = 13, minute = 21, second = 15),
            Locale.CHINA
        )
        assertEquals("中午 012115", period13.text)
        assertEquals(listOf(4), StatusBarClockPolicy.findRedOneIndices(period13.text, period13.hourRanges))

        val period23 = CustomDateFormatter.formatWithFields(
            "a hhmmss",
            dateOf(hour = 23, minute = 21, second = 15),
            Locale.CHINA
        )
        assertEquals("晚上 112115", period23.text)
        assertEquals(listOf(3, 4), StatusBarClockPolicy.findRedOneIndices(period23.text, period23.hourRanges))

        val dated = CustomDateFormatter.formatWithFields(
            "MM月dd日 HH:mm:ss",
            dateOf(year = 2026, month = 11, day = 11, hour = 21, minute = 11, second = 11),
            Locale.CHINA
        )
        assertEquals("11月11日 21:11:11", dated.text)
        assertEquals(listOf(8), StatusBarClockPolicy.findRedOneIndices(dated.text, dated.hourRanges))

        val lunarPrefixed = CustomDateFormatter.formatWithFields(
            "N HH:mm",
            dateOf(year = 2026, month = 11, day = 11, hour = 1, minute = 0),
            Locale.CHINA
        )
        assertTrue(lunarPrefixed.text.endsWith(" 01:00"))
        assertEquals(
            listOf(lunarPrefixed.text.length - 4),
            StatusBarClockPolicy.findRedOneIndices(lunarPrefixed.text, lunarPrefixed.hourRanges)
        )

        val quoted0911 = CustomDateFormatter.formatWithFields(
            "'11点' HH:mm",
            dateOf(hour = 9, minute = 11),
            Locale.CHINA
        )
        assertEquals("11点 09:11", quoted0911.text)
        assertEquals(emptyList<Int>(), StatusBarClockPolicy.findRedOneIndices(quoted0911.text, quoted0911.hourRanges))

        val quoted2111 = CustomDateFormatter.formatWithFields(
            "'11点' HH:mm",
            dateOf(hour = 21, minute = 11),
            Locale.CHINA
        )
        assertEquals("11点 21:11", quoted2111.text)
        assertEquals(listOf(5), StatusBarClockPolicy.findRedOneIndices(quoted2111.text, quoted2111.hourRanges))

        val dateOnly = CustomDateFormatter.formatWithFields(
            "MM-dd",
            dateOf(year = 2026, month = 11, day = 11, hour = 11, minute = 0),
            Locale.US
        )
        assertEquals("11-11", dateOnly.text)
        assertTrue(dateOnly.hourRanges.isEmpty())
        assertEquals(emptyList<Int>(), StatusBarClockPolicy.findRedOneIndices(dateOnly.text, dateOnly.hourRanges))
    }

    @Test
    fun customFormatterInvalidPatternFallsBackToDefault() {
        val fallback = CustomDateFormatter.formatWithFields(
            "invalid pattern",
            dateOf(hour = 11, minute = 21),
            Locale.US
        )
        assertEquals("11:21", fallback.text)
        assertEquals(listOf(0..1), fallback.hourRanges)
        assertEquals(listOf(0, 1), StatusBarClockPolicy.findRedOneIndices(fallback.text, fallback.hourRanges))
    }

    @Test
    fun containsSecondsPatternDetectsOnlyUnescapedSecondsTokens() {
        assertFalse(StatusBarClockPolicy.containsSecondsPattern("HH:mm"))
        assertTrue(StatusBarClockPolicy.containsSecondsPattern("HH:mm:ss"))
        assertTrue(StatusBarClockPolicy.containsSecondsPattern("hhmmss"))
        assertTrue(StatusBarClockPolicy.containsSecondsPattern("HH:mm:ss.SSS"))
        assertFalse(StatusBarClockPolicy.containsSecondsPattern("'s' HH:mm"))
        assertFalse(StatusBarClockPolicy.containsSecondsPattern("'S' HH:mm"))
        assertTrue(StatusBarClockPolicy.containsSecondsPattern("''s HH:mm"))
        assertFalse(StatusBarClockPolicy.containsSecondsPattern(""))
    }

    @Test
    fun optionsEnablementSecondsLinkageAndColorPriority() {
        for (displaySeconds in listOf(false, true)) {
            for (redOne in listOf(false, true)) {
                for (customClock in listOf(false, true)) {
                    val options = StatusBarClockOptions(
                        displaySeconds = displaySeconds,
                        redOneHour = redOne,
                        customClock = customClock
                    )
                    assertEquals(displaySeconds || redOne || customClock, options.enabled)
                }
            }
        }

        assertTrue(
            StatusBarClockOptions(
                displaySeconds = false,
                customClock = true,
                customFormat = "HH:mm:ss"
            ).effectiveShowSeconds
        )
        assertFalse(
            StatusBarClockOptions(
                displaySeconds = false,
                customClock = true,
                customFormat = "HH:mm"
            ).effectiveShowSeconds
        )
        assertFalse(
            StatusBarClockOptions(
                displaySeconds = false,
                customClock = true,
                customFormat = "'s' HH:mm"
            ).effectiveShowSeconds
        )
        assertFalse(
            StatusBarClockOptions(
                displaySeconds = false,
                customClock = false,
                customFormat = "HH:mm:ss"
            ).effectiveShowSeconds
        )

        assertTrue(
            StatusBarClockOptions(
                customClock = true,
                textColorEnabled = true,
                redOneHour = false
            ).effectiveCustomTextColorEnabled
        )
        assertFalse(
            StatusBarClockOptions(
                customClock = true,
                textColorEnabled = true,
                redOneHour = true
            ).effectiveCustomTextColorEnabled
        )
        assertFalse(
            StatusBarClockOptions(
                customClock = false,
                textColorEnabled = true,
                redOneHour = false
            ).effectiveCustomTextColorEnabled
        )

        assertEquals("HH:mm", StatusBarClockOptions(customFormat = "").effectiveCustomFormat)
        assertEquals("HH:mm", StatusBarClockOptions(customFormat = "   ").effectiveCustomFormat)
    }

    private fun stockRedOneIndices(text: CharSequence): List<Int> =
        StatusBarClockPolicy.findRedOneIndices(text, StatusBarClockPolicy.findStockHourRanges(text))

    private fun dateOf(
        year: Int = 2026,
        month: Int = 11,
        day: Int = 11,
        hour: Int,
        minute: Int,
        second: Int = 0
    ): Date = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.time
}
