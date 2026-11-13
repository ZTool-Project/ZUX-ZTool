package com.qimian233.ztool.data.update

/** Picks the newest release the receive-beta switch allows. */
object ReleaseSelector {

    fun select(
        entries: List<ReleaseEntry>,
        installedCode: Int,
        receivePrerelease: Boolean,
        ignoredCode: Int
    ): ReleaseEntry? {
        val allowedRank = if (receivePrerelease) ReleaseChannel.CANARY.rank else ReleaseChannel.STABLE.rank
        val best = entries
            .asSequence()
            .filter { it.channel.rank <= allowedRank }
            .filter { it.versionCode > installedCode }
            .maxByOrNull { it.versionCode }
            ?: return null
        return best.takeIf { it.versionCode != ignoredCode }
    }
}
