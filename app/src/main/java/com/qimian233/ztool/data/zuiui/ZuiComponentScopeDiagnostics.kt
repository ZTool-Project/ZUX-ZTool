package com.qimian233.ztool.data.zuiui

/** Installed APK identity used to verify whether a cached ZUI scan verdict is fresh. */
data class InstalledPackageFingerprint(
    val sourceDir: String,
    val lastUpdateTime: Long
)

/** Diagnostic summary of ZUI component scope detection and cache maintenance. */
data class ZuiComponentScopeDiagnostic(
    val hasScannedOnce: Boolean,
    val isScanRunning: Boolean,
    val runningScanned: Int,
    val runningTotal: Int,
    val lastCheckedAt: Long?,
    val installedAppsAvailable: Boolean,
    val totalInstalledApps: Int,
    val cachedVerdictCount: Int,
    val freshVerdictCount: Int,
    val unscannedInstalledCount: Int,
    val outdatedVerdictCount: Int,
    val orphanedVerdictCount: Int,
    val detectedZuiPackages: List<String>,
    val isServiceConnected: Boolean,
    val inScopeZuiPackages: List<String>,
    val pendingZuiPackages: List<String>
)

/** Evaluates ZUI component scan cache maintenance and LSPosed scope coverage. */
object ZuiComponentScopeDiagnostics {

    fun evaluate(
        snapshot: ZuiComponentScanSnapshot,
        liveState: ZuiComponentScanState,
        installedFingerprints: Map<String, InstalledPackageFingerprint>,
        currentScope: Set<String>?,
        isServiceConnected: Boolean
    ): ZuiComponentScopeDiagnostic {
        val hasScannedOnce = snapshot.firstScanStarted || liveState.hasScannedOnce
        val installedAvailable = installedFingerprints.isNotEmpty()
        val connected = isServiceConnected && currentScope != null
        val effectiveScope = if (connected) currentScope else emptySet()
        val lastCheckedAt = snapshot.verdicts.values.maxOfOrNull { it.checkedAt }?.takeIf { it > 0L }

        val orphanedVerdictCount: Int
        val unscannedInstalledCount: Int
        val outdatedVerdictCount: Int
        val freshVerdictCount: Int
        val detectedZuiPackages: List<String>

        if (installedAvailable) {
            val activeVerdicts = snapshot.verdicts.filterKeys { it in installedFingerprints }
            orphanedVerdictCount = snapshot.verdicts.size - activeVerdicts.size
            unscannedInstalledCount = installedFingerprints.keys.count { it !in snapshot.verdicts }
            outdatedVerdictCount = activeVerdicts.count { (pkg, verdict) ->
                val fp = installedFingerprints.getValue(pkg)
                verdict.lastUpdateTime != fp.lastUpdateTime || verdict.sourceDir != fp.sourceDir
            }
            freshVerdictCount = activeVerdicts.size - outdatedVerdictCount
            detectedZuiPackages = activeVerdicts
                .filterValues { it.containsZuiComponents }
                .keys
                .sorted()
        } else {
            orphanedVerdictCount = 0
            unscannedInstalledCount = 0
            outdatedVerdictCount = 0
            freshVerdictCount = 0
            detectedZuiPackages = snapshot.verdicts
                .filterValues { it.containsZuiComponents }
                .keys
                .sorted()
        }

        val inScopeZuiPackages = if (connected) {
            detectedZuiPackages.filter { it in effectiveScope }
        } else {
            emptyList()
        }
        val pendingZuiPackages = if (connected) {
            detectedZuiPackages.filter { it !in effectiveScope }
        } else {
            emptyList()
        }

        return ZuiComponentScopeDiagnostic(
            hasScannedOnce = hasScannedOnce,
            isScanRunning = liveState.running,
            runningScanned = liveState.scanned,
            runningTotal = liveState.total,
            lastCheckedAt = lastCheckedAt,
            installedAppsAvailable = installedAvailable,
            totalInstalledApps = installedFingerprints.size,
            cachedVerdictCount = snapshot.verdicts.size,
            freshVerdictCount = freshVerdictCount,
            unscannedInstalledCount = unscannedInstalledCount,
            outdatedVerdictCount = outdatedVerdictCount,
            orphanedVerdictCount = orphanedVerdictCount,
            detectedZuiPackages = detectedZuiPackages,
            isServiceConnected = connected,
            inScopeZuiPackages = inScopeZuiPackages,
            pendingZuiPackages = pendingZuiPackages
        )
    }
}
