package com.qimian233.ztool.data.update

/** One published GitHub release, as far as the update feed exposes it. */
data class ReleaseEntry(
    val tag: String,
    val versionCode: Int,
    val channel: ReleaseChannel,
    val releaseUrl: String,
    val notes: String,
    val hasMedia: Boolean
)
