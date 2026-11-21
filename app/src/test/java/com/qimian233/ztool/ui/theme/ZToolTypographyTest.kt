package com.qimian233.ztool.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class ZToolTypographyTest {

    @Test
    fun miuixTextStyles_applyFontFamilyAcrossAllSlots() {
        val textStyles = buildZToolMiuixTextStyles(FontFamily.Serif)
        val slots = listOf(
            textStyles.main,
            textStyles.paragraph,
            textStyles.body1,
            textStyles.body2,
            textStyles.button,
            textStyles.footnote1,
            textStyles.footnote2,
            textStyles.headline1,
            textStyles.headline2,
            textStyles.subtitle,
            textStyles.title1,
            textStyles.title2,
            textStyles.title3,
            textStyles.title4
        )
        assertEquals(14, slots.size)
        slots.forEach { style ->
            assertEquals(FontFamily.Serif, style.fontFamily)
            assertEquals(Color.Unspecified, style.color)
        }
    }

    @Test
    fun materialTypography_appliesFontFamilyAndRetainsMaterialScale() {
        val miuixStyles = buildZToolMiuixTextStyles(FontFamily.Serif)
        val typography = buildZToolTypography(
            style = FrontendStyle.Material3Expressive,
            miuixTextStyles = miuixStyles,
            fontFamily = FontFamily.Serif
        )

        typography.allSlots().forEach { style ->
            assertEquals(FontFamily.Serif, style.fontFamily)
        }
        assertEquals(16.sp, typography.bodyLarge.fontSize)
        assertEquals(16.sp, typography.titleMedium.fontSize)
    }

    @Test
    fun miuixMappedTypography_appliesFontFamilyMiuixScaleAndClearsLetterSpacing() {
        val miuixStyles = buildZToolMiuixTextStyles(FontFamily.Serif)
        val typography = buildZToolTypography(
            style = FrontendStyle.Miuix,
            miuixTextStyles = miuixStyles,
            fontFamily = FontFamily.Serif
        )

        typography.allSlots().forEach { style ->
            assertEquals(FontFamily.Serif, style.fontFamily)
            assertEquals(TextUnit.Unspecified, style.letterSpacing)
            assertEquals(Color.Unspecified, style.color)
        }
        assertEquals(17.sp, typography.titleMedium.fontSize)
        assertEquals(FontWeight.Medium, typography.titleMedium.fontWeight)
        assertEquals(14.sp, typography.titleSmall.fontSize)
        assertEquals(FontWeight.Bold, typography.titleSmall.fontWeight)
        assertEquals(16.sp, typography.bodyLarge.fontSize)
        assertEquals(14.sp, typography.bodyMedium.fontSize)
        assertEquals(13.sp, typography.bodySmall.fontSize)
        assertEquals(11.sp, typography.labelSmall.fontSize)
    }

    private fun Typography.allSlots(): List<TextStyle> = listOf(
        displayLarge,
        displayMedium,
        displaySmall,
        headlineLarge,
        headlineMedium,
        headlineSmall,
        titleLarge,
        titleMedium,
        titleSmall,
        bodyLarge,
        bodyMedium,
        bodySmall,
        labelLarge,
        labelMedium,
        labelSmall,
        displayLargeEmphasized,
        displayMediumEmphasized,
        displaySmallEmphasized,
        headlineLargeEmphasized,
        headlineMediumEmphasized,
        headlineSmallEmphasized,
        titleLargeEmphasized,
        titleMediumEmphasized,
        titleSmallEmphasized,
        bodyLargeEmphasized,
        bodyMediumEmphasized,
        bodySmallEmphasized,
        labelLargeEmphasized,
        labelMediumEmphasized,
        labelSmallEmphasized
    )
}
