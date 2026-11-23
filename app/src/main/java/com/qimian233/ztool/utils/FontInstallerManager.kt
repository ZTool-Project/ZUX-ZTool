package com.qimian233.ztool.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.net.Uri
import android.os.Build
import android.os.Process
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.R
import org.xml.sax.InputSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.util.Random
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

data class ManagedFontInfo(
    val folderName: String,
    val folderPath: String,
    val fontFilePath: String,
    val name: String,
    val author: String,
    val description: String,
    val language: String,
    val fileSizeBytes: Long
)

data class LocalFontsScanResult(
    val managedFonts: List<ManagedFontInfo>,
    val allLocalFontNames: Set<String>
)

enum class FontNameValidationError {
    EMPTY,
    INVALID_CHARACTERS,
    RESERVED_NAME,
    DUPLICATE
}

enum class FontValidationFailureReason {
    INVALID_FONT_FILE,
    EMPTY_NAME,
    INVALID_NAME_CHARACTERS,
    RESERVED_NAME,
    DUPLICATE_NAME,
    EMPTY_DESCRIPTION
}

class FontValidationException(
    val reason: FontValidationFailureReason,
    message: String
) : Exception(message)

/** Manages local ZUI font validation, installation, scanning, preview cache, and removal. */
class FontInstallerManager {

    /** Copies a picked font [uri] to private temp storage and validates its SFNT structure. */
    @Throws(Exception::class)
    fun copyFontToTemp(context: Context, uri: Uri): File {
        val tempDir = File(context.filesDir, TEMP_FONT_DIR)
        if (!tempDir.exists()) tempDir.mkdirs()
        tempDir.listFiles()?.forEach { it.delete() }
        val tempFile = File(tempDir, "temp_font_${System.currentTimeMillis()}.ttf")

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("InputStream is null")
        try {
            input.use { inputStream ->
                FileOutputStream(tempFile).use { out ->
                    val buffer = ByteArray(4096)
                    var len: Int
                    while (inputStream.read(buffer).also { len = it } != -1) {
                        out.write(buffer, 0, len)
                    }
                }
            }
            validateFontFile(tempFile)
            return tempFile
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    /** Validates that [fontFile] is a loadable TrueType or OpenType font. */
    @Throws(FontValidationException::class)
    fun validateFontFile(fontFile: File) {
        if (!fontFile.exists() || !fontFile.isFile || fontFile.length() < MIN_FONT_FILE_BYTES) {
            throw FontValidationException(
                FontValidationFailureReason.INVALID_FONT_FILE,
                "Invalid or empty font file"
            )
        }
        val header = ByteArray(4)
        val bytesRead = try {
            FileInputStream(fontFile).use { it.read(header) }
        } catch (e: IOException) {
            throw FontValidationException(
                FontValidationFailureReason.INVALID_FONT_FILE,
                e.message ?: "Failed to read font header"
            )
        }
        if (bytesRead < 4 || !hasValidSfntHeader(header)) {
            throw FontValidationException(
                FontValidationFailureReason.INVALID_FONT_FILE,
                "Unsupported or corrupted font file header"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Font.Builder(fontFile).build()
            } catch (t: Throwable) {
                throw FontValidationException(
                    FontValidationFailureReason.INVALID_FONT_FILE,
                    t.message ?: "Failed to parse font tables"
                )
            }
        }
        val typeface = try {
            Typeface.createFromFile(fontFile)
        } catch (t: Throwable) {
            throw FontValidationException(
                FontValidationFailureReason.INVALID_FONT_FILE,
                t.message ?: "Typeface.createFromFile failed"
            )
        }
        if (typeface == null || typeface == Typeface.DEFAULT) {
            throw FontValidationException(
                FontValidationFailureReason.INVALID_FONT_FILE,
                "Unable to construct typeface from font file"
            )
        }
        val measured = Paint().apply {
            this.typeface = typeface
            textSize = 32f
        }.measureText("A")
        if (measured <= 0f) {
            throw FontValidationException(
                FontValidationFailureReason.INVALID_FONT_FILE,
                "Font failed glyph measurement"
            )
        }
    }

    /** Installs a validated [fontFile] with [fontName] and [fontDesc] into ZUI's local font store. */
    @Throws(Exception::class)
    fun installFont(context: Context, fontFile: File, fontName: String, fontDesc: String) {
        val cleanName = fontName.trim()
        val cleanDesc = fontDesc.trim()
        val existingNames = scanLocalFonts(context).allLocalFontNames

        when (validateFontName(fontName, existingNames)) {
            FontNameValidationError.EMPTY -> throw FontValidationException(
                FontValidationFailureReason.EMPTY_NAME,
                "Font name must not be empty"
            )
            FontNameValidationError.INVALID_CHARACTERS -> throw FontValidationException(
                FontValidationFailureReason.INVALID_NAME_CHARACTERS,
                "Font name contains invalid characters"
            )
            FontNameValidationError.RESERVED_NAME -> throw FontValidationException(
                FontValidationFailureReason.RESERVED_NAME,
                "Font name is reserved by the system"
            )
            FontNameValidationError.DUPLICATE -> throw FontValidationException(
                FontValidationFailureReason.DUPLICATE_NAME,
                "Duplicate font name: $cleanName"
            )
            null -> Unit
        }
        if (cleanDesc.isEmpty()) {
            throw FontValidationException(
                FontValidationFailureReason.EMPTY_DESCRIPTION,
                "Font description must not be empty"
            )
        }
        validateFontFile(fontFile)

        val executor = EnhancedShellExecutor.getInstance()
        val folderName = allocateUniqueFolderName(executor)
        val targetFolderPath = "$FONT_BASE_PATH/$folderName"

        val tempDir = File(context.filesDir, TEMP_FONT_DIR).apply { mkdirs() }
        val xmlTempFile = File(tempDir, "font.xml")
        FileUtils.writeStringToFile(xmlTempFile, generateFontXml(context, cleanName, cleanDesc))

        val (smallFile, previewFile) = generatePreviewFiles(
            context = context,
            tempDir = tempDir,
            fontPath = fontFile.absolutePath,
            fontName = cleanName
        )

        try {
            val copyScript = buildString {
                append("mkdir -p ").append(shellQuote(targetFolderPath))
                append(" && cp ").append(shellQuote(fontFile.absolutePath))
                    .append(' ').append(shellQuote("$targetFolderPath/font.ttf"))
                append(" && cp ").append(shellQuote(xmlTempFile.absolutePath))
                    .append(' ').append(shellQuote("$targetFolderPath/font.xml"))
                append(" && cp ").append(shellQuote(smallFile.absolutePath))
                    .append(' ').append(shellQuote("$targetFolderPath/small.png"))
                append(" && cp ").append(shellQuote(previewFile.absolutePath))
                    .append(' ').append(shellQuote("$targetFolderPath/preview.png"))
            }
            val copyResult = executor.executeRootScript(copyScript, timeoutSeconds = 15)
            if (!copyResult.isSuccess) {
                executor.executeRootScript("rm -rf ${shellQuote(targetFolderPath)}")
                throw IOException(
                    copyResult.error.ifBlank { copyResult.output }
                        .ifBlank { "Failed to copy font files (${copyResult.exitCode})" }
                )
            }
            setFolderPermissions(executor, targetFolderPath)
            FileUtils.deleteRecursive(tempDir)
        } finally {
            xmlTempFile.delete()
            smallFile.delete()
            previewFile.delete()
        }
    }

    /** Scans `.localFont` via root and returns ZTool-managed fonts plus all local names. */
    fun scanLocalFonts(context: Context): LocalFontsScanResult {
        val executor = EnhancedShellExecutor.getInstance()
        val script = buildString {
            append("BASE=").append(shellQuote(FONT_BASE_PATH)).append('\n')
            append("if [ ! -d \"\$BASE\" ]; then exit 0; fi\n")
            append("for d in \"\$BASE\"/*; do\n")
            append("  [ -d \"\$d\" ] || continue\n")
            append("  [ -f \"\$d/font.xml\" ] || continue\n")
            append("  f=''\n")
            append("  if [ -f \"\$d/font.ttf\" ]; then f=\"\$d/font.ttf\"\n")
            append("  elif [ -f \"\$d/font.TTF\" ]; then f=\"\$d/font.TTF\"\n")
            append("  else continue; fi\n")
            append("  sz=\$(stat -c '%s' \"\$f\" 2>/dev/null || echo 0)\n")
            append("  printf '%s\\n' ").append(shellQuote(ENTRY_MARKER)).append('\n')
            append("  printf 'DIR=%s\\n' \"\${d##*/}\"\n")
            append("  printf 'FONT=%s\\n' \"\$f\"\n")
            append("  printf 'SIZE=%s\\n' \"\$sz\"\n")
            append("  printf '%s\\n' ").append(shellQuote(XML_BEGIN_MARKER)).append('\n')
            append("  cat \"\$d/font.xml\"\n")
            append("  printf '\\n%s\\n' ").append(shellQuote(XML_END_MARKER)).append('\n')
            append("done\n")
        }
        val result = executor.executeRootScript(script, timeoutSeconds = 10)
        if (!result.isSuccess) {
            throw IOException(
                result.error.ifBlank { result.output }
                    .ifBlank { "Failed to scan local fonts (${result.exitCode})" }
            )
        }
        val recognizedAuthors = setOf(
            "Import By ZTool",
            "Imported by ZTool",
            context.getString(R.string.common_font_author)
        )
        return parseScanOutput(result.output, recognizedAuthors)
    }

    /** Copies a managed font into ZTool's cache directory if missing or size-mismatched. */
    fun ensurePreviewFontFile(context: Context, font: ManagedFontInfo): File? {
        if (!isSafeFolderName(font.folderName)) return null
        val previewDir = File(context.cacheDir, PREVIEW_CACHE_DIR)
        if (!previewDir.exists() && !previewDir.mkdirs()) return null
        val target = File(previewDir, "${font.folderName}.ttf")
        if (target.exists() && font.fileSizeBytes > 0L && target.length() == font.fileSizeBytes) {
            return target
        }

        val uid = Process.myUid()
        val script = buildString {
            append("cp ").append(shellQuote(font.fontFilePath))
                .append(' ').append(shellQuote(target.absolutePath))
            append(" && chown $uid:$uid ").append(shellQuote(target.absolutePath))
            append(" && chmod 600 ").append(shellQuote(target.absolutePath))
            append(" && CTX=\$(stat -c '%C' ").append(shellQuote(previewDir.absolutePath)).append(" 2>/dev/null)")
            append("; if [ -n \"\$CTX\" ] && [ \"\$CTX\" != \"?\" ]; then chcon \"\$CTX\" ")
                .append(shellQuote(target.absolutePath)).append("; fi")
        }
        val res = EnhancedShellExecutor.getInstance().executeRootScript(script, timeoutSeconds = 15)
        return if (res.isSuccess && target.exists() && target.canRead()) target else null
    }

    /** Removes cached preview font files whose source folder no longer exists. */
    fun prunePreviewCache(context: Context, validFolderNames: Set<String>) {
        val previewDir = File(context.cacheDir, PREVIEW_CACHE_DIR)
        if (!previewDir.exists() || !previewDir.isDirectory) return
        previewDir.listFiles()?.forEach { file ->
            if (file.isFile && file.nameWithoutExtension !in validFolderNames) {
                file.delete()
            }
        }
    }

    /** Stages [font] to ZUI's ThemesProvider and requests `zuithemes` to apply it. */
    @Throws(Exception::class)
    fun applyFont(font: ManagedFontInfo) {
        val cleanName = font.name.trim()
        require(cleanName.isNotEmpty() && !cleanName.contains('/') && !cleanName.contains('\\') && !cleanName.contains("..")) {
            "Invalid font name for apply: ${font.name}"
        }
        val stagedFileName = "$cleanName.ttf"
        val stagedMirrorPath = "$THEMES_PROVIDER_FILES_MIRROR/$stagedFileName"
        val script = buildString {
            append("if [ ! -f ").append(shellQuote(font.fontFilePath)).append(" ]; then exit 2; fi\n")
            append("DIR=").append(shellQuote(THEMES_PROVIDER_FILES_MIRROR)).append('\n')
            append("DST=").append(shellQuote(stagedMirrorPath)).append('\n')
            append("mkdir -p \"\$DIR\"\n")
            append("OWNER=\$(stat -c '%u:%g' \"\$DIR\")\n")
            append("CTX=\$(stat -c '%C' \"\$DIR\" 2>/dev/null)\n")
            append("cp ").append(shellQuote(font.fontFilePath)).append(" \"\$DST\" &&\n")
            append("chown \"\$OWNER\" \"\$DST\" &&\n")
            append("chmod 600 \"\$DST\" &&\n")
            append("if [ -n \"\$CTX\" ] && [ \"\$CTX\" != \"?\" ]; then chcon \"\$CTX\" \"\$DST\"; fi\n")
        }
        val stageResult = EnhancedShellExecutor.getInstance().executeRootScript(script, timeoutSeconds = 15)
        if (!stageResult.isSuccess) {
            throw IOException(
                stageResult.error.ifBlank { stageResult.output }
                    .ifBlank { "Failed to stage font (${stageResult.exitCode})" }
            )
        }
        ZuiThemeServiceBridge.changeSystemFont(
            fontFilePath = "$THEMES_PROVIDER_FILES_DATA/$stagedFileName",
            fontName = cleanName
        )
    }

    /** Restores ZUI's built-in default font and returns its font identifier. */
    @Throws(Exception::class)
    fun restoreSystemDefaultFont(): String {
        val defaultFontName = ZuiThemeServiceBridge.getDefaultSystemFontName()
        ZuiThemeServiceBridge.changeSystemFont("", defaultFontName)
        return defaultFontName
    }

    /** Deletes [font] from `.localFont`, switching to the system default font first when needed. */
    @Throws(Exception::class)
    fun deleteFont(context: Context, font: ManagedFontInfo, switchToDefaultFirst: Boolean) {
        require(isSafeFolderName(font.folderName)) {
            "Unsafe font folder name: ${font.folderName}"
        }
        val expectedFolderPath = "$FONT_BASE_PATH/${font.folderName}"
        require(font.folderPath == expectedFolderPath) {
            "Unexpected font folder path: ${font.folderPath}"
        }

        if (switchToDefaultFirst) {
            restoreSystemDefaultFont()
            val deadline = System.currentTimeMillis() + FONT_SWITCH_POLL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val active = ZuiThemeServiceBridge.getCurrentFontName()
                if (!active.equals(font.name, ignoreCase = true)) break
                Thread.sleep(FONT_SWITCH_POLL_INTERVAL_MS)
            }
        }

        val removeParts = mutableListOf("rm -rf ${shellQuote(expectedFolderPath)}")
        val cleanName = font.name.trim()
        if (cleanName.isNotEmpty() && !cleanName.contains('/') && !cleanName.contains('\\') && !cleanName.contains("..")) {
            removeParts += "rm -f ${shellQuote("$HOMESETTINGS_FONT_MIRROR/$cleanName.ttf")} ${shellQuote("$THEMES_PROVIDER_FILES_MIRROR/$cleanName.ttf")}"
        }
        val deleteResult = EnhancedShellExecutor.getInstance()
            .executeRootScript(removeParts.joinToString(" && "), timeoutSeconds = 10)
        if (!deleteResult.isSuccess) {
            throw IOException(
                deleteResult.error.ifBlank { deleteResult.output }
                    .ifBlank { "Failed to delete font (${deleteResult.exitCode})" }
            )
        }
        File(File(context.cacheDir, PREVIEW_CACHE_DIR), "${font.folderName}.ttf").delete()
    }

    private fun generatePreviewFiles(
        context: Context,
        tempDir: File,
        fontPath: String,
        fontName: String
    ): Pair<File, File> {
        val tf = Typeface.createFromFile(fontPath)
        val small = generateFontPreviewBitmap(tf, fontName, SMALL_PREVIEW_WIDTH, SMALL_PREVIEW_HEIGHT)
        val smallFile = saveBitmapToFile(small, File(tempDir, "small.png"))
        small.recycle()

        val preview = generateFontPreviewBitmap(
            tf,
            context.getString(R.string.common_font_preview_text),
            LARGE_PREVIEW_WIDTH,
            LARGE_PREVIEW_HEIGHT
        )
        val previewFile = saveBitmapToFile(preview, File(tempDir, "preview.png"))
        preview.recycle()
        return smallFile to previewFile
    }

    private fun setFolderPermissions(executor: EnhancedShellExecutor, folderPath: String) {
        val script = buildString {
            append("BASE=").append(shellQuote(FONT_BASE_PATH)).append('\n')
            append("TARGET=").append(shellQuote(folderPath)).append('\n')
            append("OWNER=\$(stat -c '%u:%g' \"\$BASE\") &&\n")
            append("CTX=\$(stat -c '%C' \"\$BASE\" 2>/dev/null)\n")
            append("chown -R \"\$OWNER\" \"\$TARGET\" &&\n")
            append("chmod 700 \"\$TARGET\" &&\n")
            append("chmod 600 \"\$TARGET\"/* &&\n")
            append("if [ -n \"\$CTX\" ] && [ \"\$CTX\" != \"?\" ]; then chcon -R \"\$CTX\" \"\$TARGET\"; fi\n")
        }
        val res = executor.executeRootScript(script, timeoutSeconds = 10)
        if (!res.isSuccess) {
            throw IOException(
                res.error.ifBlank { res.output }
                    .ifBlank { "Failed to set font permissions (${res.exitCode})" }
            )
        }
    }

    private fun generateFontPreviewBitmap(typeface: Typeface, text: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val paint = Paint().apply {
            this.typeface = typeface
            color = Color.BLACK
            isAntiAlias = true
        }

        val lines = text.split('\n')
        val paddingX = (width * 0.08f).coerceAtLeast(8f)
        val paddingY = (height * 0.08f).coerceAtLeast(6f)
        val maxWidth = (width - paddingX * 2f).coerceAtLeast(16f)
        val maxHeight = (height - paddingY * 2f).coerceAtLeast(16f)
        val maxSize = (height * 0.65f).coerceAtMost(96f).coerceAtLeast(12f)
        val lineSpacing = 1.2f

        val optimalSize = calculateOptimalTextSize(
            paint = paint,
            lines = lines,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            minSize = 10f,
            maxSize = maxSize,
            lineSpacingMultiplier = lineSpacing
        )
        paint.textSize = optimalSize

        val fm = paint.fontMetrics
        val singleLineHeight = fm.descent - fm.ascent
        val step = if (lines.size > 1) singleLineHeight * lineSpacing else singleLineHeight
        val totalHeight = if (lines.size > 1) {
            step * (lines.size - 1) + singleLineHeight
        } else {
            singleLineHeight
        }
        val startBaseline = (height - totalHeight) / 2f - fm.ascent

        if (lines.size == 1) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(lines[0], width / 2f, startBaseline, paint)
        } else {
            paint.textAlign = Paint.Align.LEFT
            val maxLineWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
            val startX = ((width - maxLineWidth) / 2f).coerceAtLeast(paddingX)
            for (i in lines.indices) {
                canvas.drawText(lines[i], startX, startBaseline + i * step, paint)
            }
        }
        return bitmap
    }

    private fun calculateOptimalTextSize(
        paint: Paint,
        lines: List<String>,
        maxWidth: Float,
        maxHeight: Float,
        minSize: Float,
        maxSize: Float,
        lineSpacingMultiplier: Float
    ): Float {
        if (lines.isEmpty()) return minSize
        var low = minSize
        var high = maxSize.coerceAtLeast(minSize)
        var best = low
        repeat(24) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            val fm = paint.fontMetrics
            val singleLineHeight = fm.descent - fm.ascent
            val step = if (lines.size > 1) singleLineHeight * lineSpacingMultiplier else singleLineHeight
            val totalHeight = if (lines.size > 1) {
                step * (lines.size - 1) + singleLineHeight
            } else {
                singleLineHeight
            }
            val widest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
            if (widest <= maxWidth && ceil(totalHeight.toDouble()) <= maxHeight) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    private fun allocateUniqueFolderName(executor: EnhancedShellExecutor): String {
        repeat(10) {
            val candidate = generateRandomFolderName()
            val exists = executor.executeRootScript("[ -e ${shellQuote("$FONT_BASE_PATH/$candidate")} ]")
            if (!exists.isSuccess) return candidate
        }
        return generateRandomFolderName() + (System.currentTimeMillis() % 1000).toString()
    }

    private fun generateRandomFolderName(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz"
        val random = Random()
        val sb = StringBuilder(6)
        for (i in 0 until 6) sb.append(chars[random.nextInt(chars.length)])
        return sb.toString()
    }

    private fun generateFontXml(context: Context, name: String, desc: String): String {
        val safeName = escapeXml(name)
        val safeLang = escapeXml(context.getString(R.string.common_font_language))
        val safeAuthor = escapeXml(context.getString(R.string.common_font_author))
        val safeDesc = escapeXml(desc)
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<ZFont>\n" +
            "<name>$safeName</name>\n" +
            "<language>$safeLang</language>\n" +
            "<author>$safeAuthor</author>\n" +
            "<abstract>$safeDesc</abstract>\n" +
            "</ZFont>"
    }

    private fun saveBitmapToFile(bmp: Bitmap, file: File): File {
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }

    companion object {
        private const val FONT_BASE_PATH = "/data_mirror/data_ce/null/0/com.zui.homesettings/files/.ZFont/.localFont"
        private const val HOMESETTINGS_FONT_MIRROR = "/data_mirror/data_ce/null/0/com.zui.homesettings/files/Font"
        private const val THEMES_PROVIDER_FILES_MIRROR = "/data_mirror/data_ce/null/0/com.zui.themes.provider/files"
        private const val THEMES_PROVIDER_FILES_DATA = "/data/data/com.zui.themes.provider/files"
        private const val TEMP_FONT_DIR = "temp_fonts"
        private const val PREVIEW_CACHE_DIR = "font_previews"
        private const val MIN_FONT_FILE_BYTES = 12L
        private const val SMALL_PREVIEW_WIDTH = 249
        private const val SMALL_PREVIEW_HEIGHT = 70
        private const val LARGE_PREVIEW_WIDTH = 948
        private const val LARGE_PREVIEW_HEIGHT = 945
        private const val FONT_SWITCH_POLL_TIMEOUT_MS = 3000L
        private const val FONT_SWITCH_POLL_INTERVAL_MS = 150L
        private const val ENTRY_MARKER = "===ZTOOL_FONT_ENTRY==="
        private const val XML_BEGIN_MARKER = "===XML_BEGIN==="
        private const val XML_END_MARKER = "===XML_END==="

        private val SAFE_FOLDER_REGEX = Regex("^[a-zA-Z0-9._-]+$")
        private val RESERVED_FONT_NAMES = setOf("system", "ZukChinese", "Rookery", "SystemFont")

        internal fun isSafeFolderName(folderName: String): Boolean {
            return SAFE_FOLDER_REGEX.matches(folderName) &&
                folderName != "." &&
                folderName != ".." &&
                !folderName.contains("..")
        }

        internal fun escapeXml(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 < input.length && Character.isLowSurrogate(input[i + 1])) {
                        sb.append(ch).append(input[i + 1])
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                if (Character.isLowSurrogate(ch)) {
                    i++
                    continue
                }
                val code = ch.code
                val validXmlChar = code == 0x9 || code == 0xA || code == 0xD ||
                    (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
                if (!validXmlChar) {
                    i++
                    continue
                }
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&apos;")
                    else -> sb.append(ch)
                }
                i++
            }
            return sb.toString()
        }

        internal fun hasValidSfntHeader(header: ByteArray): Boolean {
            if (header.size < 4) return false
            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            val b2 = header[2].toInt() and 0xFF
            val b3 = header[3].toInt() and 0xFF
            val isTtf = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
            val isAppleTrue = b0 == 0x74 && b1 == 0x72 && b2 == 0x75 && b3 == 0x65
            val isOtf = b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F
            return isTtf || isAppleTrue || isOtf
        }

        internal fun validateFontName(
            rawName: String,
            existingNames: Collection<String>
        ): FontNameValidationError? {
            val trimmed = rawName.trim()
            if (trimmed.isEmpty()) return FontNameValidationError.EMPTY
            if (rawName.contains('/') ||
                rawName.contains('\\') ||
                rawName.contains('\u0000') ||
                rawName.contains('\n') ||
                rawName.contains('\r') ||
                trimmed == "." ||
                trimmed == ".." ||
                trimmed.contains("..")
            ) {
                return FontNameValidationError.INVALID_CHARACTERS
            }
            if (RESERVED_FONT_NAMES.any { it.equals(trimmed, ignoreCase = true) }) {
                return FontNameValidationError.RESERVED_NAME
            }
            if (existingNames.any { it.trim().equals(trimmed, ignoreCase = true) }) {
                return FontNameValidationError.DUPLICATE
            }
            return null
        }

        internal fun parseScanOutput(
            output: String,
            recognizedAuthors: Set<String>
        ): LocalFontsScanResult {
            if (output.isBlank()) return LocalFontsScanResult(emptyList(), emptySet())

            val normalizedAuthors = recognizedAuthors.map { it.trim().lowercase() }.toSet()
            val managedFonts = mutableListOf<ManagedFontInfo>()
            val allNames = linkedSetOf<String>()

            val blocks = output.split(ENTRY_MARKER)
            for (rawBlock in blocks) {
                if (rawBlock.isBlank()) continue
                val xmlBeginIdx = rawBlock.indexOf(XML_BEGIN_MARKER)
                val xmlEndIdx = rawBlock.indexOf(XML_END_MARKER)
                if (xmlBeginIdx < 0 || xmlEndIdx <= xmlBeginIdx) continue

                val headerPart = rawBlock.substring(0, xmlBeginIdx)
                val xmlPart = rawBlock.substring(xmlBeginIdx + XML_BEGIN_MARKER.length, xmlEndIdx)

                var dir = ""
                var fontPath = ""
                var fileSize = 0L
                for (line in headerPart.lineSequence()) {
                    val trimmedLine = line.trim()
                    when {
                        trimmedLine.startsWith("DIR=") -> dir = trimmedLine.removePrefix("DIR=")
                        trimmedLine.startsWith("FONT=") -> fontPath = trimmedLine.removePrefix("FONT=")
                        trimmedLine.startsWith("SIZE=") -> fileSize = trimmedLine.removePrefix("SIZE=").toLongOrNull() ?: 0L
                    }
                }
                if (dir.isBlank() || fontPath.isBlank() || !isSafeFolderName(dir)) continue

                val parsed = parseFontXml(xmlPart) ?: continue
                if (parsed.name.isBlank()) continue

                allNames.add(parsed.name)
                if (parsed.author.trim().lowercase() in normalizedAuthors) {
                    managedFonts.add(
                        ManagedFontInfo(
                            folderName = dir,
                            folderPath = "$FONT_BASE_PATH/$dir",
                            fontFilePath = fontPath,
                            name = parsed.name,
                            author = parsed.author,
                            description = parsed.description,
                            language = parsed.language,
                            fileSizeBytes = fileSize
                        )
                    )
                }
            }

            managedFonts.sortWith(compareBy({ it.name.lowercase() }, { it.folderName }))
            return LocalFontsScanResult(
                managedFonts = managedFonts,
                allLocalFontNames = allNames
            )
        }

        private data class ParsedFontXml(
            val name: String,
            val author: String,
            val description: String,
            val language: String
        )

        private fun parseFontXml(xmlText: String): ParsedFontXml? {
            val trimmed = xmlText.trim()
            if (trimmed.isEmpty()) return null
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    runCatching { isExpandEntityReferences = false }
                }
                val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(trimmed)))
                val root = doc.documentElement ?: return@runCatching null
                fun tagText(tag: String): String {
                    val nodes = root.getElementsByTagName(tag)
                    return if (nodes.length > 0) nodes.item(0).textContent?.trim().orEmpty() else ""
                }
                ParsedFontXml(
                    name = tagText("name"),
                    author = tagText("author"),
                    description = tagText("abstract"),
                    language = tagText("language")
                )
            }.getOrNull()
        }
    }
}
