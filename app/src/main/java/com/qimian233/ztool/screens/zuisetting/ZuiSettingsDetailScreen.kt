package com.qimian233.ztool.screens.zuisetting

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.qimian233.ztool.ui.icons.ZToolIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.qimian233.ztool.R
import com.qimian233.ztool.data.settings.CustomizeAboutDeviceInfoRepository
import com.qimian233.ztool.data.settings.SettingsDetailRepository
import com.qimian233.ztool.screens.safecenter.RestartConfirmDialog
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.SettingItem
import com.qimian233.ztool.ui.components.SettingSection
import com.qimian233.ztool.ui.components.ZToolExtendedFloatingActionButton
import com.qimian233.ztool.ui.components.ZToolPageSurface
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolSettingsList
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.viewmodel.SettingsDetailUiState
import com.qimian233.ztool.viewmodel.SettingsDetailViewModel

@Composable
fun SettingsDetailRoute(
    title: String,
    packageName: String,
    onBack: () -> Unit,
    onOpenFontManager: () -> Unit = {},
    targetId: String? = null
) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
        ?: error("SettingsDetailRoute requires a ViewModelStoreOwner")
    val viewModel = remember(owner) {
        ViewModelProvider(
            owner,
            SettingsDetailViewModelFactory(
                SettingsDetailRepository(context.applicationContext),
                CustomizeAboutDeviceInfoRepository(context.applicationContext)
            )
        )[SettingsDetailViewModel::class.java]
    }
    val aboutDeviceInfoImageSavedText = stringResource(R.string.settings_about_device_info_image_saved)
    val aboutDeviceInfoImageSaveFailedText = stringResource(R.string.settings_about_device_info_image_save_failed)
    val aboutDeviceInfoImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val saved = viewModel.saveAboutDeviceInfoHeaderImage(uri)
            Toast.makeText(
                context,
                if (saved) aboutDeviceInfoImageSavedText else aboutDeviceInfoImageSaveFailedText,
                Toast.LENGTH_SHORT
            ).show()
        }
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
        SettingsDetailScreen(
            title = title,
            state = uiState,
            onBack = onBack,
            scrollState = scrollState,
            highlightRegistry = highlightRegistry,
            onOpenFontManager = onOpenFontManager,
        onAllowNativePermissionControllerChanged = viewModel::setAllowNativePermissionController,
        onAllowDisableDolbyChanged = viewModel::setAllowDisableDolby,
        onAppDetailsChanged = viewModel::setAppDetails,
        onAppIconUnmaskChanged = viewModel::setAppIconUnmask,
        aboutDeviceInfoState = uiState.aboutDeviceInfoState,
        onAboutDeviceInfoEnabledChanged = viewModel::setAboutDeviceInfoEnabled,
        onAboutDeviceInfoModelEnabledChanged = viewModel::setAboutDeviceInfoModelEnabled,
        onAboutDeviceInfoCpuEnabledChanged = viewModel::setAboutDeviceInfoCpuEnabled,
        onAboutDeviceInfoRamEnabledChanged = viewModel::setAboutDeviceInfoRamEnabled,
        onAboutDeviceInfoRomEnabledChanged = viewModel::setAboutDeviceInfoRomEnabled,
        onAboutDeviceInfoSoftwareEnabledChanged = viewModel::setAboutDeviceInfoSoftwareEnabled,
        onAboutDeviceInfoHeaderEnabledChanged = viewModel::setAboutDeviceInfoHeaderEnabled,
        onAboutDeviceInfoModelChanged = viewModel::setAboutDeviceInfoModel,
        onAboutDeviceInfoCpuChanged = viewModel::setAboutDeviceInfoCpu,
        onAboutDeviceInfoRamChanged = viewModel::setAboutDeviceInfoRam,
        onAboutDeviceInfoRomChanged = viewModel::setAboutDeviceInfoRom,
        onAboutDeviceInfoSoftwareChanged = viewModel::setAboutDeviceInfoSoftware,
        onAboutDeviceInfoHeaderSelected = { aboutDeviceInfoImageLauncher.launch(arrayOf("image/*")) },
        onAllowAddingLanguageChanged = viewModel::setAllowAddingLanguage,
        onRestartScope = viewModel::showRestartDialog,
        )
    }

    if (uiState.showRestartDialog) {
        RestartConfirmDialog(
            packageName = packageName,
            onConfirm = {
                viewModel.restartScope()
            },
            onDismiss = viewModel::dismissRestartDialog
        )
    }
}

private class SettingsDetailViewModelFactory(
    private val repository: SettingsDetailRepository,
    private val aboutDeviceInfoRepository: CustomizeAboutDeviceInfoRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsDetailViewModel::class.java)) {
            return SettingsDetailViewModel(repository, aboutDeviceInfoRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
private fun SettingsDetailScreen(
    title: String,
    state: SettingsDetailUiState,
    onBack: () -> Unit,
    scrollState: ScrollState,
    highlightRegistry: HighlightAnchorRegistry,
    onOpenFontManager: () -> Unit,
    onAllowNativePermissionControllerChanged: (Boolean) -> Unit,
    onAllowDisableDolbyChanged: (Boolean) -> Unit,
    onAppDetailsChanged: (Boolean) -> Unit,
    onAppIconUnmaskChanged: (Boolean) -> Unit,
    aboutDeviceInfoState: com.qimian233.ztool.data.settings.CustomizeAboutDeviceInfoState,
    onAboutDeviceInfoEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoModelEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoCpuEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoRamEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoRomEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoSoftwareEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoHeaderEnabledChanged: (Boolean) -> Unit,
    onAllowAddingLanguageChanged: (Boolean) -> Unit,
    onAboutDeviceInfoModelChanged: (String) -> Unit,
    onAboutDeviceInfoCpuChanged: (String) -> Unit,
    onAboutDeviceInfoRamChanged: (String) -> Unit,
    onAboutDeviceInfoRomChanged: (String) -> Unit,
    onAboutDeviceInfoSoftwareChanged: (String) -> Unit,
    onAboutDeviceInfoHeaderSelected: (Uri) -> Unit,
    onRestartScope: () -> Unit
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
                onClick = onRestartScope,
                icon = {
                    Icon(
                        imageVector = ZToolIcons.Refresh,
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.common_restart_yes)) }
            )
        }
    ) { innerPadding ->
        ZToolPageSurface(
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
                    .padding(bottom = 88.dp)
            ) {
                ZToolSettingsList(
                    highlightRegistry = highlightRegistry,
                    sections = settingsDetailSections(
                        state = state,
                        onOpenFontManager = onOpenFontManager,
                        onAllowNativePermissionControllerChanged = onAllowNativePermissionControllerChanged,
                        onAllowDisableDolbyChanged = onAllowDisableDolbyChanged,
                        onAppDetailsChanged = onAppDetailsChanged,
                        onAppIconUnmaskChanged = onAppIconUnmaskChanged,
                        aboutDeviceInfoState = aboutDeviceInfoState,
                        onAboutDeviceInfoEnabledChanged = onAboutDeviceInfoEnabledChanged,
                        onAboutDeviceInfoModelEnabledChanged = onAboutDeviceInfoModelEnabledChanged,
                        onAboutDeviceInfoCpuEnabledChanged = onAboutDeviceInfoCpuEnabledChanged,
                        onAboutDeviceInfoRamEnabledChanged = onAboutDeviceInfoRamEnabledChanged,
                        onAboutDeviceInfoRomEnabledChanged = onAboutDeviceInfoRomEnabledChanged,
                        onAboutDeviceInfoSoftwareEnabledChanged = onAboutDeviceInfoSoftwareEnabledChanged,
                        onAboutDeviceInfoHeaderEnabledChanged = onAboutDeviceInfoHeaderEnabledChanged,
                        onAboutDeviceInfoModelChanged = onAboutDeviceInfoModelChanged,
                        onAboutDeviceInfoCpuChanged = onAboutDeviceInfoCpuChanged,
                        onAboutDeviceInfoRamChanged = onAboutDeviceInfoRamChanged,
                        onAboutDeviceInfoRomChanged = onAboutDeviceInfoRomChanged,
                        onAboutDeviceInfoSoftwareChanged = onAboutDeviceInfoSoftwareChanged,
                        onAboutDeviceInfoHeaderSelected = onAboutDeviceInfoHeaderSelected,
                        onAllowAddingLanguageChanged = onAllowAddingLanguageChanged,
                    )
                )
            }
        }
    }
}

@Composable
private fun settingsDetailSections(
    state: SettingsDetailUiState,
    onOpenFontManager: () -> Unit,
    onAllowNativePermissionControllerChanged: (Boolean) -> Unit,
    onAllowDisableDolbyChanged: (Boolean) -> Unit,
    onAppDetailsChanged: (Boolean) -> Unit,
    onAppIconUnmaskChanged: (Boolean) -> Unit,
    aboutDeviceInfoState: com.qimian233.ztool.data.settings.CustomizeAboutDeviceInfoState,
    onAboutDeviceInfoEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoModelEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoCpuEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoRamEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoRomEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoSoftwareEnabledChanged: (Boolean) -> Unit,
    onAboutDeviceInfoHeaderEnabledChanged: (Boolean) -> Unit,
    onAllowAddingLanguageChanged: (Boolean) -> Unit,
    onAboutDeviceInfoModelChanged: (String) -> Unit,
    onAboutDeviceInfoCpuChanged: (String) -> Unit,
    onAboutDeviceInfoRamChanged: (String) -> Unit,
    onAboutDeviceInfoRomChanged: (String) -> Unit,
    onAboutDeviceInfoSoftwareChanged: (String) -> Unit,
    onAboutDeviceInfoHeaderSelected: (Uri) -> Unit,
): List<SettingSection> {
    return buildList {
        add(
            SettingSection(
                title = stringResource(R.string.settings_font_settings_title),
                items = listOf(
                    settingsDetailActionItem(
                        key = "settings_detail_font_manager",
                        title = stringResource(R.string.settings_font_manager_title),
                        summary = stringResource(R.string.settings_font_manager_summary),
                        onClick = onOpenFontManager
                    )
                )
            )
        )

        add(
            SettingSection(
                title = stringResource(R.string.settings_about_device_info_title),
                items = buildList {
                    add(
                        SettingItem.Switch(
                            key = "settings_detail_about_device_info_master",
                            title = stringResource(R.string.settings_about_device_info_master),
                            summary = stringResource(R.string.settings_about_device_info_master_summary),
                            checked = aboutDeviceInfoState.enabled,
                            onCheckedChange = onAboutDeviceInfoEnabledChanged
                        )
                    )
                    if (aboutDeviceInfoState.enabled) {
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_about_device_info_model",
                                title = stringResource(R.string.settings_about_device_info_model_title),
                                checked = aboutDeviceInfoState.modelEnabled,
                                onCheckedChange = onAboutDeviceInfoModelEnabledChanged
                            )
                        )
                        if (aboutDeviceInfoState.modelEnabled) {
                            add(SettingItem.TextInput(
                                key = "settings_detail_about_device_info_model_input",
                                label = stringResource(R.string.settings_about_device_info_model_label),
                                value = aboutDeviceInfoState.model,
                                onValueChange = onAboutDeviceInfoModelChanged
                            ))
                        }
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_about_device_info_cpu",
                                title = stringResource(R.string.settings_about_device_info_cpu_title),
                                checked = aboutDeviceInfoState.cpuEnabled,
                                onCheckedChange = onAboutDeviceInfoCpuEnabledChanged
                            )
                        )
                        if (aboutDeviceInfoState.cpuEnabled) {
                            add(SettingItem.TextInput(
                                key = "settings_detail_about_device_info_cpu_input",
                                label = stringResource(R.string.settings_about_device_info_cpu_label),
                                value = aboutDeviceInfoState.cpu,
                                onValueChange = onAboutDeviceInfoCpuChanged
                            ))
                        }
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_about_device_info_ram",
                                title = stringResource(R.string.settings_about_device_info_ram_title),
                                checked = aboutDeviceInfoState.ramEnabled,
                                onCheckedChange = onAboutDeviceInfoRamEnabledChanged
                            )
                        )
                        if (aboutDeviceInfoState.ramEnabled) {
                            add(SettingItem.TextInput(
                                key = "settings_detail_about_device_info_ram_input",
                                label = stringResource(R.string.settings_about_device_info_ram_label),
                                value = aboutDeviceInfoState.ram,
                                onValueChange = onAboutDeviceInfoRamChanged
                            ))
                        }
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_about_device_info_rom",
                                title = stringResource(R.string.settings_about_device_info_rom_title),
                                checked = aboutDeviceInfoState.romEnabled,
                                onCheckedChange = onAboutDeviceInfoRomEnabledChanged
                            )
                        )
                        if (aboutDeviceInfoState.romEnabled) {
                            add(SettingItem.TextInput(
                                key = "settings_detail_about_device_info_rom_input",
                                label = stringResource(R.string.settings_about_device_info_rom_label),
                                value = aboutDeviceInfoState.rom,
                                onValueChange = onAboutDeviceInfoRomChanged
                            ))
                        }
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_about_device_info_software",
                                title = stringResource(R.string.settings_about_device_info_software_title),
                                checked = aboutDeviceInfoState.softwareEnabled,
                                onCheckedChange = onAboutDeviceInfoSoftwareEnabledChanged
                            )
                        )
                        if (aboutDeviceInfoState.softwareEnabled) {
                            add(SettingItem.TextInput(
                                key = "settings_detail_about_device_info_software_input",
                                label = stringResource(R.string.settings_about_device_info_software_label),
                                value = aboutDeviceInfoState.software,
                                onValueChange = onAboutDeviceInfoSoftwareChanged
                            ))
                        }
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_about_device_info_header",
                                title = stringResource(R.string.settings_about_device_info_header_title),
                                checked = aboutDeviceInfoState.headerEnabled,
                                onCheckedChange = onAboutDeviceInfoHeaderEnabledChanged
                            )
                        )
                        if (aboutDeviceInfoState.headerEnabled) {
                            add(
                                settingsDetailActionItem(
                                    key = "settings_detail_about_device_info_header_action",
                                    title = stringResource(R.string.settings_about_device_info_header_action),
                                    summary = stringResource(R.string.settings_about_device_info_header_action_summary),
                                    onClick = { onAboutDeviceInfoHeaderSelected(Uri.EMPTY) }
                                )
                            )
                        }
                    }
                }
            )
        )

        add(
            SettingSection(
                title = stringResource(R.string.settings_misc),
                items = listOf(
                    SettingItem.Switch(
                        key = "settings_detail_native_permission_controller",
                        title = stringResource(R.string.settings_native_permission_controller_enable_title),
                        summary = stringResource(R.string.settings_native_permission_controller_enable_summary),
                        checked = state.allowNativePermissionController,
                        onCheckedChange = onAllowNativePermissionControllerChanged
                    ),
                    SettingItem.Switch(
                        key = "settings_detail_allow_adding_language",
                        title = stringResource(R.string.settings_allow_adding_language),
                        checked = state.allowAddingLanguages,
                        onCheckedChange = onAllowAddingLanguageChanged
                    ),
                    SettingItem.Switch(
                        key = "settings_detail_allow_disable_dolby",
                        title = stringResource(R.string.settings_allow_disable_dolby),
                        summary = stringResource(R.string.settings_allow_disable_dolby_summary),
                        checked = state.allowDisableDolby,
                        onCheckedChange = onAllowDisableDolbyChanged
                    ),
                    SettingItem.Switch(
                        key = "settings_detail_app_details_completion",
                        title = stringResource(R.string.settings_app_details_completion),
                        summary = stringResource(R.string.settings_app_details_completion_summary),
                        checked = state.appDetail,
                        onCheckedChange = onAppDetailsChanged
                    ),
                    SettingItem.Switch(
                        key = "settings_detail_app_icon_unmask",
                        title = stringResource(R.string.settings_app_icon_unmask_title),
                        summary = stringResource(R.string.settings_app_icon_unmask_summary),
                        checked = state.appIconUnmask,
                        onCheckedChange = onAppIconUnmaskChanged
                    ),
                )
            )
        )

    }
}

@Composable
private fun settingsDetailActionItem(
    title: String,
    summary: String,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    key: String
): SettingItem {
    return SettingItem.Action(
        key = key,
        title = title,
        summary = summary,
        onClick = onClick,
        trailingContent = {
            if (icon != null) {
                icon()
            } else {
                Icon(
                    imageVector = ZToolIcons.ArrowForward,
                    contentDescription = null,
                    tint = LocalZToolColorScheme.current.onSurfaceVariant
                )
            }
        }
    )
}
