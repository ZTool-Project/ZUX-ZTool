package com.qimian233.ztool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalIsPlatformDialog
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

val DIALOG_BUTTON_HORIZONTAL_ARRANGEMENT = 0
val DIALOG_BUTTON_VERTICAL_ARRANGEMENT = 1

val LocalDialogButtonModifier = staticCompositionLocalOf<Modifier> { Modifier }

@Composable
fun ZToolDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    buttonArrangement: Int = DIALOG_BUTTON_HORIZONTAL_ARRANGEMENT
) {
    val isPlatformDialog = LocalIsPlatformDialog.current

    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        val miuixContent = @Composable {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                if (title != null) {
                    ProvideTextStyle(MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Medium)) {
                        CompositionLocalProvider(LocalContentColor provides LocalZToolColorScheme.current.onSurface) {
                            title()
                        }
                    }
                }
                if (text != null) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(top = if (title == null) 0.dp else 16.dp)
                    ) {
                        ProvideTextStyle(MiuixTheme.textStyles.body1) {
                            CompositionLocalProvider(LocalContentColor provides LocalZToolColorScheme.current.onSurfaceVariant) {
                                text()
                            }
                        }
                    }
                }
                if (buttonArrangement == DIALOG_BUTTON_HORIZONTAL_ARRANGEMENT) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (dismissButton != null) {
                            Box(Modifier.weight(1f)) {
                                CompositionLocalProvider(
                                    LocalDialogButtonModifier provides Modifier.fillMaxWidth()
                                ) {
                                    dismissButton()
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(
                                LocalDialogButtonModifier provides Modifier.fillMaxWidth()
                            ) {
                                confirmButton()
                            }
                        }
                    }
                } else {
                    Column (
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            CompositionLocalProvider(
                                LocalDialogButtonModifier provides Modifier.fillMaxWidth()
                            ) {
                                confirmButton()
                            }
                        }
                        if (dismissButton != null) {
                            Box(Modifier.fillMaxWidth()) {
                                CompositionLocalProvider(
                                    LocalDialogButtonModifier provides Modifier.fillMaxWidth()
                                ) {
                                    dismissButton()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isPlatformDialog) {
            MiuixSurface(
                color = LocalZToolColorScheme.current.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp)
            ) {
                miuixContent()
            }
        } else {
            // Miuix's OverlayDialog caps content width at DialogDefaults.MaxWidth (420.dp)
            // and measures its Column children against the remaining height, so tall content
            // compresses the trailing buttons. Render the same surface in a platform window
            // capped at 560.dp, using a weighted text slot so tall or scrollable bodies
            // receive finite height bounds without compressing trailing buttons.
            androidx.compose.ui.window.Dialog(
                onDismissRequest = onDismissRequest,
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                MiuixSurface(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    color = LocalZToolColorScheme.current.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    miuixContent()
                }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        title = title,
        text = text,
        dismissButton = dismissButton,
        containerColor = LocalZToolColorScheme.current.surface,
        titleContentColor = LocalZToolColorScheme.current.onSurface,
        textContentColor = LocalZToolColorScheme.current.onSurfaceVariant
    )
}

@Suppress("unused")
@Composable
fun ZToolDialogSurface(
    content: @Composable () -> Unit
) {
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        val contentColor = LocalZToolColorScheme.current.onSurface
        MiuixSurface(
            color = LocalZToolColorScheme.current.surface,
            contentColor = contentColor
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                content()
            }
        }
        return
    }

    Surface(
        color = LocalZToolColorScheme.current.surface,
        content = content
    )
}
