package com.qimian233.ztool.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimian233.ztool.data.gametool.CpuCoreCatalog
import com.qimian233.ztool.data.gametool.CpuCoreInfo
import com.qimian233.ztool.data.gametool.DeviceModel
import com.qimian233.ztool.data.gametool.GameToolRestartResult
import com.qimian233.ztool.data.gametool.GameToolSettingsRepository
import com.qimian233.ztool.data.gametool.ThermalZoneInfo
import com.qimian233.ztool.data.keys.PreferenceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameToolSettingsViewModel(
    private val repository: GameToolSettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(GameToolSettingsUiState())
    val uiState: StateFlow<GameToolSettingsUiState> = _uiState.asStateFlow()

    fun loadSettings() {
        try {
            val loaded = repository.loadState()
            _uiState.update { current ->
                loaded.copy(
                    availableModels = current.availableModels,
                    availableCpuCores = current.availableCpuCores,
                    availableThermalZones = current.availableThermalZones,
                    showThermalZonePicker = current.showThermalZonePicker,
                    showRestartConfirmDialog = current.showRestartConfirmDialog
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load game tool settings", e)
        }
        loadAvailableModels()
        loadAvailableCpuCores()
        loadAvailableThermalZones()
    }

    private fun loadAvailableModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val models = runCatching { repository.loadAvailableModels() }.getOrDefault(emptyList())
            _uiState.update { it.copy(availableModels = models) }
        }
    }

    private fun loadAvailableCpuCores() {
        viewModelScope.launch(Dispatchers.IO) {
            val cores = runCatching { repository.loadAvailableCpuCores() }.getOrDefault(emptyList())
            _uiState.update { it.copy(availableCpuCores = cores) }
        }
    }

    private fun loadAvailableThermalZones() {
        viewModelScope.launch(Dispatchers.IO) {
            val zones = runCatching { repository.loadAvailableThermalZones() }.getOrDefault(emptyList())
            _uiState.update { it.copy(availableThermalZones = zones) }
        }
    }

    fun setDisableGameAudio(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(disableGameAudio = enabled)
        repository.saveDisableGameAudio(enabled)
    }

    fun setDisguiseDevice(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(disguiseDevice = enabled)
        repository.saveDisguiseDevice(enabled)
    }

    fun setDisguiseModel(model: DeviceModel) {
        _uiState.value = _uiState.value.copy(disguiseModel = model.code)
        repository.saveDisguiseModel(model.code)
    }

    fun setFixCpuFrequency(enabled: Boolean) {
        _uiState.update { it.copy(fixCpuFrequency = enabled) }
        repository.saveFixCpuFrequency(enabled)
    }

    fun setCpuFreqCore(coreIndex: Int) {
        val currentState = _uiState.value
        val allowedNodes = CpuCoreCatalog.availableNodesForSelection(
            currentState.availableCpuCores,
            coreIndex
        )
        val nextNode = if (currentState.cpuFreqNode in allowedNodes) {
            currentState.cpuFreqNode
        } else {
            allowedNodes.firstOrNull() ?: PreferenceKeys.FIX_CPU_CLOCK_NODE.default
        }
        _uiState.update {
            it.copy(
                cpuFreqCore = coreIndex,
                cpuFreqNode = nextNode
            )
        }
        repository.saveCpuFreqCore(coreIndex)
        if (nextNode != currentState.cpuFreqNode) {
            repository.saveCpuFreqNode(nextNode)
        }
    }

    fun setCpuFreqNode(node: String) {
        if (node !in CpuCoreCatalog.ALLOWED_FREQ_NODES) return
        _uiState.update { it.copy(cpuFreqNode = node) }
        repository.saveCpuFreqNode(node)
    }

    fun setFixSocTemperature(enabled: Boolean) {
        _uiState.update { it.copy(fixSocTemperature = enabled) }
        repository.saveFixSocTemperature(enabled)
    }

    fun openThermalZonePicker() {
        _uiState.update { it.copy(showThermalZonePicker = true) }
        loadAvailableThermalZones()
    }

    fun dismissThermalZonePicker() {
        _uiState.update { it.copy(showThermalZonePicker = false) }
    }

    fun setSocTempZone(type: String, indexHint: Int) {
        _uiState.update {
            it.copy(
                socTempZoneType = type,
                socTempZoneIndexHint = indexHint,
                showThermalZonePicker = false
            )
        }
        repository.saveSocTempZone(type, indexHint)
    }

    fun setMistakeTouchMode(mode: MistakeTouchMode) {
        _uiState.value = _uiState.value.copy(mistakeTouchMode = mode)
        repository.saveMistakeTouchMode(mode)
    }

    fun setWhitelistPackages(packageNames: List<String>) {
        packageNames.forEach {
            Log.d(TAG, "Selected game package: $it")
        }
        _uiState.value = _uiState.value.copy(targetGamePackages = packageNames)
        repository.saveWhitelistPackages(packageNames)
    }

    fun loadManagedGamePackages(): List<String> {
        return repository.loadManagedGamePackages()
    }

    fun showRestartConfirmDialog() {
        _uiState.value = _uiState.value.copy(showRestartConfirmDialog = true)
    }

    fun dismissRestartConfirmDialog() {
        _uiState.value = _uiState.value.copy(showRestartConfirmDialog = false)
    }

    fun forceStopPackage(onFailure: () -> Unit) {
        _uiState.value = _uiState.value.copy(showRestartConfirmDialog = false)
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.forceStopPackage()
            if (result is GameToolRestartResult.Failure) {
                withContext(Dispatchers.Main) {
                    onFailure()
                }
            }
        }
    }

    companion object {
        private const val TAG = "GameToolSettngs"
    }
}

enum class MistakeTouchMode {
    Default,
    AllGames,
    Whitelist
}

data class GameToolSettingsUiState(
    val disableGameAudio: Boolean = false,
    val disguiseDevice: Boolean = false,
    val disguiseModel: String = PreferenceKeys.DEVICE_DISGUISE_MODEL.default,
    val availableModels: List<DeviceModel> = emptyList(),
    val fixCpuFrequency: Boolean = false,
    val cpuFreqCore: Int = PreferenceKeys.FIX_CPU_CLOCK_CORE.default,
    val cpuFreqNode: String = PreferenceKeys.FIX_CPU_CLOCK_NODE.default,
    val availableCpuCores: List<CpuCoreInfo> = emptyList(),
    val fixSocTemperature: Boolean = false,
    val socTempZoneType: String = PreferenceKeys.FIX_SOC_TEMP_ZONE_TYPE.default,
    val socTempZoneIndexHint: Int = PreferenceKeys.FIX_SOC_TEMP_ZONE_INDEX_HINT.default,
    val availableThermalZones: List<ThermalZoneInfo> = emptyList(),
    val showThermalZonePicker: Boolean = false,
    val mistakeTouchMode: MistakeTouchMode = MistakeTouchMode.Default,
    val targetGamePackages: List<String> = emptyList(),
    val showRestartConfirmDialog: Boolean = false
) {
    val whitelistCount: Int
        get() = targetGamePackages.size
}
