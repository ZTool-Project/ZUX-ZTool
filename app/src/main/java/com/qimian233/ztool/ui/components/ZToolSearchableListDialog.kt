package com.qimian233.ztool.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.R

/** Dialog with a search bar and filtered scrollable list for single- or multi-select pickers. */
@Composable
fun <T> ZToolSearchableListDialog(
    title: String?,
    items: List<T>,
    itemKey: (T) -> Any,
    matchesQuery: (T, String) -> Boolean,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    searchPlaceholder: String = stringResource(R.string.common_search_hint),
    headerContent: (@Composable () -> Unit)? = null,
    emptyText: String? = null,
    itemContent: @Composable (T) -> Unit
) {
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val filteredItems = remember(query, items) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) {
            items
        } else {
            items.filter { item -> matchesQuery(item, normalizedQuery) }
        }
    }

    ZToolDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = if (!title.isNullOrBlank()) {
            { Text(text = title, fontWeight = FontWeight.SemiBold) }
        } else {
            null
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ZToolSearchField(
                    state = queryState,
                    placeholder = searchPlaceholder,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )

                headerContent?.invoke()

                if (filteredItems.isEmpty() && !emptyText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(
                            items = filteredItems,
                            key = itemKey
                        ) { item ->
                            itemContent(item)
                        }
                    }
                }
            }
        }
    )
}
