package com.qimian233.ztool.data.home

/**
 * Canonical upstream coordinates of this project, used by the first-run source
 * verification page. They are deliberately kept out of the user-facing copy:
 * the page names the accepted forms but must never print the answer.
 */
object ZToolSource {
    const val GITHUB_OWNER = "ZTool-Project"
    const val GITHUB_REPO = "ZUX-ZTool"
    const val GITHUB_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    /** The owner before the 2026 organization transfer; old links still resolve there. */
    const val LEGACY_GITHUB_OWNER = "qwqawa64"
}

private const val GITHUB_HOST = "github.com"
private const val SCHEME_MARKER = "://"

/**
 * Whether [rawInput] identifies this project's GitHub repository. Four forms are
 * accepted, because users arrive here from stores that expose different amounts
 * of the address:
 *
 * 1. the bare repository name — `ZUX-ZTool`;
 * 2. `owner/repository` — `ZTool-Project/ZUX-ZTool` (or the legacy owner);
 * 3. the full URL — `https://github.com/ZTool-Project/ZUX-ZTool`;
 * 4. a URL with any suffix — `https://github.com/ZTool-Project/ZUX-ZTool/releases`.
 *
 * Matching is case-insensitive and tolerant of a missing scheme, a `www.`
 * prefix, and trailing slashes. Anything pointing at a different repository
 * fails.
 */
fun isVerifiedSourceInput(rawInput: String): Boolean {
    val candidate = rawInput.trim().lowercase()
    if (candidate.isEmpty()) return false

    val withoutScheme = candidate.substringAfter(SCHEME_MARKER, candidate).removePrefix("www.")
    val withoutHost = withoutScheme.removePrefix("$GITHUB_HOST/")

    val segments = withoutHost.split('/').filter { it.isNotBlank() }
    val owners = setOf(
        ZToolSource.GITHUB_OWNER.lowercase(),
        ZToolSource.LEGACY_GITHUB_OWNER.lowercase()
    )
    val repo = ZToolSource.GITHUB_REPO.lowercase()

    return when {
        segments.size == 1 -> segments[0] == repo
        segments.size >= 2 -> segments[0] in owners && segments[1] == repo
        else -> false
    }
}
