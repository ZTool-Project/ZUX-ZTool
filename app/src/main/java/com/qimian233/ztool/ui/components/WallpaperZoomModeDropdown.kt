package com.qimian233.ztool.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.qimian233.ztool.R
import com.qimian233.ztool.data.keys.WallpaperZoomModes

/** Builds a shared [SettingItem.Dropdown] for wallpaper zoom modes. */
@Composable
fun wallpaperZoomModeDropdown(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
    key: String,
    options: List<String> = WallpaperZoomModes.entries
): SettingItem.Dropdown<String> {
    val labelMap = mapOf(
        WallpaperZoomModes.CONTINUOUS to stringResource(R.string.common_wallpaper_zoom_mode_continuous),
        WallpaperZoomModes.CONTINUOUS_BOUNCE to
            stringResource(R.string.common_wallpaper_zoom_mode_continuous_bounce),
        WallpaperZoomModes.GESTURE_START to
            stringResource(R.string.common_wallpaper_zoom_mode_gesture_start),
        WallpaperZoomModes.DISABLE to stringResource(R.string.common_wallpaper_zoom_mode_disable)
    )
    val effectiveOptions = options.ifEmpty { WallpaperZoomModes.entries }
    val normalized = if (selectedMode in effectiveOptions) {
        selectedMode
    } else {
        effectiveOptions.first()
    }
    return SettingItem.Dropdown(
        label = stringResource(R.string.common_wallpaper_zoom_mode_title),
        value = labelMap[normalized] ?: normalized,
        options = effectiveOptions,
        optionLabel = { mode -> labelMap[mode] ?: mode },
        onOptionSelected = onModeSelected,
        key = key
    )
}
