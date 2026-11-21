package com.qimian233.ztool.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.qimian233.ztool.ui.icons.ZToolIcons
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.qimian233.ztool.R
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec

/** A themed search input for content with an inline result list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZToolSearchField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    if (LocalZToolThemeSpec.current.style == FrontendStyle.Miuix) {
        var expanded by remember { mutableStateOf(false) }
        top.yukonga.miuix.kmp.basic.InputField(
            query = state.text.toString(),
            onQueryChange = state::setTextAndPlaceCursorAtEnd,
            onSearch = {
                expanded = false
                focusManager.clearFocus()
                onSearch(it)
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier.fillMaxWidth(),
            label = placeholder
        )
        return
    }

    val searchBarState = rememberSearchBarState()
    val containerColor = SearchBarDefaults.colors().containerColor
    SearchBarDefaults.InputField(
        textFieldState = state,
        searchBarState = searchBarState,
        onSearch = {
            focusManager.clearFocus()
            onSearch(it)
        },
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(ZToolIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (state.text.isNotEmpty()) {
                IconButton(
                    onClick = { state.setTextAndPlaceCursorAtEnd("") },
                    shapes = IconButtonDefaults.shapes()
                ) {
                    Icon(
                        ZToolIcons.Close,
                        contentDescription = stringResource(R.string.common_search_clear_button)
                    )
                }
            }
        },
        colors = SearchBarDefaults.inputFieldColors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor
        )
    )
}
