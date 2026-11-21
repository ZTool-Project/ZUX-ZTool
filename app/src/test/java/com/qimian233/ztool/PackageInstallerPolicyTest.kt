package com.qimian233.ztool

import com.qimian233.ztool.hook.modules.packageinstaller.DeleteCheckBoxAction
import com.qimian233.ztool.hook.modules.packageinstaller.InstallFlowState
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerConstants
import com.qimian233.ztool.hook.modules.packageinstaller.PackageInstallerOptions
import com.qimian233.ztool.hook.modules.packageinstaller.ScanResultSnapshot
import com.qimian233.ztool.hook.modules.packageinstaller.V2ColorPalette
import com.qimian233.ztool.hook.modules.packageinstaller.isV2DarkColorFixActive
import com.qimian233.ztool.hook.modules.packageinstaller.remapV2ButtonTextColor
import com.qimian233.ztool.hook.modules.packageinstaller.remapV2TextColor
import com.qimian233.ztool.hook.modules.packageinstaller.resolveDeleteCheckBoxAction
import com.qimian233.ztool.hook.modules.packageinstaller.resolvePermissionDialogId
import com.qimian233.ztool.hook.modules.packageinstaller.resolvePermsManageType
import com.qimian233.ztool.hook.modules.packageinstaller.shouldClearDialogChildBackground
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

    @Test
    fun v2DarkColorFixRequiresBothNativeStyleAndDarkFixEnabled() {
        assertFalse(isV2DarkColorFixActive(nativeStyle = false, darkColorFix = false))
        assertFalse(isV2DarkColorFixActive(nativeStyle = false, darkColorFix = true))
        assertFalse(isV2DarkColorFixActive(nativeStyle = true, darkColorFix = false))
        assertTrue(isV2DarkColorFixActive(nativeStyle = true, darkColorFix = true))
    }

    @Test
    fun dialogChildBackgroundClearsOnlyUnfixedHostContainerColor() {
        val hostLight = 0xFFE7E7F1.toInt()
        val dark = 0xFF1D1F26.toInt()
        val divider = 0x1F000000

        assertTrue(shouldClearDialogChildBackground(hostLight, hostLight, dark))
        assertFalse(shouldClearDialogChildBackground(divider, hostLight, dark))
        assertFalse(shouldClearDialogChildBackground(dark, dark, dark))
    }

    @Test
    fun v2ColorRemappersReplaceOnlyMismatchedSemanticTokens() {
        val host = V2ColorPalette(
            surfaceContainerHigh = 0xFFE7E7F1.toInt(),
            primary = 0xFF4C5E8B.toInt(),
            onPrimary = 0xFFF9F8FF.toInt(),
            onSurface = 0xFF30323A.toInt(),
            onSurfaceVariant = 0xFF5D5F68.toInt(),
            outlineVariant = 0xFFB0B1BC.toInt(),
        )
        val dark = V2ColorPalette(
            surfaceContainerHigh = 0xFF1D1F26.toInt(),
            primary = 0xFFB8C6EE.toInt(),
            onPrimary = 0xFF323F60.toInt(),
            onSurface = 0xFFE4E5F0.toInt(),
            onSurfaceVariant = 0xFFA9AAB5.toInt(),
            outlineVariant = 0xFF454850.toInt(),
        )

        assertEquals(dark.onSurface, remapV2TextColor(host.onSurface, host, dark))
        assertEquals(dark.onSurfaceVariant, remapV2TextColor(host.onSurfaceVariant, host, dark))
        assertEquals(dark.primary, remapV2TextColor(host.primary, host, dark))
        assertEquals(0xFF123456.toInt(), remapV2TextColor(0xFF123456.toInt(), host, dark))
        assertEquals(dark.onSurface, remapV2TextColor(dark.onSurface, dark, dark))

        assertEquals(dark.onPrimary, remapV2ButtonTextColor(host.onPrimary, host, dark))
        assertEquals(dark.primary, remapV2ButtonTextColor(host.primary, host, dark))
        assertEquals(0xFF654321.toInt(), remapV2ButtonTextColor(0xFF654321.toInt(), host, dark))
        assertEquals(dark.primary, remapV2ButtonTextColor(dark.primary, dark, dark))
    }

    @Test
    fun lockDeleteCheckBoxAloneDoesNotActivateNoDeleteOrOemOwner() {
        val lockOnly = PackageInstallerOptions(lockDeleteCheckBox = true)
        assertFalse(lockOnly.enabled)
        assertFalse(lockOnly.needsNoDelete)
    }

    @Test
    fun intersectAllowsHotTogglingLockDeleteOnlyWhenNoDeleteIsInstalledAndActive() {
        val installedNoDeleteOnly = PackageInstallerOptions(
            disableDeletePackage = true,
            lockDeleteCheckBox = false,
        )
        val currentBothOn = PackageInstallerOptions(
            disableDeletePackage = true,
            lockDeleteCheckBox = true,
        )
        val effectiveBothOn = currentBothOn.intersect(installedNoDeleteOnly)
        assertTrue(effectiveBothOn.disableDeletePackage)
        assertTrue(effectiveBothOn.lockDeleteCheckBox)

        val currentNoDeleteOff = PackageInstallerOptions(
            disableDeletePackage = false,
            lockDeleteCheckBox = true,
        )
        val effectiveNoDeleteOff = currentNoDeleteOff.intersect(installedNoDeleteOnly)
        assertFalse(effectiveNoDeleteOff.disableDeletePackage)
        assertFalse(effectiveNoDeleteOff.lockDeleteCheckBox)

        val installedNotInstalled = PackageInstallerOptions(
            disableDeletePackage = false,
            lockDeleteCheckBox = false,
        )
        val effectiveNotInstalled = currentBothOn.intersect(installedNotInstalled)
        assertFalse(effectiveNotInstalled.disableDeletePackage)
        assertFalse(effectiveNotInstalled.lockDeleteCheckBox)
    }

    @Test
    fun resolveDeleteCheckBoxActionDistinguishesUncheckOnlyAndLockedModes() {
        val disabled = PackageInstallerOptions(disableDeletePackage = false, lockDeleteCheckBox = true)
        assertEquals(
            DeleteCheckBoxAction.NONE,
            resolveDeleteCheckBoxAction(disabled, isConfigurationChanged = false),
        )
        assertEquals(
            DeleteCheckBoxAction.NONE,
            resolveDeleteCheckBoxAction(disabled, isConfigurationChanged = true),
        )

        val nativeOverride = PackageInstallerOptions(
            nativeStyle = true,
            disableDeletePackage = true,
            lockDeleteCheckBox = true,
        )
        assertEquals(
            DeleteCheckBoxAction.NONE,
            resolveDeleteCheckBoxAction(nativeOverride, isConfigurationChanged = false),
        )

        val unlocked = PackageInstallerOptions(disableDeletePackage = true, lockDeleteCheckBox = false)
        assertEquals(
            DeleteCheckBoxAction.UNCHECK_ONLY,
            resolveDeleteCheckBoxAction(unlocked, isConfigurationChanged = false),
        )
        assertEquals(
            DeleteCheckBoxAction.NONE,
            resolveDeleteCheckBoxAction(unlocked, isConfigurationChanged = true),
        )

        val locked = PackageInstallerOptions(disableDeletePackage = true, lockDeleteCheckBox = true)
        assertEquals(
            DeleteCheckBoxAction.UNCHECK_AND_LOCK,
            resolveDeleteCheckBoxAction(locked, isConfigurationChanged = false),
        )
        assertEquals(
            DeleteCheckBoxAction.UNCHECK_AND_LOCK,
            resolveDeleteCheckBoxAction(locked, isConfigurationChanged = true),
        )
    }
}
