package com.qimian233.ztool.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.qimian233.ztool.ui.icons.ZToolIcons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.qimian233.ztool.R
import com.qimian233.ztool.search.SearchEntry
import com.qimian233.ztool.search.SearchParentMode
import com.qimian233.ztool.search.SearchUiState
import com.qimian233.ztool.search.SearchViewModel
import com.qimian233.ztool.ui.components.LocalSearchOverlayState
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import com.qimian233.ztool.ui.theme.ZToolFontFamilyTextStyle

/** Docked feature search bar shared by the Features and Settings tabs. */
@Composable
fun FeatureSearchBar(
    visible: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenEntry: (SearchEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val overlayState = LocalSearchOverlayState.current
    val owner = LocalViewModelStoreOwner.current
        ?: error("FeatureSearchBar requires a ViewModelStoreOwner")
    val viewModel: SearchViewModel = remember(owner) {
        ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        return SearchViewModel(context.applicationContext) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        )[SearchViewModel::class.java]
    }
    val uiState by viewModel.uiState.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut() + slideOutVertically { -it / 4 }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    overlayState.bounds = coordinates.boundsInRoot()
                }
        ) {
            when (LocalZToolThemeSpec.current.style) {
                FrontendStyle.Miuix -> MiuixFeatureSearchBar(
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                    uiState = uiState,
                    onQueryChanged = viewModel::setQuery,
                    onOpenEntry = onOpenEntry,
                    modifier = Modifier.fillMaxWidth()
                )
                FrontendStyle.Material3Expressive -> Material3FeatureSearchBar(
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                    uiState = uiState,
                    onQueryChanged = viewModel::setQuery,
                    onOpenEntry = onOpenEntry,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun Material3FeatureSearchBar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    uiState: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onOpenEntry: (SearchEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val searchBarState = rememberSearchBarState(
        initialValue = if (expanded) SearchBarValue.Expanded else SearchBarValue.Collapsed
    )
    val textFieldState = rememberTextFieldState(initialText = uiState.query)

    LaunchedEffect(uiState.query) {
        if (textFieldState.text.toString() != uiState.query) {
            textFieldState.setTextAndPlaceCursorAtEnd(uiState.query)
        }
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { query ->
            onQueryChanged(query)
        }
    }

    LaunchedEffect(searchBarState.targetValue) {
        val targetExpanded = searchBarState.targetValue == SearchBarValue.Expanded
        if (targetExpanded != expanded) {
            onExpandedChange(targetExpanded)
        }
    }

    LaunchedEffect(expanded) {
        if (expanded && searchBarState.targetValue != SearchBarValue.Expanded) {
            searchBarState.animateToExpanded()
        } else if (!expanded && searchBarState.targetValue != SearchBarValue.Collapsed) {
            searchBarState.animateToCollapsed()
        }
    }

    val colors = SearchBarDefaults.colors()
    Surface(
        shape = SearchBarDefaults.dockedShape,
        color = colors.containerColor,
        contentColor = contentColorFor(colors.containerColor),
        tonalElevation = SearchBarDefaults.TonalElevation,
        shadowElevation = SearchBarDefaults.ShadowElevation,
        modifier = modifier.zIndex(1f)
    ) {
        Column {
            SearchBarDefaults.InputField(
                // Stretch the tap/focus target to match the full-width bar around it.
                modifier = Modifier.fillMaxWidth(),
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = { onExpandedChange(false) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = ZToolIcons.Search,
                        contentDescription = null
                    )
                },
                colors = colors.inputFieldColors
            )

            AnimatedVisibility(
                visible = expanded,
                enter = DockedEnterTransition,
                exit = DockedExitTransition
            ) {
                val windowContainerHeight = with(LocalDensity.current) {
                    LocalWindowInfo.current.containerSize.height.toDp()
                }
                val maxHeight = windowContainerHeight * DockedExpandedTableMaxHeightScreenRatio
                val minHeight = DockedExpandedTableMinHeight.coerceAtMost(maxHeight)

                Column(Modifier.heightIn(min = minHeight, max = maxHeight)) {
                    HorizontalDivider(color = colors.dividerColor)
                    ResultsContent(uiState, onOpenEntry)
                }
            }
        }
    }

    BackHandler(enabled = expanded) { onExpandedChange(false) }
}

private val DockedExpandedTableMinHeight = 240.dp
private const val DockedExpandedTableMaxHeightScreenRatio = 2f / 3f
private val DockedEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val DockedExitEasing = CubicBezierEasing(0.0f, 1.0f, 0.0f, 1.0f)
private val DockedEnterFloatSpec: FiniteAnimationSpec<Float> =
    tween(durationMillis = 600, delayMillis = 100, easing = DockedEnterEasing)
private val DockedExitFloatSpec: FiniteAnimationSpec<Float> =
    tween(durationMillis = 350, delayMillis = 100, easing = DockedExitEasing)
private val DockedEnterSizeSpec: FiniteAnimationSpec<IntSize> =
    tween(durationMillis = 600, delayMillis = 100, easing = DockedEnterEasing)
private val DockedExitSizeSpec: FiniteAnimationSpec<IntSize> =
    tween(durationMillis = 350, delayMillis = 100, easing = DockedExitEasing)
private val DockedEnterTransition: EnterTransition =
    fadeIn(DockedEnterFloatSpec) + expandVertically(DockedEnterSizeSpec)
private val DockedExitTransition: ExitTransition =
    fadeOut(DockedExitFloatSpec) + shrinkVertically(DockedExitSizeSpec)

@Composable
private fun MiuixFeatureSearchBar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    uiState: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onOpenEntry: (SearchEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = LocalZToolColorScheme.current
    top.yukonga.miuix.kmp.basic.SearchBar(
        inputField = {
            top.yukonga.miuix.kmp.basic.InputField(
                query = uiState.query,
                onQueryChange = onQueryChanged,
                onSearch = { onExpandedChange(false) },
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                label = stringResource(R.string.search_hint),
                textStyle = ZToolFontFamilyTextStyle
            )
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        content = {
            // Distinct result surface, inset 10dp per side (20dp narrower than the
            // input field) so it reads as an overlay card, not page content.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorScheme.surfaceContainer)
                    .padding(vertical = 4.dp)
            ) {
                ResultsContent(uiState, onOpenEntry)
            }
        }
    )
}

/** Result area shared by both styles: hint / no-results / grouped results. */
@Composable
private fun ResultsContent(
    uiState: SearchUiState,
    onOpenEntry: (SearchEntry) -> Unit
) {
    when {
        uiState.groups.isNotEmpty() -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                uiState.groups.forEach { group ->
                    item(key = "header_${group.key.first}_${group.key.second}") {
                        SearchResultGroupHeader(groupTitleRes = group.key.first, groupSuffixRes = group.key.second)
                    }
                    items(items = group.entries, key = { it.id }) { entry ->
                        SearchResultRow(
                            entry = entry,
                            onClick = { onOpenEntry(entry) }
                        )
                    }
                }
            }
        }
        uiState.searched -> {
            Text(
                text = stringResource(R.string.search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            )
        }
        else -> {
            Text(
                text = stringResource(R.string.search_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            )
        }
    }
}

@Composable
private fun SearchResultGroupHeader(
    groupTitleRes: Int,
    groupSuffixRes: Int?
) {
    Text(
        text = stringResource(groupTitleRes) + (groupSuffixRes?.let { stringResource(it) } ?: ""),
        style = MaterialTheme.typography.titleSmall,
        color = LocalZToolColorScheme.current.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SearchResultRow(
    entry: SearchEntry,
    onClick: () -> Unit
) {
    val annotation = entry.parentTitleRes?.let { parentTitleRes ->
        val parentTitle = stringResource(parentTitleRes)
        if (entry.parentMode == SearchParentMode.REQUIRE_OFF) {
            stringResource(R.string.search_requires_parent_switch_off, parentTitle)
        } else {
            stringResource(R.string.search_requires_parent_switch, parentTitle)
        }
    } ?: entry.conditionNoteRes?.let { stringResource(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(entry.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = LocalZToolColorScheme.current.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            entry.summaryRes?.let { summaryRes ->
                Text(
                    text = stringResource(summaryRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalZToolColorScheme.current.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            annotation?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZToolColorScheme.current.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = ZToolIcons.ArrowForward,
            contentDescription = null,
            tint = LocalZToolColorScheme.current.onSurfaceVariant
        )
    }
}
