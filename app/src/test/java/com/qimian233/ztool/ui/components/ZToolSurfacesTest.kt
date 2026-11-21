package com.qimian233.ztool.ui.components

import androidx.compose.ui.graphics.Color
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.ZToolThemeSettings
import com.qimian233.ztool.ui.theme.buildZToolColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ZToolSurfacesTest {

    @Test
    fun resolveMiuixCardContentColor_darkTheme_resolvesExpectedContentColors() {
        assertMiuixCardContentColors(darkTheme = true)
    }

    @Test
    fun resolveMiuixCardContentColor_lightTheme_resolvesExpectedContentColors() {
        assertMiuixCardContentColors(darkTheme = false)
    }

    private fun assertMiuixCardContentColors(darkTheme: Boolean) {
        val colorScheme = buildZToolColorScheme(
            settings = ZToolThemeSettings(
                frontendStyle = FrontendStyle.Miuix,
                dynamicColorEnabled = false
            ),
            darkTheme = darkTheme,
            systemSeed = null
        )

        assertEquals(
            colorScheme.onSurface,
            resolveMiuixCardContentColor(
                colorScheme = colorScheme,
                containerColor = Color.Unspecified,
                fallbackContentColor = colorScheme.onBackground
            )
        )
        assertEquals(
            colorScheme.onErrorContainer,
            resolveMiuixCardContentColor(
                colorScheme = colorScheme,
                containerColor = colorScheme.errorContainer,
                fallbackContentColor = colorScheme.onBackground
            )
        )
        assertEquals(
            colorScheme.onBackground,
            resolveMiuixCardContentColor(
                colorScheme = colorScheme,
                containerColor = Color.Transparent,
                fallbackContentColor = colorScheme.onBackground
            )
        )
    }
}
