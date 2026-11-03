package com.qimian233.ztool.data.update

/** Minimal SemVer 2.0.0 value for release tags; build metadata is not accepted in tags. */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String>?
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }

        val left = prerelease
        val right = other.prerelease
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1

        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index]
            val b = right[index]
            val aNumber = a.toIntOrNull()
            val bNumber = b.toIntOrNull()
            val result = when {
                aNumber != null && bNumber != null -> compareValues(aNumber, bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return compareValues(left.size, right.size)
    }

    companion object {
        private val TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""")

        fun parseTag(tag: String): SemVer? {
            val match = TAG.matchEntire(tag) ?: return null
            val prerelease = match.groupValues[4]
                .takeIf { it.isNotEmpty() }
                ?.split('.')
                ?.takeIf { parts -> parts.all { it.isNotEmpty() } }
            return SemVer(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                prerelease = prerelease
            )
        }
    }
}
