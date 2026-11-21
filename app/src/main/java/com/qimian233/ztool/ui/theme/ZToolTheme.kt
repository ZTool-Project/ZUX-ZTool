package com.qimian233.ztool.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.qimian233.ztool.data.theme.ThemePreferencesRepository
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

data class ZToolThemeSpec(
    val style: FrontendStyle,
    val useExpressiveMotion: Boolean = true,
    val tabletOnly: Boolean = true,
    val dynamicColorEnabled: Boolean = true,
    val manualColorEnabled: Boolean = false
)

val LocalZToolThemeSpec = staticCompositionLocalOf {
    ZToolThemeSpec(style = FrontendStyle.Material3Expressive)
}

val LocalIsPlatformDialog = staticCompositionLocalOf { false }

/** Whether the Miuix floating bottom bar (Liquid Glass) is enabled. Only meaningful in Miuix mode. */
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }

/** Whether blur effects are enabled on the floating bottom bar. Requires Android 13+. Only meaningful when [LocalEnableFloatingBottomBar] is true. */
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

@Composable
fun ZToolTheme(
    style: FrontendStyle = FrontendStyle.Material3Expressive,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    settings: ZToolThemeSettings? = null,
    isPlatformDialog: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemDarkTheme = (
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ) == Configuration.UI_MODE_NIGHT_YES
    val repository = remember(context) { ThemePreferencesRepository(context.applicationContext) }
    var observedSettings by remember(settings, style, darkTheme, dynamicColor, configuration.uiMode) {
        mutableStateOf(
            settings ?: repository.loadSettings()
        )
    }

    DisposableEffect(repository, settings) {
        if (settings != null) {
            observedSettings = settings
            onDispose { }
        } else {
            observedSettings = repository.loadSettings()
            val unregister = repository.observeSettings { updatedSettings ->
                observedSettings = updatedSettings
            }
            onDispose { unregister() }
        }
    }

    val effectiveSettings = (settings ?: observedSettings).let { loadedSettings ->
        if (settings == null && style != FrontendStyle.Material3Expressive) {
            loadedSettings.copy(frontendStyle = style)
        } else {
            loadedSettings
        }
    }
    val effectiveDarkTheme = when (effectiveSettings.themeMode) {
        ThemeMode.FollowSystem -> systemDarkTheme
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val systemSeed = if (effectiveSettings.dynamicColorEnabled && !effectiveSettings.manualColorEnabled) {
        remember(context) { readSystemPaletteSeed(context) }
    } else {
        null
    }

    val isMiuixStyle = effectiveSettings.frontendStyle == FrontendStyle.Miuix

    val miuixBreakerActive = isMiuixStyle && !effectiveSettings.dynamicColorEnabled
    val colorScheme = remember(effectiveSettings, effectiveDarkTheme, systemSeed) {
        if (miuixBreakerActive) {
            val miuixDefault = if (effectiveDarkTheme) {
                miuixDarkColorScheme()
            } else {
                miuixLightColorScheme()
            }
            val m3Scheme = miuixDefault.toMaterialColorScheme(darkTheme = effectiveDarkTheme)
            if (effectiveDarkTheme && effectiveSettings.amoledBlackEnabled) {
                m3Scheme.withAmoledBlackSurfaces()
            } else {
                m3Scheme
            }
        } else {
            buildZToolColorScheme(
                settings = effectiveSettings,
                darkTheme = effectiveDarkTheme,
                systemSeed = systemSeed
            )
        }
    }

    val themeSpec = ZToolThemeSpec(
        style = effectiveSettings.frontendStyle,
        useExpressiveMotion = !isMiuixStyle,
        dynamicColorEnabled = effectiveSettings.dynamicColorEnabled,
        manualColorEnabled = effectiveSettings.manualColorEnabled
    )
    val movableContent = remember(content) { movableContentOf(content) }

    val miuixColors = remember(colorScheme, effectiveDarkTheme) {
        if (effectiveSettings.dynamicColorEnabled) {
            colorScheme.toMiuixColors(darkTheme = effectiveDarkTheme)
        } else if (effectiveDarkTheme) {
            miuixDarkColorScheme()
        } else {
            miuixLightColorScheme()
        }
    }
    // The Miuix Scaffold stretches platform dialog windows; apply only its theme there.
    val useMiuixScaffold = isMiuixStyle && !isPlatformDialog
    val miuixTextStyles = remember { buildZToolMiuixTextStyles() }
    val materialTypography = remember(effectiveSettings.frontendStyle, miuixTextStyles) {
        buildZToolTypography(
            style = effectiveSettings.frontendStyle,
            miuixTextStyles = miuixTextStyles
        )
    }

    val themedContent: @Composable () -> Unit = {
        MaterialExpressiveTheme(
            motionScheme = if (themeSpec.useExpressiveMotion) MotionScheme.expressive() else MotionScheme.standard(),
            colorScheme = colorScheme,
            typography = materialTypography,
        ) {
            CompositionLocalProvider(
                LocalZToolColorScheme provides colorScheme,
                LocalContentColor provides colorScheme.onBackground
            ) {
                MiuixTheme(
                    colors = miuixColors,
                    textStyles = miuixTextStyles,
                    content = {
                        if (useMiuixScaffold) {
                            top.yukonga.miuix.kmp.basic.Scaffold { _ ->
                                movableContent()
                            }
                        } else {
                            movableContent()
                        }
                    }
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalZToolThemeSpec provides themeSpec,
        LocalIsPlatformDialog provides isPlatformDialog,
        LocalEnableFloatingBottomBar provides effectiveSettings.enableFloatingBottomBar,
        LocalEnableFloatingBottomBarBlur provides effectiveSettings.enableFloatingBottomBarBlur,
        LocalUseAlternativeIcon provides effectiveSettings.useAlternativeIcon,
        content = themedContent
    )
}

private fun Colors.toMaterialColorScheme(darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = onPrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiaryContainer,
            onTertiary = onTertiaryContainer,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceContainer,
            surfaceDim = surface,
            surfaceBright = surfaceContainer,
            surfaceContainerLowest = background,
            surfaceContainerLow = surfaceContainer,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceTint = primary,
            inverseSurface = onSurface,
            inverseOnSurface = surface,
            outline = outline,
            outlineVariant = dividerLine,
            scrim = Color.Black,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = onPrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiaryContainer,
            onTertiary = onTertiaryContainer,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceContainer,
            surfaceDim = surface,
            surfaceBright = surfaceContainer,
            surfaceContainerLowest = background,
            surfaceContainerLow = surfaceContainer,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceTint = primary,
            inverseSurface = onSurface,
            inverseOnSurface = surface,
            outline = outline,
            outlineVariant = dividerLine,
            scrim = Color.Black,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer
        )
    }
}

private fun ColorScheme.toMiuixColors(darkTheme: Boolean): Colors {
    return if (darkTheme) {
        miuixDarkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryVariant = primaryContainer,
            onPrimaryVariant = onPrimaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondaryContainer,
            onSecondary = onSecondaryContainer,
            secondaryVariant = surfaceVariant,
            onSecondaryVariant = onSurfaceVariant,
            secondaryContainer = surfaceContainer,
            onSecondaryContainer = onSurfaceVariant,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            surfaceContainer = surfaceContainer,
            onSurfaceContainer = onSurface,
            surfaceContainerHigh = surfaceContainerHigh,
            onSurfaceContainerHigh = onSurfaceVariant,
            surfaceContainerHighest = surfaceContainerHighest,
            onSurfaceContainerHighest = onSurface,
            outline = outline,
            dividerLine = outlineVariant
        )
    } else {
        miuixLightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryVariant = primaryContainer,
            onPrimaryVariant = onPrimaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondaryContainer,
            onSecondary = onSecondaryContainer,
            secondaryVariant = surfaceVariant,
            onSecondaryVariant = onSurfaceVariant,
            secondaryContainer = surfaceContainer,
            onSecondaryContainer = onSurfaceVariant,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            surfaceContainer = surfaceContainer,
            onSurfaceContainer = onSurface,
            surfaceContainerHigh = surfaceContainerHigh,
            onSurfaceContainerHigh = onSurfaceVariant,
            surfaceContainerHighest = surfaceContainerHighest,
            onSurfaceContainerHighest = onSurface,
            outline = outline,
            dividerLine = outlineVariant
        )
    }
}
