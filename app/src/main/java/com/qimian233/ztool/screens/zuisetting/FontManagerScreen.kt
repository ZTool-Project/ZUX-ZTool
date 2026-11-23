package com.qimian233.ztool.screens.zuisetting

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.qimian233.ztool.R
import com.qimian233.ztool.data.settings.FontManagerRepository
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.LoadingDialog
import com.qimian233.ztool.ui.components.SettingItem
import com.qimian233.ztool.ui.components.SettingSection
import com.qimian233.ztool.ui.components.ZToolButton
import com.qimian233.ztool.ui.components.ZToolCard
import com.qimian233.ztool.ui.components.ZToolCircularProgressIndicator
import com.qimian233.ztool.ui.components.ZToolDialog
import com.qimian233.ztool.ui.components.ZToolPageSurface
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolSettingsList
import com.qimian233.ztool.ui.components.ZToolTextButton
import com.qimian233.ztool.ui.components.ZToolTextInputRow
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.ui.icons.ZToolIcons
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.utils.FontNameValidationError
import com.qimian233.ztool.utils.FontValidationFailureReason
import com.qimian233.ztool.viewmodel.FontActionResult
import com.qimian233.ztool.viewmodel.FontInstallResult
import com.qimian233.ztool.viewmodel.FontManagerBusyAction
import com.qimian233.ztool.viewmodel.FontManagerUiState
import com.qimian233.ztool.viewmodel.FontManagerViewModel
import com.qimian233.ztool.viewmodel.FontPreparationResult
import com.qimian233.ztool.viewmodel.ManagedFontUiItem

private const val EXTRA_FONT_CHANGED = "android.intent.configuration.theme.font_changed"

/** Font management route for importing, previewing, applying, and deleting ZTool fonts. */
@Composable
fun FontManagerRoute(
    title: String,
    onBack: () -> Unit,
    targetId: String? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val owner = LocalViewModelStoreOwner.current
        ?: error("FontManagerRoute requires a ViewModelStoreOwner")
    val viewModel = remember(owner) {
        ViewModelProvider(
            owner,
            FontManagerViewModelFactory(FontManagerRepository(context.applicationContext))
        )[FontManagerViewModel::class.java]
    }

    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val highlightRegistry = remember { HighlightAnchorRegistry() }

    val preparingText = stringResource(R.string.settings_preparing_font_file)
    val importingText = stringResource(R.string.settings_importing_font)
    val applyingText = stringResource(R.string.settings_font_manager_applying_message)
    val restoringText = stringResource(R.string.settings_font_manager_restoring_message)
    val deletingText = stringResource(R.string.settings_font_manager_deleting_message)
    val selectFontText = stringResource(R.string.settings_select_ttf_file)
    val unknownFilenameText = stringResource(R.string.settings_unknown_font_filename)
    val importSuccessTitleText = stringResource(R.string.settings_import_success_title)
    val importSuccessMessageText = stringResource(R.string.settings_import_success_message)
    val importFailedTitleText = stringResource(R.string.settings_import_failed_title)
    val actionFailedTitleText = stringResource(R.string.settings_font_manager_action_failed_title)
    val restoreSuccessText = stringResource(R.string.settings_font_manager_restore_success_message)
    val invalidFileText = stringResource(R.string.settings_font_manager_invalid_file_error)

    var pendingImportFileName by remember { mutableStateOf<String?>(null) }
    var importDialogError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<ManagedFontUiItem?>(null) }
    var messageDialogState by remember { mutableStateOf<Pair<String, String>?>(null) }

    val loadingDialog = remember(context) { LoadingDialog(context) }

    LaunchedEffect(viewModel) {
        viewModel.loadFonts()
    }

    LaunchedEffect(uiState.busyAction) {
        val message = when (uiState.busyAction) {
            FontManagerBusyAction.PREPARING -> preparingText
            FontManagerBusyAction.IMPORTING -> importingText
            FontManagerBusyAction.APPLYING -> applyingText
            FontManagerBusyAction.RESTORING_DEFAULT -> restoringText
            FontManagerBusyAction.DELETING -> deletingText
            null -> null
        }
        if (message != null) {
            loadingDialog.show(message)
        } else {
            loadingDialog.dismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            loadingDialog.dismiss()
        }
    }

    DisposableEffect(context, viewModel) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.getBooleanExtra(EXTRA_FONT_CHANGED, false) == true) {
                    viewModel.refreshCurrentFontState()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            viewModel.prepareFontImport(uri) { prepResult ->
                when (prepResult) {
                    is FontPreparationResult.Success -> {
                        importDialogError = null
                        pendingImportFileName = prepResult.originalFileName ?: unknownFilenameText
                    }
                    is FontPreparationResult.Failure -> {
                        val message = if (prepResult.reason == FontValidationFailureReason.INVALID_FONT_FILE) {
                            invalidFileText
                        } else {
                            prepResult.message.ifBlank { invalidFileText }
                        }
                        messageDialogState = importFailedTitleText to message
                    }
                }
            }
        }
    }

    fun launchFontPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "font/ttf",
                    "font/otf",
                    "application/x-font-ttf",
                    "application/x-font-otf",
                    "application/font-sfnt",
                    "application/octet-stream"
                )
            )
        }
        try {
            fontPickerLauncher.launch(Intent.createChooser(intent, selectFontText))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.settings_no_file_manager_found, Toast.LENGTH_SHORT).show()
        }
    }

    HighlightController(
        highlightTargetId = targetId,
        scrollState = scrollState,
        registry = highlightRegistry,
        onConsumed = { }
    ) {
        FontManagerScreen(
            title = title,
            state = uiState,
            scrollState = scrollState,
            highlightRegistry = highlightRegistry,
            onBack = onBack,
            onRefresh = { viewModel.loadFonts() },
            onImportFont = ::launchFontPicker,
            onRestoreDefaultFont = {
                viewModel.restoreSystemDefaultFont { result ->
                    when (result) {
                        FontActionResult.Success -> {
                            Toast.makeText(context, restoreSuccessText, Toast.LENGTH_SHORT).show()
                        }
                        is FontActionResult.Failure -> {
                            messageDialogState = actionFailedTitleText to result.message
                        }
                    }
                }
            },
            onApplyFont = { item ->
                viewModel.applyFont(item) { result ->
                    when (result) {
                        FontActionResult.Success -> {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.settings_font_manager_apply_success_message, item.info.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is FontActionResult.Failure -> {
                            messageDialogState = actionFailedTitleText to result.message
                        }
                    }
                }
            },
            onRequestDeleteFont = { item ->
                pendingDeleteItem = item
            }
        )
    }

    pendingImportFileName?.let { originalFileName ->
        FontImportInfoDialog(
            originalFileName = originalFileName,
            serverError = importDialogError,
            validateName = viewModel::validateImportName,
            onClearServerError = { importDialogError = null },
            onConfirm = { name, description ->
                viewModel.installFont(name, description) { result ->
                    when (result) {
                        FontInstallResult.Success -> {
                            pendingImportFileName = null
                            importDialogError = null
                            messageDialogState = importSuccessTitleText to importSuccessMessageText
                        }
                        is FontInstallResult.Failure -> {
                            val validationText = result.reason?.let { reason ->
                                mapValidationFailureMessage(resources, reason)
                            }
                            if (validationText != null &&
                                result.reason != FontValidationFailureReason.INVALID_FONT_FILE
                            ) {
                                importDialogError = validationText
                            } else {
                                pendingImportFileName = null
                                importDialogError = null
                                viewModel.clearPendingImport()
                                messageDialogState = importFailedTitleText to (validationText ?: result.message)
                            }
                        }
                    }
                }
            },
            onCancel = {
                pendingImportFileName = null
                importDialogError = null
                viewModel.clearPendingImport()
            }
        )
    }

    pendingDeleteItem?.let { item ->
        val deleteTitle = if (item.isActive) {
            stringResource(R.string.settings_font_manager_delete_active_title)
        } else {
            stringResource(R.string.settings_font_manager_delete_title)
        }
        val deleteMessage = if (item.isActive) {
            stringResource(
                R.string.settings_font_manager_delete_active_message,
                item.info.name,
                uiState.defaultSystemFontName
            )
        } else {
            stringResource(R.string.settings_font_manager_delete_message, item.info.name)
        }
        val deleteConfirmText = if (item.isActive) {
            stringResource(R.string.settings_font_manager_delete_active_button)
        } else {
            stringResource(R.string.settings_font_manager_delete_button)
        }

        ZToolDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text(text = deleteTitle) },
            text = { Text(text = deleteMessage) },
            confirmButton = {
                ZToolTextButton(
                    text = deleteConfirmText,
                    onClick = {
                        pendingDeleteItem = null
                        viewModel.deleteFont(item) { result ->
                            when (result) {
                                FontActionResult.Success -> {
                                    Toast.makeText(
                                        context,
                                        resources.getString(
                                            R.string.settings_font_manager_delete_success_message,
                                            item.info.name
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is FontActionResult.Failure -> {
                                    messageDialogState = actionFailedTitleText to result.message
                                }
                            }
                        }
                    }
                )
            },
            dismissButton = {
                ZToolTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingDeleteItem = null },
                    isPrimary = false
                )
            }
        )
    }

    messageDialogState?.let { (dialogTitle, message) ->
        ZToolDialog(
            onDismissRequest = { messageDialogState = null },
            title = { Text(text = dialogTitle) },
            text = { Text(text = message) },
            confirmButton = {
                ZToolTextButton(
                    text = stringResource(R.string.settings_got_it_button),
                    onClick = { messageDialogState = null }
                )
            }
        )
    }
}

private class FontManagerViewModelFactory(
    private val repository: FontManagerRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FontManagerViewModel::class.java)) {
            return FontManagerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
private fun FontManagerScreen(
    title: String,
    state: FontManagerUiState,
    scrollState: ScrollState,
    highlightRegistry: HighlightAnchorRegistry,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onImportFont: () -> Unit,
    onRestoreDefaultFont: () -> Unit,
    onApplyFont: (ManagedFontUiItem) -> Unit,
    onRequestDeleteFont: (ManagedFontUiItem) -> Unit
) {
    val colorScheme = LocalZToolColorScheme.current
    val restoreSummary = if (state.isUsingSystemDefault) {
        stringResource(
            R.string.settings_font_manager_restore_default_already_summary,
            state.defaultSystemFontName
        )
    } else {
        stringResource(
            R.string.settings_font_manager_restore_default_current_summary,
            state.defaultSystemFontName,
            state.currentFontName
        )
    }

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
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.isLoading && state.busyAction == null
                    ) {
                        Icon(
                            imageVector = ZToolIcons.Refresh,
                            contentDescription = null
                        )
                    }
                }
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
            ) {
                ZToolSettingsList(
                    highlightRegistry = highlightRegistry,
                    sections = listOf(
                        SettingSection(
                            items = listOf(
                                SettingItem.Action(
                                    key = "settings_font_manager_import",
                                    title = stringResource(R.string.settings_import_font_title),
                                    summary = stringResource(R.string.settings_import_font_summary),
                                    enabled = state.busyAction == null,
                                    onClick = onImportFont,
                                    trailingContent = {
                                        Icon(
                                            imageVector = ZToolIcons.Add,
                                            contentDescription = null,
                                            tint = colorScheme.onSurfaceVariant
                                        )
                                    }
                                ),
                                SettingItem.Action(
                                    key = "settings_font_manager_restore_default",
                                    title = stringResource(R.string.settings_font_manager_restore_default_title),
                                    summary = restoreSummary,
                                    enabled = !state.isUsingSystemDefault && state.busyAction == null,
                                    onClick = onRestoreDefaultFont,
                                    trailingContent = {
                                        Icon(
                                            imageVector = ZToolIcons.Restore,
                                            contentDescription = null,
                                            tint = colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            )
                        )
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.settings_font_manager_list_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                when {
                    state.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ZToolCircularProgressIndicator()
                        }
                    }
                    !state.rootAvailable -> {
                        FontManagerBannerCard(
                            message = stringResource(R.string.settings_font_manager_root_required_message),
                            isError = true
                        )
                    }
                    state.errorMessage != null && state.fonts.isEmpty() -> {
                        FontManagerBannerCard(
                            message = state.errorMessage,
                            isError = true
                        )
                    }
                    state.fonts.isEmpty() -> {
                        FontManagerBannerCard(
                            message = stringResource(R.string.settings_font_manager_empty_message),
                            isError = false
                        )
                    }
                    else -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            state.fonts.forEach { item ->
                                ManagedFontCard(
                                    item = item,
                                    enabled = state.busyAction == null,
                                    onApply = { onApplyFont(item) },
                                    onDelete = { onRequestDeleteFont(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FontManagerBannerCard(
    message: String,
    isError: Boolean
) {
    val colorScheme = LocalZToolColorScheme.current
    val containerColor = if (isError) colorScheme.errorContainer else colorScheme.surfaceContainer
    val contentColor = if (isError) colorScheme.onErrorContainer else colorScheme.onSurfaceVariant

    ZToolCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isError) {
                Icon(
                    imageVector = ZToolIcons.Warning,
                    contentDescription = null,
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(14.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun ManagedFontCard(
    item: ManagedFontUiItem,
    enabled: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = LocalZToolColorScheme.current
    val sizeText = remember(item.info.fileSizeBytes) {
        Formatter.formatShortFileSize(context, item.info.fileSizeBytes)
    }
    val previewFontFamily = remember(item.previewFilePath) {
        val path = item.previewFilePath ?: return@remember null
        runCatching {
            val nativeTypeface = Typeface.createFromFile(path) ?: return@runCatching null
            FontFamily(androidx.compose.ui.text.font.Typeface(nativeTypeface))
        }.getOrNull()
    }

    ZToolCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.info.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_font_manager_in_use_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.info.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (previewFontFamily != null) {
                    Text(
                        text = stringResource(R.string.settings_font_manager_preview_example),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = previewFontFamily),
                        color = colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_font_manager_preview_unavailable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZToolTextButton(
                    text = stringResource(R.string.settings_font_manager_delete_button),
                    onClick = onDelete,
                    enabled = enabled,
                    isPrimary = false
                )
                Spacer(modifier = Modifier.width(8.dp))
                ZToolButton(
                    onClick = onApply,
                    enabled = enabled && !item.isActive,
                    isPrimary = true
                ) {
                    Text(
                        text = stringResource(
                            if (item.isActive) {
                                R.string.settings_font_manager_applied_button
                            } else {
                                R.string.settings_font_manager_apply_button
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FontImportInfoDialog(
    originalFileName: String,
    serverError: String?,
    validateName: (String) -> FontNameValidationError?,
    onClearServerError: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    val suggestedName = remember(originalFileName) {
        originalFileName
            .substringBeforeLast('.', originalFileName)
            .trim()
            .takeIf { it.isNotEmpty() && validateName(it) == null }
            .orEmpty()
    }
    val defaultDescription = stringResource(R.string.settings_default_font_description, originalFileName)
    var fontName by remember(originalFileName) { mutableStateOf(suggestedName) }
    var fontDescription by remember(originalFileName) { mutableStateOf(defaultDescription) }
    var submitAttempted by remember { mutableStateOf(false) }

    val nameError = validateName(fontName)
    val localNameErrorMessage = when (nameError) {
        FontNameValidationError.EMPTY ->
            if (submitAttempted) stringResource(R.string.settings_font_manager_empty_name_error) else null
        FontNameValidationError.INVALID_CHARACTERS ->
            stringResource(R.string.settings_font_manager_invalid_name_error)
        FontNameValidationError.RESERVED_NAME ->
            stringResource(R.string.settings_font_manager_reserved_name_error)
        FontNameValidationError.DUPLICATE ->
            stringResource(R.string.settings_font_manager_duplicate_name_error)
        null -> null
    }
    val descriptionErrorMessage = if (submitAttempted && fontDescription.trim().isEmpty()) {
        stringResource(R.string.settings_font_manager_empty_description_error)
    } else {
        null
    }

    val displayedError = serverError ?: localNameErrorMessage ?: descriptionErrorMessage
    val canSubmit = nameError == null && fontDescription.trim().isNotEmpty()

    ZToolDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.settings_input_font_info_title),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.settings_do_not_use_duplicated_font_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZToolColorScheme.current.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ZToolTextInputRow(
                    label = stringResource(R.string.settings_font_name),
                    value = fontName,
                    onValueChange = {
                        fontName = it
                        onClearServerError()
                    },
                    singleLine = true,
                    horizontalPadding = 0.dp
                )
                ZToolTextInputRow(
                    label = stringResource(R.string.settings_font_description),
                    value = fontDescription,
                    onValueChange = {
                        fontDescription = it
                        onClearServerError()
                    },
                    singleLine = false,
                    horizontalPadding = 0.dp
                )
                if (displayedError != null) {
                    Text(
                        text = displayedError,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalZToolColorScheme.current.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            ZToolTextButton(
                onClick = {
                    submitAttempted = true
                    if (canSubmit) {
                        onConfirm(fontName.trim(), fontDescription.trim())
                    }
                },
                text = stringResource(R.string.settings_confirm_button)
            )
        },
        dismissButton = {
            ZToolTextButton(
                onClick = onCancel,
                text = stringResource(R.string.common_cancel),
                isPrimary = false
            )
        }
    )
}

private fun mapValidationFailureMessage(
    resources: android.content.res.Resources,
    reason: FontValidationFailureReason
): String = when (reason) {
    FontValidationFailureReason.INVALID_FONT_FILE ->
        resources.getString(R.string.settings_font_manager_invalid_file_error)
    FontValidationFailureReason.EMPTY_NAME ->
        resources.getString(R.string.settings_font_manager_empty_name_error)
    FontValidationFailureReason.INVALID_NAME_CHARACTERS ->
        resources.getString(R.string.settings_font_manager_invalid_name_error)
    FontValidationFailureReason.RESERVED_NAME ->
        resources.getString(R.string.settings_font_manager_reserved_name_error)
    FontValidationFailureReason.DUPLICATE_NAME ->
        resources.getString(R.string.settings_font_manager_duplicate_name_error)
    FontValidationFailureReason.EMPTY_DESCRIPTION ->
        resources.getString(R.string.settings_font_manager_empty_description_error)
}
