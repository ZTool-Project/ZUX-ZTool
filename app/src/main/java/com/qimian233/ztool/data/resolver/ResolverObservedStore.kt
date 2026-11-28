package com.qimian233.ztool.data.resolver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Snapshot of hook-observed targets grouped by menu bucket. */
data class ObservedSnapshot(
    val open: List<ResolverObservedEntry> = emptyList(),
    val share: List<ResolverObservedEntry> = emptyList(),
    val directShare: List<ResolverObservedEntry> = emptyList()
)

/** File-backed atomic store in noBackupFilesDir for hook-reported target observations. */
class ResolverObservedStore(context: Context) {

    private val storeFile = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    fun merge(bucket: String, entries: List<ResolverObservedEntry>): Int {
        if (entries.isEmpty() || bucket !in VALID_BUCKETS) return 0
        synchronized(fileLock) {
            val current = readLocked()
            val targetMap = LinkedHashMap<String, ResolverObservedEntry>()
            bucketList(current, bucket).forEach { targetMap[it.key] = it }

            val now = System.currentTimeMillis()
            for (incoming in entries) {
                val existing = targetMap[incoming.key]
                val merged = if (existing == null) {
                    incoming.copy(seenAt = if (incoming.seenAt > 0L) incoming.seenAt else now)
                } else {
                    existing.copy(
                        label = incoming.label.ifEmpty { existing.label },
                        className = incoming.className.ifEmpty { existing.className },
                        shortcutId = incoming.shortcutId.ifEmpty { existing.shortcutId },
                        action = incoming.action.ifEmpty { existing.action },
                        mimeType = incoming.mimeType.ifEmpty { existing.mimeType },
                        userId = if (incoming.userId != 0) incoming.userId else existing.userId,
                        seenAt = maxOf(existing.seenAt, if (incoming.seenAt > 0L) incoming.seenAt else now)
                    )
                }
                targetMap[incoming.key] = merged
            }

            val trimmed = targetMap.values
                .sortedByDescending { it.seenAt }
                .take(MAX_ENTRIES_PER_BUCKET)

            val updated = when (bucket) {
                ResolverRules.BUCKET_OPEN -> current.copy(open = trimmed)
                ResolverRules.BUCKET_SHARE -> current.copy(share = trimmed)
                ResolverRules.BUCKET_DIRECT_SHARE -> current.copy(directShare = trimmed)
                else -> current
            }
            writeLocked(updated)
            return trimmed.size
        }
    }

    fun readAll(): ObservedSnapshot {
        synchronized(fileLock) {
            return readLocked()
        }
    }

    fun clear() {
        synchronized(fileLock) {
            if (storeFile.exists()) {
                storeFile.delete()
            }
        }
    }

    private fun bucketList(snapshot: ObservedSnapshot, bucket: String): List<ResolverObservedEntry> =
        when (bucket) {
            ResolverRules.BUCKET_OPEN -> snapshot.open
            ResolverRules.BUCKET_SHARE -> snapshot.share
            ResolverRules.BUCKET_DIRECT_SHARE -> snapshot.directShare
            else -> emptyList()
        }

    private fun readLocked(): ObservedSnapshot {
        if (!storeFile.isFile) return ObservedSnapshot()
        return try {
            val root = JSONObject(storeFile.readText(Charsets.UTF_8))
            ObservedSnapshot(
                open = decodeArray(root.optJSONArray(ResolverRules.BUCKET_OPEN)),
                share = decodeArray(root.optJSONArray(ResolverRules.BUCKET_SHARE)),
                directShare = decodeArray(root.optJSONArray(ResolverRules.BUCKET_DIRECT_SHARE))
            )
        } catch (_: Throwable) {
            ObservedSnapshot()
        }
    }

    private fun decodeArray(arr: JSONArray?): List<ResolverObservedEntry> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                ResolverObservedEntry.fromJson(obj)?.let { add(it) }
            }
        }
    }

    private fun writeLocked(snapshot: ObservedSnapshot) {
        val parent = storeFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val root = JSONObject().apply {
            put(ResolverRules.BUCKET_OPEN, JSONArray().apply { snapshot.open.forEach { put(it.toJson()) } })
            put(ResolverRules.BUCKET_SHARE, JSONArray().apply { snapshot.share.forEach { put(it.toJson()) } })
            put(ResolverRules.BUCKET_DIRECT_SHARE, JSONArray().apply { snapshot.directShare.forEach { put(it.toJson()) } })
        }
        val tempFile = File(storeFile.parentFile, "$FILE_NAME.tmp")
        tempFile.writeText(root.toString(), Charsets.UTF_8)
        if (!tempFile.renameTo(storeFile)) {
            storeFile.writeText(root.toString(), Charsets.UTF_8)
            tempFile.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "resolver_observed_cache.json"
        private const val MAX_ENTRIES_PER_BUCKET = 300
        private val VALID_BUCKETS = setOf(
            ResolverRules.BUCKET_OPEN,
            ResolverRules.BUCKET_SHARE,
            ResolverRules.BUCKET_DIRECT_SHARE
        )
        private val fileLock = Any()
    }
}
