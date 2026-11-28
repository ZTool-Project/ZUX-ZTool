package com.qimian233.ztool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationRailState
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail as MiuixNavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem as MiuixNavigationRailItem
import top.yukonga.miuix.kmp.basic.ScrollBehavior as MiuixScrollBehaviorType
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar

private val LocalMiuixNavigationRowScope = staticCompositionLocalOf<RowScope?> { null }

private val LocalMaterialRailExpanded = staticCompositionLocalOf { false }

private val LocalMiuixTopAppBarScrollBehavior = staticCompositionLocalOf<MiuixScrollBehaviorType?> { null }

enum class ZToolNavigationRailValue {
    Collapsed,
    Expanded
}

@Stable
class ZToolNavigationRailState(initialValue: ZToolNavigationRailValue) {
    var currentValue by mutableStateOf(initialValue)

    val expanded: Boolean
        get() = currentValue == ZToolNavigationRailValue.Expanded

    fun collapse() {
        currentValue = ZToolNavigationRailValue.Collapsed
    }

    fun expand() {
        currentValue = ZToolNavigationRailValue.Expanded
    }

    fun toggle() {
        currentValue = if (expanded) {
            ZToolNavigationRailValue.Collapsed
        } else {
            ZToolNavigationRailValue.Expanded
        }
    }
}

@Composable
fun rememberZToolNavigationRailState(
    initialValue: ZToolNavigationRailValue = ZToolNavigationRailValue.Collapsed
): ZToolNavigationRailState = remember {
    ZToolNavigationRailState(initialValue)
}

fun Modifier.collapseNavigationRailOnPointerDown(
    state: ZToolNavigationRailState
): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        state.collapse()
    }
}

@Composable
fun ZToolScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val useMiuix = LocalZToolThemeSpec.current.style == FrontendStyle.Miuix
    val scrollBehavior = if (useMiuix) MiuixScrollBehavior() else null
    val scaffoldModifier = scrollBehavior?.let {
        modifier.nestedScroll(it.nestedScrollConnection)
    } ?: modifier

    CompositionLocalProvider(LocalMiuixTopAppBarScrollBehavior provides scrollBehavior) {
        if (useMiuix) {
            Box(modifier = Modifier.fillMaxSize()) {
                top.yukonga.miuix.kmp.basic.Scaffold(
                    modifier = scaffoldModifier,
                    topBar = topBar,
                    content = content
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp)
                ) {
                    floatingActionButton()
                }
            }
        } else {
            Scaffold(
                modifier = scaffoldModifier,
                topBar = topBar,
                floatingActionButton = floatingActionButton,
                containerColor = LocalZToolColorScheme.current.background,
                content = content
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZToolTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    addNavIcon : Boolean = true
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        if (addNavIcon) {
            MiuixTopAppBar(
                title = title,
                modifier = modifier,
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = LocalMiuixTopAppBarScrollBehavior.current,
            )
        } else {
            MiuixTopAppBar(
                title = title,
                modifier = modifier,
                actions = actions,
                scrollBehavior = LocalMiuixTopAppBarScrollBehavior.current,
            )
        }

        return
    }

    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        subtitle = {},
        titleHorizontalAlignment = Alignment.Start,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LocalZToolColorScheme.current.surface,
            scrolledContainerColor = LocalZToolColorScheme.current.surfaceContainer
        ),
        modifier = modifier
    )
}

@Composable
fun ZToolNavigationRail(
    modifier: Modifier = Modifier,
    state: ZToolNavigationRailState? = null,
    content: @Composable () -> Unit,
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        val railState = state ?: rememberZToolNavigationRailState()
        val miuixRailState = rememberNavigationRailState(railState.currentValue.toMiuixValue())

        SyncMiuixNavigationRailState(
            zToolState = railState,
            miuixState = miuixRailState
        )

        MiuixNavigationRail(
            modifier = modifier,
            state = miuixRailState
        ) {
            content()
        }
        return
    }

    val railState = state ?: rememberZToolNavigationRailState()
    val materialState = rememberWideNavigationRailState(
        if (railState.expanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed
    )
    LaunchedEffect(railState, materialState) {
        snapshotFlow { railState.expanded }.collectLatest { expanded ->
            if (expanded) materialState.expand() else materialState.collapse()
        }
    }
    CompositionLocalProvider(
        LocalMaterialRailExpanded provides (materialState.targetValue == WideNavigationRailValue.Expanded)
    ) {
        WideNavigationRail(
            state = materialState,
            colors = WideNavigationRailDefaults.colors(
                containerColor = LocalZToolColorScheme.current.surface,
                contentColor = LocalZToolColorScheme.current.onSurface
            ),
            arrangement = Arrangement.Center,
            modifier = modifier
        ) {
            content()
        }
    }
}

@Composable
private fun SyncMiuixNavigationRailState(
    zToolState: ZToolNavigationRailState,
    miuixState: NavigationRailState
) {
    LaunchedEffect(zToolState, miuixState) {
        snapshotFlow { zToolState.currentValue }
            .collectLatest { value ->
                val miuixValue = value.toMiuixValue()
                if (miuixState.currentValue != miuixValue) {
                    when (value) {
                        ZToolNavigationRailValue.Collapsed -> miuixState.collapse()
                        ZToolNavigationRailValue.Expanded -> miuixState.expand()
                    }
                }
            }
    }

    LaunchedEffect(zToolState, miuixState) {
        snapshotFlow { miuixState.currentValue }
            .collectLatest { value ->
                val zToolValue = value.toZToolValue()
                if (zToolState.currentValue != zToolValue) {
                    zToolState.currentValue = zToolValue
                }
            }
    }
}

private fun ZToolNavigationRailValue.toMiuixValue(): NavigationRailValue {
    return when (this) {
        ZToolNavigationRailValue.Collapsed -> NavigationRailValue.Collapsed
        ZToolNavigationRailValue.Expanded -> NavigationRailValue.Expanded
    }
}

private fun NavigationRailValue.toZToolValue(): ZToolNavigationRailValue {
    return when (this) {
        NavigationRailValue.Collapsed -> ZToolNavigationRailValue.Collapsed
        NavigationRailValue.Expanded -> ZToolNavigationRailValue.Expanded
    }
}

@Composable
fun ZToolNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        MiuixNavigationRailItem(
            selected = selected,
            onClick = onClick,
            icon = icon,
            label = label,
        )
        return
    }

    WideNavigationRailItem(
        railExpanded = LocalMaterialRailExpanded.current,
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp)
            )
        },
        label = { Text(label) },
        enabled = enabled,
        colors = WideNavigationRailItemDefaults.colors(
            selectedIconColor = LocalZToolColorScheme.current.onSecondaryContainer,
            selectedTextColorTopIconPosition = LocalZToolColorScheme.current.onSurface,
            selectedTextColorStartIconPosition = LocalZToolColorScheme.current.onSurface,
            selectedIndicatorColor = LocalZToolColorScheme.current.secondaryContainer,
            unselectedIconColor = LocalZToolColorScheme.current.onSurfaceVariant,
            unselectedTextColor = LocalZToolColorScheme.current.onSurfaceVariant
        ),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZToolNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        top.yukonga.miuix.kmp.basic.NavigationBar(
            modifier = modifier.navigationBarsPadding().height(64.dp),
            color = LocalZToolColorScheme.current.surface,
        ) {
            CompositionLocalProvider(LocalMiuixNavigationRowScope provides this) { content() }
        }
        return
    }

    // ShortNavigationBar's MeasurePolicy constrains items against incoming minWidth;
    // Box resets min constraints so caller fillMaxWidth() cannot stretch every item to bar width.
    Box(modifier = modifier) {
        ShortNavigationBar(
            containerColor = LocalZToolColorScheme.current.surface,
            contentColor = LocalZToolColorScheme.current.onSurface,
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZToolNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        val rowScope = LocalMiuixNavigationRowScope.current ?: return
        with(rowScope) {
            MiuixNavigationBarItem(
                selected = selected,
                onClick = onClick,
                icon = icon,
                label = label,
                modifier = modifier
                    .weight(1f)
                    .width(80.dp)
                    .height(72.dp),
                enabled = enabled
            )
        }
        return
    }

    ShortNavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp)
            )
        },
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        colors = ShortNavigationBarItemDefaults.colors(
            selectedIconColor = LocalZToolColorScheme.current.onSecondaryContainer,
            selectedTextColorTopIconPosition = LocalZToolColorScheme.current.onSurface,
            selectedTextColorStartIconPosition = LocalZToolColorScheme.current.onSurface,
            selectedIndicatorColor = LocalZToolColorScheme.current.secondaryContainer,
            unselectedIconColor = LocalZToolColorScheme.current.onSurfaceVariant,
            unselectedTextColor = LocalZToolColorScheme.current.onSurfaceVariant
        )
    )
}
