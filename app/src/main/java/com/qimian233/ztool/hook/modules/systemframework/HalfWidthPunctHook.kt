package com.qimian233.ztool.hook.modules.systemframework

import android.os.SystemClock
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import com.qimian233.ztool.hook.modules.systemframework.input.CommitKind
import com.qimian233.ztool.hook.modules.systemframework.input.HalfWidthPunctRules
import com.qimian233.ztool.hook.modules.systemframework.input.InputConnectionInterceptor
import com.qimian233.ztool.hook.modules.systemframework.input.InputConnectionProxyHub
import com.qimian233.ztool.hook.modules.systemframework.input.InputConnectionSession
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

// See docs/research/input_connection_system_server_proxy.md.

/**
 * Forces configured full-width symbols to half-width ASCII across all IMEs
 * by intercepting proxied IRemoteInputConnection commits in system_server.
 */
class HalfWidthPunctHook : SystemHookModule(), InputConnectionInterceptor {

    override val id: String = INTERCEPTOR_ID
    override val priority: Int = 100

    private val configLock = Any()

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var punctTable: Map<Char, Char> = emptyMap()

    @Volatile
    private var lastRawSigns: String? = null

    @Volatile
    private var snapshotLoadedAt: Long = -SNAPSHOT_REFRESH_INTERVAL_MS

    override fun getModuleName(): String = PreferenceKeys.HALF_WIDTH_PUNCT.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        refreshSnapshotIfStale(force = true)
        InputConnectionProxyHub.registerInterceptor(this)
        val installed = InputConnectionProxyHub.ensureInstalled(xposed, param.classLoader, logger)
        if (installed) {
            logger.info("HalfWidthPunctHook registered on InputConnectionProxyHub, table=$punctTable")
        } else {
            logger.warn("HalfWidthPunctHook proxy installation failed")
        }
    }

    override fun isActive(): Boolean {
        refreshSnapshotIfStale()
        return enabled && punctTable.isNotEmpty()
    }

    override fun rewriteCommitText(
        session: InputConnectionSession,
        text: CharSequence,
        kind: CommitKind
    ): CharSequence? {
        refreshSnapshotIfStale()
        if (!enabled || session.isPasswordField) return null
        val rewritten = HalfWidthPunctRules.rewrite(text, punctTable) ?: return null
        logger.debug("punct [${session.packageName}]: $text -> $rewritten")
        return rewritten
    }

    private fun refreshSnapshotIfStale(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - snapshotLoadedAt <= SNAPSHOT_REFRESH_INTERVAL_MS) return
        synchronized(configLock) {
            val current = SystemClock.elapsedRealtime()
            if (!force && current - snapshotLoadedAt <= SNAPSHOT_REFRESH_INTERVAL_MS) return
            try {
                val prefs = remotePreferences
                enabled = prefs.getBoolean(
                    PreferenceKeys.HALF_WIDTH_PUNCT.name,
                    PreferenceKeys.HALF_WIDTH_PUNCT.default
                )
                val rawSigns = prefs.getString(
                    PreferenceKeys.HALF_WIDTH_PUNCT_SIGNS.name,
                    PreferenceKeys.HALF_WIDTH_PUNCT_SIGNS.default
                ) ?: PreferenceKeys.HALF_WIDTH_PUNCT_SIGNS.default
                if (rawSigns != lastRawSigns) {
                    punctTable = HalfWidthPunctRules.buildPunctTable(rawSigns)
                    lastRawSigns = rawSigns
                }
            } catch (_: Throwable) {
                // Keep last known snapshot when remote preferences are temporarily unreachable.
            }
            snapshotLoadedAt = current
        }
    }

    companion object {
        private const val INTERCEPTOR_ID = "half_width_punct"
        private const val SNAPSHOT_REFRESH_INTERVAL_MS = 2_000L
    }
}
