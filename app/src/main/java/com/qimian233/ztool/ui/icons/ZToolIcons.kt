package com.qimian233.ztool.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.HideSource
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RestorePage
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update

/** Lazily resolves an icon for [style] without evaluating the inactive branch. */
internal inline fun resolveZToolIcon(
    style: FrontendStyle,
    material: () -> ImageVector,
    miuix: () -> ImageVector
): ImageVector = when (style) {
    FrontendStyle.Material3Expressive -> material()
    FrontendStyle.Miuix -> miuix()
}

/**
 * Single entry point for UI icons across Material3 Expressive and Miuix styles.
 * Dual-style properties dispatch on [LocalZToolThemeSpec]; domain icons share a single glyph.
 */
object ZToolIcons {

    @Composable
    @ReadOnlyComposable
    private inline fun themed(
        material: () -> ImageVector,
        miuix: () -> ImageVector
    ): ImageVector = resolveZToolIcon(LocalZToolThemeSpec.current.style, material, miuix)

    val ArrowBack: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.AutoMirrored.Rounded.ArrowBack }, { MiuixIcons.Back })

    val ArrowForward: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.AutoMirrored.Rounded.ArrowForward }, { MiuixIcons.Basic.ArrowRight })

    val DropdownIndicator: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Filled.ArrowDropDown }, { MiuixIcons.Basic.ArrowUpDown })

    val Refresh: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Refresh }, { MiuixIcons.Refresh })

    val Search: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Search }, { MiuixIcons.Basic.Search })

    val Close: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Close }, { MiuixIcons.Basic.Close })

    val Check: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Check }, { MiuixIcons.Basic.Check })

    val SwitchCheck: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Filled.Check }, { MiuixIcons.Basic.Check })

    val SwitchClose: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Filled.Close }, { MiuixIcons.Basic.Close })

    val Info: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Info }, { MiuixIcons.Info })

    val Add: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Add }, { MiuixIcons.Add })

    val Delete: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Delete }, { MiuixIcons.Delete })

    val DeleteForever: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.DeleteForever }, { MiuixIcons.Delete })

    val ExpandMore: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.ExpandMore }, { MiuixIcons.ExpandMore })

    val Backup: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Backup }, { MiuixIcons.Backup })

    val Restore: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Restore }, { MiuixIcons.Reset })

    val RestartAlt: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.RestartAlt }, { MiuixIcons.Reset })

    val SettingsBackupRestore: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.SettingsBackupRestore }, { MiuixIcons.Reset })

    val Tune: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Tune }, { MiuixIcons.Tune })

    val Update: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Update }, { MiuixIcons.Update })

    val SystemUpdate: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.SystemUpdate }, { MiuixIcons.Update })

    val VisibilityOff: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.VisibilityOff }, { MiuixIcons.Hide })

    val Palette: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Palette }, { MiuixIcons.Theme })

    val Style: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Style }, { MiuixIcons.Theme })

    val Favorite: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Favorite }, { MiuixIcons.Favorites })

    val Image: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Image }, { MiuixIcons.Image })

    val History: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.History }, { MiuixIcons.Recent })

    val Article: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.AutoMirrored.Rounded.Article }, { MiuixIcons.Notes })

    val Language: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Language }, { MiuixIcons.Translate })

    val Apps: ImageVector
        @Composable @ReadOnlyComposable
        get() = themed({ Icons.Rounded.Apps }, { MiuixIcons.GridView })

    val OpenInNew: ImageVector
        get() = Icons.AutoMirrored.Rounded.OpenInNew

    val Android: ImageVector
        get() = Icons.Rounded.Android

    val BatterySaver: ImageVector
        get() = Icons.Rounded.BatterySaver

    val BlurOn: ImageVector
        get() = Icons.Rounded.BlurOn

    val Brightness4: ImageVector
        get() = Icons.Rounded.Brightness4

    val BugReport: ImageVector
        get() = Icons.Rounded.BugReport

    val Build: ImageVector
        get() = Icons.Rounded.Build

    val BuildCircle: ImageVector
        get() = Icons.Rounded.BuildCircle

    val CallToAction: ImageVector
        get() = Icons.Rounded.CallToAction

    val Cancel: ImageVector
        get() = Icons.Rounded.Cancel

    val Category: ImageVector
        get() = Icons.Rounded.Category

    val CheckCircle: ImageVector
        get() = Icons.Rounded.CheckCircle

    val Code: ImageVector
        get() = Icons.Rounded.Code

    val Colorize: ImageVector
        get() = Icons.Rounded.Colorize

    val Contrast: ImageVector
        get() = Icons.Rounded.Contrast

    val DarkMode: ImageVector
        get() = Icons.Rounded.DarkMode

    val Dashboard: ImageVector
        get() = Icons.Rounded.Dashboard

    val DataObject: ImageVector
        get() = Icons.Rounded.DataObject

    val DesignServices: ImageVector
        get() = Icons.Rounded.DesignServices

    val DeveloperBoard: ImageVector
        get() = Icons.Rounded.DeveloperBoard

    val Extension: ImageVector
        get() = Icons.Rounded.Extension

    val FormatColorFill: ImageVector
        get() = Icons.Rounded.FormatColorFill

    val Gavel: ImageVector
        get() = Icons.Rounded.Gavel

    val HideSource: ImageVector
        get() = Icons.Rounded.HideSource

    val Lightbulb: ImageVector
        get() = Icons.Rounded.Lightbulb

    val Memory: ImageVector
        get() = Icons.Rounded.Memory

    val Numbers: ImageVector
        get() = Icons.Rounded.Numbers

    val PhoneAndroid: ImageVector
        get() = Icons.Rounded.PhoneAndroid

    val Public: ImageVector
        get() = Icons.Rounded.Public

    val QueryStats: ImageVector
        get() = Icons.Rounded.QueryStats

    val RestorePage: ImageVector
        get() = Icons.Rounded.RestorePage

    val RocketLaunch: ImageVector
        get() = Icons.Rounded.RocketLaunch

    val Save: ImageVector
        get() = Icons.Rounded.Save

    val Science: ImageVector
        get() = Icons.Rounded.Science

    val Security: ImageVector
        get() = Icons.Rounded.Security

    val SettingsEthernet: ImageVector
        get() = Icons.Rounded.SettingsEthernet

    val Smartphone: ImageVector
        get() = Icons.Rounded.Smartphone

    val Speed: ImageVector
        get() = Icons.Rounded.Speed

    val SwapHoriz: ImageVector
        get() = Icons.Rounded.SwapHoriz

    val Swipe: ImageVector
        get() = Icons.Rounded.Swipe

    val Verified: ImageVector
        get() = Icons.Rounded.Verified

    val Warning: ImageVector
        get() = Icons.Rounded.Warning

    val Widgets: ImageVector
        get() = Icons.Rounded.Widgets
}
