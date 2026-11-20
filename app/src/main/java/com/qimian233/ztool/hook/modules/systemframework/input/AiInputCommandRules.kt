package com.qimian233.ztool.hook.modules.systemframework.input

/**
 * Pure-Kotlin trigger parsing, command extraction, and package compatibility rules
 * for AI global input. Kept free of Android/libxposed dependencies for JVM tests.
 */
object AiInputCommandRules {

    const val AI_COMMAND_MAX_LENGTH = 10_000
    const val MAX_LOOKBACK_CHARS = 21_000

    val DEFAULT_TRIGGERS: List<String> = listOf("&&")

    val BLACKLIST_PACKAGES: Set<String> = setOf("com.zuxos.cadlauncher")

    val DOCUMENT_LIST_PACKAGES: Set<String> = setOf(
        "cn.wps.moffice_eng",
        "com.microsoft.office.word",
        "com.zui.notes",
        "com.microsoft.office.outlook"
    )

    val SPECIAL_LIST_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",
        "com.google.android.keep",
        "com.whatsapp"
    )

    val WPS_THROTTLE_PACKAGES: Set<String> = setOf("cn.wps.moffice_eng")

    data class ParsedTriggers(
        val normalizedTriggers: List<String>,
        val endingChars: Set<Char>
    )

    sealed interface CommandEvaluation {
        data object NoMatch : CommandEvaluation
        data class StartOpened(val normalizedTrigger: String) : CommandEvaluation
        data class InvalidTooLong(val normalizedTrigger: String) : CommandEvaluation
        data class ExecuteCommand(
            val normalizedTrigger: String,
            val command: String,
            val rawSpanLength: Int,
            val beforeContextEndOffset: Int
        ) : CommandEvaluation
    }

    /** Builds normalized triggers sorted longest-first, falling back to `"&&"`. */
    fun parseEffectiveTriggers(rawConfig: String?): ParsedTriggers {
        val configured = rawConfig
            .orEmpty()
            .split(',')
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) null else normalizeText(trimmed)
            }
            .distinct()
        val effective = if (configured.isEmpty()) DEFAULT_TRIGGERS else configured
        val sorted = effective.sortedByDescending { it.length }
        val endings = sorted.mapTo(HashSet()) { it[it.length - 1] }
        return ParsedTriggers(normalizedTriggers = sorted, endingChars = endings)
    }

    /** Normalizes full-width ASCII (`FF01..FF5E`) 1:1 to ASCII (`21..7E`). */
    fun normalizeChar(c: Char): Char =
        if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c

    /** Length-preserving normalization across a CharSequence. */
    fun normalizeText(text: CharSequence): String = buildString(text.length) {
        for (i in 0 until text.length) {
            append(normalizeChar(text[i]))
        }
    }

    /** Fast check on committed text tail to skip lookback IPC on non-trigger keys. */
    fun mightEndWithTrigger(committedText: CharSequence, triggers: ParsedTriggers): Boolean {
        if (committedText.isEmpty() || triggers.endingChars.isEmpty()) return false
        return normalizeChar(committedText[committedText.length - 1]) in triggers.endingChars
    }

    fun isOfficialDefaultTrigger(normalizedTrigger: String): Boolean =
        normalizedTrigger == "&&"

    /** Evaluates text before cursor immediately after a candidate trigger commit. */
    fun evaluateBeforeText(
        beforeText: CharSequence,
        triggers: ParsedTriggers
    ): CommandEvaluation {
        if (beforeText.isEmpty() || triggers.normalizedTriggers.isEmpty()) {
            return CommandEvaluation.NoMatch
        }
        val raw = beforeText.toString()
        val norm = normalizeText(raw)
        val matched = triggers.normalizedTriggers.firstOrNull { norm.endsWith(it) }
            ?: return CommandEvaluation.NoMatch

        val closingStart = raw.length - matched.length
        val searchLimit = closingStart - matched.length
        if (searchLimit < 0) {
            return CommandEvaluation.StartOpened(matched)
        }

        val openStart = norm.lastIndexOf(matched, startIndex = searchLimit)
        if (openStart < 0) {
            return CommandEvaluation.StartOpened(matched)
        }

        val promptStart = openStart + matched.length
        val promptLength = closingStart - promptStart
        if (promptLength > AI_COMMAND_MAX_LENGTH) {
            return CommandEvaluation.InvalidTooLong(matched)
        }

        val rawPrompt = raw.substring(promptStart, closingStart)
        if (rawPrompt.trim().isEmpty()) {
            return CommandEvaluation.StartOpened(matched)
        }

        return CommandEvaluation.ExecuteCommand(
            normalizedTrigger = matched,
            command = rawPrompt,
            rawSpanLength = raw.length - openStart,
            beforeContextEndOffset = openStart
        )
    }
}
