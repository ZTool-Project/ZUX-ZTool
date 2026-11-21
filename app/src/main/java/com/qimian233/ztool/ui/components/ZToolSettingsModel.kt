package com.qimian233.ztool.ui.components

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.qimian233.ztool.R
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class SettingSection(
    val title: String? = null,
    val items: List<SettingItem>
)

sealed interface SettingItem {
    val key: String
    val enabled: Boolean

    /** Minimum SDK API level, inclusive; null = unbounded. */
    val minSdkVersion: Int? get() = null

    /** Maximum SDK API level, inclusive; null = unbounded. */
    val maxSdkVersion: Int? get() = null

    /** Replaces `summary` when the current SDK level is outside the bounds. */
    val unsupportedHint: String? get() = null

    data class Switch(
        val title: String,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val summary: String? = null,
        val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String
    ) : SettingItem

    data class Checkbox(
        val title: String,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val summary: String? = null,
        val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String
    ) : SettingItem

    data class Entry(
        val title: String,
        val onClick: () -> Unit,
        val summary: String? = null,
        val icon: ImageVector? = null,
        val leadingContent: (@Composable RowScope.() -> Unit)? = null,
        val trailingContent: (@Composable RowScope.() -> Unit)? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String
    ) : SettingItem

    data class Dropdown<T>(
        val label: String,
        val value: String,
        val options: List<T>,
        val optionLabel: (T) -> String,
        val onOptionSelected: (T) -> Unit,
        val summary: String? = null,
        val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String,
        val optionSupportingText: ((T) -> String?)? = null,
        val optionIcon: ((T) -> (@Composable () -> Unit)?)? = null
    ) : SettingItem

    data class Slider(
        val title: String,
        val value: Float,
        val onValueChange: (Float) -> Unit,
        val summary: String? = null,
        val valueText: String? = null,
        val valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
        val steps: Int = 0,
        val onValueChangeFinished: (() -> Unit)? = null,
        val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String
    ) : SettingItem

    data class TextInput(
        val label: String,
        val value: String,
        val onValueChange: (String) -> Unit,
        val title: String? = null,
        val summary: String? = null,
        val placeholder: String? = null,
        val singleLine: Boolean = true,
        val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String
    ) : SettingItem

    data class ColorPreview(
        val title: String,
        val color: Color,
        val onClick: () -> Unit,
        val summary: String? = null,
        val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String
    ) : SettingItem

    data class Action(
        val title: String,
        val onClick: () -> Unit,
        val summary: String? = null,
        val icon: ImageVector? = null,
        val leadingContent: (@Composable RowScope.() -> Unit)? = null,
        val trailingContent: (@Composable RowScope.() -> Unit)? = null,
        override val enabled: Boolean = true,
        override val minSdkVersion: Int? = null,
        override val maxSdkVersion: Int? = null,
        override val unsupportedHint: String? = null,
        override val key: String
    ) : SettingItem

    data class Custom(
        val content: @Composable () -> Unit,
        override val enabled: Boolean = true,
        override val key: String
    ) : SettingItem
}

@Composable
fun ZToolSettingsList(
    sections: List<SettingSection>,
    modifier: Modifier = Modifier,
    sectionSpacing: Dp = 16.dp,
    bottomPadding: Dp = 0.dp,
    highlightRegistry: HighlightAnchorRegistry? = null,

    gutter: Dp? = null
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalHighlightRegistry provides highlightRegistry
    ) {
        ZToolSettingsNavigationEventProvider {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                HighlightContainerMarker(registry = highlightRegistry)
                sections.forEach { section ->
                    ZToolSettingsSection(section = section, gutter = gutter)
                }
                if (bottomPadding > 0.dp) {
                    Spacer(modifier = Modifier.height(bottomPadding))
                }
            }
        }
    }
}

@Composable
fun ZToolSettingsNavigationEventProvider(content: @Composable () -> Unit) {
    val providedNavigationEventDispatcherOwner = LocalNavigationEventDispatcherOwner.current
    if (LocalZToolThemeSpec.current.style != FrontendStyle.Miuix ||
        providedNavigationEventDispatcherOwner != null
    ) {
        content()
        return
    }

    val navigationEventDispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides navigationEventDispatcherOwner,
        content = content
    )
}

/** Content inset of a Material Expressive settings section, i.e. its row gutter. */
private val ExpressiveSectionContentPadding = 12.dp

@Composable
fun ZToolSettingsSection(
    section: SettingSection,
    modifier: Modifier = Modifier,
    titlePadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
    gutter: Dp? = null
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Material3Expressive) {
        MaterialExpressiveSettingsSection(
            section = section,
            modifier = modifier,
            titlePadding = titlePadding,
            contentPadding = gutter ?: ExpressiveSectionContentPadding
        )
        return
    }

    val sectionModifier = if (gutter != null) {
        modifier.padding(horizontal = gutter)
    } else {
        modifier
    }
    Column(modifier = sectionModifier.fillMaxWidth()) {
        if (section.title != null) {
            Text(
                text = section.title,
                style = MiuixTheme.textStyles.subtitle,
                color = LocalZToolColorScheme.current.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
            )
        }
        ZToolCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(vertical = 4.dp)
            ) {
                section.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        ZToolSettingsDivider()
                    }
                    HighlightableSettingRow(highlightKey = item.key) {
                        ZToolSettingItem(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialExpressiveSettingsSection(
    section: SettingSection,
    modifier: Modifier = Modifier,
    titlePadding: PaddingValues,
    contentPadding: Dp
) {
    ZToolCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(contentPadding)
        ) {
            if (section.title != null) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = LocalZToolColorScheme.current.onSurface,
                    modifier = Modifier.padding(titlePadding)
                )
            }

            ExpressiveSectionItems(items = section.items, itemSpacing = 4.dp)
        }
    }
}

@Composable
fun ColumnScope.ExpressiveSectionItems(
    count: Int,
    shapeForIndex: ((index: Int, count: Int) -> Shape)? = null,
    content: @Composable ColumnScope.((Int) -> Modifier) -> Unit
) {
    val isMiuix = LocalZToolThemeSpec.current.style == FrontendStyle.Miuix
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isMiuix) 0.dp else 4.dp)
    ) {
        content { index ->
            if (isMiuix) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.fillMaxWidth().then(
                    ExpressiveListPosition(index, count, shapeForIndex?.invoke(index, count))
                )
            }
        }
    }
}

@Composable
fun ExpressiveSectionItems(
    items: List<SettingItem>,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = 4.dp,
    shapeForIndex: ((index: Int, count: Int) -> Shape)? = null
) {
    val isMiuix = LocalZToolThemeSpec.current.style == FrontendStyle.Miuix
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isMiuix) 0.dp else itemSpacing)
    ) {
        items.forEachIndexed { index, item ->
            val shapes = if (isMiuix) null else ListItemDefaults.segmentedShapes(
                index = index,
                count = items.size,
                defaultShapes = ListItemDefaults.shapes(shape = shapeForIndex?.invoke(index, items.size))
            )
            HighlightableSettingRow(highlightKey = item.key, shape = shapes?.shape) {
                CompositionLocalProvider(LocalExpressiveListShapes provides shapes) {
                    ZToolSettingItem(item = item, modifier = Modifier)
                }
            }
        }
    }
}

@Composable
fun ZToolSettingItem(
    item: SettingItem,
    modifier: Modifier = Modifier
) {
    val supported = isAndroidVersionSupported(
        sdkInt = Build.VERSION.SDK_INT,
        minSdkVersion = item.minSdkVersion,
        maxSdkVersion = item.maxSdkVersion
    )
    val effectiveEnabled = item.enabled && supported
    val unsupportedSummary = if (supported) {
        null
    } else {
        item.unsupportedHint?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.common_android_version_unsupported_hint)
    }

    when (item) {
        is SettingItem.Switch -> {
            LaunchedEffect(item.key, supported, item.checked) {
                if (!supported && item.checked) item.onCheckedChange(false)
            }
            ZToolSwitchRow(
                title = item.title,
                summary = unsupportedSummary ?: item.summary,
                checked = if (supported) item.checked else false,
                onCheckedChange = if (supported) item.onCheckedChange else { _ -> },
                enabled = effectiveEnabled,
                rippleOnly = !supported,
                icon = item.icon,
                modifier = modifier
            )
        }

        is SettingItem.Checkbox -> {
            LaunchedEffect(item.key, supported, item.checked) {
                if (!supported && item.checked) item.onCheckedChange(false)
            }
            ZToolCheckboxRow(
                title = item.title,
                summary = unsupportedSummary ?: item.summary,
                checked = if (supported) item.checked else false,
                onCheckedChange = if (supported) item.onCheckedChange else { _ -> },
                enabled = effectiveEnabled,
                rippleOnly = !supported,
                icon = item.icon,
                modifier = modifier
            )
        }

        is SettingItem.Entry -> {
            ZListItem(
                title = item.title,
                summary = unsupportedSummary ?: item.summary,
                enabled = effectiveEnabled,
                onClick = item.onClick,
                leadingContent = item.leadingContent ?: item.icon?.let { icon ->
                    { ZToolSettingLeadingIcon(icon = icon, enabled = effectiveEnabled) }
                },
                trailingContent = item.trailingContent,
                rippleOnly = !supported,
                modifier = modifier
            )
        }

        is SettingItem.Dropdown<*> -> {
            ZToolPopupMenuSettingItem(
                item = item,
                modifier = modifier,
                supported = supported,
                enabled = effectiveEnabled,
                summary = unsupportedSummary ?: item.summary
            )
        }

        is SettingItem.Slider -> {
            ZToolSliderRow(
                title = item.title,
                summary = unsupportedSummary ?: item.summary,
                value = item.value,
                valueText = item.valueText,
                valueRange = item.valueRange,
                steps = item.steps,
                onValueChange = item.onValueChange,
                onValueChangeFinished = item.onValueChangeFinished,
                enabled = effectiveEnabled,
                icon = item.icon,
                modifier = modifier
            )
        }

        is SettingItem.TextInput -> {
            ZToolTextInputRow(
                label = item.label,
                value = item.value,
                onValueChange = item.onValueChange,
                title = item.title,
                summary = unsupportedSummary ?: item.summary,
                placeholder = item.placeholder,
                singleLine = item.singleLine,
                enabled = effectiveEnabled,
                icon = item.icon,
                modifier = modifier
            )
        }

        is SettingItem.ColorPreview -> {
            ZListItem(
                title = item.title,
                summary = unsupportedSummary ?: item.summary,
                enabled = effectiveEnabled,
                onClick = item.onClick,
                leadingContent = item.icon?.let { icon ->
                    { ZToolSettingLeadingIcon(icon = icon, enabled = effectiveEnabled) }
                },
                trailingContent = {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(item.color, CircleShape)
                    )
                },
                rippleOnly = !supported,
                modifier = modifier
            )
        }

        is SettingItem.Action -> {
            ZListItem(
                title = item.title,
                summary = unsupportedSummary ?: item.summary,
                enabled = effectiveEnabled,
                onClick = item.onClick,
                leadingContent = item.leadingContent ?: item.icon?.let { icon ->
                    { ZToolSettingLeadingIcon(icon = icon, enabled = effectiveEnabled) }
                },
                trailingContent = item.trailingContent,
                rippleOnly = !supported,
                modifier = modifier
            )
        }

        is SettingItem.Custom -> {
            ZToolSegmentedSettingContainer(modifier = modifier.fillMaxWidth()) {
                item.content()
            }
        }
    }
}

@Composable
private fun <T> ZToolPopupMenuSettingItem(
    item: SettingItem.Dropdown<T>,
    modifier: Modifier = Modifier,
    supported: Boolean = true,
    enabled: Boolean = item.enabled,
    summary: String? = item.summary
) {
    ZToolPopupMenuSettingRow(
        title = item.label,
        value = item.value,
        options = item.options,
        optionLabel = item.optionLabel,
        optionSupportingText = item.optionSupportingText,
        optionIcon = item.optionIcon,
        onOptionSelected = item.onOptionSelected,
        summary = summary,
        enabled = enabled,
        rippleOnly = !supported,
        icon = item.icon,
        modifier = modifier
    )
}

@Composable
fun ZToolSliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    valueText: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    horizontalPadding: Dp = 24.dp,
    verticalPadding: Dp = 16.dp
) {
    ZToolSegmentedSettingContainer(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
            if (title != null) {
                SettingsTitleBlock(
                    title = title,
                    summary = summary,
                    enabled = enabled,
                    icon = icon
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZToolSlider(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChangeFinished = onValueChangeFinished,
                    modifier = Modifier.weight(1f)
                )
                if (valueText != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalZToolColorScheme.current.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.widthIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ZToolSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished
        )
    } else {
        val sliderState = remember(steps, valueRange) {
            SliderState(value = value, steps = steps, trackRange = valueRange)
        }
        sliderState.value = value
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@Composable
fun ZToolOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            useLabelAsPlaceholder = placeholder != null,
            singleLine = singleLine,
            enabled = enabled,
            readOnly = readOnly,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            enabled = enabled,
            readOnly = readOnly,
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
    }
}

@Composable
fun ZToolTextInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    horizontalPadding: Dp = 24.dp,
    verticalPadding: Dp = 16.dp
) {
    ZToolSegmentedSettingContainer(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
            if (title != null) {
                SettingsTitleBlock(
                    title = title,
                    summary = summary,
                    enabled = enabled,
                    icon = icon
                )
            } else if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalZToolColorScheme.current.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            ZToolOutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                placeholder = placeholder,
                singleLine = singleLine,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (title != null || summary != null) 12.dp else 0.dp)
            )
        }
    }
}

@Composable
fun ZToolArgbColorTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    defaultText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    previewSize: Dp = 36.dp,
    onEditingFinished: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val normalizedDefault = defaultText?.normalizeArgbColorTextOrNull()
    val previewColor = value.parseArgbColorOrNull()
        ?: normalizedDefault?.parseArgbColorOrNull()
        ?: Color.Transparent
    val hasError = value.isNotEmpty() && value.parseArgbColorOrNull() == null

    fun finishEditing() {
        val fixedValue = value.normalizeArgbColorTextOrNull()
            ?: normalizedDefault
        if (fixedValue != null && fixedValue != value) {
            onValueChange(fixedValue)
        }
        onEditingFinished?.invoke()
    }

    ZToolSegmentedSettingContainer(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                ZToolSettingLeadingIcon(icon = icon, enabled = enabled)
                Spacer(modifier = Modifier.width(16.dp))
            }
            Box(
                modifier = Modifier
                    .size(previewSize)
                    .background(previewColor, RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, LocalZToolColorScheme.current.onSurfaceVariant), shape = RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            ZToolOutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it.sanitizeArgbColorText()) },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            finishEditing()
                        }
                    },
                label = label,
                placeholder = defaultText?.sanitizeArgbColorText(),
                isError = hasError,
                supportingText = if (hasError) errorText else summary,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        finishEditing()
                        focusManager.clearFocus()
                    }
                )
            )
        }
    }
}

fun String.sanitizeArgbColorText(): String {
    return trim()
        .removePrefix("#")
        .filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        .take(8)
        .uppercase()
}

fun String.normalizeArgbColorTextOrNull(): String? {
    val sanitized = sanitizeArgbColorText()
    return when (sanitized.length) {
        8 -> sanitized
        6 -> "FF$sanitized"
        else -> null
    }
}

fun String.parseArgbColorOrNull(): Color? {
    val normalized = normalizeArgbColorTextOrNull() ?: return null
    return normalized.toLongOrNull(16)?.let { Color(it) }
}

@Composable
private fun SettingsTitleBlock(
    title: String,
    summary: String?,
    enabled: Boolean,
    icon: ImageVector?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            ZToolSettingLeadingIcon(icon = icon, enabled = enabled)
            Spacer(modifier = Modifier.padding(horizontal = 8.dp))
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) LocalZToolColorScheme.current.onSurface else LocalZToolColorScheme.current.onSurfaceVariant
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalZToolColorScheme.current.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
