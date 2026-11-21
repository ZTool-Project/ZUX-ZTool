package com.qimian233.ztool

import com.qimian233.ztool.hook.modules.packageinstaller.InstallFlowState
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerConstants
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerOptions
import com.qimian233.ztool.hook.modules.packageinstaller.ScanResultSnapshot
import com.qimian233.ztool.hook.modules.packageinstaller.resolvePermissionDialogId
import com.qimian233.ztool.hook.modules.packageinstaller.resolvePermsManageType
import com.qimian233.ztool.hook.modules.packageinstaller.shouldEnqueueCompletionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageInstallerPolicyTest {
    @Test
    fun nativeStyleDisablesUnifiedOemOwnerAndGroupsEvenWhenOemTogglesAreStored() {
        val allOff = PackageInstallerOptions()
        assertFalse(allOff.enabled)
        assertFalse(allOff.needsScanFlow)
        assertFalse(allOff.needsPermissions)
        assertFalse(allOff.needsAdBlock)
        assertFalse(allOff.needsNoDelete)

        val oemActive = PackageInstallerOptions(
            nativeStyle = false,
            disableInstallerAd = true,
            disableDeletePackage = true,
            disableScanApk = true,
            alwaysAllowPermission = true,
            skipWarnPage = true,
        )
        assertTrue(oemActive.enabled)
        assertTrue(oemActive.needsScanFlow)
        assertTrue(oemActive.needsPermissions)
        assertTrue(oemActive.needsAdBlock)
        assertTrue(oemActive.needsNoDelete)

        val nativeOverride = oemActive.copy(nativeStyle = true)
        assertFalse(nativeOverride.enabled)
        assertFalse(nativeOverride.needsScanFlow)
        assertFalse(nativeOverride.needsPermissions)
        assertFalse(nativeOverride.needsAdBlock)
        assertFalse(nativeOverride.needsNoDelete)
    }

    @Test
    fun eachOemToggleOnlyActivatesItsOwnGroupAndIntersectRespectsInstalledCapabilities() {
        val scanOnly = PackageInstallerOptions(disableScanApk = true)
        assertTrue(scanOnly.enabled)
        assertTrue(scanOnly.needsScanFlow)
        assertFalse(scanOnly.needsPermissions)
        assertFalse(scanOnly.needsAdBlock)
        assertFalse(scanOnly.needsNoDelete)

        val warnOnly = PackageInstallerOptions(skipWarnPage = true)
        assertTrue(warnOnly.enabled)
        assertTrue(warnOnly.needsScanFlow)

        val installed = PackageInstallerOptions(disableScanApk = true, disableInstallerAd = true)
        val current = PackageInstallerOptions(
            disableScanApk = true,
            skipWarnPage = true,
            disableInstallerAd = false,
        )
        val effective = current.intersect(installed)
        assertTrue(effective.disableScanApk)
        assertFalse(effective.skipWarnPage)
        assertFalse(effective.disableInstallerAd)
    }

    @Test
    fun completionMessageEnqueueDeduplicatesPendingScanAndSafeCheckMessages() {
        assertTrue(shouldEnqueueCompletionMessage(hasScanOkQueued = false, hasSafeCheckQueued = false))
        assertFalse(shouldEnqueueCompletionMessage(hasScanOkQueued = true, hasSafeCheckQueued = false))
        assertFalse(shouldEnqueueCompletionMessage(hasScanOkQueued = false, hasSafeCheckQueued = true))
        assertFalse(shouldEnqueueCompletionMessage(hasScanOkQueued = true, hasSafeCheckQueued = true))
    }

    @Test
    fun autoAdvanceRequiresCompletedSafeBarrierAndSkipsUnsafeOrNonStandardPaths() {
        val state = InstallFlowState()
        val warnEnabled = PackageInstallerOptions(skipWarnPage = true)
        val readySnapshot = ScanResultSnapshot(
            scanAppResult = PackageInstallerConstants.SCAN_APP_OK,
            checkSafeInstallResult = PackageInstallerConstants.CHECK_SAFEINSTALL_BAD,
            isNext = true,
            isClicked = false,
            forbidInstall = false,
            isFinishingOrDestroyed = false,
        )

        assertTrue(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot))
        assertFalse(state.shouldAutoAdvanceToConfirm(PackageInstallerOptions(skipWarnPage = false), readySnapshot))
        assertFalse(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot.copy(scanAppResult = 0)))
        assertFalse(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot.copy(checkSafeInstallResult = 0)))
        assertFalse(
            state.shouldAutoAdvanceToConfirm(
                warnEnabled,
                readySnapshot.copy(scanAppResult = PackageInstallerConstants.SCAN_APP_BAD),
            )
        )
        assertFalse(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot.copy(forbidInstall = true)))
        assertFalse(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot.copy(isNext = false)))
        assertFalse(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot.copy(isClicked = true)))
        assertFalse(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot.copy(isFinishingOrDestroyed = true)))

        state.markAdvancedToConfirm()
        assertFalse(state.shouldAutoAdvanceToConfirm(warnEnabled, readySnapshot))
        assertTrue(state.shouldSkipRedundantShowResult(warnEnabled))
    }

    @Test
    fun configurationChangeResetsFlowAdvanceGuardAndClickedState() {
        val state = InstallFlowState()
        val warnEnabled = PackageInstallerOptions(skipWarnPage = true)
        val warnDisabled = PackageInstallerOptions(skipWarnPage = false)

        state.markAdvancedToConfirm()
        assertTrue(state.shouldSkipRedundantShowResult(warnEnabled))
        assertFalse(state.shouldResetClickedOnConfigChange(warnEnabled, isConfigurationChanged = false))
        assertTrue(state.shouldResetClickedOnConfigChange(warnEnabled, isConfigurationChanged = true))
        assertFalse(state.shouldResetClickedOnConfigChange(warnDisabled, isConfigurationChanged = true))

        state.onShowScanApp()
        assertFalse(state.advancedToConfirm)
        assertFalse(state.shouldSkipRedundantShowResult(warnEnabled))
    }

    @Test
    fun permissionHelpersForceTrustModeAndAllAllowDialogOnlyWhenEnabled() {
        assertEquals(PackageInstallerConstants.PERMS_MANAGE_TYPE_TRUST, resolvePermsManageType(0, true))
        assertEquals(PackageInstallerConstants.PERMS_MANAGE_TYPE_TRUST, resolvePermsManageType(1, true))
        assertEquals(PackageInstallerConstants.PERMS_MANAGE_TYPE_TRUST, resolvePermsManageType(4, true))
        assertEquals(0, resolvePermsManageType(0, false))
        assertEquals(3, resolvePermsManageType(3, false))

        assertEquals(PackageInstallerConstants.DLG_PERMS_ALL_ALLOW, resolvePermissionDialogId(10, true))
        assertEquals(12, resolvePermissionDialogId(12, true))
        assertEquals(8, resolvePermissionDialogId(8, true))
        assertEquals(10, resolvePermissionDialogId(10, false))
    }
}
