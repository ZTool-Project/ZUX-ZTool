package com.qimian233.ztool.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimian233.ztool.data.resolver.ResolverCandidate
import com.qimian233.ztool.data.resolver.ResolverRestartResult
import com.qimian233.ztool.data.resolver.ResolverRuleBucket
import com.qimian233.ztool.data.resolver.ResolverRules
import com.qimian233.ztool.data.resolver.ResolverSettingsRepository
import com.qimian233.ztool.data.resolver.ResolverSettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ResolverDialogKind {
    OpenPinned,
    OpenBlocked,
    SharePinned,
    ShareBlocked,
    DirectSharePinned,
    DirectShareBlocked
}

data class ResolverMenuSettingsUiState(
    val enabled: Boolean = false,
    val autoLaunchSingleOpen: Boolean = false,
    val hideAllDirectShare: Boolean = false,
    val openPinned: List<String> = emptyList(),
    val openBlocked: List<String> = emptyList(),
    val sharePinned: List<String> = emptyList(),
    val shareBlocked: List<String> = emptyList(),
    val directSharePinned: List<String> = emptyList(),
    val directShareBlocked: List<String> = emptyList(),
    val openCandidates: List<ResolverCandidate> = emptyList(),
    val shareCandidates: List<ResolverCandidate> = emptyList(),
    val directShareCandidates: List<ResolverCandidate> = emptyList(),
    val observedOpenCount: Int = 0,
    val observedShareCount: Int = 0,
    val observedDirectShareCount: Int = 0,
    val isScanning: Boolean = false,
    val activeDialog: ResolverDialogKind? = null,
    val showRestartDialog: Boolean = false
)

/** ViewModel backing Share, Open-With, and Direct Share rule customization and discovery. */
class ResolverMenuSettingsViewModel(
    private val repository: ResolverSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResolverMenuSettingsUiState())
    val uiState: StateFlow<ResolverMenuSettingsUiState> = _uiState.asStateFlow()

    fun loadSettings() {
        val snapshot = repository.loadSettings()
        applySnapshot(snapshot)
        scanTargets(forceRootEnrichment = false, snapshotOverride = snapshot)
    }

    fun refreshTargetsWithRoot() {
        val snapshot = repository.loadSettings()
        applySnapshot(snapshot)
        scanTargets(forceRootEnrichment = true, snapshotOverride = snapshot)
    }

    private fun applySnapshot(snapshot: ResolverSettingsSnapshot) {
        _uiState.value = _uiState.value.copy(
            enabled = snapshot.enabled,
            autoLaunchSingleOpen = snapshot.autoLaunchSingleOpen,
            hideAllDirectShare = snapshot.hideAllDirectShare,
            openPinned = snapshot.openPinned,
            openBlocked = snapshot.openBlocked,
            sharePinned = snapshot.sharePinned,
            shareBlocked = snapshot.shareBlocked,
            directSharePinned = snapshot.directSharePinned,
            directShareBlocked = snapshot.directShareBlocked,
            observedOpenCount = snapshot.observedOpenCount,
            observedShareCount = snapshot.observedShareCount,
            observedDirectShareCount = snapshot.observedDirectShareCount
        )
    }

    private fun scanTargets(
        forceRootEnrichment: Boolean,
        snapshotOverride: ResolverSettingsSnapshot? = null
    ) {
        _uiState.value = _uiState.value.copy(isScanning = true)
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = snapshotOverride ?: repository.loadSettings()
            val result = repository.scanTargets(snapshot, forceRootEnrichment)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    openCandidates = result.openTargets,
                    shareCandidates = result.shareTargets,
                    directShareCandidates = result.directShareTargets,
                    observedOpenCount = snapshot.observedOpenCount,
                    observedShareCount = snapshot.observedShareCount,
                    observedDirectShareCount = snapshot.observedDirectShareCount,
                    isScanning = false
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled)
        repository.saveEnabled(enabled)
    }

    fun setAutoLaunchSingleOpen(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoLaunchSingleOpen = enabled)
        repository.saveAutoLaunchSingleOpen(enabled)
    }

    fun setHideAllDirectShare(hidden: Boolean) {
        _uiState.value = _uiState.value.copy(hideAllDirectShare = hidden)
        repository.saveHideAllDirectShare(hidden)
    }

    fun openDialog(kind: ResolverDialogKind) {
        _uiState.value = _uiState.value.copy(activeDialog = kind)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(activeDialog = null)
    }

    fun toggleBlockedKey(kind: ResolverDialogKind, key: String) {
        val normalized = key.trim()
        if (normalized.isEmpty()) return
        when (kind) {
            ResolverDialogKind.OpenBlocked -> {
                val updated = toggleInList(_uiState.value.openBlocked, normalized)
                val cleanedPinned = _uiState.value.openPinned.filterNot { it == normalized }
                _uiState.value = _uiState.value.copy(openBlocked = updated, openPinned = cleanedPinned)
                repository.saveRuleList(ResolverRuleBucket.OpenBlocked, updated)
                repository.saveRuleList(ResolverRuleBucket.OpenPinned, cleanedPinned)
            }
            ResolverDialogKind.ShareBlocked -> {
                val updated = toggleInList(_uiState.value.shareBlocked, normalized)
                val cleanedPinned = _uiState.value.sharePinned.filterNot { it == normalized }
                _uiState.value = _uiState.value.copy(shareBlocked = updated, sharePinned = cleanedPinned)
                repository.saveRuleList(ResolverRuleBucket.ShareBlocked, updated)
                repository.saveRuleList(ResolverRuleBucket.SharePinned, cleanedPinned)
            }
            ResolverDialogKind.DirectShareBlocked -> {
                val updated = toggleInList(_uiState.value.directShareBlocked, normalized)
                val cleanedPinned = _uiState.value.directSharePinned.filterNot { it == normalized }
                _uiState.value = _uiState.value.copy(
                    directShareBlocked = updated,
                    directSharePinned = cleanedPinned
                )
                repository.saveRuleList(ResolverRuleBucket.DirectShareBlocked, updated)
                repository.saveRuleList(ResolverRuleBucket.DirectSharePinned, cleanedPinned)
            }
            else -> Unit
        }
    }

    fun addPinnedKey(kind: ResolverDialogKind, key: String) {
        val normalized = key.trim()
        if (normalized.isEmpty()) return
        when (kind) {
            ResolverDialogKind.OpenPinned -> {
                val current = _uiState.value.openPinned
                if (normalized in current) return
                val updated = current + normalized
                val cleanedBlocked = _uiState.value.openBlocked.filterNot { it == normalized }
                _uiState.value = _uiState.value.copy(openPinned = updated, openBlocked = cleanedBlocked)
                repository.saveRuleList(ResolverRuleBucket.OpenPinned, updated)
                repository.saveRuleList(ResolverRuleBucket.OpenBlocked, cleanedBlocked)
            }
            ResolverDialogKind.SharePinned -> {
                val current = _uiState.value.sharePinned
                if (normalized in current) return
                val updated = current + normalized
                val cleanedBlocked = _uiState.value.shareBlocked.filterNot { it == normalized }
                _uiState.value = _uiState.value.copy(sharePinned = updated, shareBlocked = cleanedBlocked)
                repository.saveRuleList(ResolverRuleBucket.SharePinned, updated)
                repository.saveRuleList(ResolverRuleBucket.ShareBlocked, cleanedBlocked)
            }
            ResolverDialogKind.DirectSharePinned -> {
                val current = _uiState.value.directSharePinned
                if (normalized in current) return
                val updated = current + normalized
                val cleanedBlocked = _uiState.value.directShareBlocked.filterNot { it == normalized }
                _uiState.value = _uiState.value.copy(
                    directSharePinned = updated,
                    directShareBlocked = cleanedBlocked
                )
                repository.saveRuleList(ResolverRuleBucket.DirectSharePinned, updated)
                repository.saveRuleList(ResolverRuleBucket.DirectShareBlocked, cleanedBlocked)
            }
            else -> Unit
        }
    }

    fun removePinnedKey(kind: ResolverDialogKind, key: String) {
        updatePinnedList(kind) { list -> list.filterNot { it == key } }
    }

    fun movePinnedKey(kind: ResolverDialogKind, index: Int, delta: Int) {
        updatePinnedList(kind) { list ->
            val target = index + delta
            if (index !in list.indices || target !in list.indices) return@updatePinnedList list
            list.toMutableList().apply {
                val item = removeAt(index)
                add(target, item)
            }
        }
    }

    fun clearRuleList(kind: ResolverDialogKind) {
        when (kind) {
            ResolverDialogKind.OpenPinned -> {
                _uiState.value = _uiState.value.copy(openPinned = emptyList())
                repository.saveRuleList(ResolverRuleBucket.OpenPinned, emptyList())
            }
            ResolverDialogKind.OpenBlocked -> {
                _uiState.value = _uiState.value.copy(openBlocked = emptyList())
                repository.saveRuleList(ResolverRuleBucket.OpenBlocked, emptyList())
            }
            ResolverDialogKind.SharePinned -> {
                _uiState.value = _uiState.value.copy(sharePinned = emptyList())
                repository.saveRuleList(ResolverRuleBucket.SharePinned, emptyList())
            }
            ResolverDialogKind.ShareBlocked -> {
                _uiState.value = _uiState.value.copy(shareBlocked = emptyList())
                repository.saveRuleList(ResolverRuleBucket.ShareBlocked, emptyList())
            }
            ResolverDialogKind.DirectSharePinned -> {
                _uiState.value = _uiState.value.copy(directSharePinned = emptyList())
                repository.saveRuleList(ResolverRuleBucket.DirectSharePinned, emptyList())
            }
            ResolverDialogKind.DirectShareBlocked -> {
                _uiState.value = _uiState.value.copy(directShareBlocked = emptyList())
                repository.saveRuleList(ResolverRuleBucket.DirectShareBlocked, emptyList())
            }
        }
    }

    fun clearObservedCache() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearObservedCache()
            val snapshot = repository.loadSettings()
            withContext(Dispatchers.Main) {
                applySnapshot(snapshot)
            }
            scanTargets(forceRootEnrichment = false, snapshotOverride = snapshot)
        }
    }

    fun showRestartDialog() {
        _uiState.value = _uiState.value.copy(showRestartDialog = true)
    }

    fun dismissRestartDialog() {
        _uiState.value = _uiState.value.copy(showRestartDialog = false)
    }

    fun restartScope(onFailure: () -> Unit) {
        _uiState.value = _uiState.value.copy(showRestartDialog = false)
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.restartResolverScope()
            if (result is ResolverRestartResult.Failure) {
                withContext(Dispatchers.Main) {
                    onFailure()
                }
            }
        }
    }

    private inline fun updatePinnedList(
        kind: ResolverDialogKind,
        transform: (List<String>) -> List<String>
    ) {
        when (kind) {
            ResolverDialogKind.OpenPinned -> {
                val updated = transform(_uiState.value.openPinned)
                _uiState.value = _uiState.value.copy(openPinned = updated)
                repository.saveRuleList(ResolverRuleBucket.OpenPinned, updated)
            }
            ResolverDialogKind.SharePinned -> {
                val updated = transform(_uiState.value.sharePinned)
                _uiState.value = _uiState.value.copy(sharePinned = updated)
                repository.saveRuleList(ResolverRuleBucket.SharePinned, updated)
            }
            ResolverDialogKind.DirectSharePinned -> {
                val updated = transform(_uiState.value.directSharePinned)
                _uiState.value = _uiState.value.copy(directSharePinned = updated)
                repository.saveRuleList(ResolverRuleBucket.DirectSharePinned, updated)
            }
            else -> Unit
        }
    }

    private fun toggleInList(list: List<String>, item: String): List<String> =
        if (item in list) list.filterNot { it == item } else list + item

    companion object {
        fun packageRuleKey(packageName: String, isDirectShare: Boolean): String =
            if (isDirectShare) {
                ResolverRules.directSharePackageWildcard(packageName)
            } else {
                ResolverRules.packageWildcardKey(packageName)
            }
    }
}
