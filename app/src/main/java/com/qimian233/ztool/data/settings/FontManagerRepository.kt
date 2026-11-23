package com.qimian233.ztool.data.settings

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.utils.FontInstallerManager
import com.qimian233.ztool.utils.ManagedFontInfo
import com.qimian233.ztool.utils.ZuiThemeServiceBridge
import java.io.File

data class FontImportPreparation(
    val file: File,
    val originalFileName: String?
)

data class FontManagerEntry(
    val info: ManagedFontInfo,
    val previewFilePath: String?,
    val isActive: Boolean
)

data class FontManagerSnapshot(
    val rootAvailable: Boolean,
    val currentFontName: String,
    val defaultSystemFontName: String,
    val isUsingSystemDefault: Boolean,
    val fonts: List<FontManagerEntry>,
    val allLocalFontNames: Set<String>
)

/** Repository for scanning, importing, previewing, applying, and deleting ZTool fonts. */
class FontManagerRepository(
    val context: Context,
    private val shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance(),
    private val fontInstallerManager: FontInstallerManager = FontInstallerManager()
) {

    /** Loads root status, active font identity, and ZTool-imported fonts with preview cache. */
    fun loadSnapshot(): FontManagerSnapshot {
        val rootAvailable = shellExecutor.checkRootAccess().isSuccess
        val defaultFontName = ZuiThemeServiceBridge.getDefaultSystemFontName()
        val currentFontName = ZuiThemeServiceBridge.getCurrentFontName()
        val isUsingSystemDefault = ZuiThemeServiceBridge.isSystemDefaultFont(currentFontName)

        if (!rootAvailable) {
            return FontManagerSnapshot(
                rootAvailable = false,
                currentFontName = currentFontName,
                defaultSystemFontName = defaultFontName,
                isUsingSystemDefault = isUsingSystemDefault,
                fonts = emptyList(),
                allLocalFontNames = emptySet()
            )
        }

        val scanResult = fontInstallerManager.scanLocalFonts(context)
        fontInstallerManager.prunePreviewCache(
            context,
            scanResult.managedFonts.map { it.folderName }.toSet()
        )
        val entries = scanResult.managedFonts.map { font ->
            val previewFile = fontInstallerManager.ensurePreviewFontFile(context, font)
            val isActive = font.name.trim().equals(currentFontName.trim(), ignoreCase = true)
            FontManagerEntry(
                info = font,
                previewFilePath = previewFile?.absolutePath,
                isActive = isActive
            )
        }
        return FontManagerSnapshot(
            rootAvailable = true,
            currentFontName = currentFontName,
            defaultSystemFontName = defaultFontName,
            isUsingSystemDefault = isUsingSystemDefault,
            fonts = entries,
            allLocalFontNames = scanResult.allLocalFontNames
        )
    }

    /** Copies and validates a picked font [uri], returning its temp file and display name. */
    fun prepareFontImport(uri: Uri): FontImportPreparation {
        val fontFile = fontInstallerManager.copyFontToTemp(context, uri)
        return FontImportPreparation(
            file = fontFile,
            originalFileName = getFileName(uri)
        )
    }

    /** Installs the prepared [fontFile] with [fontName] and [fontDescription]. */
    fun installFont(fontFile: File?, fontName: String, fontDescription: String) {
        fontInstallerManager.installFont(
            context = context,
            fontFile = requireNotNull(fontFile) { "fontFile must be prepared before installing" },
            fontName = fontName,
            fontDesc = fontDescription
        )
    }

    fun clearPreparedFont(fontFile: File?) {
        runCatching { fontFile?.delete() }
    }

    /** Applies [font] via ZUI's theme service and waits briefly for the active font to switch. */
    fun applyFont(font: ManagedFontInfo): String {
        fontInstallerManager.applyFont(font)
        return awaitFontChange(font.name)
    }

    /** Switches back to ZUI's regional default system font. */
    fun restoreSystemDefaultFont(): String {
        val defaultFont = fontInstallerManager.restoreSystemDefaultFont()
        return awaitFontChange(defaultFont)
    }

    /** Deletes [font], restoring the system default font first when [isActive] is true. */
    fun deleteFont(font: ManagedFontInfo, isActive: Boolean) {
        fontInstallerManager.deleteFont(context, font, switchToDefaultFirst = isActive)
    }

    /** Returns the current active font name and whether it is a built-in system font. */
    fun readCurrentFontState(): Pair<String, Boolean> {
        val current = ZuiThemeServiceBridge.getCurrentFontName()
        return current to ZuiThemeServiceBridge.isSystemDefaultFont(current)
    }

    /** Polls the active font name until it matches [expectedFontName] or [timeoutMs] elapses. */
    fun awaitFontChange(expectedFontName: String, timeoutMs: Long = 3500L): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var current = ZuiThemeServiceBridge.getCurrentFontName()
        while (!current.equals(expectedFontName.trim(), ignoreCase = true) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(150L)
            current = ZuiThemeServiceBridge.getCurrentFontName()
        }
        return current
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
                cursor.use {
                    if (it != null && it.moveToFirst()) {
                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) result = it.getString(index)
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (result == null) {
            result = uri.path
            val value = result
            if (value != null) {
                val cut = value.lastIndexOf('/')
                if (cut != -1) result = value.substring(cut + 1)
            }
        }
        return result
    }
}
