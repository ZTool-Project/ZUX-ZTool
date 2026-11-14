package com.qimian233.ztool.hook.modules.systemui.keyguard

import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

internal fun fetchOwnerInfoQuote(config: OwnerInfoQuoteConfig): String? {
    val address = config.apiUrl.trim().let {
        if (it.startsWith("https://", true) || it.startsWith("http://", true)) it else "https://$it"
    }
    val connection = URL(address).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "ZTool-OwnerInfo/2.0")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                // Custom endpoints must not retain arbitrarily large responses in SystemUI.
                require(text.length + count <= 1_048_576) { "Quote response exceeds 1 MiB of text" }
                text.append(buffer, 0, count)
            }
            text.toString()
        }
        return parseOwnerInfoQuote(response, config.regex)
    } finally {
        connection.disconnect()
    }
}

internal fun parseOwnerInfoQuote(response: String, regex: String): String? {
    val matcher = Pattern.compile(regex).matcher(response)
    if (!matcher.find() || matcher.groupCount() < 1) return null
    val value = matcher.group(1) ?: return null
    val decoded = StringBuilder()
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char != '\\') {
            decoded.append(char)
            continue
        }
        if (index == value.length) return null
        when (val escaped = value[index++]) {
            '"', '\\', '/' -> decoded.append(escaped)
            'b' -> decoded.append('\b')
            'f' -> decoded.append('\u000c')
            'n' -> decoded.append('\n')
            'r' -> decoded.append('\r')
            't' -> decoded.append('\t')
            'u' -> {
                if (index + 4 > value.length) return null
                val code = value.substring(index, index + 4).toIntOrNull(16) ?: return null
                decoded.append(code.toChar())
                index += 4
            }
            else -> return null
        }
    }
    return decoded.toString().takeIf { it.isNotBlank() }
}
