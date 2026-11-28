package com.qimian233.ztool.data.zuiui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZuiComponentScopeDiagnosticsTest {

    @Test
    fun evaluate_neverScanned_countsAllInstalledAsUnscanned() {
        val installed = mapOf(
            "com.example.a" to InstalledPackageFingerprint("/data/app/a.apk", 100L),
            "com.example.b" to InstalledPackageFingerprint("/data/app/b.apk", 200L)
        )

        val diag = ZuiComponentScopeDiagnostics.evaluate(
            snapshot = ZuiComponentScanSnapshot(),
            liveState = ZuiComponentScanState(),
            installedFingerprints = installed,
            currentScope = setOf("com.example.a"),
            isServiceConnected = true
        )

        assertFalse(diag.hasScannedOnce)
        assertFalse(diag.isScanRunning)
        assertNull(diag.lastCheckedAt)
        assertTrue(diag.installedAppsAvailable)
        assertEquals(2, diag.totalInstalledApps)
        assertEquals(0, diag.freshVerdictCount)
        assertEquals(2, diag.unscannedInstalledCount)
        assertEquals(0, diag.outdatedVerdictCount)
        assertEquals(0, diag.orphanedVerdictCount)
        assertTrue(diag.detectedZuiPackages.isEmpty())
        assertTrue(diag.inScopeZuiPackages.isEmpty())
        assertTrue(diag.pendingZuiPackages.isEmpty())
    }

    @Test
    fun evaluate_runningScan_surfacesLiveProgress() {
        val diag = ZuiComponentScopeDiagnostics.evaluate(
            snapshot = ZuiComponentScanSnapshot(firstScanStarted = true),
            liveState = ZuiComponentScanState(
                running = true,
                scanned = 4,
                total = 10,
                hasScannedOnce = true
            ),
            installedFingerprints = mapOf(
                "com.example.a" to InstalledPackageFingerprint("/data/app/a.apk", 100L)
            ),
            currentScope = emptySet(),
            isServiceConnected = true
        )

        assertTrue(diag.hasScannedOnce)
        assertTrue(diag.isScanRunning)
        assertEquals(4, diag.runningScanned)
        assertEquals(10, diag.runningTotal)
    }

    @Test
    fun evaluate_mixedMaintenanceCache_computesAllBucketsAndScopeSplit() {
        val installed = mapOf(
            "com.zui.fresh.selected" to InstalledPackageFingerprint("/app/1.apk", 100L),
            "com.zui.fresh.pending" to InstalledPackageFingerprint("/app/2.apk", 200L),
            "com.plain.fresh" to InstalledPackageFingerprint("/app/3.apk", 300L),
            "com.zui.updated.time" to InstalledPackageFingerprint("/app/4.apk", 401L),
            "com.plain.moved.dir" to InstalledPackageFingerprint("/app/5-new.apk", 500L),
            "com.app.unscanned" to InstalledPackageFingerprint("/app/6.apk", 600L)
        )
        val verdicts = mapOf(
            "com.zui.fresh.selected" to ZuiComponentVerdict(true, "/app/1.apk", 100L, 1_000L),
            "com.zui.fresh.pending" to ZuiComponentVerdict(true, "/app/2.apk", 200L, 2_000L),
            "com.plain.fresh" to ZuiComponentVerdict(false, "/app/3.apk", 300L, 1_500L),
            "com.zui.updated.time" to ZuiComponentVerdict(true, "/app/4.apk", 400L, 1_200L),
            "com.plain.moved.dir" to ZuiComponentVerdict(false, "/app/5.apk", 500L, 1_300L),
            "com.zui.uninstalled" to ZuiComponentVerdict(true, "/app/old.apk", 50L, 9_000L)
        )

        val diag = ZuiComponentScopeDiagnostics.evaluate(
            snapshot = ZuiComponentScanSnapshot(firstScanStarted = true, verdicts = verdicts),
            liveState = ZuiComponentScanState(),
            installedFingerprints = installed,
            currentScope = setOf("com.zui.fresh.selected"),
            isServiceConnected = true
        )

        assertTrue(diag.hasScannedOnce)
        assertEquals(9_000L, diag.lastCheckedAt)
        assertEquals(6, diag.totalInstalledApps)
        assertEquals(6, diag.cachedVerdictCount)
        assertEquals(3, diag.freshVerdictCount)
        assertEquals(1, diag.unscannedInstalledCount)
        assertEquals(2, diag.outdatedVerdictCount)
        assertEquals(1, diag.orphanedVerdictCount)
        assertEquals(
            listOf("com.zui.fresh.pending", "com.zui.fresh.selected", "com.zui.updated.time"),
            diag.detectedZuiPackages
        )
        assertEquals(listOf("com.zui.fresh.selected"), diag.inScopeZuiPackages)
        assertEquals(
            listOf("com.zui.fresh.pending", "com.zui.updated.time"),
            diag.pendingZuiPackages
        )
    }

    @Test
    fun evaluate_disconnectedFramework_keepsDetectedPackagesWithoutSelectionSplit() {
        val installed = mapOf(
            "com.zui.app" to InstalledPackageFingerprint("/app/z.apk", 100L)
        )
        val verdicts = mapOf(
            "com.zui.app" to ZuiComponentVerdict(true, "/app/z.apk", 100L, 5_000L)
        )

        val diag = ZuiComponentScopeDiagnostics.evaluate(
            snapshot = ZuiComponentScanSnapshot(firstScanStarted = true, verdicts = verdicts),
            liveState = ZuiComponentScanState(),
            installedFingerprints = installed,
            currentScope = null,
            isServiceConnected = false
        )

        assertFalse(diag.isServiceConnected)
        assertEquals(listOf("com.zui.app"), diag.detectedZuiPackages)
        assertTrue(diag.inScopeZuiPackages.isEmpty())
        assertTrue(diag.pendingZuiPackages.isEmpty())
    }

    @Test
    fun evaluate_emptyInstalledFingerprints_fallsBackToCachedVerdicts() {
        val verdicts = mapOf(
            "com.zui.a" to ZuiComponentVerdict(true, "/a.apk", 1L, 1_000L),
            "com.zui.b" to ZuiComponentVerdict(true, "/b.apk", 2L, 2_000L),
            "com.plain.c" to ZuiComponentVerdict(false, "/c.apk", 3L, 3_000L)
        )

        val diag = ZuiComponentScopeDiagnostics.evaluate(
            snapshot = ZuiComponentScanSnapshot(firstScanStarted = true, verdicts = verdicts),
            liveState = ZuiComponentScanState(),
            installedFingerprints = emptyMap(),
            currentScope = setOf("com.zui.a"),
            isServiceConnected = true
        )

        assertFalse(diag.installedAppsAvailable)
        assertEquals(3, diag.cachedVerdictCount)
        assertEquals(listOf("com.zui.a", "com.zui.b"), diag.detectedZuiPackages)
        assertEquals(listOf("com.zui.a"), diag.inScopeZuiPackages)
        assertEquals(listOf("com.zui.b"), diag.pendingZuiPackages)
    }
}
