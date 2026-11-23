package com.qimian233.ztool.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.InternalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.emptyCacheFontFamilyResolver
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/** Shared font family applied across both Material3 Expressive and Miuix themes. */
// Must stay SansSerif: Default resolves to a stale Typeface.DEFAULT on ZUI theme fonts.
// See docs/research/zui_theme_font_compose_bridge.md
val ZToolDefaultFontFamily: FontFamily = FontFamily.SansSerif

/** Returns a font resolver that is replaced whenever the ZUI theme font changes. */
// Compose's shared resolver caches typefaces process-wide, so a theme font switch would
// otherwise only show after a restart. See docs/research/zui_theme_font_compose_bridge.md
@OptIn(InternalTextApi::class)
@Composable
internal fun rememberThemeFontFamilyResolver(): FontFamily.Resolver {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val themedSansSerif = remember(configuration) {
        Typeface.create(FontFamily.SansSerif.name, Typeface.NORMAL)
    }
    return remember(context, themedSansSerif) { emptyCacheFontFamilyResolver(context) }
}

/** Family-only style for library parameters that otherwise build styles without a family. */
// The Miuix InputField label is a raw TextStyle merged with its textStyle parameter.
val ZToolFontFamilyTextStyle: TextStyle = TextStyle(fontFamily = ZToolDefaultFontFamily)

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
