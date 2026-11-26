package com.qimian233.ztool.data.freeform

import android.content.Context
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.data.launcher.LauncherSettingsRepository
import com.qimian233.ztool.data.settings.OvConfigSelection
import com.qimian233.ztool.data.settings.SettingsDetailRepository
import com.qimian233.ztool.data.systemframework.FrameworkSettingsRepository
import com.qimian233.ztool.data.systemui.SystemUiMiscSettingsRepository
import com.qimian233.ztool.utils.EmbeddingConfigManager
import com.qimian233.ztool.utils.OvCommonConfigManager
import com.qimian233.ztool.viewmodel.FreeformHubUiState

/**
 * Aggregates the small-window settings owned by the settings, SystemUI, launcher and
 * framework repositories for the consolidated freeform hub.
 *
 * The hub only composes existing persistence; every key and default stays owned by
 * its original repository so hook-side reads are unaffected.
 */
class FreeformHubRepository(
    context: Context,
    private val settingsDetailRepository: SettingsDetailRepository = SettingsDetailRepository(context),
    private val systemUiMiscSettingsRepository: SystemUiMiscSettingsRepository =
        SystemUiMiscSettingsRepository(context),
    private val launcherSettingsRepository: LauncherSettingsRepository =
        LauncherSettingsRepository(context),
    private val frameworkSettingsRepository: FrameworkSettingsRepository =
        FrameworkSettingsRepository(context),
    private val shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance()
) {
    fun loadState(): FreeformHubUiState {
        val detail = settingsDetailRepository.loadState()
        val misc = systemUiMiscSettingsRepository.loadState()
        val launcher = launcherSettingsRepository.loadState()
        val framework = frameworkSettingsRepository.loadState()
        return FreeformHubUiState(
            removeBlacklist = detail.removeBlacklist,
            moduleEnabled = detail.moduleEnabled,
            showZuiForceConfig = detail.showZuiForceConfig,
            forceFreeformEntryHookEnabled = detail.forceFreeformEntryHookEnabled,
            unlockOvCommonAppList = detail.unlockOvCommonAppList,
            edgeBubbleEnabled = detail.edgeBubbleEnabled,
            edgeBubbleTriggerPercent = detail.edgeBubbleTriggerPercent,
            floatMandatory = detail.floatMandatory,
            splitScreenMandatory = detail.splitScreenMandatory,
            freeformFullscreenThreshold = misc.freeformFullscreenThreshold,
            freeformFullscreenWidthPercent = misc.freeformFullscreenWidthPercent,
            freeformFullscreenHeightPercent = misc.freeformFullscreenHeightPercent,
            freeformBottomEdgeResize = misc.freeformBottomEdgeResize,
            cornerHandleAutoHide = misc.cornerHandleAutoHide,
            freeformNoForehead = misc.freeformNoForehead,
            freeformNonlinearAnim = misc.freeformNonlinearAnim,
            freeformNonlinearAnimOpen = misc.freeformNonlinearAnimOpen,
            freeformNonlinearAnimClose = misc.freeformNonlinearAnimClose,
            freeformNonlinearAnimBoundsChange = misc.freeformNonlinearAnimBoundsChange,
            freeformNonlinearAnimEdgeSnapBack = misc.freeformNonlinearAnimEdgeSnapBack,
            freeformNonlinearAnimEdgeDock = misc.freeformNonlinearAnimEdgeDock,
            freeformNonlinearAnimBubbleRestore = misc.freeformNonlinearAnimBubbleRestore,
            freeformHandleMenuAnim = misc.freeformHandleMenuAnim,
            forceFreeformEntry = launcher.forceFreeformEntry,
            forceRelativeAppFreeform = framework.forceRelativeAppFreeform
        )
    }

    fun saveRemoveBlacklist(enabled: Boolean) =
        settingsDetailRepository.saveRemoveBlacklist(enabled)

    fun saveUnlockOvCommonAppList(enabled: Boolean) =
        settingsDetailRepository.saveUnlockOvCommonAppList(enabled)

    fun isModuleEnabled(): Boolean = settingsDetailRepository.isModuleEnabled()

    fun setModuleEnabled(enabled: Boolean): String =
        settingsDetailRepository.setModuleEnabled(enabled)

    fun restoreOriginalModule(): String = settingsDetailRepository.restoreOriginalModule()

    fun loadFlashedConfigs(): HashSet<String> = settingsDetailRepository.loadFlashedConfigs()

    fun loadEmbeddingConfigFiles(): List<EmbeddingConfigManager.ConfigFileInfo> =
        settingsDetailRepository.loadEmbeddingConfigFiles()

    fun deleteEmbeddingConfigs(
        configs: List<EmbeddingConfigManager.ConfigFileInfo>,
        flashed: Set<String>
    ): Int = settingsDetailRepository.deleteEmbeddingConfigs(configs, flashed)

    fun flashEmbeddingConfigs(configs: List<EmbeddingConfigManager.ConfigFileInfo>) =
        settingsDetailRepository.flashEmbeddingConfigs(configs)

    fun loadOvConfigSelection(mode: Int): OvConfigSelection =
        settingsDetailRepository.loadOvConfigSelection(mode)

    fun saveOvConfig(
        configMap: MutableMap<String, OvCommonConfigManager.AppConfig>,
        selectedPackages: List<String>,
        mode: Int
    ): String = settingsDetailRepository.saveOvConfig(configMap, selectedPackages, mode)

    fun saveFloatMandatory(enabled: Boolean) =
        settingsDetailRepository.saveForceResizableActivities(enabled)

    fun saveSplitScreenMandatory(enabled: Boolean) =
        settingsDetailRepository.saveSplitScreenMandatory(enabled)

    fun saveEdgeBubbleEnabled(enabled: Boolean) =
        settingsDetailRepository.saveEdgeBubbleEnabled(enabled)

    fun saveEdgeBubbleTriggerPercent(percent: Int) =
        settingsDetailRepository.saveEdgeBubbleTriggerPercent(percent)

    fun saveFreeformFullscreenThreshold(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformFullscreenThreshold(enabled)

    fun saveFreeformFullscreenWidthPercent(percent: Int) =
        systemUiMiscSettingsRepository.saveFreeformFullscreenWidthPercent(percent)

    fun saveFreeformFullscreenHeightPercent(percent: Int) =
        systemUiMiscSettingsRepository.saveFreeformFullscreenHeightPercent(percent)

    fun saveFreeformBottomEdgeResize(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformBottomEdgeResize(enabled)

    fun saveCornerHandleAutoHide(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveCornerHandleAutoHide(enabled)

    fun saveFreeformNoForehead(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNoForehead(enabled)

    fun saveFreeformNonlinearAnim(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNonlinearAnim(enabled)

    fun saveFreeformNonlinearAnimOpen(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNonlinearAnimOpen(enabled)

    fun saveFreeformNonlinearAnimClose(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNonlinearAnimClose(enabled)

    fun saveFreeformNonlinearAnimBoundsChange(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNonlinearAnimBoundsChange(enabled)

    fun saveFreeformNonlinearAnimEdgeSnapBack(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNonlinearAnimEdgeSnapBack(enabled)

    fun saveFreeformNonlinearAnimEdgeDock(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNonlinearAnimEdgeDock(enabled)

    fun saveFreeformNonlinearAnimBubbleRestore(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformNonlinearAnimBubbleRestore(enabled)

    fun saveFreeformHandleMenuAnim(enabled: Boolean) =
        systemUiMiscSettingsRepository.saveFreeformHandleMenuAnim(enabled)

    fun saveForceFreeformEntry(enabled: Boolean) =
        launcherSettingsRepository.saveForceFreeformEntry(enabled)

    fun saveForceRelativeAppFreeform(enabled: Boolean) =
        frameworkSettingsRepository.saveForceRelativeAppFreeform(enabled)

    /** Triggers a full reboot; required because the hub also touches framework scopes. */
    fun restartSystem(): EnhancedShellExecutor.ShellResult =
        shellExecutor.executeRootCommand("reboot", RESTART_TIMEOUT_SECONDS)

    private companion object {
        const val RESTART_TIMEOUT_SECONDS = 5
    }
}
