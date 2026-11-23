package com.qimian233.ztool.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontInstallerValidationTest {

    @Test
    fun escapeXml_escapesSpecialCharactersAndStripsIllegalControlChars() {
        val raw = "A & B <tag> \"quote\" 'single'\u0000\u0007\n中文字体"
        val escaped = FontInstallerManager.escapeXml(raw)
        assertEquals(
            "A &amp; B &lt;tag&gt; &quot;quote&quot; &apos;single&apos;\n中文字体",
            escaped
        )
    }

    @Test
    fun hasValidSfntHeader_acceptsTrueTypeAppleTrueAndOtfAndRejectsOthers() {
        assertTrue(FontInstallerManager.hasValidSfntHeader(byteArrayOf(0x00, 0x01, 0x00, 0x00)))
        assertTrue(FontInstallerManager.hasValidSfntHeader("true".toByteArray(Charsets.US_ASCII)))
        assertTrue(FontInstallerManager.hasValidSfntHeader("OTTO".toByteArray(Charsets.US_ASCII)))

        assertFalse(FontInstallerManager.hasValidSfntHeader(byteArrayOf(0x00, 0x01, 0x00)))
        assertFalse(FontInstallerManager.hasValidSfntHeader("ttcf".toByteArray(Charsets.US_ASCII)))
        assertFalse(
            FontInstallerManager.hasValidSfntHeader(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            )
        )
    }

    @Test
    fun validateFontName_rejectsEmptyInvalidReservedAndDuplicateNames() {
        val existing = listOf("OPlus Serif", "MiSans")

        assertEquals(
            FontNameValidationError.EMPTY,
            FontInstallerManager.validateFontName("   ", existing)
        )
        assertEquals(
            FontNameValidationError.INVALID_CHARACTERS,
            FontInstallerManager.validateFontName("a/b", existing)
        )
        assertEquals(
            FontNameValidationError.INVALID_CHARACTERS,
            FontInstallerManager.validateFontName("a\\b", existing)
        )
        assertEquals(
            FontNameValidationError.INVALID_CHARACTERS,
            FontInstallerManager.validateFontName("..", existing)
        )
        assertEquals(
            FontNameValidationError.INVALID_CHARACTERS,
            FontInstallerManager.validateFontName("foo..bar", existing)
        )
        assertEquals(
            FontNameValidationError.INVALID_CHARACTERS,
            FontInstallerManager.validateFontName("line\nbreak", existing)
        )
        assertEquals(
            FontNameValidationError.RESERVED_NAME,
            FontInstallerManager.validateFontName("zukchinese", existing)
        )
        assertEquals(
            FontNameValidationError.RESERVED_NAME,
            FontInstallerManager.validateFontName("SYSTEM", existing)
        )
        assertEquals(
            FontNameValidationError.RESERVED_NAME,
            FontInstallerManager.validateFontName("Rookery", existing)
        )
        assertEquals(
            FontNameValidationError.DUPLICATE,
            FontInstallerManager.validateFontName("oplus serif", existing)
        )
        assertNull(FontInstallerManager.validateFontName("HarmonyOS Sans", existing))
    }

    @Test
    fun parseScanOutput_filtersRecognizedAuthorsAndDecodesXmlEntities() {
        val scanOutput = """
            ===ZTOOL_FONT_ENTRY===
            DIR=lysccl
            FONT=/data_mirror/data_ce/null/0/com.zui.homesettings/files/.ZFont/.localFont/lysccl/font.ttf
            SIZE=40571448
            ===XML_BEGIN===
            <?xml version="1.0" encoding="utf-8"?>
            <ZFont>
            <name>OPlus &amp; Serif</name>
            <language>简体</language>
            <author>Import By ZTool</author>
            <abstract>Desc &lt;1&gt;</abstract>
            </ZFont>
            ===XML_END===
            ===ZTOOL_FONT_ENTRY===
            DIR=abcdef
            FONT=/data_mirror/data_ce/null/0/com.zui.homesettings/files/.ZFont/.localFont/abcdef/font.ttf
            SIZE=1024000
            ===XML_BEGIN===
            <?xml version="1.0" encoding="utf-8"?>
            <ZFont>
            <name>ImportedVariant</name>
            <language>简体</language>
            <author>imported by ztool</author>
            <abstract>Test font</abstract>
            </ZFont>
            ===XML_END===
            ===ZTOOL_FONT_ENTRY===
            DIR=thirdp
            FONT=/data_mirror/data_ce/null/0/com.zui.homesettings/files/.ZFont/.localFont/thirdp/font.ttf
            SIZE=2048000
            ===XML_BEGIN===
            <?xml version="1.0" encoding="utf-8"?>
            <ZFont>
            <name>ThirdPartyFont</name>
            <language>简体</language>
            <author>Hanyi</author>
            <abstract>Official store font</abstract>
            </ZFont>
            ===XML_END===
        """.trimIndent()

        val result = FontInstallerManager.parseScanOutput(
            output = scanOutput,
            recognizedAuthors = setOf("Import By ZTool", "Imported by ZTool")
        )

        assertEquals(2, result.managedFonts.size)
        assertEquals(
            setOf("OPlus & Serif", "ImportedVariant", "ThirdPartyFont"),
            result.allLocalFontNames
        )

        val imported = result.managedFonts.first { it.folderName == "lysccl" }
        assertEquals("OPlus & Serif", imported.name)
        assertEquals("Desc <1>", imported.description)
        assertEquals(40571448L, imported.fileSizeBytes)
        assertEquals("简体", imported.language)
    }
}
