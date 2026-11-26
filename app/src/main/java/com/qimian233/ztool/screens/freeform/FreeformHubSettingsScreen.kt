package com.qimian233.ztool.screens.freeform

import android.app.AppOpsManager
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.qimian233.ztool.ui.icons.ZToolIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.qimian233.ztool.R
import com.qimian233.ztool.data.freeform.FreeformEdgeBubbleConfig
import com.qimian233.ztool.data.freeform.FreeformHubRepository
import com.qimian233.ztool.data.systemui.SystemUiMiscSettingsRepository
import com.qimian233.ztool.screens.zuisetting.floatingwindow.FloatingWindow
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.LoadingDialog
import com.qimian233.ztool.ui.components.SettingItem
import com.qimian233.ztool.ui.components.SettingSection
import com.qimian233.ztool.ui.components.ZToolDialog
import com.qimian233.ztool.ui.components.ZToolExtendedFloatingActionButton
import com.qimian233.ztool.ui.components.ZToolRestartSystemDialog
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolSettingsList
import com.qimian233.ztool.ui.components.ZToolTextButton
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.ui.components.showPlatformComposeDialog
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.utils.AppChooserDialog
import com.qimian233.ztool.utils.EmbeddingConfigManager
import com.qimian233.ztool.utils.OvCommonConfigManager
import com.qimian233.ztool.viewmodel.FreeformHubUiState
import com.qimian233.ztool.viewmodel.FreeformHubViewModel
import com.qimian233.ztool.viewmodel.SettingsDetailConfigFlashResult
import com.qimian233.ztool.viewmodel.SettingsDetailModuleResult
import com.qimian233.ztool.viewmodel.SettingsDetailOvConfigSelectionResult
import com.qimian233.ztool.viewmodel.SettingsDetailRestoreResult

/**
 * Consolidated small-window page. Every change applies after a full system reboot,
 * because the set spans framework scopes and the One Vision Magisk mount.
 */
@Composable
fun FreeformHubSettingsRoute(
    title: String,
    onBack: () -> Unit,
    onOpenStrategySearch: () -> Unit,
    targetId: String? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context as? android.app.Activity
    val owner = LocalViewModelStoreOwner.current
        ?: error("FreeformHubSettingsRoute requires a ViewModelStoreOwner")
    val viewModel = remember(owner) {
        ViewModelProvider(
            owner,
            FreeformHubViewModelFactory(FreeformHubRepository(context.applicationContext))
        )[FreeformHubViewModel::class.java]
    }

    val gotItButtonText = stringResource(R.string.settings_got_it_button)
    val savingConfigText = stringResource(R.string.settings_saving_config)
    val saveSuccessTitleText = stringResource(R.string.settings_success_title)
    val saveSuccessMessageText = stringResource(R.string.settings_save_success_message)
    val errorTitleText = stringResource(R.string.common_error_title)
    val errorPrefixText = stringResource(R.string.common_error_prefix)
    val configSuffixText = stringResource(R.string.settings_config_suffix)
    val tipTitleText = stringResource(R.string.settings_tip_title)
    val installModuleFirstText = stringResource(R.string.settings_install_module_first)
    val flashingConfigText = stringResource(R.string.settings_flashing_config)
    val confirmRestoreTitleText = stringResource(R.string.settings_confirm_restore_title)
    val confirmRestoreMessageText = stringResource(R.string.settings_confirm_restore_message)
    val confirmButtonText = stringResource(R.string.settings_confirm_button)
    val restartNoText = stringResource(R.string.common_restart_no)
    val restoringModuleText = stringResource(R.string.settings_restoring_module)
    val restoreSuccessMessageText = stringResource(R.string.settings_restore_success_message)
    val installingModuleText = stringResource(R.string.settings_installing_module)
    val removingModuleText = stringResource(R.string.settings_removing_module)
    val installSuccessText = stringResource(R.string.settings_install_success_message)
    val removeSuccessText = stringResource(R.string.settings_remove_success_message)
    val zuiForceSplitTitleText = stringResource(R.string.settings_zui_force_split_title)
    val zuiForceFreeformTitleText = stringResource(R.string.settings_zui_force_freeform_title)
    val zuiForceFixedTitleText = stringResource(R.string.settings_zui_force_fixed_title)

    var floatingWindow by remember { mutableStateOf<FloatingWindow?>(null) }
    var loadingDialog by remember { mutableStateOf<LoadingDialog?>(null) }

    fun showMessageDialog(dialogTitle: String, message: String) {
        if (activity == null) return
        showPlatformComposeDialog(activity, width = ViewGroup.LayoutParams.WRAP_CONTENT) { dialog ->
            SimpleMessageDialogContent(
                title = dialogTitle,
                message = message,
                confirmText = gotItButtonText,
                onConfirm = { dialog.dismiss() }
            )
        }
    }

    fun showConfirmDialog(
        dialogTitle: String,
        message: String,
        confirmText: String,
        dismissText: String,
        onConfirm: () -> Unit
    ) {
        if (activity == null) return
        showPlatformComposeDialog(activity, width = ViewGroup.LayoutParams.WRAP_CONTENT) { dialog ->
            SimpleMessageDialogContent(
                title = dialogTitle,
                message = message,
                confirmText = confirmText,
                dismissText = dismissText,
                onConfirm = {
                    dialog.dismiss()
                    onConfirm()
                },
                onDismiss = { dialog.dismiss() }
            )
        }
    }

    fun showComposeDialog(content: @Composable () -> Unit): Dialog? {
        return activity?.let {
            showPlatformComposeDialog(it, width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                content()
            }
        }
    }

    fun hideFloatingWindow() {
        floatingWindow?.hide()
        if (floatingWindow != null) {
            floatingWindow = null
            Toast.makeText(
                context,
                R.string.settings_floating_window_closed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showFloatingWindow() {
        val hostActivity = activity as? androidx.activity.ComponentActivity ?: return
        if (floatingWindow != null) {
            hideFloatingWindow()
            return
        }
        floatingWindow = FloatingWindow.create(hostActivity)
        Toast.makeText(
            context,
            R.string.settings_floating_window_started,
            Toast.LENGTH_SHORT
        ).show()
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    lateinit var requestOverlayPermission: () -> Unit
    fun startFloatingWindow() {
        if (!Settings.canDrawOverlays(context)) {
            requestOverlayPermission()
            return
        }
        if (!hasUsageStatsPermission()) {
            Toast.makeText(
                context,
                R.string.settings_request_usage_stats_permission,
                Toast.LENGTH_LONG
            ).show()
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        showFloatingWindow()
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            startFloatingWindow()
        } else {
            Toast.makeText(
                context,
                R.string.settings_overlay_permission_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    requestOverlayPermission = {
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            )
        )
    }

    fun saveOvConfig(
        configMap: MutableMap<String, OvCommonConfigManager.AppConfig>,
        selectedApps: List<AppChooserDialog.AppInfo>,
        mode: Int
    ) {
        loadingDialog = LoadingDialog(context).also { it.show(savingConfigText) }
        viewModel.saveOvConfig(
            configMap = configMap,
            selectedPackages = selectedApps.map { it.packageName },
            mode = mode
        ) { result ->
            activity?.runOnUiThread {
                loadingDialog?.dismiss()
                if (result == "success") {
                    showMessageDialog(saveSuccessTitleText, saveSuccessMessageText)
                } else {
                    showMessageDialog(errorTitleText, errorPrefixText + result)
                }
            }
        }
    }

    fun showConfigSelectionDialog(configs: List<EmbeddingConfigManager.ConfigFileInfo>) {
        if (activity == null) return
        if (configs.isEmpty()) {
            Toast.makeText(context, R.string.settings_no_config_files_prompt, Toast.LENGTH_SHORT).show()
            return
        }
        val flashedConfigs = viewModel.loadFlashedConfigs()
        lateinit var dialog: Dialog
        val shownDialog = showComposeDialog {
            EmbeddingConfigSelectionDialogContent(
                configs = configs,
                flashedConfigs = flashedConfigs,
                configLabel = { config ->
                    config.timestamp + " " + config.appName + configSuffixText
                },
                onAlreadyFlashedClick = {
                    Toast.makeText(
                        context,
                        R.string.settings_config_already_flashed,
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onDelete = { selectedConfigs ->
                    val count = viewModel.deleteEmbeddingConfigs(selectedConfigs, flashedConfigs)
                    Toast.makeText(
                        context,
                        resources.getString(R.string.settings_delete_success, count),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    showConfigSelectionDialog(viewModel.loadEmbeddingConfigFiles())
                },
                onFlash = { selectedConfigs ->
                    dialog.dismiss()
                    if (!viewModel.uiState.value.moduleEnabled) {
                        showMessageDialog(tipTitleText, installModuleFirstText)
                        return@EmbeddingConfigSelectionDialogContent
                    }
                    loadingDialog = LoadingDialog(context).also { it.show(flashingConfigText) }
                    viewModel.flashEmbeddingConfigs(selectedConfigs) { result ->
                        activity.runOnUiThread {
                            loadingDialog?.dismiss()
                            when (result) {
                                SettingsDetailConfigFlashResult.Success ->
                                    showMessageDialog(saveSuccessTitleText, saveSuccessMessageText)

                                is SettingsDetailConfigFlashResult.Failure -> showMessageDialog(
                                    errorTitleText,
                                    resources.getString(
                                        R.string.settings_flash_failed_message,
                                        result.message
                                    )
                                )
                            }
                        }
                    }
                },
                onRestore = {
                    dialog.dismiss()
                    showConfirmDialog(
                        dialogTitle = confirmRestoreTitleText,
                        message = confirmRestoreMessageText,
                        confirmText = confirmButtonText,
                        dismissText = restartNoText
                    ) {
                        loadingDialog = LoadingDialog(context).also { it.show(restoringModuleText) }
                        viewModel.restoreOriginalModule { result ->
                            activity.runOnUiThread {
                                loadingDialog?.dismiss()
                                when (result) {
                                    SettingsDetailRestoreResult.Success -> showMessageDialog(
                                        saveSuccessTitleText,
                                        restoreSuccessMessageText
                                    )

                                    is SettingsDetailRestoreResult.Failure -> showMessageDialog(
                                        errorTitleText,
                                        result.message
                                    )
                                }
                            }
                        }
                    }
                },
                onCancel = { dialog.dismiss() }
            )
        }
        if (shownDialog != null) {
            dialog = shownDialog
        }
    }

    fun openOvConfigDialog(mode: Int, dialogTitle: String) {
        if (activity == null) return
        viewModel.loadOvConfigSelection(mode) { result ->
            activity.runOnUiThread {
                when (result) {
                    is SettingsDetailOvConfigSelectionResult.Success -> AppChooserDialog.show(
                        activity,
                        result.selection.allPackages,
                        result.selection.selectedPackages,
                        dialogTitle,
                        object : AppChooserDialog.AppSelectionCallback {
                            override fun onSelected(selectedApps: List<AppChooserDialog.AppInfo>) {
                                saveOvConfig(result.selection.configMap, selectedApps, mode)
                            }

                            override fun onCancel() = Unit
                        }
                    )

                    is SettingsDetailOvConfigSelectionResult.Failure -> showMessageDialog(
                        errorTitleText,
                        errorPrefixText + result.message
                    )
                }
            }
        }
    }

    fun handleModuleSwitch(isChecked: Boolean) {
        if (activity == null) return
        loadingDialog = LoadingDialog(context).also {
            it.show(if (isChecked) installingModuleText else removingModuleText)
        }
        viewModel.setModuleEnabled(isChecked) { result ->
            activity.runOnUiThread {
                loadingDialog?.dismiss()
                when (result) {
                    SettingsDetailModuleResult.AlreadyEnabled -> Unit
                    is SettingsDetailModuleResult.Success -> showMessageDialog(
                        tipTitleText,
                        if (result.enabled) installSuccessText else removeSuccessText
                    )

                    is SettingsDetailModuleResult.Failure -> showMessageDialog(
                        errorTitleText,
                        if (result.requestedEnabled) {
                            resources.getString(
                                R.string.settings_install_failed_message,
                                result.message
                            )
                        } else {
                            resources.getString(
                                R.string.settings_remove_failed_message,
                                result.message
                            )
                        }
                    )
                }
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.loadSettings()
    }

    DisposableEffect(Unit) {
        onDispose {
            loadingDialog?.dismiss()
            floatingWindow?.hide()
        }
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
        FreeformHubSettingsScreen(
            title = title,
            state = uiState,
            onBack = onBack,
            onRemoveBlacklistChanged = viewModel::setRemoveBlacklist,
            onUnlockOvCommonAppListChanged = viewModel::setUnlockOvCommonAppList,
            onModuleEnabledChanged = ::handleModuleSwitch,
            onStartFloatingWindow = ::startFloatingWindow,
            onOpenConfigSelection = {
                showConfigSelectionDialog(viewModel.loadEmbeddingConfigFiles())
            },
            onOpenStrategySearch = onOpenStrategySearch,
            onZuiForceSplit = {
                openOvConfigDialog(
                    OvCommonConfigManager.MODE_SPLIT_SCREEN,
                    zuiForceSplitTitleText
                )
            },
            onZuiForceFreeform = {
                openOvConfigDialog(
                    OvCommonConfigManager.MODE_FREEFORM_FREE,
                    zuiForceFreeformTitleText
                )
            },
            onZuiForceFixed = {
                openOvConfigDialog(
                    OvCommonConfigManager.MODE_FREEFORM_FIXED,
                    zuiForceFixedTitleText
                )
            },
            onEdgeBubbleChanged = viewModel::setEdgeBubbleEnabled,
            onEdgeBubbleTriggerPercentChanged = viewModel::setEdgeBubbleTriggerPercent,
            onFloatMandatoryChanged = viewModel::setFloatMandatory,
            onSplitScreenMandatoryChanged = viewModel::setSplitScreenMandatory,
            onFreeformFullscreenThresholdChanged = viewModel::setFreeformFullscreenThreshold,
            onFreeformFullscreenWidthPercentChanged = viewModel::setFreeformFullscreenWidthPercent,
            onFreeformFullscreenHeightPercentChanged = viewModel::setFreeformFullscreenHeightPercent,
            onFreeformBottomEdgeResizeChanged = viewModel::setFreeformBottomEdgeResize,
            onCornerHandleAutoHideChanged = viewModel::setCornerHandleAutoHide,
            onFreeformNoForeheadChanged = viewModel::setFreeformNoForehead,
            onFreeformNonlinearAnimChanged = viewModel::setFreeformNonlinearAnim,
            onFreeformNonlinearAnimOpenChanged = viewModel::setFreeformNonlinearAnimOpen,
            onFreeformNonlinearAnimCloseChanged = viewModel::setFreeformNonlinearAnimClose,
            onFreeformNonlinearAnimBoundsChangeChanged = viewModel::setFreeformNonlinearAnimBoundsChange,
            onFreeformNonlinearAnimEdgeSnapBackChanged = viewModel::setFreeformNonlinearAnimEdgeSnapBack,
            onFreeformNonlinearAnimEdgeDockChanged = viewModel::setFreeformNonlinearAnimEdgeDock,
            onFreeformNonlinearAnimBubbleRestoreChanged = viewModel::setFreeformNonlinearAnimBubbleRestore,
            onFreeformHandleMenuAnimChanged = viewModel::setFreeformHandleMenuAnim,
            onForceFreeformEntryChanged = viewModel::setForceFreeformEntry,
            onForceRelativeAppFreeformChanged = viewModel::setForceRelativeAppFreeform,
            onRestartSystem = viewModel::showRestartDialog,
            scrollState = scrollState,
            highlightRegistry = highlightRegistry
        )
    }

    if (uiState.showRestartDialog) {
        val restartFailPrefix = stringResource(R.string.common_restart_fail_prefix)
        ZToolRestartSystemDialog(
            title = stringResource(R.string.system_framework_restart_system_title),
            message = stringResource(R.string.system_framework_restart_system_message),
            onConfirm = {
                viewModel.restartSystem { error ->
                    Toast.makeText(
                        context,
                        restartFailPrefix + error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = viewModel::dismissRestartDialog
        )
    }
}

private class FreeformHubViewModelFactory(
    private val repository: FreeformHubRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FreeformHubViewModel::class.java)) {
            return FreeformHubViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
private fun FreeformHubSettingsScreen(
    title: String,
    state: FreeformHubUiState,
    onBack: () -> Unit,
    onRemoveBlacklistChanged: (Boolean) -> Unit,
    onUnlockOvCommonAppListChanged: (Boolean) -> Unit,
    onModuleEnabledChanged: (Boolean) -> Unit,
    onStartFloatingWindow: () -> Unit,
    onOpenConfigSelection: () -> Unit,
    onOpenStrategySearch: () -> Unit,
    onZuiForceSplit: () -> Unit,
    onZuiForceFreeform: () -> Unit,
    onZuiForceFixed: () -> Unit,
    onEdgeBubbleChanged: (Boolean) -> Unit,
    onEdgeBubbleTriggerPercentChanged: (Int) -> Unit,
    onFloatMandatoryChanged: (Boolean) -> Unit,
    onSplitScreenMandatoryChanged: (Boolean) -> Unit,
    onFreeformFullscreenThresholdChanged: (Boolean) -> Unit,
    onFreeformFullscreenWidthPercentChanged: (Int) -> Unit,
    onFreeformFullscreenHeightPercentChanged: (Int) -> Unit,
    onFreeformBottomEdgeResizeChanged: (Boolean) -> Unit,
    onCornerHandleAutoHideChanged: (Boolean) -> Unit,
    onFreeformNoForeheadChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimOpenChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimCloseChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimBoundsChangeChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimEdgeSnapBackChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimEdgeDockChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimBubbleRestoreChanged: (Boolean) -> Unit,
    onFreeformHandleMenuAnimChanged: (Boolean) -> Unit,
    onForceFreeformEntryChanged: (Boolean) -> Unit,
    onForceRelativeAppFreeformChanged: (Boolean) -> Unit,
    onRestartSystem: () -> Unit,
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
                onClick = onRestartSystem,
                icon = { Icon(ZToolIcons.Refresh, contentDescription = null) },
                text = { Text(stringResource(R.string.freeform_hub_restart_button)) }
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
                    sections = freeformHubSections(
                        state = state,
                        onRemoveBlacklistChanged = onRemoveBlacklistChanged,
                        onUnlockOvCommonAppListChanged = onUnlockOvCommonAppListChanged,
                        onModuleEnabledChanged = onModuleEnabledChanged,
                        onStartFloatingWindow = onStartFloatingWindow,
                        onOpenConfigSelection = onOpenConfigSelection,
                        onOpenStrategySearch = onOpenStrategySearch,
                        onZuiForceSplit = onZuiForceSplit,
                        onZuiForceFreeform = onZuiForceFreeform,
                        onZuiForceFixed = onZuiForceFixed,
                        onEdgeBubbleChanged = onEdgeBubbleChanged,
                        onEdgeBubbleTriggerPercentChanged = onEdgeBubbleTriggerPercentChanged,
                        onFloatMandatoryChanged = onFloatMandatoryChanged,
                        onSplitScreenMandatoryChanged = onSplitScreenMandatoryChanged,
                        onFreeformFullscreenThresholdChanged = onFreeformFullscreenThresholdChanged,
                        onFreeformFullscreenWidthPercentChanged = onFreeformFullscreenWidthPercentChanged,
                        onFreeformFullscreenHeightPercentChanged = onFreeformFullscreenHeightPercentChanged,
                        onFreeformBottomEdgeResizeChanged = onFreeformBottomEdgeResizeChanged,
                        onCornerHandleAutoHideChanged = onCornerHandleAutoHideChanged,
                        onFreeformNoForeheadChanged = onFreeformNoForeheadChanged,
                        onFreeformNonlinearAnimChanged = onFreeformNonlinearAnimChanged,
                        onFreeformNonlinearAnimOpenChanged = onFreeformNonlinearAnimOpenChanged,
                        onFreeformNonlinearAnimCloseChanged = onFreeformNonlinearAnimCloseChanged,
                        onFreeformNonlinearAnimBoundsChangeChanged = onFreeformNonlinearAnimBoundsChangeChanged,
                        onFreeformNonlinearAnimEdgeSnapBackChanged = onFreeformNonlinearAnimEdgeSnapBackChanged,
                        onFreeformNonlinearAnimEdgeDockChanged = onFreeformNonlinearAnimEdgeDockChanged,
                        onFreeformNonlinearAnimBubbleRestoreChanged = onFreeformNonlinearAnimBubbleRestoreChanged,
                        onFreeformHandleMenuAnimChanged = onFreeformHandleMenuAnimChanged,
                        onForceFreeformEntryChanged = onForceFreeformEntryChanged,
                        onForceRelativeAppFreeformChanged = onForceRelativeAppFreeformChanged,
                    ),
                    bottomPadding = 96.dp,
                    highlightRegistry = highlightRegistry
                )
            }
        }
    }
}

@Composable
private fun freeformHubSections(
    state: FreeformHubUiState,
    onRemoveBlacklistChanged: (Boolean) -> Unit,
    onUnlockOvCommonAppListChanged: (Boolean) -> Unit,
    onModuleEnabledChanged: (Boolean) -> Unit,
    onStartFloatingWindow: () -> Unit,
    onOpenConfigSelection: () -> Unit,
    onOpenStrategySearch: () -> Unit,
    onZuiForceSplit: () -> Unit,
    onZuiForceFreeform: () -> Unit,
    onZuiForceFixed: () -> Unit,
    onEdgeBubbleChanged: (Boolean) -> Unit,
    onEdgeBubbleTriggerPercentChanged: (Int) -> Unit,
    onFloatMandatoryChanged: (Boolean) -> Unit,
    onSplitScreenMandatoryChanged: (Boolean) -> Unit,
    onFreeformFullscreenThresholdChanged: (Boolean) -> Unit,
    onFreeformFullscreenWidthPercentChanged: (Int) -> Unit,
    onFreeformFullscreenHeightPercentChanged: (Int) -> Unit,
    onFreeformBottomEdgeResizeChanged: (Boolean) -> Unit,
    onCornerHandleAutoHideChanged: (Boolean) -> Unit,
    onFreeformNoForeheadChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimOpenChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimCloseChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimBoundsChangeChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimEdgeSnapBackChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimEdgeDockChanged: (Boolean) -> Unit,
    onFreeformNonlinearAnimBubbleRestoreChanged: (Boolean) -> Unit,
    onFreeformHandleMenuAnimChanged: (Boolean) -> Unit,
    onForceFreeformEntryChanged: (Boolean) -> Unit,
    onForceRelativeAppFreeformChanged: (Boolean) -> Unit,
): List<SettingSection> {
    return buildList {
        add(
            SettingSection(
                title = stringResource(R.string.settings_embedding_setting_title),
                items = listOf(
                    SettingItem.Switch(
                        key = "settings_detail_remove_blacklist",
                        title = stringResource(R.string.settings_embedding_setting_remove_blacklist),
                        summary = stringResource(R.string.settings_embedding_setting_remove_blacklist_summary),
                        checked = state.removeBlacklist,
                        onCheckedChange = onRemoveBlacklistChanged
                    ),
                    SettingItem.Switch(
                        key = "settings_detail_role_module",
                        title = stringResource(R.string.settings_role_module_title),
                        summary = stringResource(R.string.settings_role_module_summary),
                        checked = state.moduleEnabled,
                        onCheckedChange = onModuleEnabledChanged
                    ),
                    freeformHubActionItem(
                        key = "settings_detail_custom_landscape_view",
                        title = stringResource(R.string.settings_custom_landscape_view),
                        summary = stringResource(R.string.settings_custom_landscape_view_summary),
                        onClick = onStartFloatingWindow,
                        icon = {
                            Icon(
                                imageVector = ZToolIcons.OpenInNew,
                                contentDescription = null,
                                tint = LocalZToolColorScheme.current.onSurfaceVariant
                            )
                        }
                    ),
                    freeformHubActionItem(
                        key = "settings_detail_custom_landscape_result",
                        title = stringResource(R.string.settings_custom_landscape_result_title),
                        summary = stringResource(R.string.settings_custom_landscape_result_summary),
                        onClick = onOpenConfigSelection
                    ),
                    freeformHubActionItem(
                        key = "settings_detail_yi_shi_jie_rules",
                        title = stringResource(R.string.settings_yi_shi_jie_rules),
                        summary = stringResource(R.string.settings_yi_shi_jie_rules_summary),
                        onClick = onOpenStrategySearch
                    )
                )
            )
        )

        add(
            SettingSection(
                title = stringResource(
                    if (state.showZuiForceConfig) {
                        R.string.settings_zui_force_config_title
                    } else {
                        R.string.settings_embedding_title
                    }
                ),
                items = if (state.showZuiForceConfig) {
                    val forceConfigEditorSupported = Build.VERSION.SDK_INT <= Build.VERSION_CODES.BAKLAVA
                    val forceConfigNativeSummary =
                        stringResource(R.string.settings_zui_force_config_native_summary)
                    buildList {
                        if (forceConfigEditorSupported) {
                            add(
                                SettingItem.Custom(
                                    key = "deco_freeform_hub_force_config_summary",
                                    content = {
                                        Text(
                                            text = stringResource(R.string.settings_zui_force_config_summary),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = LocalZToolColorScheme.current.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                        )
                                    }
                                )
                            )
                            if (state.forceFreeformEntryHookEnabled) {
                                add(
                                    SettingItem.Custom(
                                        key = "deco_freeform_hub_force_config_hook_warning",
                                        content = {
                                            Text(
                                                text = stringResource(
                                                    R.string.settings_zui_force_config_hook_warning
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = LocalZToolColorScheme.current.onSurfaceVariant,
                                                modifier = Modifier.padding(
                                                    horizontal = 24.dp,
                                                    vertical = 8.dp
                                                )
                                            )
                                        }
                                    )
                                )
                            }
                        }
                        add(
                            freeformHubActionItem(
                                key = "settings_detail_zui_force_split",
                                title = stringResource(R.string.settings_zui_force_split_title),
                                summary = stringResource(R.string.settings_zui_force_split_summary),
                                maxSdkVersion = Build.VERSION_CODES.BAKLAVA,
                                unsupportedHint = forceConfigNativeSummary,
                                onClick = onZuiForceSplit
                            )
                        )
                        add(
                            freeformHubActionItem(
                                key = "settings_detail_zui_force_freeform",
                                title = stringResource(R.string.settings_zui_force_freeform_title),
                                summary = stringResource(R.string.settings_zui_force_freeform_summary),
                                maxSdkVersion = Build.VERSION_CODES.BAKLAVA,
                                unsupportedHint = forceConfigNativeSummary,
                                onClick = onZuiForceFreeform
                            )
                        )
                        add(
                            freeformHubActionItem(
                                key = "settings_detail_zui_force_fixed",
                                title = stringResource(R.string.settings_zui_force_fixed_title),
                                summary = stringResource(R.string.settings_zui_force_fixed_summary),
                                maxSdkVersion = Build.VERSION_CODES.BAKLAVA,
                                unsupportedHint = forceConfigNativeSummary,
                                onClick = onZuiForceFixed
                            )
                        )
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_unlock_ov_common_app_list",
                                title = stringResource(R.string.settings_unlock_ov_common_app_list_title),
                                summary = stringResource(R.string.settings_unlock_ov_common_app_list_summary),
                                checked = state.unlockOvCommonAppList,
                                onCheckedChange = onUnlockOvCommonAppListChanged,
                                minSdkVersion = Build.VERSION_CODES.BAKLAVA
                            )
                        )
                        add(
                            SettingItem.Switch(
                                key = "settings_detail_edge_bubble",
                                title = stringResource(R.string.settings_edge_bubble_title),
                                summary = stringResource(R.string.settings_edge_bubble_summary),
                                checked = state.edgeBubbleEnabled,
                                onCheckedChange = onEdgeBubbleChanged
                            )
                        )
                        if (state.edgeBubbleEnabled) {
                            add(
                                SettingItem.Slider(
                                    key = "settings_detail_edge_bubble_percent",
                                    title = stringResource(
                                        R.string.settings_edge_bubble_trigger_percent_title
                                    ),
                                    summary = stringResource(
                                        R.string.settings_edge_bubble_trigger_percent_summary
                                    ),
                                    value = state.edgeBubbleTriggerPercent.toFloat(),
                                    valueText = stringResource(
                                        R.string.settings_edge_bubble_trigger_percent_label,
                                        state.edgeBubbleTriggerPercent
                                    ),
                                    valueRange =
                                        FreeformEdgeBubbleConfig.TRIGGER_PERCENT_MIN.toFloat()..
                                            FreeformEdgeBubbleConfig.TRIGGER_PERCENT_MAX.toFloat(),
                                    steps = (FreeformEdgeBubbleConfig.TRIGGER_PERCENT_MAX -
                                        FreeformEdgeBubbleConfig.TRIGGER_PERCENT_MIN) /
                                        FreeformEdgeBubbleConfig.TRIGGER_PERCENT_STEP - 1,
                                    onValueChange = {
                                        onEdgeBubbleTriggerPercentChanged(it.toInt())
                                    }
                                )
                            )
                            add(
                                SettingItem.Custom(
                                    key = "deco_freeform_hub_edge_bubble_hint",
                                    content = {
                                        Text(
                                            text = stringResource(R.string.settings_edge_bubble_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = LocalZToolColorScheme.current.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                horizontal = 24.dp,
                                                vertical = 8.dp
                                            )
                                        )
                                    }
                                )
                            )
                        }
                    }
                } else {
                    listOf(
                        SettingItem.Switch(
                            key = "settings_detail_float_app_mandatory",
                            title = stringResource(R.string.settings_float_app_mandatory),
                            summary = stringResource(R.string.settings_float_app_mandatory_summary),
                            checked = state.floatMandatory,
                            onCheckedChange = onFloatMandatoryChanged,
                            maxSdkVersion = Build.VERSION_CODES.BAKLAVA
                        ),
                        SettingItem.Switch(
                            key = "settings_detail_split_screen_mandatory",
                            title = stringResource(R.string.settings_split_screen_mandatory_title),
                            summary = stringResource(R.string.settings_split_screen_mandatory_summary),
                            checked = state.splitScreenMandatory,
                            onCheckedChange = onSplitScreenMandatoryChanged,
                            maxSdkVersion = Build.VERSION_CODES.BAKLAVA
                        )
                    )
                }
            )
        )

        add(
            SettingSection(
                title = stringResource(R.string.freeform_hub_system_ui_title),
                items = buildList {
                    add(
                        SettingItem.Switch(
                            key = "system_ui_misc_freeform_fullscreen_threshold",
                            title = stringResource(R.string.system_ui_misc_freeform_fullscreen_threshold_title),
                            summary = stringResource(R.string.system_ui_misc_freeform_fullscreen_threshold_summary),
                            checked = state.freeformFullscreenThreshold,
                            onCheckedChange = onFreeformFullscreenThresholdChanged
                        )
                    )
                    if (state.freeformFullscreenThreshold) {
                        add(
                            SettingItem.Switch(
                                key = "system_ui_misc_freeform_bottom_edge_resize",
                                title = stringResource(R.string.system_ui_misc_freeform_bottom_edge_resize_title),
                                summary = stringResource(R.string.system_ui_misc_freeform_bottom_edge_resize_summary),
                                checked = state.freeformBottomEdgeResize,
                                onCheckedChange = onFreeformBottomEdgeResizeChanged
                            )
                        )
                        add(
                            SettingItem.Custom(
                                key = "deco_freeform_hub_fullscreen_preview",
                                content = {
                                    FreeformFullscreenPreview(
                                        widthPercent = state.freeformFullscreenWidthPercent,
                                        heightPercent = state.freeformFullscreenHeightPercent
                                    )
                                }
                            )
                        )
                        add(
                            SettingItem.Slider(
                                key = "system_ui_misc_freeform_fullscreen_width",
                                title = stringResource(R.string.system_ui_misc_freeform_fullscreen_width_title),
                                summary = stringResource(R.string.system_ui_misc_freeform_fullscreen_width_summary),
                                value = state.freeformFullscreenWidthPercent.toFloat(),
                                valueText = stringResource(
                                    R.string.system_ui_misc_freeform_fullscreen_percent_label,
                                    state.freeformFullscreenWidthPercent
                                ),
                                valueRange = SystemUiMiscSettingsRepository.FREEFORM_FULLSCREEN_PERCENT_MIN.toFloat()..
                                    SystemUiMiscSettingsRepository.FREEFORM_FULLSCREEN_PERCENT_MAX.toFloat(),
                                steps = 9,
                                onValueChange = {
                                    onFreeformFullscreenWidthPercentChanged(it.toInt())
                                }
                            )
                        )
                        add(
                            SettingItem.Slider(
                                key = "system_ui_misc_freeform_fullscreen_height",
                                title = stringResource(R.string.system_ui_misc_freeform_fullscreen_height_title),
                                summary = stringResource(R.string.system_ui_misc_freeform_fullscreen_height_summary),
                                value = state.freeformFullscreenHeightPercent.toFloat(),
                                valueText = stringResource(
                                    R.string.system_ui_misc_freeform_fullscreen_percent_label,
                                    state.freeformFullscreenHeightPercent
                                ),
                                valueRange = SystemUiMiscSettingsRepository.FREEFORM_FULLSCREEN_PERCENT_MIN.toFloat()..
                                    SystemUiMiscSettingsRepository.FREEFORM_FULLSCREEN_PERCENT_MAX.toFloat(),
                                steps = 9,
                                onValueChange = {
                                    onFreeformFullscreenHeightPercentChanged(it.toInt())
                                }
                            )
                        )
                    }
                    add(
                        SettingItem.Switch(
                            key = "system_ui_misc_freeform_no_forehead",
                            title = stringResource(R.string.system_ui_misc_freeform_no_forehead_title),
                            summary = stringResource(R.string.system_ui_misc_freeform_no_forehead_summary),
                            checked = state.freeformNoForehead,
                            onCheckedChange = onFreeformNoForeheadChanged
                        )
                    )
                    add(
                        SettingItem.Switch(
                            key = "system_ui_misc_corner_handle_auto_hide",
                            title = stringResource(R.string.system_ui_misc_corner_handle_auto_hide_title),
                            summary = stringResource(R.string.system_ui_misc_corner_handle_auto_hide_summary),
                            checked = state.cornerHandleAutoHide,
                            onCheckedChange = onCornerHandleAutoHideChanged
                        )
                    )
                    add(
                        SettingItem.Switch(
                            key = "system_ui_misc_freeform_nonlinear_anim",
                            title = stringResource(R.string.system_ui_misc_freeform_nonlinear_anim_title),
                            summary = stringResource(R.string.system_ui_misc_freeform_nonlinear_anim_summary),
                            checked = state.freeformNonlinearAnim,
                            onCheckedChange = onFreeformNonlinearAnimChanged
                        )
                    )
                    if (state.freeformNonlinearAnim) {
                        add(
                            SettingItem.Switch(
                                key = "system_ui_misc_freeform_nonlinear_anim_open",
                                title = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_open_title
                                ),
                                summary = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_open_summary
                                ),
                                checked = state.freeformNonlinearAnimOpen,
                                onCheckedChange = onFreeformNonlinearAnimOpenChanged
                            )
                        )
                        add(
                            SettingItem.Switch(
                                key = "system_ui_misc_freeform_nonlinear_anim_close",
                                title = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_close_title
                                ),
                                summary = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_close_summary
                                ),
                                checked = state.freeformNonlinearAnimClose,
                                onCheckedChange = onFreeformNonlinearAnimCloseChanged
                            )
                        )
                        add(
                            SettingItem.Switch(
                                key = "system_ui_misc_freeform_nonlinear_anim_bounds_change",
                                title = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_bounds_change_title
                                ),
                                summary = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_bounds_change_summary
                                ),
                                checked = state.freeformNonlinearAnimBoundsChange,
                                onCheckedChange = onFreeformNonlinearAnimBoundsChangeChanged
                            )
                        )
                        add(
                            SettingItem.Switch(
                                key = "system_ui_misc_freeform_nonlinear_anim_edge_snap_back",
                                title = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_edge_snap_back_title
                                ),
                                summary = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_edge_snap_back_summary
                                ),
                                checked = state.freeformNonlinearAnimEdgeSnapBack,
                                onCheckedChange = onFreeformNonlinearAnimEdgeSnapBackChanged
                            )
                        )
                        add(
                            SettingItem.Switch(
                                key = "system_ui_misc_freeform_nonlinear_anim_edge_dock",
                                title = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_edge_dock_title
                                ),
                                summary = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_edge_dock_summary
                                ),
                                checked = state.freeformNonlinearAnimEdgeDock,
                                onCheckedChange = onFreeformNonlinearAnimEdgeDockChanged
                            )
                        )
                        add(
                            SettingItem.Switch(
                                key = "system_ui_misc_freeform_nonlinear_anim_bubble_restore",
                                title = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_bubble_restore_title
                                ),
                                summary = stringResource(
                                    R.string.system_ui_misc_freeform_nonlinear_anim_bubble_restore_summary
                                ),
                                checked = state.freeformNonlinearAnimBubbleRestore,
                                onCheckedChange = onFreeformNonlinearAnimBubbleRestoreChanged
                            )
                        )
                    }
                    add(
                        SettingItem.Switch(
                            key = "system_ui_misc_freeform_handle_menu_anim",
                            title = stringResource(R.string.system_ui_misc_freeform_handle_menu_anim_title),
                            checked = state.freeformHandleMenuAnim,
                            onCheckedChange = onFreeformHandleMenuAnimChanged
                        )
                    )
                }
            )
        )

        add(
            SettingSection(
                title = stringResource(R.string.freeform_hub_launcher_title),
                items = listOf(
                    SettingItem.Switch(
                        key = "launcher_force_freeform_entry",
                        title = stringResource(R.string.launcher_force_freeform_entry_title),
                        summary = stringResource(R.string.launcher_force_freeform_entry_summary),
                        checked = state.forceFreeformEntry,
                        onCheckedChange = onForceFreeformEntryChanged
                    )
                )
            )
        )

        add(
            SettingSection(
                title = stringResource(R.string.freeform_hub_relative_launch_title),
                items = listOf(
                    SettingItem.Switch(
                        key = "framework_force_relative_app_freeform",
                        title = stringResource(R.string.system_framework_force_relative_app_freeform_title),
                        checked = state.forceRelativeAppFreeform,
                        onCheckedChange = onForceRelativeAppFreeformChanged
                    )
                )
            )
        )
    }
}

@Composable
private fun freeformHubActionItem(
    title: String,
    summary: String,
    onClick: () -> Unit,
    key: String,
    minSdkVersion: Int? = null,
    maxSdkVersion: Int? = null,
    unsupportedHint: String? = null,
    icon: (@Composable () -> Unit)? = null
): SettingItem {
    return SettingItem.Action(
        key = key,
        title = title,
        summary = summary,
        minSdkVersion = minSdkVersion,
        maxSdkVersion = maxSdkVersion,
        unsupportedHint = unsupportedHint,
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

private enum class ThresholdWinner { Width, Height, Both, None }

@Composable
private fun FreeformFullscreenPreview(
    widthPercent: Int,
    heightPercent: Int
) {
    val configuration = LocalConfiguration.current
    val widthEnabled = widthPercent < 100
    val heightEnabled = heightPercent < 100
    val widthRemaining = if (widthEnabled) {
        (widthPercent - 50).coerceAtLeast(0) * configuration.screenWidthDp
    } else {
        Int.MAX_VALUE
    }
    val heightRemaining = if (heightEnabled) {
        (heightPercent - 50).coerceAtLeast(0) * configuration.screenHeightDp
    } else {
        Int.MAX_VALUE
    }
    val winner = when {
        !widthEnabled && !heightEnabled -> ThresholdWinner.None
        !widthEnabled -> ThresholdWinner.Height
        !heightEnabled -> ThresholdWinner.Width
        widthRemaining < heightRemaining -> ThresholdWinner.Width
        heightRemaining < widthRemaining -> ThresholdWinner.Height
        else -> ThresholdWinner.Both
    }

    Text(
        text = stringResource(
            when (winner) {
                ThresholdWinner.Width -> R.string.system_ui_misc_freeform_fullscreen_preview_width
                ThresholdWinner.Height -> R.string.system_ui_misc_freeform_fullscreen_preview_height
                ThresholdWinner.Both -> R.string.system_ui_misc_freeform_fullscreen_preview_both
                ThresholdWinner.None -> R.string.system_ui_misc_freeform_fullscreen_preview_disabled
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = LocalZToolColorScheme.current.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
private fun SimpleMessageDialogContent(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    ZToolDialog(
        onDismissRequest = { onDismiss?.invoke() ?: onConfirm() },
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            ZToolTextButton(onClick = onConfirm, text = confirmText)
        },
        dismissButton = if (dismissText != null && onDismiss != null) {
            {
                ZToolTextButton(
                    onClick = onDismiss,
                    text = dismissText,
                    isPrimary = false
                )
            }
        } else null
    )
}
