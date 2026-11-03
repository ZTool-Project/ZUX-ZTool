package com.qimian233.ztool.screens.ztoolsettings.about

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import com.qimian233.ztool.BuildConfig
import com.qimian233.ztool.MainActivity
import com.qimian233.ztool.ModuleActivationProbe
import com.qimian233.ztool.R
import com.qimian233.ztool.data.home.AgreementRepository
import com.qimian233.ztool.data.home.HomeRepository
import com.qimian233.ztool.data.home.HomeTipRepository
import com.qimian233.ztool.data.home.ZToolSource
import com.qimian233.ztool.screens.home.HomeViewModelFactory
import com.qimian233.ztool.ui.components.ExpressiveSectionItems
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightContainerMarker
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.HighlightableSettingRow
import com.qimian233.ztool.ui.components.ZListItem
import com.qimian233.ztool.ui.components.ZToolCard
import com.qimian233.ztool.ui.components.ZToolDialog
import com.qimian233.ztool.ui.components.ZToolPageSurface
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolSettingLeadingAvatar
import com.qimian233.ztool.ui.components.ZToolSettingLeadingIcon
import com.qimian233.ztool.ui.components.ZToolSwitchRow
import com.qimian233.ztool.ui.components.ZToolTextButton
import com.qimian233.ztool.ui.firstrun.AgreementContentCard
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.ui.theme.FrontendStyle
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme
import com.qimian233.ztool.ui.theme.LocalUseAlternativeIcon
import com.qimian233.ztool.ui.theme.LocalZToolThemeSpec
import com.qimian233.ztool.ui.theme.appLogoRes
import com.qimian233.ztool.viewmodel.HomeViewModel
import com.qimian233.ztool.viewmodel.UpdateInfo

@Composable
fun SettingsAboutRoute(
    onBack: () -> Unit,
    onOpenAcknowledgements: () -> Unit,
    onOpenChangelog: () -> Unit,
    targetId: String? = null
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val homeViewModel = remember(activity) {
        val repository = HomeRepository(
            context = context.applicationContext,
            moduleActiveChecker = ModuleActivationProbe::isModuleActive
        )
        ViewModelProvider(
            activity,
            HomeViewModelFactory(
                repository = repository,
                tipRepository = HomeTipRepository(context.applicationContext)
            )
        )[HomeViewModel::class.java]
    }
    val homeState by homeViewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val highlightRegistry = remember { HighlightAnchorRegistry() }

    LaunchedEffect(Unit) {
        homeViewModel.checkAppUpdate()
    }

    HighlightController(
        highlightTargetId = targetId,
        scrollState = scrollState,
        registry = highlightRegistry,
        onConsumed = { }
    ) {
        SettingsAboutScreen(
            onBack = onBack,
            scrollState = scrollState,
            highlightRegistry = highlightRegistry,
            onOpenGithub = { openExternalLink(context, ZToolSource.GITHUB_URL) },
            onOpenGithubIssues = { openExternalLink(context, "${ZToolSource.GITHUB_URL}/issues") },
            onOpenAcknowledgements = onOpenAcknowledgements,
            onOpenChangelog = onOpenChangelog,
            onOpenSocialLink = { link ->
                openExternalLink(
                    context = context,
                    link = link.url,
                    shouldDeterminePackage = link.packageName != null,
                    packageName = link.packageName.orEmpty()
                )
            },
            onCheckUpdate = { homeViewModel.checkAppUpdate(force = true) },
            isCheckingUpdate = homeState.isCheckingAppUpdate,
            updateCheckCompleted = homeState.updateCheckCompleted,
            updateCheckError = homeState.updateCheckError,
            updateInfo = homeState.updateInfo,
            isPrereleaseUpdateEnabled = homeState.isPrereleaseUpdateEnabled,
            onTogglePrereleaseUpdate = { homeViewModel.setPrereleaseUpdateEnabled(it) },
            onOpenUpdate = { url -> openExternalLink(context, url) }
        )
    }
}

@Composable
private fun SettingsAboutScreen(
    scrollState: androidx.compose.foundation.ScrollState,
    highlightRegistry: HighlightAnchorRegistry,
    onBack: () -> Unit,
    onOpenGithub: () -> Unit,
    onOpenGithubIssues: () -> Unit,
    onOpenAcknowledgements: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenSocialLink: (DeveloperSocialLink) -> Unit,
    onCheckUpdate: () -> Unit,
    isCheckingUpdate: Boolean,
    updateCheckCompleted: Boolean,
    updateCheckError: String?,
    updateInfo: UpdateInfo?,
    isPrereleaseUpdateEnabled: Boolean,
    onTogglePrereleaseUpdate: (Boolean) -> Unit,
    onOpenUpdate: (String) -> Unit
) {
    val context = LocalContext.current
    val unknownString = stringResource(R.string.common_unknown)
    val versionName = remember(context) {
        runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName.orEmpty()
        }.getOrDefault(unknownString)
    }
    val commitCount = BuildConfig.GIT_COMMIT_COUNT
    val commitHash = BuildConfig.GIT_COMMIT_HASH
    val updateSummary = when {
        isCheckingUpdate -> stringResource(R.string.common_loading)
        updateInfo != null -> stringResource(
            R.string.page_settings_update_available_version_format,
            updateInfo.versionName,
            updateInfo.versionCode
        )
        updateCheckError != null -> stringResource(
            R.string.page_settings_about_app_update_failed_summary,
            updateCheckError
        )
        updateCheckCompleted -> stringResource(R.string.page_settings_about_app_update_latest_summary)
        else -> stringResource(R.string.page_settings_about_app_update_placeholder_summary)
    }
    val updateRowClick = {
        val available = updateInfo
        if (available != null) {
            onOpenUpdate(available.downloadUrl)
        } else {
            onCheckUpdate()
        }
    }
    val agreementMarkdown = remember(context) {
        AgreementRepository(context).loadAgreementMarkdown()
    }
    var showAgreementDialog by remember { mutableStateOf(false) }
    var selectedDeveloper by remember { mutableStateOf<DeveloperProfile?>(null) }

    ZToolScaffold(
        topBar = {
            ZToolTopAppBar(
                title = stringResource(R.string.page_settings_about_ztool_title),
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
                AboutHeaderCard(versionName, commitCount, commitHash)
                Spacer(modifier = Modifier.height(16.dp))
                AboutSectionCard(stringResource(R.string.page_settings_about_developers_title), DEVELOPER_PROFILES.size) { getModifier ->
                    DEVELOPER_PROFILES.forEachIndexed { index, profile ->
                        AboutActionRow(
                            title = stringResource(profile.nameRes),
                            summary = stringResource(profile.roleRes),
                            onClick = { selectedDeveloper = profile },
                            highlightKey = profile.highlightKey,
                            modifier = getModifier(index),
                            leadingContent = { ZToolSettingLeadingAvatar(profile.avatarRes) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                AboutSectionCard(stringResource(R.string.page_settings_about_project_info_title), 5) { getModifier ->
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_about_view_source_title),
                        summary = null,
                        onClick = onOpenGithub,
                        highlightKey = "about_view_source",
                        modifier = getModifier(0),
                        icon = Icons.Rounded.Code
                    )
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_about_view_issues_title),
                        summary = stringResource(R.string.page_settings_about_view_issues_summary),
                        onClick = onOpenGithubIssues,
                        highlightKey = "about_view_issues",
                        modifier = getModifier(1),
                        icon = Icons.Rounded.BugReport
                    )
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_about_license_title),
                        summary = stringResource(R.string.page_settings_about_license_summary),
                        onClick = {
                            openExternalLink(
                                context,
                                "${ZToolSource.GITHUB_URL}/blob/master/LICENSE"
                            )
                        },
                        modifier = getModifier(2),
                        icon = Icons.Rounded.Gavel
                    )
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_about_acknowledgements_title),
                        summary = stringResource(R.string.page_settings_about_acknowledgements_entry_summary),
                        onClick = onOpenAcknowledgements,
                        highlightKey = "about_acknowledgements",
                        modifier = getModifier(3),
                        icon = Icons.Rounded.Favorite
                    )
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_about_user_agreement_title),
                        summary = stringResource(R.string.page_settings_about_user_agreement_summary),
                        onClick = { showAgreementDialog = true },
                        highlightKey = "about_user_agreement",
                        modifier = getModifier(4),
                        icon = Icons.AutoMirrored.Rounded.Article
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                AboutSectionCard(stringResource(R.string.page_settings_about_app_update_section_title), 3) { getModifier ->
                    AboutSwitchRow(
                        title = stringResource(R.string.page_settings_about_prerelease_update_title),
                        summary = stringResource(R.string.page_settings_about_prerelease_update_summary),
                        checked = isPrereleaseUpdateEnabled,
                        onCheckedChange = onTogglePrereleaseUpdate,
                        highlightKey = "about_prerelease_update",
                        modifier = getModifier(0),
                        icon = Icons.Rounded.Science
                    )
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_about_app_update_title),
                        summary = updateSummary,
                        onClick = updateRowClick,
                        highlightKey = "about_check_update",
                        modifier = getModifier(1),
                        icon = Icons.Rounded.SystemUpdate,
                        showTrailingArrow = true
                    )
                    AboutActionRow(
                        title = stringResource(R.string.page_settings_about_changelog_title),
                        summary = stringResource(R.string.page_settings_about_changelog_summary),
                        onClick = onOpenChangelog,
                        highlightKey = "about_changelog",
                        modifier = getModifier(2),
                        icon = Icons.Rounded.History
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showAgreementDialog) {
        ZToolDialog(
            onDismissRequest = { showAgreementDialog = false },
            title = { Text(stringResource(R.string.page_settings_about_user_agreement_title)) },
            text = {
                AgreementContentCard(markdownText = agreementMarkdown)
            },
            confirmButton = {
                ZToolTextButton(
                    text = stringResource(R.string.settings_close_button),
                    onClick = { showAgreementDialog = false }
                )
            }
        )
    }

    selectedDeveloper?.let { profile ->
        DeveloperProfileDialog(
            profile = profile,
            onDismissRequest = { selectedDeveloper = null },
            onOpenSocialLink = { link ->
                selectedDeveloper = null
                onOpenSocialLink(link)
            }
        )
    }
}

internal const val SettingsAboutRouteName = "SettingsAbout"

@Composable
private fun AboutHeaderCard(
    versionName: String,
    @Suppress("SameParameterValue") commitCount: Int,
    @Suppress("SameParameterValue") commitHash: String
) {
    AboutSectionContainer {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                bitmap = ImageBitmap.imageResource(
                    appLogoRes(LocalUseAlternativeIcon.current)
                ),
                contentDescription = stringResource(R.string.common_splash_logo_description),
                modifier = Modifier.height(88.dp)
            )
            Text(
                text = stringResource(R.string.page_settings_app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = LocalZToolColorScheme.current.onSurface
            )
            Text(
                text = "$versionName - $commitCount - $commitHash",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.common_splash_slogan),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalZToolColorScheme.current.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutSectionContainer(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isExpressive = LocalZToolThemeSpec.current.style == FrontendStyle.Material3Expressive
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!isExpressive && title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
            )
        }
        ZToolCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isExpressive) 12.dp else 0.dp)
            ) {
                if (isExpressive && title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = LocalZToolColorScheme.current.onSurface,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                content()
            }
        }
    }
}

@Composable
internal fun AboutSectionCard(
    title: String,
    @Suppress("SameParameterValue") itemCount: Int,
    content: @Composable ColumnScope.(getModifier: (Int) -> Modifier) -> Unit
) {
    val isExpressive = LocalZToolThemeSpec.current.style == FrontendStyle.Material3Expressive
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!isExpressive) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
            )
        }
        ZToolCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isExpressive) 12.dp else 0.dp)
            ) {
                if (isExpressive) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = LocalZToolColorScheme.current.onSurface,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                ExpressiveSectionItems(count = itemCount, content = content)
            }
        }
    }
}

@Composable
internal fun AboutActionRow(
    title: String,
    summary: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    showTrailingArrow: Boolean = true,
    trailingLabel: String? = null,
    highlightKey: String? = null
) {
    HighlightableSettingRow(highlightKey = highlightKey, modifier = modifier) {
        ZListItem(
            title = title,
            summary = summary,
            onClick = onClick,
            modifier = Modifier,
            leadingContent = if (leadingContent != null) {
                { leadingContent() }
            } else if (icon != null) {
                { ZToolSettingLeadingIcon(icon = icon) }
            } else {
                null
            },
            trailingContent = if (showTrailingArrow || trailingLabel != null) {
                {
                    if (trailingLabel != null) {
                        Text(
                            text = trailingLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalZToolColorScheme.current.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (showTrailingArrow) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = LocalZToolColorScheme.current.onSurfaceVariant
                        )
                    }
                }
            } else null
        )
    }
}

@Composable
private fun AboutSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    highlightKey: String? = null
) {
    HighlightableSettingRow(highlightKey = highlightKey, modifier = modifier) {
        ZToolSwitchRow(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
            icon = icon
        )
    }
}

internal fun openExternalLink(
    context: Context,
    link: String,
    shouldDeterminePackage: Boolean = false,
    packageName: String = ""
) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, link.toUri()).apply {
                if (shouldDeterminePackage) setPackage(packageName)
            }
        )
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.common_open_web_link_failed), Toast.LENGTH_SHORT).show()
    }
}
