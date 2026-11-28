package com.qimian233.ztool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dim strength of [SearchScrim], as an opaque fraction of black. Tune here. */
private const val ScrimAlpha = 0.60f

private val ScrimColor = Color.Black.copy(alpha = ScrimAlpha)

/** Screen-space surface cutout excluded from the [SearchScrim] dim fill. */
@Immutable
data class SearchCutout(
    val bounds: Rect,
    val shape: Shape
)

/**
 * Shell-owned handle for the docked search dim layer. Screens publish the search
 * bounds, shaped cutouts, visibility, and dismiss callback; the shell draws [SearchScrim].
 */
@Stable
class SearchOverlayState {
    internal var bounds: Rect? by mutableStateOf(null)
    internal val cutouts = mutableStateMapOf<Any, SearchCutout>()
    internal var visible: Boolean by mutableStateOf(false)
    internal var onDismiss: () -> Unit = {}

    internal fun updateCutout(key: Any, bounds: Rect, shape: Shape) {
        if (bounds.isEmpty) {
            cutouts.remove(key)
            return
        }
        val current = cutouts[key]
        if (current == null || current.bounds != bounds || current.shape != shape) {
            cutouts[key] = SearchCutout(bounds = bounds, shape = shape)
        }
    }

    internal fun removeCutout(key: Any) {
        cutouts.remove(key)
    }
}

/**
 * The [SearchOverlayState] provided by the app shell. Screens composed outside the shell
 * read a throwaway instance, so search still works without the dim layer.
 */
val LocalSearchOverlayState = staticCompositionLocalOf { SearchOverlayState() }

/** Registers this element's root bounds and [shape] as an undimmed cutout in [SearchScrim]. */
@Composable
fun Modifier.searchOverlayCutout(shape: Shape): Modifier {
    val overlayState = LocalSearchOverlayState.current
    val key = remember { Any() }
    DisposableEffect(overlayState, key) {
        onDispose { overlayState.removeCutout(key) }
    }
    return onGloballyPositioned { coordinates ->
        overlayState.updateCutout(key, coordinates.boundsInRoot(), shape)
    }
}

/**
 * Dim layer for the docked search, hosted last in the app shell so it covers navigation chrome.
 * Draws the dim fill clipped around registered [SearchOverlayState.cutouts] and uses four
 * transparent dismiss bands around [SearchOverlayState.bounds] so search input stays interactive.
 */
@Composable
fun SearchScrim(
    state: SearchOverlayState,
    modifier: Modifier = Modifier
) {
    // The outer box is measured even while hidden, so the cutout is already right on
    // the first visible frame.
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val cutoutPath = remember { Path() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerOrigin = coordinates.boundsInRoot().topLeft
            }
    ) {
        AnimatedVisibility(
            visible = state.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val cutouts = state.cutouts.values.toList()
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            cutoutPath.rewind()
                            for (cutout in cutouts) {
                                val local = cutout.bounds.translate(
                                    -containerOrigin.x,
                                    -containerOrigin.y
                                )
                                if (!local.isEmpty) {
                                    val outline = cutout.shape.createOutline(
                                        size = local.size,
                                        layoutDirection = layoutDirection,
                                        density = density
                                    )
                                    addOutlineAt(cutoutPath, outline, local.topLeft)
                                }
                            }
                            if (cutoutPath.isEmpty) {
                                drawRect(ScrimColor)
                            } else {
                                clipPath(cutoutPath, clipOp = ClipOp.Difference) {
                                    drawRect(ScrimColor)
                                }
                            }
                        }
                )

                val hole = resolveScrimHole(state.bounds, containerOrigin, maxWidth, maxHeight)
                val onDismiss = { state.onDismiss() }
                if (hole == null) {
                    DismissBand(modifier = Modifier.fillMaxSize(), onDismiss = onDismiss)
                } else {
                    DismissBand(modifier = Modifier.size(maxWidth, hole.top), onDismiss = onDismiss)
                    DismissBand(
                        modifier = Modifier
                            .offset(y = hole.bottom)
                            .size(maxWidth, maxHeight - hole.bottom),
                        onDismiss = onDismiss
                    )
                    DismissBand(
                        modifier = Modifier
                            .offset(y = hole.top)
                            .size(hole.left, hole.bottom - hole.top),
                        onDismiss = onDismiss
                    )
                    DismissBand(
                        modifier = Modifier
                            .offset(x = hole.right, y = hole.top)
                            .size(maxWidth - hole.right, hole.bottom - hole.top),
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

private fun addOutlineAt(
    path: Path,
    outline: Outline,
    offset: Offset
) {
    when (outline) {
        is Outline.Rectangle -> path.addRect(outline.rect.translate(offset))
        is Outline.Rounded -> path.addRoundRect(outline.roundRect.translate(offset))
        is Outline.Generic -> path.addPath(outline.path, offset)
    }
}

/** The cutout rectangle in scrim-local coordinates, clamped to the scrim's own size. */
@Composable
private fun resolveScrimHole(
    bounds: Rect?,
    containerOrigin: Offset,
    width: Dp,
    height: Dp
): ScrimHole? {
    if (bounds == null) return null
    val density = LocalDensity.current
    val local = bounds.translate(-containerOrigin.x, -containerOrigin.y)
    val left = with(density) { local.left.toDp() }.coerceIn(0.dp, width)
    val top = with(density) { local.top.toDp() }.coerceIn(0.dp, height)
    val right = with(density) { local.right.toDp() }.coerceIn(left, width)
    val bottom = with(density) { local.bottom.toDp() }.coerceIn(top, height)
    return ScrimHole(left = left, top = top, right = right, bottom = bottom)
}

private data class ScrimHole(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp
)

@Composable
private fun DismissBand(
    modifier: Modifier,
    onDismiss: () -> Unit
) {
    Box(
        modifier = modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { onDismiss() }
    )
}
