package com.qimian233.ztool.screens.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.R
import com.qimian233.ztool.ui.components.ZToolCard
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.viewmodel.LauncherPreviewConfig

/** Tallest mock frame; the device aspect ratio is kept below this cap. */
private val MaxPreviewHeight = 320.dp
private val PreviewCornerRadius = 20.dp

// Page-box bands measured on a ZUI tablet screenshot (2590x1619). The square side is solved
// from the banded height, so these ratios decide the displayed cell size.

/** Status bar and search-bar reserve above the wallpaper: 160/1619. */
private const val TopBandFraction = 0.099f

/** Dock area below the last row: 331/1619, of which the pill is 195/1619 plus its gaps. */
private const val BottomBandFraction = 0.204f
private const val DockBandFraction = 0.120f
private const val DockBottomGapFraction = 0.024f

/** Side dead space with the wide-grid hook off: 32/2590 on a tablet, far more on phones. */
private const val DefaultSideInsetFractionTablet = 0.012f
private const val DefaultSideInsetFractionPhone = 0.11f
private const val TabletWidthDp = 600f

/** Icon diameter against the cell's short side: 120/188 and 122/184 measured. */
private const val IconToCellFraction = 0.65f
private const val MaxIconToCellFraction = 0.8f

/** Cell aspect above which square mode squares the cell; mirrors LauncherWideGridHook. */
private const val SquareAspectThreshold = 1.2f

/** Label block under the icon and its gap to it, as fractions of the row pitch. */
private const val LabelHeightFraction = 0.14f
private const val LabelGapFraction = 0.10f

/** Dock pill width against its content: gap and padding are half an icon each. */
private const val DockGapToIconFraction = 0.5f

/** Slot carrying the separator between the fixed dock icons and the app icons. */
private const val DockSeparatorIndex = 5

/** Big-folder span, the host 2x2 child grid, and the grid the hook rewrites it to. */
private const val BigFolderSpan = 2
private const val HostChildColumns = 4
private const val HostChildRows = 3
private const val HookedChildColumns = 3
private const val HookedChildRows = 3

/**
 * Host big-folder ratios: `ChildIconScale` is `BigFolderConfig.CHILD_ICON_SCALE`, the other
 * two are read off the hook's DeviceProfile log (folderIcon 158 / icon 190, widgetPad 40).
 */
private const val ChildIconScale = 0.8235f
private const val FolderIconToIcon = 0.832f
private const val WidgetPaddingToIcon = 0.21f

/** Background corner radius against its height; the host uses a fixed dimension. */
private const val FolderBackgroundRadiusFraction = 0.14f

/** The launcher's "app updated" dot is blue by definition, regardless of the theme. */
private val UpdateDotBlue = Color(0xFF3B82F6)

private const val PageDotCount = 3

/**
 * Top card of the launcher settings page: a graphical preview of the current desktop
 * style driven by [config].
 */
@Composable
fun LauncherPreviewCard(config: LauncherPreviewConfig, modifier: Modifier = Modifier) {
    val colors = LocalZToolColorScheme.current
    ZToolCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.launcher_preview_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            LauncherScreenPreview(config = config, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Cell box and side padding of the mock frame, in mock dp. */
private class MockGrid(
    val cellWidth: Dp,
    val cellHeight: Dp,
    val sidePadding: Dp,
    val iconSize: Dp
)

/**
 * Mirrors the launcher's own cell math: the page box is the whole frame, the top and bottom
 * bands are its paddings, cells are width- and height-driven, and square mode solves the
 * cell height as the square side, padded symmetrically so the grid stays centred.
 */
private fun mockGrid(
    config: LauncherPreviewConfig,
    frameWidth: Dp,
    frameHeight: Dp,
    screenWidth: Float
): MockGrid {
    val columns = config.columns.coerceAtLeast(1)
    val rows = config.rows.coerceAtLeast(1)
    val hostPadding = when {
        config.wideGrid -> frameWidth * (config.wideGridSideInset / screenWidth)
        screenWidth >= TabletWidthDp -> frameWidth * DefaultSideInsetFractionTablet
        else -> frameWidth * DefaultSideInsetFractionPhone
    }
    val hostCellWidth = ((frameWidth - hostPadding * 2) / columns).coerceAtLeast(1.dp)
    val hostCellHeight =
        (frameHeight * (1f - TopBandFraction - BottomBandFraction) / rows).coerceAtLeast(1.dp)
    // Only a clearly wide cell is squared; a mild rectangle keeps the host's per-axis cells.
    val square = config.squareCells && hostCellWidth / hostCellHeight > SquareAspectThreshold
    val cellHeight = hostCellHeight
    val cellWidth = if (square) hostCellHeight else hostCellWidth
    val sidePadding = if (square) {
        maxOf(0.dp, (frameWidth - cellWidth * columns) / 2)
    } else {
        hostPadding
    }
    val shortSide = minOf(cellWidth, cellHeight)
    val iconSize = (shortSide * IconToCellFraction * config.iconScale)
        .coerceAtMost(shortSide * MaxIconToCellFraction)
    return MockGrid(cellWidth, cellHeight, sidePadding, iconSize)
}

/**
 * Draws the desktop mock: grid cells, one 2x2 big folder, labels, update dots and the dock.
 * Geometry is proportional only, so no launcher-side pixel value is required.
 */
@Composable
fun LauncherScreenPreview(config: LauncherPreviewConfig, modifier: Modifier = Modifier) {
    val colors = LocalZToolColorScheme.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat().coerceAtLeast(1f)
    val screenHeight = configuration.screenHeightDp.toFloat().coerceAtLeast(1f)
    val frameAspect = screenWidth / screenHeight

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val frameHeight = minOf(MaxPreviewHeight, maxWidth / frameAspect)
        val frameWidth = frameHeight * frameAspect
        val shape = RoundedCornerShape(PreviewCornerRadius)
        val grid = mockGrid(config, frameWidth, frameHeight, screenWidth)
        val dockBand = frameHeight * DockBandFraction
        val dockBottomGap = frameHeight * DockBottomGapFraction

        Column(
            modifier = Modifier
                .width(frameWidth)
                .height(frameHeight)
                .clip(shape)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.outlineVariant, shape)
        ) {
            PageDots(modifier = Modifier.fillMaxWidth().height(frameHeight * TopBandFraction))
            WorkspaceGrid(
                config = config,
                grid = grid,
                colors = colors
            )
            if (config.dockVisible) {
                Spacer(
                    modifier = Modifier.height(
                        frameHeight * BottomBandFraction - dockBand - dockBottomGap
                    )
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(dockBand),
                    contentAlignment = Alignment.Center
                ) {
                    DockRow(
                        colors = colors,
                        iconSize = grid.iconSize,
                        frameWidth = frameWidth,
                        dockIconCount = config.dockIconCount,
                        backgroundVisible = config.dockBackgroundVisible
                    )
                }
                Spacer(modifier = Modifier.height(dockBottomGap))
            } else {
                Spacer(modifier = Modifier.height(frameHeight * BottomBandFraction))
            }
        }
    }
}

@Composable
private fun WorkspaceGrid(
    config: LauncherPreviewConfig,
    grid: MockGrid,
    colors: ColorScheme
) {
    val showLabel = !config.noLabel
    Box(
        modifier = Modifier.fillMaxWidth().height(grid.cellHeight * config.rows),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.padding(horizontal = grid.sidePadding)) {
            Column {
                repeat(config.rows) { row ->
                    Row {
                        repeat(config.columns) { column ->
                            Box(
                                modifier = Modifier.size(grid.cellWidth, grid.cellHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                // The top-left 2x2 cells are covered by the big folder below.
                                if (row >= BigFolderSpan || column >= BigFolderSpan) {
                                    PreviewAppIcon(
                                        iconSize = grid.iconSize,
                                        cellHeight = grid.cellHeight,
                                        showLabel = showLabel,
                                        showDot = config.bluePointVisible &&
                                            (row * config.columns + column) % 3 == 0,
                                        colors = colors
                                    )
                                }
                            }
                        }
                    }
                }
            }
            BigFolderItem(
                config = config,
                grid = grid,
                showLabel = showLabel,
                colors = colors
            )
        }
    }
}

/**
 * Draws one 2x2 big folder in the grid, switching between the stock rectangular
 * 4x3 layout and the hooked square 3x3 layout with native top label.
 */
@Composable
private fun BigFolderItem(
    config: LauncherPreviewConfig,
    grid: MockGrid,
    showLabel: Boolean,
    colors: ColorScheme
) {
    val cell = grid.cellWidth
    val pitchY = grid.cellHeight
    val slotW = cell * BigFolderSpan
    val slotH = pitchY * BigFolderSpan
    val icon = grid.iconSize
    val widgetPadding = icon * WidgetPaddingToIcon
    val availW = (slotW - widgetPadding * 2).coerceAtLeast(1.dp)
    val availH = (slotH - widgetPadding * 2).coerceAtLeast(1.dp)

    val childColumns: Int
    val childRows: Int
    val backgroundWidth: Dp
    val backgroundHeight: Dp
    val backgroundX: Dp
    val backgroundY: Dp
    val child: Dp
    val gapH: Dp
    val gapV: Dp
    val childLeft: Dp
    val childTop: Dp

    if (config.bigFolderHooked) {
        childColumns = HookedChildColumns
        childRows = HookedChildRows
        val side = minOf(availW, availH)
        backgroundWidth = side
        backgroundHeight = side
        backgroundX = (slotW - side) / 2
        backgroundY = (slotH - side) / 2

        val outerPad = side * 0.085f
        val headerH = if (showLabel) side * 0.19f else 0.dp
        val gridTop = maxOf(outerPad, headerH)
        val availGridH = (side - outerPad - gridTop).coerceAtLeast(side * 0.45f)
        val gap = availGridH * (0.055f / 0.83f)
        child = minOf((availGridH - gap * 2) / 3, (side - outerPad * 2 - gap * 2) / 3)
        gapH = gap
        gapV = gap
        val gridExtent = child * childColumns + gap * (childColumns - 1)
        childLeft = backgroundX + (side - gridExtent) / 2
        childTop = backgroundY + gridTop + (availGridH - gridExtent) / 2
    } else {
        childColumns = HostChildColumns
        childRows = HostChildRows
        backgroundWidth = availW
        backgroundHeight = availH
        backgroundX = widgetPadding
        backgroundY = widgetPadding

        val headerH = if (showLabel) backgroundHeight * 0.18f else 0.dp
        child = minOf(
            icon * FolderIconToIcon * ChildIconScale,
            (availW / (childColumns + 0.8f)),
            ((availH - headerH) / (childRows + 0.8f))
        ).coerceAtLeast(1.dp)
        gapH = maxOf(0.dp, (availW - child * childColumns) / (childColumns + 1))
        gapV = maxOf(0.dp, (availH - headerH - child * childRows) / (childRows + 1))
        val gridW = child * childColumns + gapH * (childColumns - 1)
        val gridH = child * childRows + gapV * (childRows - 1)
        childLeft = backgroundX + (availW - gridW) / 2
        childTop = backgroundY + headerH + (availH - headerH - gridH) / 2
    }

    val labelWidth = backgroundWidth * 0.46f
    val labelHeight = (backgroundHeight * 0.06f).coerceIn(1.5.dp, 4.dp)

    Box(modifier = Modifier.size(slotW, slotH)) {
        Box(
            modifier = Modifier
                .offset(x = backgroundX, y = backgroundY)
                .size(backgroundWidth, backgroundHeight)
                .clip(RoundedCornerShape(backgroundHeight * FolderBackgroundRadiusFraction))
                .background(colors.onSurfaceVariant.copy(alpha = 0.22f))
        )
        if (showLabel) {
            Box(
                modifier = Modifier
                    .offset(
                        x = backgroundX + (backgroundWidth - labelWidth) / 2,
                        y = backgroundY + backgroundHeight * 0.05f
                    )
                    .width(labelWidth)
                    .height(labelHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.45f))
            )
        }
        repeat(childRows) { row ->
            repeat(childColumns) { column ->
                Box(
                    modifier = Modifier
                        .offset(
                            x = childLeft + (child + gapH) * column,
                            y = childTop + (child + gapV) * row
                        )
                        .size(child)
                        .clip(RoundedCornerShape(child * 0.26f))
                        .background(colors.primary.copy(alpha = 0.85f))
                )
            }
        }
    }
}

/** One desktop item: the icon plate, and under it the update dot ahead of the label. */
@Composable
private fun PreviewAppIcon(
    iconSize: Dp,
    cellHeight: Dp,
    showLabel: Boolean,
    showDot: Boolean,
    colors: ColorScheme
) {
    val labelHeight = (cellHeight * LabelHeightFraction).coerceIn(1.5.dp, 5.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(RoundedCornerShape(iconSize * 0.26f))
                .background(colors.primary.copy(alpha = 0.85f))
        )
        if (showLabel || showDot) {
            Spacer(modifier = Modifier.height(cellHeight * LabelGapFraction))
            Row(
                modifier = Modifier.width(iconSize * 0.9f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showDot) {
                    Box(
                        modifier = Modifier
                            .size((cellHeight * 0.16f).coerceIn(2.dp, 6.dp))
                            .clip(CircleShape)
                            .background(UpdateDotBlue)
                    )
                    if (showLabel) Spacer(modifier = Modifier.width(2.dp))
                }
                if (showLabel) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(labelHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.onSurfaceVariant.copy(alpha = 0.45f))
                    )
                }
            }
        }
    }
}

/**
 * Centered, content-hugging dock row, matching the launcher's own dock layout.
 * [backgroundVisible] paints the plate and divider; both stay in the layout when hidden,
 * because the host draws them behind the icons and the icons must not move.
 */
@Composable
private fun DockRow(
    colors: ColorScheme,
    iconSize: Dp,
    frameWidth: Dp,
    dockIconCount: Int,
    backgroundVisible: Boolean
) {
    val gap = iconSize * DockGapToIconFraction
    val fittingIconSize = minOf(
        iconSize,
        frameWidth / (dockIconCount * (1f + DockGapToIconFraction) + DockGapToIconFraction)
    )
    val plateColor =
        if (backgroundVisible) colors.onSurfaceVariant.copy(alpha = 0.22f) else Color.Transparent
    val dividerColor =
        if (backgroundVisible) colors.onSurfaceVariant.copy(alpha = 0.5f) else Color.Transparent
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(fittingIconSize * 0.9f))
            .background(plateColor)
            .padding(horizontal = gap, vertical = gap * 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        repeat(dockIconCount) { index ->
            if (index == DockSeparatorIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(fittingIconSize * 0.8f)
                        .background(dividerColor)
                )
            }
            Box(
                modifier = Modifier
                    .size(fittingIconSize)
                    .clip(RoundedCornerShape(fittingIconSize * 0.26f))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.55f))
            )
        }
    }
}

@Composable
private fun PageDots(modifier: Modifier = Modifier) {
    val colors = LocalZToolColorScheme.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(PageDotCount) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == PageDotCount / 2) {
                            colors.primary
                        } else {
                            colors.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}
