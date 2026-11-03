package com.qimian233.ztool.screens.ztoolsettings.about

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.R
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightContainerMarker
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.ZToolPageSurface
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolTopAppBar

private data class OpenSourceProject(
    @StringRes val nameRes: Int,
    @StringRes val licenseRes: Int,
    @StringRes val summaryRes: Int,
    val url: String
)

private const val UNFUCK_ZUI_URL = "https://github.com/dantmnf/UnfuckZUI"
private const val ZUXOS_PLUS_URL = "https://github.com/morannlx/me.inkdye.zuxos"

// hunshcn/gh-proxy is the upstream project; gh-proxy.com is one of its deployments.
private const val GH_PROXY_URL = "https://github.com/hunshcn/gh-proxy"

private val openSourceProjects = listOf(
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_androidx_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_androidx_summary,
        url = "https://github.com/androidx/androidx"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_kotlin_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_kotlin_summary,
        url = "https://github.com/JetBrains/kotlin"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_miuix_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_miuix_summary,
        url = "https://github.com/YuKongA/miuix"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_material_color_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_material_color_summary,
        url = "https://github.com/material-foundation/material-color-utilities"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_libxposed_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_libxposed_summary,
        url = "https://github.com/libxposed/api"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_dexkit_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_dexkit_summary,
        url = "https://github.com/LuckyPray/DexKit"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_gson_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_gson_summary,
        url = "https://github.com/google/gson"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_commonmark_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_bsd_2_clause,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_commonmark_summary,
        url = "https://github.com/commonmark/commonmark-java"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_lunar_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_mit,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_lunar_summary,
        url = "https://github.com/6tail/lunar-java"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_tinypinyin_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_tinypinyin_summary,
        url = "https://github.com/promeg/TinyPinyin"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_hidden_api_bypass_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_hidden_api_bypass_summary,
        url = "https://github.com/LSPosed/AndroidHiddenApiBypass"
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_gh_proxy_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_mit,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_gh_proxy_summary,
        url = GH_PROXY_URL
    ),
    OpenSourceProject(
        nameRes = R.string.page_settings_about_acknowledgements_oss_leakcanary_name,
        licenseRes = R.string.page_settings_about_acknowledgements_license_apache_2_0,
        summaryRes = R.string.page_settings_about_acknowledgements_oss_leakcanary_summary,
        url = "https://github.com/square/leakcanary"
    )
)

@Composable
fun SettingsAcknowledgementsRoute(
    onBack: () -> Unit,
    targetId: String? = null
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val highlightRegistry = remember { HighlightAnchorRegistry() }

    HighlightController(
        highlightTargetId = targetId,
        scrollState = scrollState,
        registry = highlightRegistry,
        onConsumed = { }
    ) {
        SettingsAcknowledgementsScreen(
            scrollState = scrollState,
            highlightRegistry = highlightRegistry,
            onBack = onBack,
            onOpenUrl = { url -> openExternalLink(context, url) }
        )
    }
}

@Composable
private fun SettingsAcknowledgementsScreen(
    scrollState: ScrollState,
    highlightRegistry: HighlightAnchorRegistry,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    ZToolScaffold(
        topBar = {
            ZToolTopAppBar(
                title = stringResource(R.string.page_settings_about_acknowledgements_title),
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
                    .verticalScroll(scrollState)
                    .padding(horizontal = 32.dp, vertical = 32.dp)
            ) {
                HighlightContainerMarker(registry = highlightRegistry)
                Spacer(modifier = Modifier.height(24.dp))
                AboutSectionCard(
                    title = stringResource(R.string.page_settings_about_acknowledgements_special_thanks_title),
                    itemCount = 2
                ) { getModifier ->
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_credits_unfuck_zui),
                        summary = stringResource(R.string.page_settings_about_unfuckzui_summary),
                        trailingLabel = stringResource(R.string.page_settings_about_acknowledgements_license_apache_2_0),
                        onClick = { onOpenUrl(UNFUCK_ZUI_URL) },
                        highlightKey = "about_credit_unfuck_zui",
                        modifier = getModifier(0)
                    )
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_credits_zuxos_plus),
                        summary = stringResource(R.string.page_settings_about_zuxos_plus_summary),
                        trailingLabel = stringResource(R.string.page_settings_about_acknowledgements_license_unstated_label),
                        onClick = { onOpenUrl(ZUXOS_PLUS_URL) },
                        highlightKey = "about_credit_zuxos_plus",
                        modifier = getModifier(1)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                AboutSectionCard(
                    title = stringResource(R.string.page_settings_about_acknowledgements_open_source_title),
                    itemCount = openSourceProjects.size
                ) { getModifier ->
                    openSourceProjects.forEachIndexed { index, project ->
                        AboutActionRow(
                            title = stringResource(project.nameRes),
                            summary = stringResource(project.summaryRes),
                            trailingLabel = stringResource(project.licenseRes),
                            onClick = { onOpenUrl(project.url) },
                            modifier = getModifier(index)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
