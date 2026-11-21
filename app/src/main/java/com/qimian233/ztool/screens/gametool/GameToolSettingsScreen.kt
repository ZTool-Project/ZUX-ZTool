package com.qimian233.ztool.screens.gametool

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.qimian233.ztool.ui.icons.ZToolIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.qimian233.ztool.data.gametool.CpuCoreCatalog
import com.qimian233.ztool.data.gametool.DeviceModel
import com.qimian233.ztool.data.gametool.GameToolSettingsRepository
import com.qimian233.ztool.data.gametool.ThermalZoneCatalog
import com.qimian233.ztool.data.gametool.ThermalZoneHint
import com.qimian233.ztool.data.gametool.ThermalZoneInfo
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.SettingItem
import com.qimian233.ztool.ui.components.SettingSection
import com.qimian233.ztool.ui.components.ZToolDialog
import com.qimian233.ztool.ui.components.ZToolPopupMenuSettingRow
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolSearchableListDialog
import com.qimian233.ztool.ui.components.ZToolSettingsList
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.utils.AppChooserDialog
import com.qimian233.ztool.ui.components.ZToolExtendedFloatingActionButton
import com.qimian233.ztool.ui.components.ZToolTextButton
import com.qimian233.ztool.viewmodel.GameToolSettingsUiState
import com.qimian233.ztool.viewmodel.GameToolSettingsViewModel
import com.qimian233.ztool.viewmodel.MistakeTouchMode

@Composable
fun GameToolSettingsRoute(
    title: String,
    packageName: String,
    onBack: () -> Unit,
    targetId: String? = null
) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
        ?: error("GameToolSettingsRoute requires a ViewModelStoreOwner")
    val viewModel = remember(owner) {
        ViewModelProvider(
            owner,
            GameToolSettingsViewModelFactory(
                GameToolSettingsRepository(context.applicationContext)
            )
        )[GameToolSettingsViewModel::class.java]
    }

    LaunchedEffect(viewModel) {
        viewModel.loadSettings()
    }

    val uiState by viewModel.uiState.collectAsState()
    val selectGameString = stringResource(R.string.game_tool_select_game)
    val scrollState = rememberScrollState()
    val highlightRegistry = remember { HighlightAnchorRegistry() }

    HighlightController(
        highlightTargetId = targetId,
        scrollState = scrollState,
        registry = highlightRegistry,
        onConsumed = { }
    ) {
        GameToolSettingsScreen(
            title = title,
            state = uiState,
            onBack = onBack,
            onRestart = viewModel::showRestartConfirmDialog,
            onDisableGameAudioChanged = viewModel::setDisableGameAudio,
            onDisguiseDeviceChanged = viewModel::setDisguiseDevice,
            onDisguiseModelChanged = viewModel::setDisguiseModel,
            onFixCpuFrequencyChanged = viewModel::setFixCpuFrequency,
            onCpuFreqCoreChanged = viewModel::setCpuFreqCore,
            onCpuFreqNodeChanged = viewModel::setCpuFreqNode,
            onFixSocTemperatureChanged = viewModel::setFixSocTemperature,
            onOpenThermalZonePicker = viewModel::openThermalZonePicker,
            onMistakeTouchModeChanged = viewModel::setMistakeTouchMode,
            onSelectWhitelist = {
                val activity = context as? android.app.Activity
                if (activity != null) {
                    AppChooserDialog.show(
                        activity,
                        viewModel.loadManagedGamePackages(),
                        uiState.targetGamePackages,
                        selectGameString,
                        object : AppChooserDialog.AppSelectionCallback {
                            override fun onSelected(selectedApps: List<AppChooserDialog.AppInfo>) {
                                viewModel.setWhitelistPackages(selectedApps.map { it.packageName })
                            }

                            override fun onCancel() = Unit
                        }
                    )
                }
            },
            scrollState = scrollState,
            highlightRegistry = highlightRegistry
        )
    }

    if (uiState.showThermalZonePicker) {
        ThermalZonePickerDialog(
            zones = uiState.availableThermalZones,
            selectedType = uiState.socTempZoneType,
            onSelect = viewModel::setSocTempZone,
            onDismiss = viewModel::dismissThermalZonePicker
        )
    }

    if (uiState.showRestartConfirmDialog) {
        RestartConfirmDialog(
            packageName = packageName,
            onConfirm = {
                viewModel.forceStopPackage(
                    onFailure = {
                        Toast.makeText(context, R.string.game_tool_restart_fail_short, Toast.LENGTH_SHORT).show()
                    }
                )
            },
            onDismiss = viewModel::dismissRestartConfirmDialog
        )
    }
}

private class GameToolSettingsViewModelFactory(
    private val repository: GameToolSettingsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameToolSettingsViewModel::class.java)) {
            return GameToolSettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
private fun GameToolSettingsScreen(
    title: String,
    state: GameToolSettingsUiState,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onDisableGameAudioChanged: (Boolean) -> Unit,
    onDisguiseDeviceChanged: (Boolean) -> Unit,
    onDisguiseModelChanged: (DeviceModel) -> Unit,
    onFixCpuFrequencyChanged: (Boolean) -> Unit,
    onCpuFreqCoreChanged: (Int) -> Unit,
    onCpuFreqNodeChanged: (String) -> Unit,
    onFixSocTemperatureChanged: (Boolean) -> Unit,
    onOpenThermalZonePicker: () -> Unit,
    onMistakeTouchModeChanged: (MistakeTouchMode) -> Unit,
    onSelectWhitelist: () -> Unit,
    scrollState: ScrollState,
    highlightRegistry: HighlightAnchorRegistry
) {
    ZToolScaffold(
        topBar = {
            ZToolTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ZToolIcons.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ZToolExtendedFloatingActionButton(
                onClick = onRestart,
                icon = {Icon(imageVector = ZToolIcons.Refresh, contentDescription = null)},
                text = {Text(stringResource(R.string.common_restart_yes))})
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
                    sections = gameToolSettingsSections(
                        state = state,
                        onDisableGameAudioChanged = onDisableGameAudioChanged,
                        onDisguiseDeviceChanged = onDisguiseDeviceChanged,
                        onDisguiseModelChanged = onDisguiseModelChanged,
                        onFixCpuFrequencyChanged = onFixCpuFrequencyChanged,
                        onCpuFreqCoreChanged = onCpuFreqCoreChanged,
                        onCpuFreqNodeChanged = onCpuFreqNodeChanged,
                        onFixSocTemperatureChanged = onFixSocTemperatureChanged,
                        onOpenThermalZonePicker = onOpenThermalZonePicker,
                        onMistakeTouchModeChanged = onMistakeTouchModeChanged,
                        onSelectWhitelist = onSelectWhitelist
                    ),
                    bottomPadding = 96.dp,
                    highlightRegistry = highlightRegistry
                )
            }
        }
    }
}

@Composable
private fun gameToolSettingsSections(
    state: GameToolSettingsUiState,
    onDisableGameAudioChanged: (Boolean) -> Unit,
    onDisguiseDeviceChanged: (Boolean) -> Unit,
    onDisguiseModelChanged: (DeviceModel) -> Unit,
    onFixCpuFrequencyChanged: (Boolean) -> Unit,
    onCpuFreqCoreChanged: (Int) -> Unit,
    onCpuFreqNodeChanged: (String) -> Unit,
    onFixSocTemperatureChanged: (Boolean) -> Unit,
    onOpenThermalZonePicker: () -> Unit,
    onMistakeTouchModeChanged: (MistakeTouchMode) -> Unit,
    onSelectWhitelist: () -> Unit
): List<SettingSection> {
    val functionItems = buildList {
        add(
            SettingItem.Switch(
                title = stringResource(R.string.game_tool_device_model_disguise),
                summary = stringResource(R.string.game_tool_device_model_disguise_summary),
                checked = state.disguiseDevice,
                onCheckedChange = onDisguiseDeviceChanged,
                key = "game_tool_device_model_disguise"
            )
        )
        if (state.disguiseDevice) {
            val selected = state.availableModels.firstOrNull { it.code == state.disguiseModel }
                ?: DeviceModel(code = state.disguiseModel, fieldName = "")
            val options = if (state.availableModels.any { it.code == state.disguiseModel }) {
                state.availableModels
            } else {
                state.availableModels + selected
            }
            add(
                SettingItem.Dropdown(
                    label = stringResource(R.string.game_tool_device_model_disguise_model_label),
                    value = selected.code,
                    options = options,
                    optionLabel = { it.code },
                    optionSupportingText = { it.marketingName },
                    onOptionSelected = onDisguiseModelChanged,
                    summary = stringResource(R.string.game_tool_device_model_disguise_model_summary),
                    key = "game_tool_device_model_disguise_model"
                )
            )
        }
        add(
            SettingItem.Switch(
                title = stringResource(R.string.game_tool_f_ix_cpu_frequency),
                summary = stringResource(R.string.game_tool_f_ix_cpu_frequency_summary),
                checked = state.fixCpuFrequency,
                onCheckedChange = onFixCpuFrequencyChanged,
                key = "game_tool_fix_cpu_frequency"
            )
        )
        if (state.fixCpuFrequency) {
            add(buildCpuCoreDropdownItem(state, onCpuFreqCoreChanged))
            add(buildCpuNodeDropdownItem(state, onCpuFreqNodeChanged))
        }
        add(
            SettingItem.Switch(
                title = stringResource(R.string.game_tool_fix_soc_temp),
                summary = stringResource(R.string.game_tool_fix_soc_temp_summary),
                checked = state.fixSocTemperature,
                onCheckedChange = onFixSocTemperatureChanged,
                key = "game_tool_fix_soc_temp"
            )
        )
        if (state.fixSocTemperature) {
            add(buildThermalZoneEntryItem(state, onOpenThermalZonePicker))
        }
        add(
            SettingItem.Custom(
                content = {
                    MistakeTouchModeRow(
                        selectedMode = state.mistakeTouchMode,
                        onModeChanged = onMistakeTouchModeChanged
                    )
                },
                key = "game_tool_auto_open_prevent_touch"
            )
        )
        if (state.mistakeTouchMode == MistakeTouchMode.Whitelist) {
            add(
                SettingItem.Custom(
                    content = {
                        WhitelistRow(
                            whitelistCount = state.whitelistCount,
                            onClick = onSelectWhitelist
                        )
                    },
                    key = "game_tool_whitelist_config"
                )
            )
        }
    }

    return listOf(
        SettingSection(
            title = stringResource(R.string.game_tool_game_audio_setting_title),
            items = listOf(
                SettingItem.Switch(
                    title = stringResource(R.string.game_tool_game_audio_title),
                    summary = stringResource(R.string.game_tool_game_audio_summary),
                    checked = state.disableGameAudio,
                    onCheckedChange = onDisableGameAudioChanged,
                    key = "game_tool_game_audio"
                )
            )
        ),
        SettingSection(
            title = stringResource(R.string.game_tool_function_title),
            items = functionItems
        )
    )
}

@Composable
private fun MistakeTouchModeRow(
    selectedMode: MistakeTouchMode,
    onModeChanged: (MistakeTouchMode) -> Unit
) {
    val options = listOf(
        MistakeTouchMode.Default to stringResource(R.string.common_select_default),
        MistakeTouchMode.AllGames to stringResource(R.string.game_tool_select_all_games),
        MistakeTouchMode.Whitelist to stringResource(R.string.common_select_white_list)
    )
    val selectedLabel = options.first { it.first == selectedMode }.second

    ZToolPopupMenuSettingRow(
        title = stringResource(R.string.game_tool_auto_open_prevent_touch_title),
        summary = stringResource(R.string.game_tool_auto_open_prevent_touch_summary),
        value = selectedLabel,
        options = options,
        optionLabel = { it.second },
        onOptionSelected = { (mode, _) -> onModeChanged(mode) }
    )
}

@Composable
private fun WhitelistRow(
    whitelistCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.game_tool_whitelist_config_title),
                style = MaterialTheme.typography.titleMedium,
                color = LocalZToolColorScheme.current.onSurface
            )
            Text(
                text = stringResource(R.string.game_tool_whitelist_count, whitelistCount),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Icon(
            imageVector = ZToolIcons.ArrowForward,
            contentDescription = null,
            tint = LocalZToolColorScheme.current.onSurfaceVariant
        )
    }
}

private data class CpuCoreDropdownOption(
    val index: Int,
    val label: String,
    val supporting: String?
)

private data class ThermalZoneRowModel(
    val typeKey: String,
    val indexHint: Int,
    val title: String,
    val meta: String,
    val description: String,
    val tempLabel: String?,
    val isSelected: Boolean
)

@Composable
private fun buildCpuCoreDropdownItem(
    state: GameToolSettingsUiState,
    onCpuFreqCoreChanged: (Int) -> Unit
): SettingItem.Dropdown<CpuCoreDropdownOption> {
    val autoOption = CpuCoreDropdownOption(
        index = -1,
        label = stringResource(R.string.game_tool_cpu_freq_core_auto_title),
        supporting = stringResource(R.string.game_tool_cpu_freq_core_auto_desc)
    )
    val probedOptions = state.availableCpuCores.map { core ->
        val clusterText = core.clusterId?.let {
            stringResource(R.string.game_tool_cpu_freq_core_cluster, it)
        }
        val freqText = core.maxFreqKhz?.let { CpuCoreCatalog.formatMaxFreqGhz(it) }
        val meta = listOfNotNull(core.coreName, clusterText, freqText)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
        CpuCoreDropdownOption(
            index = core.index,
            label = stringResource(R.string.game_tool_cpu_freq_core_item_title, core.index),
            supporting = meta
        )
    }
    val options = buildList {
        add(autoOption)
        addAll(probedOptions)
        if (state.cpuFreqCore >= 0 && probedOptions.none { it.index == state.cpuFreqCore }) {
            add(
                CpuCoreDropdownOption(
                    index = state.cpuFreqCore,
                    label = stringResource(R.string.game_tool_cpu_freq_core_item_title, state.cpuFreqCore),
                    supporting = null
                )
            )
        }
    }
    val selected = options.firstOrNull { it.index == state.cpuFreqCore } ?: autoOption

    return SettingItem.Dropdown(
        label = stringResource(R.string.game_tool_cpu_freq_core_title),
        value = selected.label,
        options = options,
        optionLabel = { it.label },
        optionSupportingText = { it.supporting },
        onOptionSelected = { onCpuFreqCoreChanged(it.index) },
        summary = stringResource(R.string.game_tool_cpu_freq_core_summary),
        key = "game_tool_fix_cpu_frequency_core"
    )
}

@Composable
private fun buildCpuNodeDropdownItem(
    state: GameToolSettingsUiState,
    onCpuFreqNodeChanged: (String) -> Unit
): SettingItem.Dropdown<String> {
    val options = if (state.availableCpuCores.isEmpty()) {
        CpuCoreCatalog.ALLOWED_FREQ_NODES
    } else {
        CpuCoreCatalog.availableNodesForSelection(state.availableCpuCores, state.cpuFreqCore)
    }
    val selected = state.cpuFreqNode.takeIf { it in options } ?: options.first()
    val nodeDescriptions = mapOf(
        "scaling_cur_freq" to stringResource(R.string.game_tool_cpu_freq_node_scaling_cur_desc),
        "cpuinfo_cur_freq" to stringResource(R.string.game_tool_cpu_freq_node_cpuinfo_cur_desc),
        "scaling_max_freq" to stringResource(R.string.game_tool_cpu_freq_node_scaling_max_desc),
        "cpuinfo_max_freq" to stringResource(R.string.game_tool_cpu_freq_node_cpuinfo_max_desc)
    )

    return SettingItem.Dropdown(
        label = stringResource(R.string.game_tool_cpu_freq_node_title),
        value = selected,
        options = options,
        optionLabel = { it },
        optionSupportingText = { node -> nodeDescriptions[node] },
        onOptionSelected = onCpuFreqNodeChanged,
        summary = stringResource(R.string.game_tool_cpu_freq_node_summary),
        key = "game_tool_fix_cpu_frequency_node"
    )
}

@Composable
private fun buildThermalZoneEntryItem(
    state: GameToolSettingsUiState,
    onOpenThermalZonePicker: () -> Unit
): SettingItem.Entry {
    val summary = if (state.socTempZoneType.isBlank()) {
        stringResource(R.string.game_tool_soc_temp_zone_auto_summary)
    } else {
        val matched = state.availableThermalZones.firstOrNull {
            it.type.equals(state.socTempZoneType, ignoreCase = true)
        }
        if (matched != null) {
            stringResource(
                R.string.game_tool_soc_temp_zone_selected_live,
                matched.type,
                stringResource(zoneHintDescriptionRes(matched.hint)),
                ThermalZoneCatalog.formatTempC(matched.tempMilliC)
            )
        } else {
            stringResource(
                R.string.game_tool_soc_temp_zone_selected_fallback,
                state.socTempZoneType
            )
        }
    }

    return SettingItem.Entry(
        title = stringResource(R.string.game_tool_soc_temp_zone_title),
        summary = summary,
        onClick = onOpenThermalZonePicker,
        trailingContent = {
            Icon(
                imageVector = ZToolIcons.ArrowForward,
                contentDescription = null
            )
        },
        key = "game_tool_fix_soc_temp_zone"
    )
}

@Composable
private fun ThermalZonePickerDialog(
    zones: List<ThermalZoneInfo>,
    selectedType: String,
    onSelect: (type: String, indexHint: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val normalizedSelected = selectedType.trim()
    val autoTitle = stringResource(R.string.game_tool_soc_temp_zone_auto_title)
    val autoDesc = stringResource(R.string.game_tool_soc_temp_zone_auto_desc)
    val hintDescriptions = ThermalZoneHint.entries.associateWith { hint ->
        stringResource(zoneHintDescriptionRes(hint))
    }

    val autoRow = ThermalZoneRowModel(
        typeKey = "",
        indexHint = -1,
        title = autoTitle,
        meta = "",
        description = autoDesc,
        tempLabel = null,
        isSelected = normalizedSelected.isEmpty()
    )
    val sensorRows = zones.map { zone ->
        val hintDesc = hintDescriptions[zone.hint].orEmpty()
        ThermalZoneRowModel(
            typeKey = zone.type,
            indexHint = zone.index,
            title = zone.type,
            meta = stringResource(R.string.game_tool_soc_temp_zone_row_meta, zone.index, hintDesc),
            description = hintDesc,
            tempLabel = ThermalZoneCatalog.formatTempC(zone.tempMilliC),
            isSelected = normalizedSelected.equals(zone.type, ignoreCase = true)
        )
    }
    val rows = remember(autoRow, sensorRows) { listOf(autoRow) + sensorRows }

    ZToolSearchableListDialog(
        title = stringResource(R.string.game_tool_soc_temp_zone_dialog_title),
        items = rows,
        itemKey = { "${it.typeKey}#${it.indexHint}" },
        matchesQuery = { row, query ->
            row.title.lowercase().contains(query) ||
                row.meta.lowercase().contains(query) ||
                row.description.lowercase().contains(query)
        },
        searchPlaceholder = stringResource(R.string.game_tool_soc_temp_zone_search_hint),
        emptyText = stringResource(R.string.game_tool_soc_temp_zone_empty_desc),
        onDismissRequest = onDismiss,
        confirmButton = {
            ZToolTextButton(
                onClick = onDismiss,
                text = stringResource(R.string.common_cancel),
                isPrimary = false
            )
        }
    ) { row ->
        ThermalZonePickerRow(
            row = row,
            onClick = { onSelect(row.typeKey, row.indexHint) }
        )
    }
}

@Composable
private fun ThermalZonePickerRow(
    row: ThermalZoneRowModel,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.meta.ifEmpty { row.description },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (row.tempLabel != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = row.tempLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (row.isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = ZToolIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun zoneHintDescriptionRes(hint: ThermalZoneHint): Int = when (hint) {
    ThermalZoneHint.SOC_OVERALL -> R.string.game_tool_soc_temp_zone_desc_soc
    ThermalZoneHint.CPU_CLUSTER -> R.string.game_tool_soc_temp_zone_desc_cpu
    ThermalZoneHint.GPU -> R.string.game_tool_soc_temp_zone_desc_gpu
    ThermalZoneHint.SUBSYSTEM_AOSS -> R.string.game_tool_soc_temp_zone_desc_aoss
    ThermalZoneHint.SKIN_THERMISTOR -> R.string.game_tool_soc_temp_zone_desc_skin
    ThermalZoneHint.BATTERY -> R.string.game_tool_soc_temp_zone_desc_battery
    ThermalZoneHint.MEMORY_STORAGE -> R.string.game_tool_soc_temp_zone_desc_memory
    ThermalZoneHint.PMIC -> R.string.game_tool_soc_temp_zone_desc_pmic
    ThermalZoneHint.CHARGER -> R.string.game_tool_soc_temp_zone_desc_charger
    ThermalZoneHint.DISPLAY -> R.string.game_tool_soc_temp_zone_desc_display
    ThermalZoneHint.MODEM_RF -> R.string.game_tool_soc_temp_zone_desc_modem
    ThermalZoneHint.OTHER -> R.string.game_tool_soc_temp_zone_desc_other
}

@Composable
private fun RestartConfirmDialog(
    packageName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ZToolDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_restart_xp_title)) },
        text = {
            Text(
                stringResource(R.string.common_restart_xp_message_header) +
                    packageName +
                    stringResource(R.string.common_restart_xp_message)
            )
        },
        confirmButton = {
            ZToolTextButton(onClick = onConfirm, text = stringResource(R.string.common_restart_yes))
        },
        dismissButton = {
            ZToolTextButton(onClick = onDismiss, text = stringResource(R.string.common_restart_no), isPrimary = false)
        }
    )
}
