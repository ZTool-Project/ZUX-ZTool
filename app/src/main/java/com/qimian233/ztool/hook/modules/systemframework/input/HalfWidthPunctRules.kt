package com.qimian233.ztool.hook.modules.systemframework.input

/**
 * Pure-Kotlin symbol mapping and commit-rewriting rules for forced half-width
 * punctuation. Kept free of Android/libxposed dependencies for JVM unit tests.
 */
object HalfWidthPunctRules {

    // Pure CJK punctuation with no canonical ASCII counterpart (PC IME convention).
    // 、 is excluded because OEM IMEs map both / and \ to it ambiguously.
    private const val NON_CONVERTIBLE = "。，、；：？！“”‘’《》〈〉「」『』【】〔〕（）—…·～￥"

    private val PAIRS = charArrayOf(
        '(', ')', '[', ']', '{', '}', '<', '>',
        '（', '）', '［', '］', '｛', '｝', '＜', '＞', '《', '》'
    )

    /** Strips whitespace and non-ASCII/non-fullwidth characters for preference storage. */
    fun filterSignInput(value: String): String =
        value.filter { c ->
            (c.code in 0xFF01..0xFF5E || c in '!'..'~') && !c.isWhitespace()
        }

    /** Builds a fullwidth-to-ASCII replacement map from user-configured symbols. */
    fun buildPunctTable(rawConfig: String): Map<Char, Char> {
        val selected = LinkedHashSet<Char>()
        for (c in rawConfig) {
            val canonical = canonicalize(c) ?: continue
            selected.add(canonical)
        }
        // Extend bracket/quote pairs so auto-paired commits never mix widths.
        for (i in PAIRS.indices step 2) {
            if (PAIRS[i] in selected) selected.add(PAIRS[i + 1])
            if (PAIRS[i + 1] in selected) selected.add(PAIRS[i])
        }
        val table = HashMap<Char, Char>()
        for (ascii in selected) {
            if (ascii in NON_CONVERTIBLE) continue
            val fullwidth = if (ascii in '!'..'~') (ascii.code + 0xFEE0).toChar() else null
            table[fullwidth ?: ascii] = ascii
        }
        return table
    }

    /**
     * Rewrites single-char or auto-paired 2-char commits found in `punctTable`.
     * Returns null when the commit should pass through untouched.
     */
    fun rewrite(text: CharSequence, punctTable: Map<Char, Char>): String? {
        if (punctTable.isEmpty()) return null
        val len = text.length
        if (len == 1) {
            val mapped = punctTable[text[0]] ?: return null
            return mapped.toString()
        }
        if (len == 2) {
            val openMapped = punctTable[text[0]] ?: return null
            val closeMapped = punctTable[text[1]] ?: return null
            if (openMapped == text[0] && closeMapped == text[1]) return null
            return "$openMapped$closeMapped"
        }
        return null
    }

    private fun canonicalize(c: Char): Char? {
        if (c.code in 0xFF01..0xFF5E) return (c.code - 0xFEE0).toChar()
        if (c in '!'..'~') return c
        return null
    }
}
