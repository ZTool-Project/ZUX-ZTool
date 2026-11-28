package com.qimian233.ztool.data.resolver

import org.json.JSONArray
import org.json.JSONObject

/** Observed share/open/direct-share target reported by the resolver hook or scanner. */
data class ResolverObservedEntry(
    val key: String,
    val packageName: String,
    val className: String,
    val label: String,
    val shortcutId: String = "",
    val action: String = "",
    val mimeType: String = "",
    val userId: Int = 0,
    val seenAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("pkg", packageName)
        put("cls", className)
        put("label", label)
        if (shortcutId.isNotEmpty()) put("shortcutId", shortcutId)
        if (action.isNotEmpty()) put("action", action)
        if (mimeType.isNotEmpty()) put("mimeType", mimeType)
        if (userId != 0) put("userId", userId)
        if (seenAt != 0L) put("seenAt", seenAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): ResolverObservedEntry? {
            val key = obj.optString("key").trim()
            val pkg = obj.optString("pkg").trim()
            if (key.isEmpty() || pkg.isEmpty()) return null
            return ResolverObservedEntry(
                key = key,
                packageName = pkg,
                className = obj.optString("cls").trim(),
                label = obj.optString("label").trim(),
                shortcutId = obj.optString("shortcutId").trim(),
                action = obj.optString("action").trim(),
                mimeType = obj.optString("mimeType").trim(),
                userId = obj.optInt("userId", 0),
                seenAt = obj.optLong("seenAt", 0L)
            )
        }
    }
}

/** Shared rule encoding, normalization, and matching helpers for app and hook code. */
object ResolverRules {
    const val BUCKET_OPEN = "open"
    const val BUCKET_SHARE = "share"
    const val BUCKET_DIRECT_SHARE = "direct_share"

    fun normalizeClassName(packageName: String, className: String): String {
        val trimmed = className.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.startsWith(".") -> packageName + trimmed
            !trimmed.contains('.') -> "$packageName.$trimmed"
            else -> trimmed
        }
    }

    fun normalizeComponentKey(packageName: String, className: String): String {
        val pkg = packageName.trim()
        val fullCls = normalizeClassName(pkg, className)
        return if (fullCls.isEmpty()) pkg else "$pkg/$fullCls"
    }

    fun packageWildcardKey(packageName: String): String = "${packageName.trim()}/*"

    fun directShareKey(packageName: String, shortcutId: String): String =
        "${packageName.trim()}|${shortcutId.trim()}"

    fun directSharePackageWildcard(packageName: String): String = "${packageName.trim()}|*"

    fun encodeList(items: List<String>): String {
        val cleaned = items.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (cleaned.isEmpty()) return ""
        return cleaned.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            buildString(item.length + 2) {
                append('"')
                for (ch in item) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        }
    }

    fun decodeList(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("[")) {
            return trimmed.split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
        return parseJsonStringArray(trimmed)
    }

    private fun parseJsonStringArray(raw: String): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        var i = 0
        val n = raw.length
        while (i < n) {
            if (raw[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < n) {
                    val ch = raw[i++]
                    if (ch == '"') break
                    if (ch == '\\' && i < n) {
                        val esc = raw[i++]
                        when (esc) {
                            '\\', '"', '/' -> sb.append(esc)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                val value = sb.toString().trim()
                if (value.isNotEmpty() && seen.add(value)) {
                    result.add(value)
                }
            } else {
                i++
            }
        }
        return result
    }

    fun isComponentBlocked(pkg: String, cls: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty() || pkg.isBlank()) return false
        val exact = normalizeComponentKey(pkg, cls)
        return exact in blocked || packageWildcardKey(pkg) in blocked || pkg in blocked
    }

    fun componentPinIndex(pkg: String, cls: String, pinned: List<String>): Int {
        if (pinned.isEmpty() || pkg.isBlank()) return Int.MAX_VALUE
        val exact = normalizeComponentKey(pkg, cls)
        val exactIndex = pinned.indexOf(exact)
        if (exactIndex >= 0) return exactIndex
        val wildcardIndex = pinned.indexOf(packageWildcardKey(pkg))
        if (wildcardIndex >= 0) return wildcardIndex
        val pkgIndex = pinned.indexOf(pkg)
        return if (pkgIndex >= 0) pkgIndex else Int.MAX_VALUE
    }

    fun isDirectShareBlocked(pkg: String, shortcutId: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty() || pkg.isBlank()) return false
        val exact = if (shortcutId.isNotBlank()) directShareKey(pkg, shortcutId) else ""
        return (exact.isNotEmpty() && exact in blocked) ||
            directSharePackageWildcard(pkg) in blocked ||
            pkg in blocked
    }

    fun directSharePinIndex(pkg: String, shortcutId: String, pinned: List<String>): Int {
        if (pinned.isEmpty() || pkg.isBlank()) return Int.MAX_VALUE
        if (shortcutId.isNotBlank()) {
            val exactIndex = pinned.indexOf(directShareKey(pkg, shortcutId))
            if (exactIndex >= 0) return exactIndex
        }
        val wildcardIndex = pinned.indexOf(directSharePackageWildcard(pkg))
        if (wildcardIndex >= 0) return wildcardIndex
        val pkgIndex = pinned.indexOf(pkg)
        return if (pkgIndex >= 0) pkgIndex else Int.MAX_VALUE
    }

    fun encodeObservedEntries(entries: List<ResolverObservedEntry>): String {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decodeObservedEntries(raw: String?): List<ResolverObservedEntry> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(trimmed)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    ResolverObservedEntry.fromJson(obj)?.let { add(it) }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
