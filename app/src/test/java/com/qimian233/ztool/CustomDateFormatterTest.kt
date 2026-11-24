package com.qimian233.ztool

import com.nlf.calendar.Lunar
import com.qimian233.ztool.hook.modules.systemui.misc.CustomDateFormatter
import com.qimian233.ztool.hook.modules.systemui.statusbar.StatusBarClockPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CustomDateFormatterTest {

    @Test
    fun formatsAllCustomTokensWithDefaultChineseLabels() {
        val labels = CustomDateFormatter.DateLabels.DEFAULT

        // 2025-07-25 is Lunar Leap 6/1 (乙巳年闰六月初一), Friday, Leo
        val leapDate = dateOf(year = 2025, month = 7, day = 25, hour = 0, minute = 30)
        assertEquals(-6, Lunar.fromDate(leapDate).month)
        assertEquals(
            "闰六月初一 蛇 狮子座 子时 凌晨 周五",
            CustomDateFormatter.formatWithFields("N A C T a W", leapDate, Locale.CHINA, labels).text
        )

        // 2026-02-04 is Lichun (立春), Wednesday, Aquarius
        val lichun = dateOf(year = 2026, month = 2, day = 4, hour = 12, minute = 0)
        assertEquals("立春", CustomDateFormatter.formatWithFields("J", lichun, Locale.CHINA, labels).text)
        assertEquals(
            "水瓶座 午时 中午 周三",
            CustomDateFormatter.formatWithFields("C T a W", lichun, Locale.CHINA, labels).text
        )

        // Non-solar-term day produces empty string for J
        val nonTerm = dateOf(year = 2026, month = 2, day = 5, hour = 23, minute = 10)
        assertEquals("", CustomDateFormatter.formatWithFields("J", nonTerm, Locale.CHINA, labels).text)
        assertEquals("子时 晚上", CustomDateFormatter.formatWithFields("T a", nonTerm, Locale.CHINA, labels).text)
    }

    @Test
    fun formatsAllCustomTokensWithEnglishLabelsAndPreservesHourRanges() {
        val labels = englishDateLabels()

        // 2026-06-07 is Sunday, Year of Horse, Gemini, Lunar 4/22
        val sampleDate = dateOf(year = 2026, month = 6, day = 7, hour = 13, minute = 15)
        val formatted = CustomDateFormatter.formatWithFields(
            "N A C T W a hh:mm",
            sampleDate,
            Locale.US,
            labels
        )
        assertEquals(
            "Lunar 4/22 Horse Gemini Wei Sun Noon 01:15",
            formatted.text
        )
        assertEquals(
            listOf(formatted.text.length - 4),
            StatusBarClockPolicy.findRedOneIndices(formatted.text, formatted.hourRanges)
        )

        // Leap month in English
        val leapDate = dateOf(year = 2025, month = 7, day = 25, hour = 6, minute = 0)
        assertEquals(
            "Lunar Leap 6/1 Snake Leo Mao Fri Early Morning",
            CustomDateFormatter.formatWithFields("N A C T W a", leapDate, Locale.US, labels).text
        )

        // Solar term in English (2026-02-04: Beginning of Spring)
        val lichun = dateOf(year = 2026, month = 2, day = 4, hour = 9, minute = 0)
        assertEquals(
            "Beginning of Spring",
            CustomDateFormatter.formatWithFields("J", lichun, Locale.US, labels).text
        )
    }

    @Test
    fun mapsAllTimePeriodBucketsAndConstellationBoundaries() {
        val labels = englishDateLabels()

        val periodExpectations = listOf(
            0 to "Late Night",
            5 to "Late Night",
            6 to "Early Morning",
            8 to "Early Morning",
            9 to "Morning",
            11 to "Morning",
            12 to "Noon",
            13 to "Noon",
            14 to "Afternoon",
            17 to "Afternoon",
            18 to "Evening",
            23 to "Evening"
        )
        for ((hour, expected) in periodExpectations) {
            val actual = CustomDateFormatter.formatWithFields(
                "a",
                dateOf(year = 2026, month = 6, day = 7, hour = hour, minute = 0),
                Locale.US,
                labels
            ).text
            assertEquals("hour=$hour", expected, actual)
        }

        val constellationExpectations = listOf(
            Triple(1, 19, "Capricorn"),
            Triple(1, 20, "Aquarius"),
            Triple(2, 18, "Aquarius"),
            Triple(2, 19, "Pisces"),
            Triple(3, 20, "Pisces"),
            Triple(3, 21, "Aries"),
            Triple(12, 21, "Sagittarius"),
            Triple(12, 22, "Capricorn")
        )
        for ((month, day, expected) in constellationExpectations) {
            val actual = CustomDateFormatter.formatWithFields(
                "C",
                dateOf(year = 2026, month = month, day = day, hour = 12, minute = 0),
                Locale.US,
                labels
            ).text
            assertEquals("$month-$day", expected, actual)
        }
    }

    private fun englishDateLabels(): CustomDateFormatter.DateLabels =
        CustomDateFormatter.DateLabels(
            chineseHours = arrayOf(
                "Zi", "Chou", "Yin", "Mao", "Chen", "Si",
                "Wu", "Wei", "Shen", "You", "Xu", "Hai"
            ),
            constellations = arrayOf(
                "Aquarius", "Pisces", "Aries", "Taurus", "Gemini", "Cancer",
                "Leo", "Virgo", "Libra", "Scorpio", "Sagittarius", "Capricorn"
            ),
            weekdays = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
            timePeriods = arrayOf(
                "Late Night", "Early Morning", "Morning", "Noon", "Afternoon", "Evening"
            ),
            zodiacAnimals = arrayOf(
                "Rat", "Ox", "Tiger", "Rabbit", "Dragon", "Snake",
                "Horse", "Goat", "Monkey", "Rooster", "Dog", "Pig"
            ),
            solarTerms = arrayOf(
                "Beginning of Spring", "Rain Water", "Awakening of Insects",
                "Spring Equinox", "Pure Brightness", "Grain Rain",
                "Beginning of Summer", "Grain Buds", "Grain in Ear",
                "Summer Solstice", "Minor Heat", "Major Heat",
                "Beginning of Autumn", "End of Heat", "White Dew",
                "Autumn Equinox", "Cold Dew", "Frost's Descent",
                "Beginning of Winter", "Minor Snow", "Major Snow",
                "Winter Solstice", "Minor Cold", "Major Cold"
            ),
            lunarMonths = (1..12).map { it.toString() }.toTypedArray(),
            lunarDays = (1..30).map { it.toString() }.toTypedArray(),
            lunarTemplate = "Lunar %1\$s/%2\$s",
            lunarLeapTemplate = "Lunar Leap %1\$s/%2\$s"
        )

    private fun dateOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0
    ): Date = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.time
}
