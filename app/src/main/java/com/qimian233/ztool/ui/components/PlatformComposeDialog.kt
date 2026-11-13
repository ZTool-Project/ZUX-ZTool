package com.qimian233.ztool.ui.components

import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.qimian233.ztool.ui.theme.ZToolTheme

/**
 * Largest width a platform dialog card may take; matches the cap [ZToolDialog] uses for its
 * own dialog window. Dialog windows are wrap-content sized, so without a cap any content
 * using fillMaxWidth would stretch to the full screen width.
 */
private val PlatformDialogMaxWidth = 560.dp

fun createPlatformComposeDialog(
    context: Context,
    cancelable: Boolean = true,
    content: @Composable (Dialog) -> Unit
): Dialog {
    val dialog = Dialog(context)
    val composeView = ComposeView(context).apply {
        bindOwners(context)
        setContent {
            ZToolTheme(isPlatformDialog = true) {
                Box(
                    modifier = Modifier.widthIn(max = PlatformDialogMaxWidth),
                    contentAlignment = Alignment.Center
                ) {
                    content(dialog)
                }
            }
        }
    }

    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.setContentView(composeView)
    dialog.setCancelable(cancelable)
    dialog.setCanceledOnTouchOutside(cancelable)
    return dialog
}

fun showPlatformComposeDialog(
    context: Context,
    cancelable: Boolean = true,
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    content: @Composable (Dialog) -> Unit
): Dialog {
    return createPlatformComposeDialog(
        context = context,
        cancelable = cancelable,
        content = content
    ).also { dialog ->
        dialog.show()
        dialog.window?.setLayout(width, height)
    }
}

private fun ComposeView.bindOwners(context: Context) {
    (context as? LifecycleOwner)?.let(::setViewTreeLifecycleOwner)
    (context as? ViewModelStoreOwner)?.let(::setViewTreeViewModelStoreOwner)
    (context as? SavedStateRegistryOwner)?.let(::setViewTreeSavedStateRegistryOwner)
}
