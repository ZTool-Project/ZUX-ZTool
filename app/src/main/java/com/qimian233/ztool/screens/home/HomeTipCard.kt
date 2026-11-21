package com.qimian233.ztool.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.qimian233.ztool.ui.icons.ZToolIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.data.home.HomeTip
import com.qimian233.ztool.ui.components.ZToolCard
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme

private val WarnLightContainer = Color(0xFFFFE082)
private val WarnLightContent = Color(0xFF4F3900)
private val WarnDarkContainer = Color(0xFF5C4300)
private val WarnDarkContent = Color(0xFFFFE082)

/** Renders the home tip card between the module status card and the system info list. */
@Composable
internal fun TipCard(tip: HomeTip) {
    val colors = LocalZToolColorScheme.current
    val isDark = colors.surface.luminance() < 0.5f
    val containerColor: Color
    val contentColor: Color
    if (tip.hotReloadWarning) {
        containerColor = if (isDark) WarnDarkContainer else WarnLightContainer
        contentColor = if (isDark) WarnDarkContent else WarnLightContent
    } else {
        containerColor = colors.secondaryContainer
        contentColor = colors.onSecondaryContainer
    }

    ZToolCard(
        modifier = Modifier
            .fillMaxWidth()
            .homeCardGutter()
            .cardPressScale()
            .clickable(onClick = {}),
        containerColor = containerColor
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (tip.hotReloadWarning) ZToolIcons.Warning else ZToolIcons.Lightbulb,
                    contentDescription = null,
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(tip.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}
