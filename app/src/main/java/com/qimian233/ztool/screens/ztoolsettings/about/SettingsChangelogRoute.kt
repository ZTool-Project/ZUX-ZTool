package com.qimian233.ztool.screens.ztoolsettings.about

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.R
import com.qimian233.ztool.data.changelog.ChangelogEntry
import com.qimian233.ztool.data.changelog.ChangelogRepository
import com.qimian233.ztool.ui.components.ZToolCard
import com.qimian233.ztool.ui.components.ZToolMarkdownText
import com.qimian233.ztool.ui.components.ZToolPageSurface
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme

@Composable
fun SettingsChangelogRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember(context) { ChangelogRepository(context).loadEntries() }

    ZToolScaffold(
        topBar = {
            ZToolTopAppBar(
                title = stringResource(R.string.page_settings_about_changelog_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        ZToolPageSurface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 960.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 32.dp)
            ) {
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.page_settings_about_changelog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalZToolColorScheme.current.onSurfaceVariant
                    )
                } else {
                    ChangelogEntryList(entries)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ChangelogEntryList(entries: List<ChangelogEntry>) {
    val expansion = remember(entries) { mutableStateMapOf<String, Boolean>() }
    Column(modifier = Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            val expanded = expansion[entry.version] ?: (index == 0)
            ChangelogEntryCard(
                entry = entry,
                expanded = expanded,
                onToggle = { expansion[entry.version] = !expanded }
            )
            if (index != entries.lastIndex) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ChangelogEntryCard(
    entry: ChangelogEntry,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    ZToolCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.version,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LocalZToolColorScheme.current.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = LocalZToolColorScheme.current.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
            AnimatedVisibility(visible = expanded) {
                ZToolMarkdownText(
                    markdown = entry.markdown,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}
