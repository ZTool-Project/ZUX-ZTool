package com.qimian233.ztool.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.resolver.ResolverObservedStore
import com.qimian233.ztool.data.resolver.ResolverRules

/**
 * Restricted IPC endpoint receiving observed resolver targets from com.zui.resolver.
 * Rejects callers outside system UID, self UID, and com.zui.resolver.
 */
class ResolverProbeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: throw IllegalStateException("Provider context unavailable")
        val callingUid = Binder.getCallingUid()
        if (!isAuthorizedCaller(ctx, callingUid)) {
            Log.w(TAG, "Rejected unauthorized caller uid=$callingUid for method=$method")
            throw SecurityException("Unauthorized caller uid=$callingUid")
        }

        return when (method) {
            METHOD_PING -> Bundle().apply {
                putBoolean(KEY_OK, true)
                putInt(KEY_CALLING_UID, callingUid)
            }
            METHOD_REPORT_TARGETS -> {
                val bucket = extras?.getString(EXTRA_BUCKET).orEmpty()
                val rawEntries = extras?.getString(EXTRA_ENTRIES_JSON) ?: arg.orEmpty()
                val entries = ResolverRules.decodeObservedEntries(rawEntries)
                val count = ResolverObservedStore(ctx).merge(bucket, entries)
                Log.d(TAG, "Merged ${entries.size} entries into bucket=$bucket (total=$count) from uid=$callingUid")
                Bundle().apply {
                    putBoolean(KEY_OK, true)
                    putInt(KEY_COUNT, count)
                }
            }
            else -> Bundle().apply {
                putBoolean(KEY_OK, false)
            }
        }
    }

    private fun isAuthorizedCaller(ctx: android.content.Context, callingUid: Int): Boolean {
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.myUid()) {
            return true
        }
        val packages = ctx.packageManager.getPackagesForUid(callingUid) ?: return false
        return ScopeKeys.ZUI_RESOLVER.packageName in packages
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "ResolverProbeProvider"
        const val AUTHORITY = "com.qimian233.ztool.resolver_probe"
        val URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_PING = "ping"
        const val METHOD_REPORT_TARGETS = "report_targets"

        const val EXTRA_BUCKET = "bucket"
        const val EXTRA_ENTRIES_JSON = "entries_json"

        const val KEY_OK = "ok"
        const val KEY_CALLING_UID = "calling_uid"
        const val KEY_COUNT = "count"
    }
}
