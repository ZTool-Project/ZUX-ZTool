package com.qimian233.ztool.hook.modules.systemui.misc

import android.util.Log
import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import java.text.AttributedCharacterIterator
import java.text.CharacterIterator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Custom date formatter supporting lunar calendar, solar terms, and traditional tokens. */
object CustomDateFormatter {

    /** Formatted text together with 0-based character ranges belonging to hour fields. */
    data class FormattedDateText(
        val text: String,
        val hourRanges: List<IntRange>
    )

    private const val TAG = "CustomDateFormatter"

    private val CHINESE_HOURS = arrayOf(
        "子时", "丑时", "寅时", "卯时", "辰时", "巳时",
        "午时", "未时", "申时", "酉时", "戌时", "亥时"
    )

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

    /**
     * Formats [date] using [pattern], supporting N/J/T/C/A/W/a and standard tokens.
     */
    fun format(pattern: String?, date: Date?): String = formatWithFields(pattern, date).text

    /**
     * Formats [date] using [pattern] and returns the formatted string with hour field ranges.
     */
    fun formatWithFields(
        pattern: String?,
        date: Date?,
        locale: Locale = Locale.getDefault()
    ): FormattedDateText {
        if (pattern.isNullOrEmpty() || date == null) {
            return FormattedDateText("", emptyList())
        }

        return try {
            val expanded = expandCustomPatterns(pattern, date)
            formatStandardWithHourRanges(expanded, date, locale)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error formatting date: ${e.message}", e) }
            formatStandardWithHourRanges("HH:mm", date, locale)
        }
    }

    private fun expandCustomPatterns(pattern: String, date: Date): String {
        val out = StringBuilder(pattern.length)
        var lunar: Lunar? = null
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
                val replacement = getCustomReplacement(tokenType, resolvedLunar, date)
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

    private fun getCustomReplacement(type: String, lunar: Lunar, date: Date): String {
        return when (type) {
            "lunar" -> getLunarDate(lunar)
            "solarTerm" -> getSolarTerm(lunar)
            "chineseHour" -> getChineseHour(date)
            "constellation" -> getConstellation(lunar)
            "animal" -> lunar.yearShengXiao
            "week" -> getChineseWeek(date)
            "timePeriod" -> getTimePeriod(date)
            else -> ""
        }
    }

    private fun getLunarDate(lunar: Lunar): String {
        return try {
            lunar.monthInChinese + "月" + lunar.dayInChinese
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting lunar date", e) }
            ""
        }
    }

    private fun getSolarTerm(lunar: Lunar): String {
        return try {
            lunar.jieQi ?: ""
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting solar term", e) }
            ""
        }
    }

    private fun getChineseHour(date: Date): String {
        return try {
            val hourFormat = SimpleDateFormat("HH", Locale.getDefault())
            val hour = hourFormat.format(date).toInt()
            val hourIndex = (hour + 1) / 2 % 12
            CHINESE_HOURS[hourIndex]
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting Chinese hour", e) }
            ""
        }
    }

    private fun getConstellation(lunar: Lunar): String {
        return try {
            val solar: Solar = lunar.solar
            getConstellationBySolarDate(solar.month, solar.day)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting constellation", e) }
            ""
        }
    }

    private fun getConstellationBySolarDate(month: Int, day: Int): String {
        if (month == 1 && day >= 20 || month == 2 && day <= 18) return "水瓶座"
        if (month == 2 || month == 3 && day <= 20) return "双鱼座"
        if (month == 3 || month == 4 && day <= 19) return "白羊座"
        if (month == 4 || month == 5 && day <= 20) return "金牛座"
        if (month == 5 || month == 6 && day <= 21) return "双子座"
        if (month == 6 || month == 7 && day <= 22) return "巨蟹座"
        if (month == 7 || month == 8 && day <= 22) return "狮子座"
        if (month == 8 || month == 9 && day <= 22) return "处女座"
        if (month == 9 || month == 10 && day <= 23) return "天秤座"
        if (month == 10 || month == 11 && day <= 22) return "天蝎座"
        if (month == 11 || month == 12 && day <= 21) return "射手座"
        if (month == 12 || month == 1) return "摩羯座"
        return ""
    }

    private fun getChineseWeek(date: Date): String {
        return try {
            val weekFormat = SimpleDateFormat("E", Locale.CHINA)
            val week = weekFormat.format(date)
            week.replace("星期", "周")
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting Chinese week", e) }
            ""
        }
    }

    private fun getTimePeriod(date: Date): String {
        return try {
            val hourFormat = SimpleDateFormat("H", Locale.getDefault())
            val hour = hourFormat.format(date).toInt()
            when (hour) {
                in 0..<6 -> "凌晨"
                in 6..<9 -> "早上"
                in 9..<12 -> "上午"
                in 12..<14 -> "中午"
                in 14..<18 -> "下午"
                in 18..<24 -> "晚上"
                else -> ""
            }
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error getting time period", e) }
            ""
        }
    }
}
