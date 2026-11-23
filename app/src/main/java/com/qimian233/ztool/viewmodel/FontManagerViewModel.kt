package com.qimian233.ztool.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.data.settings.FontManagerRepository
import com.qimian233.ztool.utils.FontInstallerManager
import com.qimian233.ztool.utils.FontNameValidationError
import com.qimian233.ztool.utils.FontValidationException
import com.qimian233.ztool.utils.FontValidationFailureReason
import com.qimian233.ztool.utils.ManagedFontInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class FontManagerBusyAction {
    PREPARING,
    IMPORTING,
    APPLYING,
    RESTORING_DEFAULT,
    DELETING
}

data class ManagedFontUiItem(
    val info: ManagedFontInfo,
    val previewFilePath: String?,
    val isActive: Boolean
)

data class FontManagerUiState(
    val isLoading: Boolean = true,
    val busyAction: FontManagerBusyAction? = null,
    val rootAvailable: Boolean = true,
    val currentFontName: String = "",
    val defaultSystemFontName: String = "",
    val isUsingSystemDefault: Boolean = true,
    val fonts: List<ManagedFontUiItem> = emptyList(),
    val allLocalFontNames: Set<String> = emptySet(),
    val errorMessage: String? = null
)

sealed interface FontPreparationResult {
    data class Success(val originalFileName: String?) : FontPreparationResult
    data class Failure(
        val reason: FontValidationFailureReason?,
        val message: String
    ) : FontPreparationResult
}

sealed interface FontInstallResult {
    data object Success : FontInstallResult
    data class Failure(
        val reason: FontValidationFailureReason?,
        val message: String
    ) : FontInstallResult
}

sealed interface FontActionResult {
    data object Success : FontActionResult
    data class Failure(val message: String) : FontActionResult
}

/** ViewModel backing the dedicated ZTool font management page. */
class FontManagerViewModel(
    private val repository: FontManagerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FontManagerUiState())
    val uiState: StateFlow<FontManagerUiState> = _uiState.asStateFlow()

    private var currentSelectedFontFile: File? = null

    fun loadFonts(showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        }
        viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
            try {
                val snapshot = repository.loadSnapshot()
                val items = snapshot.fonts.map { entry ->
                    ManagedFontUiItem(
                        info = entry.info,
                        previewFilePath = entry.previewFilePath,
                        isActive = entry.isActive
                    )
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        rootAvailable = snapshot.rootAvailable,
                        currentFontName = snapshot.currentFontName,
                        defaultSystemFontName = snapshot.defaultSystemFontName,
                        isUsingSystemDefault = snapshot.isUsingSystemDefault,
                        fonts = items,
                        allLocalFontNames = snapshot.allLocalFontNames,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = e.message.orEmpty()
                    )
                }
            }
        }
    }

    fun refreshCurrentFontState() {
        viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
            val (currentName, isDefault) = repository.readCurrentFontState()
            withContext(Dispatchers.Main) {
                val currentState = _uiState.value
                _uiState.value = currentState.copy(
                    currentFontName = currentName,
                    isUsingSystemDefault = isDefault,
                    fonts = currentState.fonts.map { item ->
                        item.copy(
                            isActive = item.info.name.trim().equals(currentName.trim(), ignoreCase = true)
                        )
                    }
                )
            }
        }
    }

    fun prepareFontImport(
        uri: Uri,
        onResult: (FontPreparationResult) -> Unit
    ) {
        _uiState.value = _uiState.value.copy(busyAction = FontManagerBusyAction.PREPARING)
        viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
            try {
                val preparation = repository.prepareFontImport(uri)
                currentSelectedFontFile = preparation.file
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontPreparationResult.Success(preparation.originalFileName))
                }
            } catch (e: FontValidationException) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontPreparationResult.Failure(e.reason, e.message.orEmpty()))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontPreparationResult.Failure(null, e.message.orEmpty()))
                }
            }
        }
    }

    fun validateImportName(name: String): FontNameValidationError? {
        return FontInstallerManager.validateFontName(name, _uiState.value.allLocalFontNames)
    }

    fun clearPendingImport() {
        val pending = currentSelectedFontFile
        currentSelectedFontFile = null
        if (pending != null) {
            viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
                repository.clearPreparedFont(pending)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.clearPreparedFont(currentSelectedFontFile)
        currentSelectedFontFile = null
    }

    fun installFont(
        fontName: String,
        fontDescription: String,
        onResult: (FontInstallResult) -> Unit
    ) {
        _uiState.value = _uiState.value.copy(busyAction = FontManagerBusyAction.IMPORTING)
        viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
            try {
                repository.installFont(
                    fontFile = currentSelectedFontFile,
                    fontName = fontName,
                    fontDescription = fontDescription
                )
                currentSelectedFontFile = null
                val snapshot = repository.loadSnapshot()
                val items = snapshot.fonts.map { entry ->
                    ManagedFontUiItem(entry.info, entry.previewFilePath, entry.isActive)
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        busyAction = null,
                        rootAvailable = snapshot.rootAvailable,
                        currentFontName = snapshot.currentFontName,
                        defaultSystemFontName = snapshot.defaultSystemFontName,
                        isUsingSystemDefault = snapshot.isUsingSystemDefault,
                        fonts = items,
                        allLocalFontNames = snapshot.allLocalFontNames,
                        errorMessage = null
                    )
                    onResult(FontInstallResult.Success)
                }
            } catch (e: FontValidationException) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontInstallResult.Failure(e.reason, e.message.orEmpty()))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontInstallResult.Failure(null, e.message.orEmpty()))
                }
            }
        }
    }

    fun applyFont(
        item: ManagedFontUiItem,
        onResult: (FontActionResult) -> Unit
    ) {
        _uiState.value = _uiState.value.copy(busyAction = FontManagerBusyAction.APPLYING)
        viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
            try {
                repository.applyFont(item.info)
                val (currentName, isDefault) = repository.readCurrentFontState()
                withContext(Dispatchers.Main) {
                    val currentState = _uiState.value
                    _uiState.value = currentState.copy(
                        busyAction = null,
                        currentFontName = currentName,
                        isUsingSystemDefault = isDefault,
                        fonts = currentState.fonts.map { entry ->
                            entry.copy(
                                isActive = entry.info.name.trim().equals(currentName.trim(), ignoreCase = true)
                            )
                        }
                    )
                    onResult(FontActionResult.Success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontActionResult.Failure(e.message.orEmpty()))
                }
            }
        }
    }

    fun restoreSystemDefaultFont(onResult: (FontActionResult) -> Unit) {
        _uiState.value = _uiState.value.copy(busyAction = FontManagerBusyAction.RESTORING_DEFAULT)
        viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
            try {
                repository.restoreSystemDefaultFont()
                val (currentName, isDefault) = repository.readCurrentFontState()
                withContext(Dispatchers.Main) {
                    val currentState = _uiState.value
                    _uiState.value = currentState.copy(
                        busyAction = null,
                        currentFontName = currentName,
                        isUsingSystemDefault = isDefault,
                        fonts = currentState.fonts.map { entry ->
                            entry.copy(
                                isActive = entry.info.name.trim().equals(currentName.trim(), ignoreCase = true)
                            )
                        }
                    )
                    onResult(FontActionResult.Success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontActionResult.Failure(e.message.orEmpty()))
                }
            }
        }
    }

    fun deleteFont(
        item: ManagedFontUiItem,
        onResult: (FontActionResult) -> Unit
    ) {
        _uiState.value = _uiState.value.copy(busyAction = FontManagerBusyAction.DELETING)
        viewModelScope.launch(EnhancedShellExecutor.shellWorkDispatcher) {
            try {
                repository.deleteFont(item.info, isActive = item.isActive)
                val snapshot = repository.loadSnapshot()
                val items = snapshot.fonts.map { entry ->
                    ManagedFontUiItem(entry.info, entry.previewFilePath, entry.isActive)
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        busyAction = null,
                        rootAvailable = snapshot.rootAvailable,
                        currentFontName = snapshot.currentFontName,
                        defaultSystemFontName = snapshot.defaultSystemFontName,
                        isUsingSystemDefault = snapshot.isUsingSystemDefault,
                        fonts = items,
                        allLocalFontNames = snapshot.allLocalFontNames,
                        errorMessage = null
                    )
                    onResult(FontActionResult.Success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busyAction = null)
                    onResult(FontActionResult.Failure(e.message.orEmpty()))
                }
            }
        }
    }
}
