package com.qimian233.ztool.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/** Shared font family applied across both Material3 Expressive and Miuix themes. */
val ZToolDefaultFontFamily: FontFamily = FontFamily.Default

/** Builds Miuix [TextStyles] with [fontFamily] applied across every slot. */
internal fun buildZToolMiuixTextStyles(
    fontFamily: FontFamily = ZToolDefaultFontFamily
): TextStyles {
    val base = defaultTextStyles()
    return base.copy(
        main = base.main.copy(fontFamily = fontFamily),
        paragraph = base.paragraph.copy(fontFamily = fontFamily),
        body1 = base.body1.copy(fontFamily = fontFamily),
        body2 = base.body2.copy(fontFamily = fontFamily),
        button = base.button.copy(fontFamily = fontFamily),
        footnote1 = base.footnote1.copy(fontFamily = fontFamily),
        footnote2 = base.footnote2.copy(fontFamily = fontFamily),
        headline1 = base.headline1.copy(fontFamily = fontFamily),
        headline2 = base.headline2.copy(fontFamily = fontFamily),
        subtitle = base.subtitle.copy(fontFamily = fontFamily),
        title1 = base.title1.copy(fontFamily = fontFamily),
        title2 = base.title2.copy(fontFamily = fontFamily),
        title3 = base.title3.copy(fontFamily = fontFamily),
        title4 = base.title4.copy(fontFamily = fontFamily)
    )
}

/** Builds Material3 [Typography] matching [style], unified under [fontFamily]. */
internal fun buildZToolTypography(
    style: FrontendStyle,
    miuixTextStyles: TextStyles,
    fontFamily: FontFamily = ZToolDefaultFontFamily
): Typography = when (style) {
    FrontendStyle.Material3Expressive -> Typography(fontFamily = fontFamily)
    FrontendStyle.Miuix -> miuixTextStyles.toMaterialTypography()
}

private fun TextStyles.toMaterialTypography(): Typography = Typography(
    displayLarge = title1,
    displayMedium = title1,
    displaySmall = title1,
    headlineLarge = title1,
    headlineMedium = title2,
    headlineSmall = title3.copy(fontWeight = FontWeight.Medium),
    titleLarge = title4.copy(fontWeight = FontWeight.Medium),
    titleMedium = headline1.copy(fontWeight = FontWeight.Medium),
    titleSmall = subtitle,
    bodyLarge = body1,
    bodyMedium = body2,
    bodySmall = footnote1,
    labelLarge = body2.copy(fontWeight = FontWeight.Medium),
    labelMedium = footnote1.copy(fontWeight = FontWeight.Medium),
    labelSmall = footnote2
)
