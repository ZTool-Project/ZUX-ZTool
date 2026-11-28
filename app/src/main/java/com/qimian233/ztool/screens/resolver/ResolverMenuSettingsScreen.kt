package com.qimian233.ztool.screens.resolver

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.qimian233.ztool.R
import com.qimian233.ztool.data.resolver.CandidateSource
import com.qimian233.ztool.data.resolver.ResolverCandidate
import com.qimian233.ztool.data.resolver.ResolverSettingsRepository
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.SettingItem
import com.qimian233.ztool.ui.components.SettingSection
import com.qimian233.ztool.ui.components.ZToolDialog
import com.qimian233.ztool.ui.components.ZToolExtendedFloatingActionButton
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolSearchableListDialog
import com.qimian233.ztool.ui.components.ZToolSettingsList
import com.qimian233.ztool.ui.components.ZToolTextButton
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.ui.icons.ZToolIcons
import com.qimian233.ztool.viewmodel.ResolverDialogKind
import com.qimian233.ztool.viewmodel.ResolverMenuSettingsUiState
import com.qimian233.ztool.viewmodel.ResolverMenuSettingsViewModel

@Composable
fun ResolverMenuSettingsRoute(
    title: String,
    packageName: String,
    onBack: () -> Unit,
    targetId: String? = null
) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
        ?: error("ResolverMenuSettingsRoute requires a ViewModelStoreOwner")
    val viewModel = remember(owner) {
        ViewModelProvider(
            owner,
            ResolverMenuSettingsViewModelFactory(
                ResolverSettingsRepository(context.applicationContext)
            )
        )[ResolverMenuSettingsViewModel::class.java]
    }

    LaunchedEffect(viewModel) {
        viewModel.loadSettings()
    }

    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val highlightRegistry = remember { HighlightAnchorRegistry() }

    HighlightController(
        highlightTargetId = targetId,
        scrollState = scrollState,
        registry = highlightRegistry,
        onConsumed = { }
    ) {
        ResolverMenuSettingsScreen(
            title = title,
            state = uiState,
            onBack = onBack,
            onEnabledChanged = viewModel::setEnabled,
            onAutoLaunchSingleChanged = viewModel::setAutoLaunchSingleOpen,
            onHideAllDirectShareChanged = viewModel::setHideAllDirectShare,
            onOpenDialog = viewModel::openDialog,
            onRescanTargets = viewModel::refreshTargetsWithRoot,
            onClearObserved = {
                viewModel.clearObservedCache()
                Toast.makeText(context, R.string.resolver_observed_cleared_message, Toast.LENGTH_SHORT).show()
            },
            onRestartScope = viewModel::showRestartDialog,
            scrollState = scrollState,
            highlightRegistry = highlightRegistry
        )
    }

    uiState.activeDialog?.let { dialogKind ->
        ResolverRuleDialogHost(
            kind = dialogKind,
            state = uiState,
            onDismiss = viewModel::dismissDialog,
            onToggleBlocked = { key -> viewModel.toggleBlockedKey(dialogKind, key) },
            onAddPinned = { key -> viewModel.addPinnedKey(dialogKind, key) },
            onRemovePinned = { key -> viewModel.removePinnedKey(dialogKind, key) },
            onMovePinned = { idx, delta -> viewModel.movePinnedKey(dialogKind, idx, delta) },
            onClearAll = { viewModel.clearRuleList(dialogKind) }
        )
    }

    if (uiState.showRestartDialog) {
        ZToolDialog(
            onDismissRequest = viewModel::dismissRestartDialog,
            title = { Text(stringResource(R.string.common_restart_xp_title)) },
            text = {
                Text(
                    stringResource(R.string.common_restart_xp_message_header) +
                        packageName + " " +
                        stringResource(R.string.common_restart_xp_message)
                )
            },
            confirmButton = {
                ZToolTextButton(
                    onClick = {
                        viewModel.restartScope(
                            onFailure = {
                                Toast.makeText(
                                    context,
                                    R.string.system_update_restart_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    text = stringResource(R.string.common_restart_yes)
                )
            },
            dismissButton = {
                ZToolTextButton(
                    onClick = viewModel::dismissRestartDialog,
                    text = stringResource(R.string.common_restart_no),
                    isPrimary = false
                )
            }
        )
    }
}

private class ResolverMenuSettingsViewModelFactory(
    private val repository: ResolverSettingsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ResolverMenuSettingsViewModel::class.java)) {
            return ResolverMenuSettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
private fun ResolverMenuSettingsScreen(
    title: String,
    state: ResolverMenuSettingsUiState,
    onBack: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onAutoLaunchSingleChanged: (Boolean) -> Unit,
    onHideAllDirectShareChanged: (Boolean) -> Unit,
    onOpenDialog: (ResolverDialogKind) -> Unit,
    onRescanTargets: () -> Unit,
    onClearObserved: () -> Unit,
    onRestartScope: () -> Unit,
    scrollState: ScrollState,
    highlightRegistry: HighlightAnchorRegistry
) {
    ZToolScaffold(
        topBar = {
            ZToolTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ZToolIcons.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            ZToolExtendedFloatingActionButton(
                onClick = onRestartScope,
                icon = { Icon(ZToolIcons.Refresh, contentDescription = null) },
                text = { Text(stringResource(R.string.common_restart_yes)) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 960.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                ZToolSettingsList(
                    sections = resolverSettingsSections(
                        state = state,
                        onEnabledChanged = onEnabledChanged,
                        onAutoLaunchSingleChanged = onAutoLaunchSingleChanged,
                        onHideAllDirectShareChanged = onHideAllDirectShareChanged,
                        onOpenDialog = onOpenDialog,
                        onRescanTargets = onRescanTargets,
                        onClearObserved = onClearObserved
                    ),
                    bottomPadding = 88.dp,
                    highlightRegistry = highlightRegistry
                )
            }
        }
    }
}

@Composable
private fun resolverSettingsSections(
    state: ResolverMenuSettingsUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onAutoLaunchSingleChanged: (Boolean) -> Unit,
    onHideAllDirectShareChanged: (Boolean) -> Unit,
    onOpenDialog: (ResolverDialogKind) -> Unit,
    onRescanTargets: () -> Unit,
    onClearObserved: () -> Unit
): List<SettingSection> {
    val generalSection = SettingSection(
        title = stringResource(R.string.resolver_general_section_title),
        items = buildList {
            add(
                SettingItem.Switch(
                    title = stringResource(R.string.resolver_menu_enhance_title),
                    summary = stringResource(R.string.resolver_menu_enhance_summary),
                    checked = state.enabled,
                    onCheckedChange = onEnabledChanged,
                    icon = ZToolIcons.Share,
                    key = "resolver_menu_enhance"
                )
            )
            if (state.enabled) {
                add(
                    SettingItem.Switch(
                        title = stringResource(R.string.resolver_open_auto_launch_single_title),
                        summary = stringResource(R.string.resolver_open_auto_launch_single_summary),
                        checked = state.autoLaunchSingleOpen,
                        onCheckedChange = onAutoLaunchSingleChanged,
                        icon = ZToolIcons.OpenInNew,
                        key = "resolver_open_auto_launch_single"
                    )
                )
            }
        }
    )

    if (!state.enabled) {
        return listOf(generalSection)
    }

    val openSection = SettingSection(
        title = stringResource(R.string.resolver_open_section_title),
        items = listOf(
            SettingItem.Entry(
                title = stringResource(R.string.resolver_open_pinned_title),
                summary = if (state.openPinned.isEmpty()) {
                    stringResource(R.string.resolver_open_pinned_summary_empty)
                } else {
                    stringResource(R.string.resolver_open_pinned_summary_count, state.openPinned.size)
                },
                icon = ZToolIcons.PushPin,
                onClick = { onOpenDialog(ResolverDialogKind.OpenPinned) },
                key = "resolver_open_pinned"
            ),
            SettingItem.Entry(
                title = stringResource(R.string.resolver_open_blocked_title),
                summary = if (state.openBlocked.isEmpty()) {
                    stringResource(R.string.resolver_open_blocked_summary_empty)
                } else {
                    stringResource(R.string.resolver_open_blocked_summary_count, state.openBlocked.size)
                },
                icon = ZToolIcons.VisibilityOff,
                onClick = { onOpenDialog(ResolverDialogKind.OpenBlocked) },
                key = "resolver_open_blocked"
            )
        )
    )

    val shareSection = SettingSection(
        title = stringResource(R.string.resolver_share_section_title),
        items = listOf(
            SettingItem.Entry(
                title = stringResource(R.string.resolver_share_pinned_title),
                summary = if (state.sharePinned.isEmpty()) {
                    stringResource(R.string.resolver_share_pinned_summary_empty)
                } else {
                    stringResource(R.string.resolver_share_pinned_summary_count, state.sharePinned.size)
                },
                icon = ZToolIcons.PushPin,
                onClick = { onOpenDialog(ResolverDialogKind.SharePinned) },
                key = "resolver_share_pinned"
            ),
            SettingItem.Entry(
                title = stringResource(R.string.resolver_share_blocked_title),
                summary = if (state.shareBlocked.isEmpty()) {
                    stringResource(R.string.resolver_share_blocked_summary_empty)
                } else {
                    stringResource(R.string.resolver_share_blocked_summary_count, state.shareBlocked.size)
                },
                icon = ZToolIcons.VisibilityOff,
                onClick = { onOpenDialog(ResolverDialogKind.ShareBlocked) },
                key = "resolver_share_blocked"
            )
        )
    )

    val directShareSection = SettingSection(
        title = stringResource(R.string.resolver_direct_share_section_title),
        items = buildList {
            add(
                SettingItem.Switch(
                    title = stringResource(R.string.resolver_direct_share_hide_all_title),
                    summary = stringResource(R.string.resolver_direct_share_hide_all_summary),
                    checked = state.hideAllDirectShare,
                    onCheckedChange = onHideAllDirectShareChanged,
                    icon = ZToolIcons.HideSource,
                    key = "resolver_direct_share_hide_all"
                )
            )
            if (!state.hideAllDirectShare) {
                add(
                    SettingItem.Entry(
                        title = stringResource(R.string.resolver_direct_share_pinned_title),
                        summary = if (state.directSharePinned.isEmpty()) {
                            stringResource(R.string.resolver_direct_share_pinned_summary_empty)
                        } else {
                            stringResource(R.string.resolver_direct_share_pinned_summary_count, state.directSharePinned.size)
                        },
                        icon = ZToolIcons.PushPin,
                        onClick = { onOpenDialog(ResolverDialogKind.DirectSharePinned) },
                        key = "resolver_direct_share_pinned"
                    )
                )
                add(
                    SettingItem.Entry(
                        title = stringResource(R.string.resolver_direct_share_blocked_title),
                        summary = if (state.directShareBlocked.isEmpty()) {
                            stringResource(R.string.resolver_direct_share_blocked_summary_empty)
                        } else {
                            stringResource(R.string.resolver_direct_share_blocked_summary_count, state.directShareBlocked.size)
                        },
                        icon = ZToolIcons.Delete,
                        onClick = { onOpenDialog(ResolverDialogKind.DirectShareBlocked) },
                        key = "resolver_direct_share_blocked"
                    )
                )
            }
        }
    )

    val discoverySection = SettingSection(
        title = stringResource(R.string.resolver_discovery_section_title),
        items = listOf(
            SettingItem.Action(
                title = stringResource(R.string.resolver_rescan_targets_title),
                summary = if (state.isScanning) {
                    stringResource(R.string.resolver_rescan_targets_scanning_summary)
                } else {
                    stringResource(
                        R.string.resolver_rescan_targets_summary,
                        state.openCandidates.size,
                        state.shareCandidates.size,
                        state.directShareCandidates.size,
                        state.observedOpenCount,
                        state.observedShareCount,
                        state.observedDirectShareCount
                    )
                },
                icon = ZToolIcons.Refresh,
                enabled = !state.isScanning,
                onClick = onRescanTargets,
                key = "resolver_rescan_targets"
            ),
            SettingItem.Action(
                title = stringResource(R.string.resolver_clear_observed_title),
                summary = stringResource(R.string.resolver_clear_observed_summary),
                icon = ZToolIcons.DeleteForever,
                onClick = onClearObserved,
                key = "resolver_clear_observed"
            )
        )
    )

    return listOf(
        generalSection,
        openSection,
        shareSection,
        directShareSection,
        discoverySection
    )
}

@Composable
private fun ResolverRuleDialogHost(
    kind: ResolverDialogKind,
    state: ResolverMenuSettingsUiState,
    onDismiss: () -> Unit,
    onToggleBlocked: (String) -> Unit,
    onAddPinned: (String) -> Unit,
    onRemovePinned: (String) -> Unit,
    onMovePinned: (Int, Int) -> Unit,
    onClearAll: () -> Unit
) {
    val isDirectShare = kind == ResolverDialogKind.DirectSharePinned ||
        kind == ResolverDialogKind.DirectShareBlocked
    val isPinnedDialog = kind == ResolverDialogKind.OpenPinned ||
        kind == ResolverDialogKind.SharePinned ||
        kind == ResolverDialogKind.DirectSharePinned

    val candidates = when (kind) {
        ResolverDialogKind.OpenPinned, ResolverDialogKind.OpenBlocked -> state.openCandidates
        ResolverDialogKind.SharePinned, ResolverDialogKind.ShareBlocked -> state.shareCandidates
        ResolverDialogKind.DirectSharePinned, ResolverDialogKind.DirectShareBlocked -> state.directShareCandidates
    }

    val titleRes = when (kind) {
        ResolverDialogKind.OpenPinned -> R.string.resolver_open_pinned_title
        ResolverDialogKind.OpenBlocked -> R.string.resolver_open_blocked_title
        ResolverDialogKind.SharePinned -> R.string.resolver_share_pinned_title
        ResolverDialogKind.ShareBlocked -> R.string.resolver_share_blocked_title
        ResolverDialogKind.DirectSharePinned -> R.string.resolver_direct_share_pinned_title
        ResolverDialogKind.DirectShareBlocked -> R.string.resolver_direct_share_blocked_title
    }

    if (isPinnedDialog) {
        val pinnedKeys = when (kind) {
            ResolverDialogKind.OpenPinned -> state.openPinned
            ResolverDialogKind.SharePinned -> state.sharePinned
            ResolverDialogKind.DirectSharePinned -> state.directSharePinned
            ResolverDialogKind.OpenBlocked,
            ResolverDialogKind.ShareBlocked,
            ResolverDialogKind.DirectShareBlocked -> emptyList()
        }
        OrderedPinnedManagerDialog(
            title = stringResource(titleRes),
            pinnedKeys = pinnedKeys,
            candidates = candidates,
            isDirectShare = isDirectShare,
            onAddPinned = onAddPinned,
            onRemovePinned = onRemovePinned,
            onMovePinned = onMovePinned,
            onClearAll = onClearAll,
            onDismiss = onDismiss
        )
    } else {
        val blockedKeys = when (kind) {
            ResolverDialogKind.OpenBlocked -> state.openBlocked.toSet()
            ResolverDialogKind.ShareBlocked -> state.shareBlocked.toSet()
            ResolverDialogKind.DirectShareBlocked -> state.directShareBlocked.toSet()
            ResolverDialogKind.OpenPinned,
            ResolverDialogKind.SharePinned,
            ResolverDialogKind.DirectSharePinned -> emptySet()
        }
        BlockedPickerDialog(
            title = stringResource(titleRes),
            candidates = candidates,
            selectedKeys = blockedKeys,
            isDirectShare = isDirectShare,
            onToggleKey = onToggleBlocked,
            onClearAll = onClearAll,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun BlockedPickerDialog(
    title: String,
    candidates: List<ResolverCandidate>,
    selectedKeys: Set<String>,
    isDirectShare: Boolean,
    onToggleKey: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var wholePackageMode by remember { mutableStateOf(false) }

    val displayItems = remember(candidates, selectedKeys, wholePackageMode, isDirectShare) {
        if (!wholePackageMode) {
            candidates
        } else {
            val byPkg = LinkedHashMap<String, ResolverCandidate>()
            for (c in candidates) {
                val wildcardKey = ResolverMenuSettingsViewModel.packageRuleKey(c.packageName, isDirectShare)
                val existing = byPkg[wildcardKey]
                if (existing == null || (!existing.recentlySeen && c.recentlySeen)) {
                    byPkg[wildcardKey] = c.copy(
                        key = wildcardKey,
                        className = "*",
                        entryLabel = c.appLabel,
                        shortcutId = if (isDirectShare) "*" else ""
                    )
                }
            }
            byPkg.values.toList()
        }
    }

    ZToolSearchableListDialog(
        title = title,
        items = displayItems,
        itemKey = { it.key },
        matchesQuery = { item, q ->
            item.appLabel.lowercase().contains(q) ||
                item.entryLabel.lowercase().contains(q) ||
                item.packageName.lowercase().contains(q) ||
                item.key.lowercase().contains(q)
        },
        searchPlaceholder = stringResource(R.string.resolver_picker_search_hint),
        emptyText = stringResource(R.string.resolver_picker_empty_hint),
        headerContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { wholePackageMode = !wholePackageMode }
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.resolver_picker_package_mode_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = wholePackageMode,
                    onCheckedChange = { wholePackageMode = it }
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            ZToolTextButton(
                onClick = onDismiss,
                text = stringResource(R.string.common_confirm)
            )
        },
        dismissButton = {
            if (selectedKeys.isNotEmpty()) {
                ZToolTextButton(
                    onClick = onClearAll,
                    text = stringResource(R.string.resolver_rule_clear_button),
                    isPrimary = false
                )
            }
        }
    ) { candidate ->
        val checked = candidate.key in selectedKeys
        CandidatePickerRow(
            candidate = candidate,
            isWholePackage = wholePackageMode,
            checked = checked,
            onClick = { onToggleKey(candidate.key) }
        )
    }
}

@Composable
private fun OrderedPinnedManagerDialog(
    title: String,
    pinnedKeys: List<String>,
    candidates: List<ResolverCandidate>,
    isDirectShare: Boolean,
    onAddPinned: (String) -> Unit,
    onRemovePinned: (String) -> Unit,
    onMovePinned: (Int, Int) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var showAddPicker by remember { mutableStateOf(false) }
    var wholePackageMode by remember { mutableStateOf(false) }
    val candidateMap = remember(candidates) { candidates.associateBy { it.key } }

    ZToolDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.SemiBold) },
        text = {
            if (pinnedKeys.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.resolver_pinned_dialog_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    itemsIndexed(
                        items = pinnedKeys,
                        key = { _, key -> key }
                    ) { index, key ->
                        val resolved = candidateMap[key]
                        val primaryLabel = resolved?.entryLabel?.ifBlank { resolved.appLabel }
                            ?: formatFallbackRuleLabel(key, isDirectShare)
                        val secondaryLabel = resolved?.let {
                            if (it.appLabel != primaryLabel) "${it.appLabel} · $key" else key
                        } ?: key

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(32.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = primaryLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = secondaryLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onMovePinned(index, -1) },
                                enabled = index > 0
                            ) {
                                Icon(
                                    imageVector = ZToolIcons.ArrowUpward,
                                    contentDescription = stringResource(R.string.resolver_rule_move_up_label)
                                )
                            }
                            IconButton(
                                onClick = { onMovePinned(index, 1) },
                                enabled = index < pinnedKeys.lastIndex
                            ) {
                                Icon(
                                    imageVector = ZToolIcons.ArrowDownward,
                                    contentDescription = stringResource(R.string.resolver_rule_move_down_label)
                                )
                            }
                            IconButton(onClick = { onRemovePinned(key) }) {
                                Icon(
                                    imageVector = ZToolIcons.Delete,
                                    contentDescription = stringResource(R.string.resolver_rule_remove_label)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZToolTextButton(
                    onClick = { showAddPicker = true },
                    text = stringResource(R.string.resolver_pinned_add_button)
                )
                ZToolTextButton(
                    onClick = onDismiss,
                    text = stringResource(R.string.common_confirm),
                    isPrimary = false
                )
            }
        },
        dismissButton = {
            if (pinnedKeys.isNotEmpty()) {
                ZToolTextButton(
                    onClick = onClearAll,
                    text = stringResource(R.string.resolver_rule_clear_button),
                    isPrimary = false
                )
            }
        }
    )

    if (showAddPicker) {
        val availableItems = remember(candidates, pinnedKeys, wholePackageMode, isDirectShare) {
            val pinnedSet = pinnedKeys.toSet()
            if (!wholePackageMode) {
                candidates.filterNot { it.key in pinnedSet }
            } else {
                val byPkg = LinkedHashMap<String, ResolverCandidate>()
                for (c in candidates) {
                    val wildcardKey = ResolverMenuSettingsViewModel.packageRuleKey(c.packageName, isDirectShare)
                    if (wildcardKey in pinnedSet) continue
                    val existing = byPkg[wildcardKey]
                    if (existing == null || (!existing.recentlySeen && c.recentlySeen)) {
                        byPkg[wildcardKey] = c.copy(
                            key = wildcardKey,
                            className = "*",
                            entryLabel = c.appLabel,
                            shortcutId = if (isDirectShare) "*" else ""
                        )
                    }
                }
                byPkg.values.toList()
            }
        }

        ZToolSearchableListDialog(
            title = stringResource(R.string.resolver_pinned_add_button),
            items = availableItems,
            itemKey = { it.key },
            matchesQuery = { item, q ->
                item.appLabel.lowercase().contains(q) ||
                    item.entryLabel.lowercase().contains(q) ||
                    item.packageName.lowercase().contains(q) ||
                    item.key.lowercase().contains(q)
            },
            searchPlaceholder = stringResource(R.string.resolver_picker_search_hint),
            emptyText = stringResource(R.string.resolver_picker_empty_hint),
            headerContent = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { wholePackageMode = !wholePackageMode }
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.resolver_picker_package_mode_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = wholePackageMode,
                        onCheckedChange = { wholePackageMode = it }
                    )
                }
            },
            onDismissRequest = { showAddPicker = false },
            confirmButton = {
                ZToolTextButton(
                    onClick = { showAddPicker = false },
                    text = stringResource(R.string.common_cancel),
                    isPrimary = false
                )
            }
        ) { candidate ->
            CandidatePickerRow(
                candidate = candidate,
                isWholePackage = wholePackageMode,
                checked = null,
                onClick = {
                    onAddPinned(candidate.key)
                    showAddPicker = false
                }
            )
        }
    }
}

@Composable
private fun CandidatePickerRow(
    candidate: ResolverCandidate,
    isWholePackage: Boolean,
    checked: Boolean?,
    onClick: () -> Unit
) {
    val badgeText = when {
        candidate.recentlySeen -> stringResource(R.string.resolver_picker_recently_seen_label)
        candidate.source == CandidateSource.RootDumpsys -> stringResource(R.string.resolver_picker_source_dumpsys_label)
        else -> null
    }
    val subtitle = buildString {
        if (isWholePackage) {
            append(stringResource(R.string.resolver_picker_package_wildcard_label, candidate.packageName))
        } else {
            append(candidate.appLabel)
            append(" · ")
            append(candidate.key)
        }
        if (badgeText != null) {
            append(" [")
            append(badgeText)
            append("]")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.entryLabel.ifBlank { candidate.appLabel },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (checked == true) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (checked != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Checkbox(
                checked = checked,
                onCheckedChange = null
            )
        }
    }
}

private fun formatFallbackRuleLabel(key: String, isDirectShare: Boolean): String {
    if (isDirectShare) {
        val parts = key.split('|', limit = 2)
        return if (parts.size == 2 && parts[1] != "*") parts[1] else parts.firstOrNull().orEmpty()
    }
    val parts = key.split('/', limit = 2)
    return if (parts.size == 2 && parts[1] != "*") parts[1].substringAfterLast('.') else parts.firstOrNull().orEmpty()
}
