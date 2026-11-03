package com.qimian233.ztool.data.update

/** Visibility order of an update channel; a higher rank may install lower-ranked releases. */
enum class ReleaseChannel(val rank: Int) {
    STABLE(0),
    BETA(1),
    CANARY(2);

    companion object {
        /** Reads the channel back from an installed or tagged version name. */
        fun ofVersionName(versionName: String): ReleaseChannel {
            val normalized = versionName.lowercase()
            return when {
                normalized.contains("-canary") || normalized.contains("-dev") -> CANARY
                normalized.contains("-beta") || normalized.contains("-rc") -> BETA
                else -> STABLE
            }
        }

        fun ofTag(tag: String): ReleaseChannel? {
            val semver = SemVer.parseTag(tag) ?: return null
            val prerelease = semver.prerelease ?: return STABLE
            return if (prerelease.firstOrNull().orEmpty().startsWith("canary")) CANARY else BETA
        }
    }
}
