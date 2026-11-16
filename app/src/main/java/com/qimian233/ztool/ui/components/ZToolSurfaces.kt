package com.qimian233.ztool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZToolCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalZToolColorScheme.current.primary
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        InfiniteProgressIndicator(
            modifier = modifier,
            color = color
        )
    } else {
        LoadingIndicator(
            modifier = modifier,
            color = color
        )
    }
}

@Composable
fun ZToolPageSurface(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = contentAlignment,
            content = content
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalZToolColorScheme.current.background),
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
fun ZToolCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    defaultElevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        val actualColor = when (containerColor) {
            Color.Unspecified -> LocalZToolColorScheme.current.surfaceContainer
            else -> containerColor
        }
        top.yukonga.miuix.kmp.basic.Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = actualColor
        ) {
            content()
        }
        return
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (containerColor == Color.Unspecified) Color.Transparent else containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = defaultElevation)
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZToolLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        LinearProgressIndicator(progress = progress, modifier = modifier)
    } else {
        LinearWavyProgressIndicator(progress = progress, modifier = modifier)
    }
}
