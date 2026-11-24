package com.qimian233.ztool.hook.modules.systemui.misc

import android.annotation.SuppressLint
import android.content.res.Resources
import android.util.Log
import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import java.text.AttributedCharacterIterator
import java.text.CharacterIterator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Custom date formatter supporting lunar calendar, solar terms, and traditional tokens. */
object CustomDateFormatter {

    /** Formatted text together with 0-based character ranges belonging to hour fields. */
    data class FormattedDateText(
        val text: String,
        val hourRanges: List<IntRange>
    )

    /** Localized label tables used when expanding custom date/time tokens. */
    class DateLabels(
        val chineseHours: Array<String> = FALLBACK_CHINESE_HOURS,
        val constellations: Array<String> = FALLBACK_CONSTELLATIONS,
        val weekdays: Array<String> = FALLBACK_WEEKDAYS,
        val timePeriods: Array<String> = FALLBACK_TIME_PERIODS,
        val zodiacAnimals: Array<String> = FALLBACK_ZODIAC_ANIMALS,
        val solarTerms: Array<String> = FALLBACK_SOLAR_TERMS,
        val lunarMonths: Array<String> = FALLBACK_LUNAR_MONTHS,
        val lunarDays: Array<String> = FALLBACK_LUNAR_DAYS,
        val lunarTemplate: String = FALLBACK_LUNAR_TEMPLATE,
        val lunarLeapTemplate: String = FALLBACK_LUNAR_LEAP_TEMPLATE
    ) {
        companion object {
            val DEFAULT = DateLabels()
        }
    }

    private class CachedLabels(
        val resourcesIdentity: Int,
        val localeTag: String,
        val labels: DateLabels
    )

    private const val TAG = "CustomDateFormatter"
    private const val MODULE_PACKAGE = "com.qimian233.ztool"

    private const val STRING_LUNAR_TEMPLATE = "ztool_date_lunar_label"
    private const val STRING_LUNAR_LEAP_TEMPLATE = "ztool_date_lunar_leap_label"
    private const val ARRAY_CHINESE_HOURS = "ztool_date_chinese_hour_label"
    private const val ARRAY_CONSTELLATIONS = "ztool_date_constellation_name"
    private const val ARRAY_WEEKDAYS = "ztool_date_weekday_label"
    private const val ARRAY_TIME_PERIODS = "ztool_date_time_period_label"
    private const val ARRAY_ZODIAC_ANIMALS = "ztool_date_zodiac_animal_name"
    private const val ARRAY_SOLAR_TERMS = "ztool_date_solar_term_name"
    private const val ARRAY_LUNAR_MONTHS = "ztool_date_lunar_month_name"
    private const val ARRAY_LUNAR_DAYS = "ztool_date_lunar_day_name"

    private const val FALLBACK_LUNAR_TEMPLATE = "%1\$s%2\$s"
    private const val FALLBACK_LUNAR_LEAP_TEMPLATE = "闰%1\$s%2\$s"

    private val FALLBACK_CHINESE_HOURS = arrayOf(
        "子时", "丑时", "寅时", "卯时", "辰时", "巳时",
        "午时", "未时", "申时", "酉时", "戌时", "亥时"
    )
    private val FALLBACK_CONSTELLATIONS = arrayOf(
        "水瓶座", "双鱼座", "白羊座", "金牛座", "双子座", "巨蟹座",
        "狮子座", "处女座", "天秤座", "天蝎座", "射手座", "摩羯座"
    )
    private val FALLBACK_WEEKDAYS = arrayOf(
        "周日", "周一", "周二", "周三", "周四", "周五", "周六"
    )
    private val FALLBACK_TIME_PERIODS = arrayOf(
        "凌晨", "早上", "上午", "中午", "下午", "晚上"
    )
    private val FALLBACK_ZODIAC_ANIMALS = arrayOf(
        "鼠", "牛", "虎", "兔", "龙", "蛇",
        "马", "羊", "猴", "鸡", "狗", "猪"
    )
    private val FALLBACK_SOLAR_TERMS = arrayOf(
        "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
        "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
        "立秋", "处暑", "白露", "秋分", "寒露", "霜降",
        "立冬", "小雪", "大雪", "冬至", "小寒", "大寒"
    )
    private val FALLBACK_LUNAR_MONTHS = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )
    private val FALLBACK_LUNAR_DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    private val ZODIAC_KEY_TO_INDEX: Map<String, Int> =
        FALLBACK_ZODIAC_ANIMALS.withIndex().associate { (index, name) -> name to index }

    private val SOLAR_TERM_KEY_TO_INDEX: Map<String, Int> =
        FALLBACK_SOLAR_TERMS.withIndex().associate { (index, name) -> name to index }

    private val CUSTOM_PATTERNS = mapOf(
        "N" to "lunar",
        "J" to "solarTerm",
        "T" to "chineseHour",
        "C" to "constellation",
        "A" to "animal",
        "W" to "week",
        "a" to "timePeriod"
    )

    private val HOUR_FIELDS: Set<AttributedCharacterIterator.Attribute> = setOf(
        DateFormat.Field.HOUR_OF_DAY0,
        DateFormat.Field.HOUR_OF_DAY1,
        DateFormat.Field.HOUR0,
        DateFormat.Field.HOUR1
    )

    @Volatile
    private var cachedLabels: CachedLabels? = null

    /**
     * Formats [date] using [pattern], supporting N/J/T/C/A/W/a and standard tokens.
     */
    fun format(pattern: String?, date: Date?, resources: Resources? = null): String =
        formatWithFields(pattern = pattern, date = date, resources = resources).text

    /**
     * Formats [date] using [pattern] and returns the formatted string with hour field ranges.
     */
    fun formatWithFields(
        pattern: String?,
        date: Date?,
        locale: Locale? = null,
        resources: Resources? = null
    ): FormattedDateText {
        if (pattern.isNullOrEmpty() || date == null) {
            return FormattedDateText("", emptyList())
        }
        val effectiveLocale = locale ?: resolveLocale(resources)
        val labels = resolveLabels(resources)
        return formatWithFields(pattern, date, effectiveLocale, labels)
    }

    /**
     * Formats [date] using [pattern], [locale], and explicit [labels], returning hour field ranges.
     */
    fun formatWithFields(
        pattern: String?,
        date: Date?,
        locale: Locale,
        labels: DateLabels
    ): FormattedDateText {
        if (pattern.isNullOrEmpty() || date == null) {
            return FormattedDateText("", emptyList())
        }

        return try {
            val expanded = expandCustomPatterns(pattern, date, locale, labels)
            formatStandardWithHourRanges(expanded, date, locale)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error formatting date: ${e.message}", e) }
            formatStandardWithHourRanges("HH:mm", date, locale)
        }
    }

    private fun resolveLocale(resources: Resources?): Locale =
        runCatching {
            resources?.configuration?.locales?.takeIf { !it.isEmpty }?.get(0)
        }.getOrNull() ?: Locale.getDefault()

    private fun resolveLabels(resources: Resources?): DateLabels {
        if (resources == null) return DateLabels.DEFAULT
        val identity = System.identityHashCode(resources)
        val localeTag = runCatching { resources.configuration.locales.toLanguageTags() }.getOrDefault("")
        val cached = cachedLabels
        if (cached != null && cached.resourcesIdentity == identity && cached.localeTag == localeTag) {
            return cached.labels
        }
        val loaded = DateLabels(
            chineseHours = readStringArray(resources, ARRAY_CHINESE_HOURS, FALLBACK_CHINESE_HOURS),
            constellations = readStringArray(resources, ARRAY_CONSTELLATIONS, FALLBACK_CONSTELLATIONS),
            weekdays = readStringArray(resources, ARRAY_WEEKDAYS, FALLBACK_WEEKDAYS),
            timePeriods = readStringArray(resources, ARRAY_TIME_PERIODS, FALLBACK_TIME_PERIODS),
            zodiacAnimals = readStringArray(resources, ARRAY_ZODIAC_ANIMALS, FALLBACK_ZODIAC_ANIMALS),
            solarTerms = readStringArray(resources, ARRAY_SOLAR_TERMS, FALLBACK_SOLAR_TERMS),
            lunarMonths = readStringArray(resources, ARRAY_LUNAR_MONTHS, FALLBACK_LUNAR_MONTHS),
            lunarDays = readStringArray(resources, ARRAY_LUNAR_DAYS, FALLBACK_LUNAR_DAYS),
            lunarTemplate = readString(resources, STRING_LUNAR_TEMPLATE, FALLBACK_LUNAR_TEMPLATE),
            lunarLeapTemplate = readString(resources, STRING_LUNAR_LEAP_TEMPLATE, FALLBACK_LUNAR_LEAP_TEMPLATE)
        )
        cachedLabels = CachedLabels(identity, localeTag, loaded)
        return loaded
    }

    @SuppressLint("DiscouragedApi")
    private fun readString(resources: Resources, name: String, fallback: String): String = try {
        val resId = resources.getIdentifier(name, "string", MODULE_PACKAGE)
        if (resId != 0) resources.getString(resId) else fallback
    } catch (_: Throwable) {
        fallback
    }

    @SuppressLint("DiscouragedApi")
    private fun readStringArray(
        resources: Resources,
        name: String,
        fallback: Array<String>
    ): Array<String> = try {
        val resId = resources.getIdentifier(name, "array", MODULE_PACKAGE)
        if (resId == 0) {
            fallback
        } else {
            val loaded = resources.getStringArray(resId)
            if (loaded.size == fallback.size) loaded else fallback
        }
    } catch (_: Throwable) {
        fallback
    }

    private fun expandCustomPatterns(
        pattern: String,
        date: Date,
        locale: Locale,
        labels: DateLabels
    ): String {
        val out = StringBuilder(pattern.length)
        var lunar: Lunar? = null
        var calendar: Calendar? = null
        var inQuote = false
        var justClosedQuote = false
        var i = 0

        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch == '\'') {
                if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                    out.append("''")
                    justClosedQuote = false
                    i += 2
                    continue
                }
                if (!inQuote && justClosedQuote && out.isNotEmpty() && out[out.length - 1] == '\'') {
                    // Merge adjacent quote blocks so closing + opening quotes do not form ''.
                    out.deleteCharAt(out.length - 1)
                    inQuote = true
                    justClosedQuote = false
                } else {
                    inQuote = !inQuote
                    justClosedQuote = !inQuote
                    out.append('\'')
                }
                i++
                continue
            }

            if (inQuote) {
                justClosedQuote = false
                out.append(ch)
                i++
                continue
            }

            val tokenType = CUSTOM_PATTERNS[ch.toString()]
            if (tokenType != null) {
                val resolvedLunar = lunar ?: Lunar.fromDate(date).also { lunar = it }
                val resolvedCalendar = calendar ?: Calendar.getInstance().apply { time = date }.also { calendar = it }
                val replacement = getCustomReplacement(tokenType, resolvedLunar, resolvedCalendar, locale, labels)
                if (replacement.isNotEmpty()) {
                    val escaped = replacement.replace("'", "''")
                    if (justClosedQuote && out.isNotEmpty() && out[out.length - 1] == '\'') {
                        out.deleteCharAt(out.length - 1)
                        out.append(escaped).append('\'')
                    } else {
                        out.append('\'').append(escaped).append('\'')
                    }
                    justClosedQuote = true
                }
            } else {
                justClosedQuote = false
                out.append(ch)
            }
            i++
        }

        return out.toString()
    }

    private fun formatStandardWithHourRanges(
        pattern: String,
        date: Date,
        locale: Locale
    ): FormattedDateText {
        val iterator = SimpleDateFormat(pattern, locale).formatToCharacterIterator(date)
        val text = StringBuilder()
        val hourRanges = mutableListOf<IntRange>()
        var ch = iterator.first()
        while (ch != CharacterIterator.DONE) {
            val start = iterator.index
            val runLimit = iterator.getRunLimit(HOUR_FIELDS)
            if (runLimit <= start) break
            val isHour = HOUR_FIELDS.any { iterator.getAttribute(it) != null }
            while (iterator.index < runLimit && ch != CharacterIterator.DONE) {
                text.append(ch)
                ch = iterator.next()
            }
            if (isHour) {
                hourRanges += start until runLimit
            }
        }
        return FormattedDateText(text.toString(), hourRanges)
    }

    private fun getCustomReplacement(
        type: String,
        lunar: Lunar,
        calendar: Calendar,
        locale: Locale,
        labels: DateLabels
    ): String {
        return when (type) {
            "lunar" -> getLunarDate(lunar, locale, labels)
            "solarTerm" -> getSolarTerm(lunar, labels)
            "chineseHour" -> getChineseHour(calendar, labels)
            "constellation" -> getConstellation(lunar, labels)
            "animal" -> getZodiacAnimal(lunar, labels)
            "week" -> getWeekday(calendar, labels)
            "timePeriod" -> getTimePeriod(calendar, labels)
            else -> ""
        }
    }

    private fun getLunarDate(lunar: Lunar, locale: Locale, labels: DateLabels): String {
        return try {
            val rawMonth = lunar.month
            val monthIndex = abs(rawMonth) - 1
            val dayIndex = lunar.day - 1
            if (monthIndex in labels.lunarMonths.indices && dayIndex in labels.lunarDays.indices) {
                val template = if (rawMonth < 0) labels.lunarLeapTemplate else labels.lunarTemplate
                String.format(locale, template, labels.lunarMonths[monthIndex], labels.lunarDays[dayIndex])
            } else {
                lunar.monthInChinese + "月" + lunar.dayInChinese
            }
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting lunar date", e) }
            ""
        }
    }

    private fun getSolarTerm(lunar: Lunar, labels: DateLabels): String {
        return try {
            val raw = lunar.jieQi.orEmpty()
            if (raw.isEmpty()) "" else SOLAR_TERM_KEY_TO_INDEX[raw]?.let { labels.solarTerms[it] } ?: raw
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting solar term", e) }
            ""
        }
    }

    private fun getChineseHour(calendar: Calendar, labels: DateLabels): String {
        return try {
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val hourIndex = (hour + 1) / 2 % 12
            labels.chineseHours[hourIndex]
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting Chinese hour", e) }
            ""
        }
    }

    private fun getConstellation(lunar: Lunar, labels: DateLabels): String {
        return try {
            val solar: Solar = lunar.solar
            val idx = constellationIndex(solar.month, solar.day)
            if (idx in labels.constellations.indices) labels.constellations[idx] else ""
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting constellation", e) }
            ""
        }
    }

    private fun constellationIndex(month: Int, day: Int): Int {
        if (month == 1 && day >= 20 || month == 2 && day <= 18) return 0
        if (month == 2 || month == 3 && day <= 20) return 1
        if (month == 3 || month == 4 && day <= 19) return 2
        if (month == 4 || month == 5 && day <= 20) return 3
        if (month == 5 || month == 6 && day <= 21) return 4
        if (month == 6 || month == 7 && day <= 22) return 5
        if (month == 7 || month == 8 && day <= 22) return 6
        if (month == 8 || month == 9 && day <= 22) return 7
        if (month == 9 || month == 10 && day <= 23) return 8
        if (month == 10 || month == 11 && day <= 22) return 9
        if (month == 11 || month == 12 && day <= 21) return 10
        if (month == 12 || month == 1) return 11
        return -1
    }

    private fun getZodiacAnimal(lunar: Lunar, labels: DateLabels): String {
        return try {
            val raw = lunar.yearShengXiao.orEmpty()
            if (raw.isEmpty()) "" else ZODIAC_KEY_TO_INDEX[raw]?.let { labels.zodiacAnimals[it] } ?: raw
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting zodiac animal", e) }
            ""
        }
    }

    private fun getWeekday(calendar: Calendar, labels: DateLabels): String {
        return try {
            val idx = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
            if (idx in labels.weekdays.indices) labels.weekdays[idx] else ""
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting weekday", e) }
            ""
        }
    }

    private fun getTimePeriod(calendar: Calendar, labels: DateLabels): String {
        return try {
            val idx = when (calendar.get(Calendar.HOUR_OF_DAY)) {
                in 0..<6 -> 0
                in 6..<9 -> 1
                in 9..<12 -> 2
                in 12..<14 -> 3
                in 14..<18 -> 4
                in 18..<24 -> 5
                else -> -1
            }
            if (idx in labels.timePeriods.indices) labels.timePeriods[idx] else ""
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting time period", e) }
            ""
        }
    }
}
