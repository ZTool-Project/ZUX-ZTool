package com.qimian233.ztool.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.ui.theme.FrontendStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces that all UI code consumes icons through [ZToolIcons] rather than importing
 * Material or Miuix icon packages directly, and verifies lazy style resolution.
 */
class ZToolIconsContractTest {

    private val appDir: File = locateAppDir()

    @Test
    fun noDirectMaterialOrMiuixIconReferencesOutsideIconLayer() {
        val sourceRoot = File(appDir, "src/main/java/com/qimian233/ztool")
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                val normalized = file.invariantSeparatorsPath
                normalized.contains("/ui/icons/")
            }
            .mapNotNull { file ->
                val text = file.readText()
                val matched = FORBIDDEN_PREFIXES.filter { prefix -> text.contains(prefix) }
                if (matched.isEmpty()) null else "${file.relativeTo(appDir).invariantSeparatorsPath}: $matched"
            }
            .toList()

        assertTrue(
            "UI/screen files must use ZToolIcons instead of importing/referencing Material or Miuix icons directly:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun resolveZToolIcon_evaluatesOnlyActiveStyleBranch() {
        val materialIcon = testVector("material")
        val miuixIcon = testVector("miuix")

        var materialCalls = 0
        var miuixCalls = 0
        val resolvedMaterial = resolveZToolIcon(
            style = FrontendStyle.Material3Expressive,
            material = {
                materialCalls++
                materialIcon
            },
            miuix = {
                miuixCalls++
                miuixIcon
            }
        )
        assertEquals(materialIcon, resolvedMaterial)
        assertEquals(1, materialCalls)
        assertEquals(0, miuixCalls)

        materialCalls = 0
        miuixCalls = 0
        val resolvedMiuix = resolveZToolIcon(
            style = FrontendStyle.Miuix,
            material = {
                materialCalls++
                materialIcon
            },
            miuix = {
                miuixCalls++
                miuixIcon
            }
        )
        assertEquals(miuixIcon, resolvedMiuix)
        assertEquals(0, materialCalls)
        assertFalse(miuixCalls == 0)
        assertEquals(1, miuixCalls)
    }

    private fun testVector(name: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).build()

    private companion object {
        val FORBIDDEN_PREFIXES = listOf(
            "androidx.compose.material.icons.",
            "top.yukonga.miuix.kmp.icon."
        )

        fun locateAppDir(): File {
            var dir = File(requireNotNull(System.getProperty("user.dir")))
            repeat(4) {
                if (File(dir, "src/main/java/com/qimian233/ztool/ui/icons/ZToolIcons.kt").isFile) {
                    return dir
                }
                dir = dir.parentFile ?: return@repeat
            }
            error("Could not locate the app module directory from ${System.getProperty("user.dir")}")
        }
    }
}
