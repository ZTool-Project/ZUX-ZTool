package com.qimian233.ztool.data.resolver

import android.content.Context
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.utils.ModulePreferencesUtils
import com.qimian233.ztool.utils.ScopeUtils

enum class ResolverRuleBucket {
    OpenPinned,
    OpenBlocked,
    SharePinned,
    ShareBlocked,
    DirectSharePinned,
    DirectShareBlocked
}

data class ResolverSettingsSnapshot(
    val enabled: Boolean = false,
    val autoLaunchSingleOpen: Boolean = false,
    val hideAllDirectShare: Boolean = false,
    val openBlocked: List<String> = emptyList(),
    val openPinned: List<String> = emptyList(),
    val shareBlocked: List<String> = emptyList(),
    val sharePinned: List<String> = emptyList(),
    val directShareBlocked: List<String> = emptyList(),
    val directSharePinned: List<String> = emptyList(),
    val observedOpenCount: Int = 0,
    val observedShareCount: Int = 0,
    val observedDirectShareCount: Int = 0
)

sealed interface ResolverRestartResult {
    data object Success : ResolverRestartResult
    data class Failure(val message: String) : ResolverRestartResult
}

/** Persistence and discovery gateway for ZuiIntentResolver menu customization. */
class ResolverSettingsRepository(
    context: Context,
    private val shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance()
) {
    private val appContext = context.applicationContext ?: context
    private val prefsUtils = ModulePreferencesUtils(appContext)
    private val observedStore = ResolverObservedStore(appContext)
    private val scanner = ResolverTargetScanner(appContext, observedStore, shellExecutor)

    fun loadSettings(): ResolverSettingsSnapshot {
        val observed = observedStore.readAll()
        return ResolverSettingsSnapshot(
            enabled = prefsUtils.loadBooleanSetting(KEY_ENABLED, false),
            autoLaunchSingleOpen = prefsUtils.loadBooleanSetting(KEY_AUTO_LAUNCH_SINGLE, false),
            hideAllDirectShare = prefsUtils.loadBooleanSetting(KEY_HIDE_ALL_DIRECT_SHARE, false),
            openBlocked = ResolverRules.decodeList(prefsUtils.loadStringSetting(KEY_OPEN_BLOCKED, "")),
            openPinned = ResolverRules.decodeList(prefsUtils.loadStringSetting(KEY_OPEN_PINNED, "")),
            shareBlocked = ResolverRules.decodeList(prefsUtils.loadStringSetting(KEY_SHARE_BLOCKED, "")),
            sharePinned = ResolverRules.decodeList(prefsUtils.loadStringSetting(KEY_SHARE_PINNED, "")),
            directShareBlocked = ResolverRules.decodeList(prefsUtils.loadStringSetting(KEY_DIRECT_SHARE_BLOCKED, "")),
            directSharePinned = ResolverRules.decodeList(prefsUtils.loadStringSetting(KEY_DIRECT_SHARE_PINNED, "")),
            observedOpenCount = observed.open.size,
            observedShareCount = observed.share.size,
            observedDirectShareCount = observed.directShare.size
        )
    }

    fun saveEnabled(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_ENABLED, enabled)
    }

    fun saveAutoLaunchSingleOpen(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_AUTO_LAUNCH_SINGLE, enabled)
    }

    fun saveHideAllDirectShare(hidden: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_HIDE_ALL_DIRECT_SHARE, hidden)
    }

    fun saveRuleList(bucket: ResolverRuleBucket, items: List<String>) {
        val encoded = ResolverRules.encodeList(items)
        val key = when (bucket) {
            ResolverRuleBucket.OpenPinned -> KEY_OPEN_PINNED
            ResolverRuleBucket.OpenBlocked -> KEY_OPEN_BLOCKED
            ResolverRuleBucket.SharePinned -> KEY_SHARE_PINNED
            ResolverRuleBucket.ShareBlocked -> KEY_SHARE_BLOCKED
            ResolverRuleBucket.DirectSharePinned -> KEY_DIRECT_SHARE_PINNED
            ResolverRuleBucket.DirectShareBlocked -> KEY_DIRECT_SHARE_BLOCKED
        }
        prefsUtils.saveStringSetting(key, encoded)
    }

    fun scanTargets(
        snapshot: ResolverSettingsSnapshot,
        forceRootEnrichment: Boolean
    ): ResolverScanResult = scanner.scanAll(
        savedOpenKeys = (snapshot.openPinned + snapshot.openBlocked).distinct(),
        savedShareKeys = (snapshot.sharePinned + snapshot.shareBlocked).distinct(),
        savedDirectShareKeys = (snapshot.directSharePinned + snapshot.directShareBlocked).distinct(),
        forceRootEnrichment = forceRootEnrichment
    )

    fun clearObservedCache() {
        observedStore.clear()
    }

    /** Restarts only com.zui.resolver (system_server reboot is left to the user if single-target is toggled). */
    fun restartResolverScope(): ResolverRestartResult {
        return when (val result = ScopeUtils.restartScope(listOf(ScopeKeys.ZUI_RESOLVER), shellExecutor)) {
            is ScopeUtils.RestartResult.Success -> ResolverRestartResult.Success
            is ScopeUtils.RestartResult.PartialSuccess -> ResolverRestartResult.Failure(
                result.failed.joinToString()
            )
            is ScopeUtils.RestartResult.Failure -> ResolverRestartResult.Failure(result.message)
        }
    }

    companion object {
        private val KEY_ENABLED = PreferenceKeys.RESOLVER_MENU_ENHANCE.name
        private val KEY_AUTO_LAUNCH_SINGLE = PreferenceKeys.RESOLVER_OPEN_AUTO_LAUNCH_SINGLE.name
        private val KEY_HIDE_ALL_DIRECT_SHARE = PreferenceKeys.RESOLVER_DIRECT_SHARE_HIDE_ALL.name
        private val KEY_OPEN_BLOCKED = PreferenceKeys.RESOLVER_OPEN_BLOCKED.name
        private val KEY_OPEN_PINNED = PreferenceKeys.RESOLVER_OPEN_PINNED.name
        private val KEY_SHARE_BLOCKED = PreferenceKeys.RESOLVER_SHARE_BLOCKED.name
        private val KEY_SHARE_PINNED = PreferenceKeys.RESOLVER_SHARE_PINNED.name
        private val KEY_DIRECT_SHARE_BLOCKED = PreferenceKeys.RESOLVER_DIRECT_SHARE_BLOCKED.name
        private val KEY_DIRECT_SHARE_PINNED = PreferenceKeys.RESOLVER_DIRECT_SHARE_PINNED.name
    }
}
