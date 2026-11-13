package com.qimian233.ztool.ui.firstrun

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.R
import com.qimian233.ztool.ui.components.ZToolCard
import com.qimian233.ztool.ui.components.ZToolSettingsDivider
import com.qimian233.ztool.ui.components.ZToolSwitchRow
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme

/** Final first-run page: update source, automatic checks and the pre-release channel. */
@Composable
internal fun FirstrunUpdatePage(
    isGhProxyUpdateEnabled: Boolean,
    isAutoCheckUpdateEnabled: Boolean,
    isPrereleaseUpdateEnabled: Boolean,
    isLastPage: Boolean,
    pageScrollState: ScrollState,
    onGhProxyUpdateChanged: (Boolean) -> Unit,
    onAutoCheckUpdateChanged: (Boolean) -> Unit,
    onPrereleaseUpdateChanged: (Boolean) -> Unit,
    onNext: (Offset) -> Unit,
    onBack: (Offset) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScrollState)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PageHeader(
                title = stringResource(R.string.page_firstrun_update_title),
                subtitle = stringResource(R.string.page_firstrun_update_summary)
            )

            Text(
                text = stringResource(R.string.page_firstrun_update_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant
            )

            ZToolCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    ZToolSwitchRow(
                        title = stringResource(R.string.page_firstrun_update_gh_proxy_title),
                        checked = isGhProxyUpdateEnabled,
                        onCheckedChange = onGhProxyUpdateChanged,
                        icon = Icons.Rounded.Speed
                    )
                    ZToolSettingsDivider()
                    ZToolSwitchRow(
                        title = stringResource(R.string.page_firstrun_update_auto_check_title),
                        checked = isAutoCheckUpdateEnabled,
                        onCheckedChange = onAutoCheckUpdateChanged,
                        icon = Icons.Rounded.Update
                    )
                    ZToolSettingsDivider()
                    ZToolSwitchRow(
                        title = stringResource(R.string.page_firstrun_update_prerelease_title),
                        summary = stringResource(R.string.page_firstrun_update_prerelease_summary),
                        checked = isPrereleaseUpdateEnabled,
                        onCheckedChange = onPrereleaseUpdateChanged,
                        icon = Icons.Rounded.Science
                    )
                }
            }
        }

        BottomActionBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            nextText = stringResource(
                if (isLastPage) R.string.page_firstrun_agreement_confirm else R.string.page_firstrun_next_step
            ),
            nextEnabled = true,
            onNext = onNext,
            onDisagree = onBack,
            negativeText = stringResource(R.string.page_firstrun_previous),
            negativeTonal = true
        )
    }
}
