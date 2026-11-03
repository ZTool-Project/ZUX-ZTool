package com.qimian233.ztool.data.changelog

import android.content.Context

/** One packaged release note: [version] from its file name, [markdown] without the leading H1. */
data class ChangelogEntry(
    val version: String,
    val markdown: String
)

/** Reads the release notes that `docs/changelog` packages into the APK assets at build time. */
class ChangelogRepository(context: Context) {
    private val assets = context.applicationContext.assets

    fun loadEntries(): List<ChangelogEntry> {
        return runCatching {
            assets.list(ASSET_ROOT).orEmpty()
                .filter { it.startsWith("v") }
                .flatMap { directory ->
                    val directoryPath = "$ASSET_ROOT/$directory"
                    assets.list(directoryPath).orEmpty()
                        .filter { it.endsWith(MARKDOWN_SUFFIX) }
                        .map { fileName ->
                            ChangelogEntry(
                                version = fileName.removeSuffix(MARKDOWN_SUFFIX),
                                markdown = readMarkdown("$directoryPath/$fileName")
                            )
                        }
                }
                .sortedWith { left, right -> compareVersions(right.version, left.version) }
        }.getOrDefault(emptyList())
    }

    private fun readMarkdown(assetPath: String): String {
        val raw = assets.open(assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .trim()
        val lines = raw.lines()
        val body = if (lines.firstOrNull()?.startsWith("# ") == true) lines.drop(1) else lines
        return body.joinToString("\n").trim()
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftCore = left.removePrefix("v").substringBefore('-')
        val rightCore = right.removePrefix("v").substringBefore('-')
        val leftParts = leftCore.split('.')
        val rightParts = rightCore.split('.')
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val difference = (leftParts.getOrNull(index)?.toIntOrNull() ?: 0)
                .compareTo(rightParts.getOrNull(index)?.toIntOrNull() ?: 0)
            if (difference != 0) return difference
        }
        val leftPreRelease = left.substringAfter('-', "")
        val rightPreRelease = right.substringAfter('-', "")
        return when {
            leftPreRelease == rightPreRelease -> 0
            leftPreRelease.isEmpty() -> 1
            rightPreRelease.isEmpty() -> -1
            else -> comparePreRelease(leftPreRelease, rightPreRelease)
        }
    }

    private fun comparePreRelease(left: String, right: String): Int {
        val leftIdentifiers = left.split('.')
        val rightIdentifiers = right.split('.')
        for (index in 0 until maxOf(leftIdentifiers.size, rightIdentifiers.size)) {
            val leftIdentifier = leftIdentifiers.getOrNull(index) ?: return -1
            val rightIdentifier = rightIdentifiers.getOrNull(index) ?: return 1
            val leftNumber = leftIdentifier.toIntOrNull()
            val rightNumber = rightIdentifier.toIntOrNull()
            val difference = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftIdentifier.compareTo(rightIdentifier)
            }
            if (difference != 0) return difference
        }
        return 0
    }

    private companion object {
        const val ASSET_ROOT = "changelog"
        const val MARKDOWN_SUFFIX = ".md"
    }
}
