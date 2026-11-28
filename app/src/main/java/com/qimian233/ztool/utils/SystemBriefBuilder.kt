package com.qimian233.ztool.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.qimian233.ztool.BuildConfig
import com.qimian233.ztool.R
import com.qimian233.ztool.data.features.FeaturesScopePromptRepository
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.zuiui.InstalledPackageFingerprint
import com.qimian233.ztool.data.zuiui.ZuiComponentScanManager
import com.qimian233.ztool.data.zuiui.ZuiComponentScanSnapshot
import com.qimian233.ztool.data.zuiui.ZuiComponentScanStore
import com.qimian233.ztool.data.zuiui.ZuiComponentScopeDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders `system_brief.txt` shipped inside an exported log archive.
 *
 * Probes are best-effort so the UTF-8 brief is always produced even without Root or LSPosed.
 */
object SystemBriefBuilder {

    /** Entry name of the brief at the root of the exported zip. */
    const val FILE_NAME = "system_brief.txt"

    fun build(context: Context): String {
        val unknown = context.getString(R.string.common_unknown)
        val builder = StringBuilder()

        builder.appendLine(context.getString(R.string.page_settings_brief_title))
        builder.appendLine(
            context.getString(R.string.page_settings_brief_generated_at, timestamp())
        )

        builder.appendLine()
        builder.appendLine(context.getString(R.string.page_settings_brief_section_device))
        builder.appendLine(
            context.getString(
                R.string.page_settings_brief_device_model,
                Build.MODEL.ifBlank { unknown }
            )
        )
        builder.appendLine(
            context.getString(
                R.string.page_settings_brief_android_version,
                context.getString(
                    R.string.page_home_android_version_prefix,
                    Build.VERSION.RELEASE.ifBlank { unknown }
                )
            )
        )
        builder.appendLine(
            context.getString(
                R.string.page_settings_brief_kernel_version,
                SystemInfoProbe.kernelVersion().ifBlank { unknown }
            )
        )
        builder.appendLine(
            context.getString(
                R.string.page_settings_brief_build_number,
                Build.DISPLAY.ifBlank { unknown }
            )
        )
        builder.appendLine(
            context.getString(R.string.page_settings_brief_rom_region, SystemInfoProbe.romRegion(context))
        )
        val (days, hours, minutes) = uptimeParts(SystemClock.elapsedRealtime())
        builder.appendLine(context.getString(R.string.page_settings_brief_uptime, days, hours, minutes))

        builder.appendLine()
        builder.appendLine(context.getString(R.string.page_settings_brief_section_root))
        builder.appendLine(
            context.getString(R.string.page_settings_brief_root_source, SystemInfoProbe.rootSource(context))
        )
        val framework = SystemInfoProbe.frameworkInfo()
        if (framework == null) {
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_framework_name,
                    context.getString(R.string.page_home_unknown_framework)
                )
            )
        } else {
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_framework_name,
                    framework.name.ifBlank { unknown }
                )
            )
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_framework_version,
                    framework.version.ifBlank { unknown },
                    framework.versionCode
                )
            )
            builder.appendLine(
                context.getString(R.string.page_settings_brief_framework_api, framework.apiVersion)
            )
        }

        builder.appendLine()
        builder.appendLine(context.getString(R.string.page_settings_brief_section_ztool))
        builder.appendLine(
            context.getString(
                R.string.page_settings_brief_ztool_version,
                SystemInfoProbe.appVersionLabel(context)
            )
        )
        builder.appendLine(
            context.getString(
                R.string.page_settings_brief_ztool_commit,
                BuildConfig.GIT_COMMIT_COUNT,
                BuildConfig.GIT_COMMIT_HASH
            )
        )

        val activeScope = SystemInfoProbe.activeScope()
        val isServiceConnected = activeScope != null
        val declaredPackages = SystemInfoProbe.scopePackages(context)
        val installedFingerprints = SystemInfoProbe.installedPackageFingerprints(context)

        builder.appendLine()
        builder.appendLine(context.getString(R.string.page_settings_brief_scope_check_title))
        appendScopeCheck(
            context = context,
            builder = builder,
            activeScope = activeScope,
            isServiceConnected = isServiceConnected,
            declaredPackages = declaredPackages,
            installedFingerprints = installedFingerprints
        )

        builder.appendLine()
        builder.appendLine(context.getString(R.string.page_settings_brief_zui_scope_title))
        appendZuiComponentScope(
            context = context,
            builder = builder,
            activeScope = activeScope,
            isServiceConnected = isServiceConnected,
            installedFingerprints = installedFingerprints
        )

        builder.appendLine()
        builder.appendLine(context.getString(R.string.page_settings_brief_section_scope))
        appendScopeVersions(
            context = context,
            builder = builder,
            unknown = unknown,
            packages = declaredPackages,
            activeScope = activeScope
        )

        return builder.toString()
    }

    private fun appendScopeCheck(
        context: Context,
        builder: StringBuilder,
        activeScope: Set<String>?,
        isServiceConnected: Boolean,
        declaredPackages: List<String>,
        installedFingerprints: Map<String, InstalledPackageFingerprint>
    ) {
        val dismissed = runCatching {
            FeaturesScopePromptRepository(context.applicationContext).loadDismissedPackages()
        }.getOrDefault(emptySet())

        val diag = FeaturesScopePromptRepository.evaluateScopeDiagnostics(
            installedPackages = installedFingerprints.keys,
            currentScope = activeScope,
            isServiceConnected = isServiceConnected,
            dismissedPackages = dismissed,
            declaredScope = declaredPackages
        )

        if (diag.isServiceConnected) {
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_scope_service_connected_message,
                    diag.activeScopeCount
                )
            )
            when {
                !diag.installedPackagesAvailable -> builder.appendLine(
                    context.getString(R.string.page_settings_brief_scope_check_unavailable_message)
                )

                diag.missingPackages.isEmpty() -> builder.appendLine(
                    context.getString(R.string.page_settings_brief_scope_check_ok_message)
                )

                else -> {
                    builder.appendLine(
                        context.getString(
                            R.string.page_settings_brief_scope_check_missing_message,
                            diag.missingPackages.size
                        )
                    )
                    builder.appendLine(
                        context.getString(R.string.page_settings_brief_scope_missing_list_label)
                    )
                    diag.missingPackages.forEach { pkg ->
                        builder.appendLine("- $pkg")
                    }
                    if (diag.affectedCards.isNotEmpty()) {
                        builder.appendLine(
                            context.getString(
                                R.string.page_settings_brief_scope_affected_features_label
                            )
                        )
                        diag.affectedCards.forEach { affected ->
                            builder.appendLine(
                                context.getString(
                                    R.string.page_settings_brief_scope_affected_feature_entry_label,
                                    context.getString(affected.card.nameRes),
                                    affected.missingPackages.joinToString(", ")
                                )
                            )
                        }
                    }
                }
            }
        } else {
            builder.appendLine(
                context.getString(R.string.page_settings_brief_scope_service_disconnected_message)
            )
        }

        if (diag.dismissedPackages.isNotEmpty()) {
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_scope_dismissed_message,
                    diag.dismissedPackages.joinToString(", ")
                )
            )
        }
        if (diag.extraScopePackages.isNotEmpty()) {
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_scope_extra_list_label,
                    diag.extraScopePackages.size
                )
            )
            diag.extraScopePackages.forEach { pkg ->
                builder.appendLine("- $pkg")
            }
        }
    }

    private fun appendZuiComponentScope(
        context: Context,
        builder: StringBuilder,
        activeScope: Set<String>?,
        isServiceConnected: Boolean,
        installedFingerprints: Map<String, InstalledPackageFingerprint>
    ) {
        val snapshot = runCatching {
            ZuiComponentScanStore(context.applicationContext).load()
        }.getOrDefault(ZuiComponentScanSnapshot())
        val liveState = ZuiComponentScanManager.state.value

        val diag = ZuiComponentScopeDiagnostics.evaluate(
            snapshot = snapshot,
            liveState = liveState,
            installedFingerprints = installedFingerprints,
            currentScope = activeScope,
            isServiceConnected = isServiceConnected
        )

        val scanStatusLine = when {
            diag.isScanRunning -> context.getString(
                R.string.page_settings_brief_zui_scan_running_message,
                diag.runningScanned,
                diag.runningTotal
            )

            !diag.hasScannedOnce -> context.getString(
                R.string.page_settings_brief_zui_scan_never_message
            )

            diag.lastCheckedAt != null -> context.getString(
                R.string.page_settings_brief_zui_scan_done_with_time_message,
                formatTimestamp(diag.lastCheckedAt)
            )

            else -> context.getString(R.string.page_settings_brief_zui_scan_done_message)
        }
        builder.appendLine(scanStatusLine)

        val cacheLine = if (diag.installedAppsAvailable) {
            context.getString(
                R.string.page_settings_brief_zui_cache_summary,
                diag.totalInstalledApps,
                diag.freshVerdictCount,
                diag.unscannedInstalledCount,
                diag.outdatedVerdictCount,
                diag.orphanedVerdictCount
            )
        } else {
            context.getString(
                R.string.page_settings_brief_zui_cache_unavailable_message,
                diag.cachedVerdictCount
            )
        }
        builder.appendLine(cacheLine)

        if (!diag.hasScannedOnce && diag.detectedZuiPackages.isEmpty()) return

        if (diag.isServiceConnected) {
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_zui_apps_summary,
                    diag.detectedZuiPackages.size,
                    diag.inScopeZuiPackages.size,
                    diag.pendingZuiPackages.size
                )
            )
            if (diag.pendingZuiPackages.isNotEmpty()) {
                builder.appendLine(
                    context.getString(
                        R.string.page_settings_brief_zui_pending_list_label,
                        diag.pendingZuiPackages.size
                    )
                )
                diag.pendingZuiPackages.forEach { pkg ->
                    builder.appendLine("- $pkg")
                }
            }
            if (diag.inScopeZuiPackages.isNotEmpty()) {
                builder.appendLine(
                    context.getString(
                        R.string.page_settings_brief_zui_selected_list_label,
                        diag.inScopeZuiPackages.size
                    )
                )
                diag.inScopeZuiPackages.forEach { pkg ->
                    builder.appendLine("- $pkg")
                }
            }
        } else {
            builder.appendLine(
                context.getString(
                    R.string.page_settings_brief_zui_apps_disconnected_summary,
                    diag.detectedZuiPackages.size
                )
            )
            if (diag.detectedZuiPackages.isNotEmpty()) {
                builder.appendLine(
                    context.getString(
                        R.string.page_settings_brief_zui_detected_list_label,
                        diag.detectedZuiPackages.size
                    )
                )
                diag.detectedZuiPackages.forEach { pkg ->
                    builder.appendLine("- $pkg")
                }
            }
        }
    }

    private fun appendScopeVersions(
        context: Context,
        builder: StringBuilder,
        unknown: String,
        packages: List<String>,
        activeScope: Set<String>?
    ) {
        if (packages.isEmpty()) {
            builder.appendLine(context.getString(R.string.page_settings_brief_scope_empty))
            return
        }

        val notInstalled = context.getString(R.string.page_settings_brief_scope_not_installed)
        val selectedLabel = context.getString(R.string.page_settings_brief_scope_selected_label)
        val unselectedLabel = context.getString(R.string.page_settings_brief_scope_unselected_label)
        for (packageName in packages) {
            val version = if (packageName == ScopeKeys.SYSTEM_SERVER.packageName) {
                // The system-server scope entry is not an installed package; its version is the platform one.
                Build.VERSION.RELEASE.ifBlank { unknown }
            } else {
                SystemInfoProbe.installedPackageVersion(context, packageName) ?: notInstalled
            }
            val line = if (activeScope == null) {
                context.getString(R.string.page_settings_brief_scope_entry, packageName, version)
            } else {
                val statusLabel = if (packageName in activeScope) selectedLabel else unselectedLabel
                context.getString(
                    R.string.page_settings_brief_scope_entry_status_label,
                    packageName,
                    version,
                    statusLabel
                )
            }
            builder.appendLine(line)
        }
    }

    private fun timestamp(): String = formatTimestamp(System.currentTimeMillis())

    private fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    private fun uptimeParts(elapsedRealtimeMs: Long): Triple<Long, Long, Long> {
        val totalMinutes = elapsedRealtimeMs / 60_000
        return Triple(
            totalMinutes / (24 * 60),
            (totalMinutes / 60) % 24,
            totalMinutes % 60
        )
    }
}
