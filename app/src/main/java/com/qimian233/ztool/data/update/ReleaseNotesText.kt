package com.qimian233.ztool.data.update

/** Turns the HTML in a releases Atom entry into the plain-text digest the home card shows. */
object ReleaseNotesText {

    private val SCRIPT_OR_STYLE = Regex(
        """<(script|style)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val BREAK_TAGS = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val LIST_ITEM_OPEN = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val BLOCK_CLOSE = Regex(
        """</(p|div|li|h[1-6]|blockquote|tr|ul|ol)\s*>""",
        RegexOption.IGNORE_CASE
    )
    private val REMAINING_TAG = Regex("""<[^>]+>""")
    private val NUMERIC_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""", RegexOption.IGNORE_CASE)
    private val NAMED_ENTITY = Regex("""&(nbsp|lt|gt|quot|apos|#39);""", RegexOption.IGNORE_CASE)
    private val MEDIA_TAG = Regex("""<(img|video)\b""", RegexOption.IGNORE_CASE)

    fun digest(html: String): String {
        if (html.isBlank()) return ""
        var text = SCRIPT_OR_STYLE.replace(html, "")
        text = BREAK_TAGS.replace(text, "\n")
        text = LIST_ITEM_OPEN.replace(text, "• ")
        text = BLOCK_CLOSE.replace(text, "\n")
        text = REMAINING_TAG.replace(text, "")
        text = decodeEntities(text)
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace(Regex("""[ \t]+\n"""), "\n")
        text = text.replace(Regex("""\n{3,}"""), "\n\n")
        return text.trim()
    }

    fun hasMedia(html: String): Boolean = MEDIA_TAG.containsMatchIn(html)

    private fun decodeEntities(text: String): String {
        var decoded = NUMERIC_ENTITY.replace(text) { match ->
            val radix = if (match.groupValues[1].equals("x", ignoreCase = true)) 16 else 10
            match.groupValues[2].toIntOrNull(radix)
                ?.takeIf { it in 1..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        decoded = NAMED_ENTITY.replace(decoded) { match ->
            when (match.groupValues[1].lowercase()) {
                "nbsp" -> " "
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos", "#39" -> "'"
                else -> match.value
            }
        }
        return decoded.replace("&amp;", "&")
    }
}
