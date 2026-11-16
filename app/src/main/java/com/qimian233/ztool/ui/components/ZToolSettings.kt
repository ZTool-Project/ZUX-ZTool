package com.qimian233.ztool.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.basic.BasicComponent as MiuixBasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl as MiuixDropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem as MiuixDropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn as MiuixListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider as MiuixPopupPositionProvider
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons.Basic as MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup as MiuixWindowListPopup

// Trailing controls must not inflate an already clickable row beyond its minimum height.
@Composable
private fun trailingControlMinimumInteractiveSize(horizontalPadding: Dp): Dp {
    return (LocalMinimumInteractiveComponentSize.current.takeOrElse { 0.dp } - horizontalPadding)
        .coerceAtLeast(0.dp)
}

@Composable
private fun miuixRowTitleColor(enabled: Boolean) =
    if (enabled) {
        BasicComponentDefaults.titleColor()
    } else {
        val color = MiuixTheme.colorScheme.disabledOnSecondaryVariant
        BasicComponentDefaults.titleColor(color = color, disabledColor = color)
    }

@Composable
private fun miuixRowSummaryColor(enabled: Boolean) =
    if (enabled) {
        BasicComponentDefaults.summaryColor()
    } else {
        val color = MiuixTheme.colorScheme.disabledOnSecondaryVariant
        BasicComponentDefaults.summaryColor(color = color, disabledColor = color)
    }

@Composable
fun ZListItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: @Composable (RowScope.() -> Unit)? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
    rippleOnly: Boolean = false
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        MiuixBasicComponent(
            modifier = modifier.semantics { if (!enabled) disabled() },
            title = title,
            titleColor = miuixRowTitleColor(enabled),
            summary = summary,
            summaryColor = miuixRowSummaryColor(enabled),
            startAction = leadingContent?.let {
                {
                    Row {
                        it()
                    }
                }
            },
            endActions = trailingContent,
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            onClick = onClick?.let { action -> { if (enabled) action() } },
            enabled = enabled || rippleOnly
        )
        return
    }

    MaterialSettingListItem(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        rippleOnly = rippleOnly,
        onClick = onClick,
        leadingContent = leadingContent?.let { { Row(content = it) } },
        trailingContent = trailingContent?.let { { Row(content = it) } }
    )
}

@Composable
fun ZToolSettingLeadingIcon(
    icon: ImageVector,
    enabled: Boolean = true
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (enabled) {
            LocalZToolColorScheme.current.onSurfaceVariant
        } else {
            LocalZToolColorScheme.current.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = Modifier.size(24.dp)
    )
}

@Composable
fun ZToolSettingLeadingAvatar(
    @DrawableRes resId: Int,
    enabled: Boolean = true
) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.38f)
    )
}

@Composable
fun ZToolSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    padding: Dp = 24.dp,
    rippleOnly: Boolean = false
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        MiuixBasicComponent(
            modifier = modifier.semantics { if (!enabled) disabled() },
            title = title,
            titleColor = miuixRowTitleColor(enabled),
            summary = summary,
            summaryColor = miuixRowSummaryColor(enabled),
            startAction = icon?.let { ic ->
                { ZToolSettingLeadingIcon(icon = ic, enabled = enabled) }
            },
            endActions = {
                MiuixSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled
                )
            },
            // A 14dp inset keeps single-line Miuix switch rows at 56dp.
            insideMargin = PaddingValues(
                horizontal = padding,
                vertical = if (summary == null) 14.dp else 16.dp
            ),
            onClick = { if (enabled) onCheckedChange(!checked) },
            enabled = enabled || rippleOnly
        )
        return
    }

    MaterialSettingListItem(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        rippleOnly = rippleOnly,
        checked = checked,
        onClick = { onCheckedChange(!checked) },
        horizontalPadding = padding,
        leadingContent = icon?.let { { ZToolSettingLeadingIcon(it, enabled) } },
        trailingContent = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides trailingControlMinimumInteractiveSize(padding)
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        disabledCheckedThumbColor = LocalZToolColorScheme.current.primary.copy(alpha = 0.38f),
                        disabledCheckedTrackColor = LocalZToolColorScheme.current.primary.copy(alpha = 0.12f),
                        disabledCheckedIconColor = LocalZToolColorScheme.current.onPrimary.copy(alpha = 0.38f),
                        disabledUncheckedThumbColor = LocalZToolColorScheme.current.onSurface.copy(alpha = 0.38f),
                        disabledUncheckedTrackColor = LocalZToolColorScheme.current.surfaceContainerHighest.copy(alpha = 0.12f),
                        disabledUncheckedBorderColor = LocalZToolColorScheme.current.outline.copy(alpha = 0.12f),
                        disabledUncheckedIconColor = LocalZToolColorScheme.current.onSurface.copy(alpha = 0.38f)
                    ),
                    thumbContent = if (checked) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    } else {
                        {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    }
                )
            }
        }
    )
}

@Composable
fun ZToolCheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    rippleOnly: Boolean = false
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        MiuixBasicComponent(
            modifier = modifier.semantics { if (!enabled) disabled() },
            title = title,
            titleColor = miuixRowTitleColor(enabled),
            summary = summary,
            summaryColor = miuixRowSummaryColor(enabled),
            startAction = icon?.let { ic ->
                { ZToolSettingLeadingIcon(icon = ic, enabled = enabled) }
            },
            endActions = {
                top.yukonga.miuix.kmp.basic.Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = { onCheckedChange(!checked) },
                    enabled = enabled
                )
            },
            insideMargin = PaddingValues(
                horizontal = 24.dp,
                vertical = if (summary == null) 14.dp else 16.dp
            ),
            onClick = { if (enabled) onCheckedChange(!checked) },
            enabled = enabled || rippleOnly
        )
        return
    }

    MaterialSettingListItem(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        rippleOnly = rippleOnly,
        checked = checked,
        onClick = { onCheckedChange(!checked) },
        leadingContent = icon?.let { { ZToolSettingLeadingIcon(it, enabled) } },
        trailingContent = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides trailingControlMinimumInteractiveSize(24.dp)
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            }
        }
    )
}

@Composable
fun ZToolCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        top.yukonga.miuix.kmp.basic.Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange(!checked) },
            modifier = modifier,
            enabled = enabled
        )
    } else {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled
        )
    }
}

@Composable
fun ZToolSettingsDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    addDefaultPadding: Boolean = true
) {
    val effectiveModifier = if (addDefaultPadding) {
        modifier.padding(start = 24.dp, end = 24.dp)
    } else {
        modifier
    }

    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        val miuixModifier = if (color == Color.Unspecified) effectiveModifier else effectiveModifier
        top.yukonga.miuix.kmp.basic.HorizontalDivider(
            modifier = miuixModifier,
            color = if (color != Color.Unspecified) color else Color.Unspecified
        )
        return
    }

    HorizontalDivider(
        modifier = effectiveModifier,
        color = if (color == Color.Unspecified) LocalZToolColorScheme.current.outlineVariant else color
    )
}

@Composable
fun <T> ZToolPopupMenuField(
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    externalExpanded: Boolean? = null,
    onExternalExpandedChange: ((Boolean) -> Unit)? = null,
    optionSupportingText: ((T) -> String?)? = null
) {
    ZToolSettingsNavigationEventProvider {
        if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
            ZToolMiuixPopupMenuField(
                value = value,
                options = options,
                optionLabel = optionLabel,
                optionSupportingText = optionSupportingText,
                onOptionSelected = onOptionSelected,
                modifier = modifier,
                enabled = enabled,
                icon = icon,
                externalExpanded = externalExpanded,
                onExternalExpandedChange = onExternalExpandedChange
            )
            return@ZToolSettingsNavigationEventProvider
        }

        ZToolMaterialPopupMenuField(
            value = value,
            options = options,
            optionLabel = optionLabel,
            optionSupportingText = optionSupportingText,
            onOptionSelected = onOptionSelected,
            modifier = modifier,
            enabled = enabled,
            icon = icon,
            externalExpanded = externalExpanded,
            onExternalExpandedChange = onExternalExpandedChange
        )
    }
}

@Composable
fun <T> ZToolPopupMenuSettingRow(
    title: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fieldMinWidth: Dp = 132.dp,
    fieldMaxWidth: Dp = 180.dp,
    rippleOnly: Boolean = false,
    optionSupportingText: ((T) -> String?)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        MiuixBasicComponent(
            modifier = modifier.semantics { if (!enabled) disabled() },
            title = title,
            titleColor = miuixRowTitleColor(enabled),
            summary = summary,
            summaryColor = miuixRowSummaryColor(enabled),
            startAction = icon?.let { ic ->
                { ZToolSettingLeadingIcon(icon = ic, enabled = enabled) }
            },
            endActions = {
                ZToolPopupMenuField(
                    value = value,
                    options = options,
                    optionLabel = optionLabel,
                    optionSupportingText = optionSupportingText,
                    onOptionSelected = onOptionSelected,
                    enabled = enabled,
                    modifier = Modifier.widthIn(min = fieldMinWidth, max = fieldMaxWidth),
                    externalExpanded = expanded,
                    onExternalExpandedChange = { expanded = it }
                )
            },
            insideMargin = PaddingValues(
                horizontal = 24.dp,
                // A 12dp inset keeps single-line Miuix popup rows at 56dp.
                vertical = if (summary == null) 12.dp else 16.dp
            ),
            onClick = {
                if (enabled && options.isNotEmpty()) {
                    expanded = true
                }
            },
            enabled = enabled || rippleOnly
        )
        return
    }

    MaterialSettingListItem(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled && options.isNotEmpty(),
        rippleOnly = rippleOnly,
        onClick = { expanded = true },
        leadingContent = icon?.let { { ZToolSettingLeadingIcon(it, enabled) } },
        trailingContent = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides trailingControlMinimumInteractiveSize(24.dp)
            ) {
                ZToolPopupMenuField(
                    value = value,
                    options = options,
                    optionLabel = optionLabel,
                    optionSupportingText = optionSupportingText,
                    onOptionSelected = onOptionSelected,
                    enabled = enabled,
                    modifier = Modifier.widthIn(min = fieldMinWidth, max = fieldMaxWidth),
                    externalExpanded = expanded,
                    onExternalExpandedChange = { expanded = it }
                )
            }
        }
    )
}

@Composable
fun <T> ZToolPopupDialogField(
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    dialogTitle: String? = null,
    externalShowDialog: Boolean? = null,
    onExternalShowDialogChange: ((Boolean) -> Unit)? = null
) {
    val internalShowDialog = remember { mutableStateOf(false) }
    val showDialog = externalShowDialog ?: internalShowDialog.value
    val setShowDialog: (Boolean) -> Unit = onExternalShowDialogChange ?: { internalShowDialog.value = it }
    fun selectedIndex(): Int = options.indexOfFirst { optionLabel(it) == value }.coerceAtLeast(0)
    var pendingIndex by remember(value, options) {
        mutableIntStateOf(selectedIndex())
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && options.isNotEmpty()) {
                pendingIndex = selectedIndex()
                setShowDialog(true)
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            ZToolSettingLeadingIcon(icon = icon, enabled = enabled)
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        }
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                LocalZToolColorScheme.current.primary
            } else {
                LocalZToolColorScheme.current.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )
        IconButton(
            shapes = if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
                IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape)
            } else {
                IconButtonDefaults.shapes()
            },
            enabled = enabled && options.isNotEmpty(),
            onClick = {
                pendingIndex = selectedIndex()
                setShowDialog(true)
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = if (enabled && options.isNotEmpty()) {
                    LocalZToolColorScheme.current.onSurfaceVariant
                } else {
                    LocalZToolColorScheme.current.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }
    }

    if (showDialog) {
        ZToolDialog(
            onDismissRequest = { setShowDialog(false) },
            title = dialogTitle?.let { titleText ->
                { Text(titleText) }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEachIndexed { index, option ->
                        val selected = index == pendingIndex
                        val style = LocalZToolThemeSpec.current.style
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    color = if (selected && style == FrontendStyle.Material3Expressive) {
                                        LocalZToolColorScheme.current.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .clickable { pendingIndex = index }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (style == FrontendStyle.Miuix) {
                                ZToolCheckbox(
                                    checked = selected,
                                    onCheckedChange = { pendingIndex = index }
                                )
                            } else {
                                RadioButton(
                                    selected = selected,
                                    onClick = { pendingIndex = index }
                                )
                            }
                            Text(
                                text = optionLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                color = LocalZToolColorScheme.current.onSurface,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        options.getOrNull(pendingIndex)?.let(onOptionSelected)
                        setShowDialog(false)
                    }
                ) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { setShowDialog(false) }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun <T> ZToolMiuixPopupMenuField(
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    externalExpanded: Boolean? = null,
    onExternalExpandedChange: ((Boolean) -> Unit)? = null,
    optionSupportingText: ((T) -> String?)? = null
) {
    val internalExpanded = remember { mutableStateOf(false) }
    val expanded = externalExpanded ?: internalExpanded.value
    val setExpanded: (Boolean) -> Unit = onExternalExpandedChange ?: { internalExpanded.value = it }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && options.isNotEmpty()) {
                    setExpanded(true)
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                ZToolSettingLeadingIcon(icon = icon, enabled = enabled)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = LocalZToolColorScheme.current.onSurfaceVariant
            )
            IconButton(
            shapes = if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
                IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape)
            } else {
                IconButtonDefaults.shapes()
            },
                enabled = enabled && options.isNotEmpty(),
                onClick = { setExpanded(true) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.ArrowUpDown,
                    contentDescription = null,
                    tint = if (enabled && options.isNotEmpty()) {
                        LocalZToolColorScheme.current.onSurfaceVariant
                    } else {
                        LocalZToolColorScheme.current.onSurfaceVariant.copy(alpha = 0.38f)
                    }
                )
            }
        }

        MiuixWindowListPopup(
            show = expanded,
            onDismissRequest = { setExpanded(false) },
            alignment = MiuixPopupPositionProvider.Align.End,
            maxHeight = 360.dp,
            minWidth = 0.dp
        ) {
            MiuixListPopupColumn {
                options.forEachIndexed { index, option ->
                    val label = optionLabel(option)
                    MiuixDropdownImpl(
                        item = MiuixDropdownItem(
                            text = label,
                            summary = optionSupportingText?.invoke(option)?.takeIf { it.isNotBlank() }
                        ),
                        optionSize = options.size,
                        isSelected = label == value,
                        index = index,
                        onSelectedIndexChange = {
                            onOptionSelected(option)
                            setExpanded(false)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ZToolMaterialPopupMenuField(
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    externalExpanded: Boolean? = null,
    onExternalExpandedChange: ((Boolean) -> Unit)? = null,
    optionSupportingText: ((T) -> String?)? = null
) {
    val internalExpanded = remember { mutableStateOf(false) }
    val expanded = externalExpanded ?: internalExpanded.value
    val setExpanded: (Boolean) -> Unit = onExternalExpandedChange ?: { internalExpanded.value = it }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && options.isNotEmpty()) {
                    setExpanded(true)
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                ZToolSettingLeadingIcon(icon = icon, enabled = enabled)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    LocalZToolColorScheme.current.primary
                } else {
                    LocalZToolColorScheme.current.onSurfaceVariant
                }
            )
            IconButton(
            shapes = if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
                IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape)
            } else {
                IconButtonDefaults.shapes()
            },
                enabled = enabled && options.isNotEmpty(),
                onClick = { setExpanded(true) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (enabled && options.isNotEmpty()) {
                        LocalZToolColorScheme.current.onSurfaceVariant
                    } else {
                        LocalZToolColorScheme.current.onSurfaceVariant.copy(alpha = 0.38f)
                    }
                )
            }
        }

        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { setExpanded(false) }
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(index = 0, count = 1),
                containerColor = LocalZToolColorScheme.current.surfaceContainerHigh,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            ) {
                options.forEachIndexed { index, option ->
                    val label = optionLabel(option)
                    val supportingText = optionSupportingText?.invoke(option)?.takeIf { it.isNotBlank() }
                    SelectableDropdownMenuItem(
                        selected = label == value,
                        shapes = MenuDefaults.itemShape(index = index, count = options.size),
                        text = { Text(label) },
                        supportingText = supportingText?.let { { Text(it) } },
                        onClick = {
                            onOptionSelected(option)
                            setExpanded(false)
                        },
                        selectedLeadingIcon = {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    )
                }
            }
        }
    }
}

internal val LocalExpressiveListShapes = staticCompositionLocalOf<ListItemShapes?> { null }

internal data class ExpressiveListPosition(
    val index: Int,
    val count: Int,
    val shape: androidx.compose.ui.graphics.Shape?
) : Modifier.Element

@Composable
private fun MaterialSettingListItem(
    title: String,
    summary: String?,
    modifier: Modifier,
    enabled: Boolean,
    rippleOnly: Boolean,
    onClick: (() -> Unit)?,
    checked: Boolean? = null,
    horizontalPadding: Dp = 24.dp,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val shapes = expressiveListShapes(modifier)
    val baseColors = ListItemDefaults.segmentedColors(
        containerColor = LocalZToolColorScheme.current.surfaceContainerHigh
    )
    val tint = LocalSettingHighlightTint.current
    val colors = baseColors.copy(
        containerColor = tint.compositeOver(baseColors.containerColor),
        selectedContainerColor = tint.compositeOver(baseColors.selectedContainerColor),
        disabledContainerColor = tint.compositeOver(baseColors.disabledContainerColor)
    )
    val rowModifier = modifier.fillMaxWidth().semantics { if (!enabled) disabled() }
    val content: @Composable () -> Unit = {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) LocalZToolColorScheme.current.onSurface else LocalZToolColorScheme.current.onSurfaceVariant
        )
    }
    val supportingContent: @Composable (() -> Unit)? = summary?.let {
        { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalZToolColorScheme.current.onSurfaceVariant) }
    }
    val padding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp)
    if (checked != null) {
        SegmentedListItem(
            checked = checked,
            onCheckedChange = { if (enabled) onClick?.invoke() },
            shapes = shapes,
            modifier = rowModifier,
            enabled = enabled || rippleOnly,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            colors = colors,
            contentPadding = padding,
            content = content
        )
    } else if (onClick != null) {
        SegmentedListItem(
            onClick = { if (enabled) onClick() },
            shapes = shapes,
            modifier = rowModifier,
            enabled = enabled || rippleOnly,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            colors = colors,
            contentPadding = padding,
            content = content
        )
    } else {
        SegmentedListItem(
            shapes = shapes,
            modifier = rowModifier,
            enabled = enabled,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            colors = colors,
            contentPadding = padding,
            content = content
        )
    }
}

@Composable
internal fun expressiveListShapes(modifier: Modifier): ListItemShapes {
    val position = modifier.foldIn<ExpressiveListPosition?>(null) { result, element ->
        if (element is ExpressiveListPosition) element else result
    }
    return position?.let {
        if (it.shape != null) ListItemDefaults.shapes(shape = it.shape)
        else ListItemDefaults.segmentedShapes(index = it.index, count = it.count)
    } ?: LocalExpressiveListShapes.current ?: ListItemDefaults.segmentedShapes(index = 0, count = 1)
}

@Composable
internal fun ZToolSegmentedSettingContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        Column(modifier = modifier, content = { content() })
    } else {
        val baseColor = LocalZToolColorScheme.current.surfaceContainerHigh
        SegmentedListItem(
            shapes = expressiveListShapes(modifier),
            modifier = modifier.fillMaxWidth(),
            colors = ListItemDefaults.segmentedColors(
                containerColor = LocalSettingHighlightTint.current.compositeOver(baseColor)
            ),
            contentPadding = PaddingValues(0.dp),
            content = content
        )
    }
}
