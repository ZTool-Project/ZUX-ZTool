package com.qimian233.ztool.data.update

/** Picks the newest release the installed build is allowed to receive. */
object ReleaseSelector {

    fun select(
        entries: List<ReleaseEntry>,
        installedCode: Int,
        installedChannel: ReleaseChannel,
        receivePrerelease: Boolean,
        ignoredCode: Int
    ): ReleaseEntry? {
        val allowedRank = maxOf(
            installedChannel.rank,
            if (receivePrerelease) ReleaseChannel.CANARY.rank else ReleaseChannel.STABLE.rank
        )
        val best = entries
            .asSequence()
            .filter { it.channel.rank <= allowedRank }
            .filter { it.versionCode > installedCode }
            .maxByOrNull { it.versionCode }
            ?: return null
        return best.takeIf { it.versionCode != ignoredCode }
    }
}
