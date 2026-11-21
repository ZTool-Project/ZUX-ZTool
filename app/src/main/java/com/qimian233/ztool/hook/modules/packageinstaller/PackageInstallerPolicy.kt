package com.qimian233.ztool.hook.modules.packageinstaller

internal object PackageInstallerConstants {
    const val SCAN_APP_BEGIN = 1
    const val SCAN_APP_OK = 2
    const val SCAN_APP_BAD = 3
    const val SCAN_APP_TIMEOUT = 4

    const val CHECK_SAFEINSTALL_FINISH = 7
    const val CHECK_SAFEINSTALL_BAD = -1
    const val CHECK_SAFEINSTALL_OK = 1

    const val GOTO_SEARCH_NONE = -1
    const val INTERCEPT_PROMPT_NONE = 0
    const val INSTALL_TYPE_NONE = 0

    const val VERIFY_TYPE_DIRECT_INSTALL = 1
    const val PERMS_MANAGE_TYPE_TRUST = 2

    const val DLG_PERMS = 10
    const val DLG_PERMS_ALL_ALLOW = 12
}

internal data class PackageInstallerOptions(
    val nativeStyle: Boolean = false,
    val disableInstallerAd: Boolean = false,
    val disableDeletePackage: Boolean = false,
    val lockDeleteCheckBox: Boolean = false,
    val disableScanApk: Boolean = false,
    val alwaysAllowPermission: Boolean = false,
    val skipWarnPage: Boolean = false,
) {
    val hasOemFeatures: Boolean
        get() = disableInstallerAd ||
            disableDeletePackage ||
            disableScanApk ||
            alwaysAllowPermission ||
            skipWarnPage

    val enabled: Boolean get() = !nativeStyle && hasOemFeatures
    val needsScanFlow: Boolean get() = !nativeStyle && (disableScanApk || skipWarnPage)
    val needsPermissions: Boolean get() = !nativeStyle && alwaysAllowPermission
    val needsAdBlock: Boolean get() = !nativeStyle && disableInstallerAd
    val needsNoDelete: Boolean get() = !nativeStyle && disableDeletePackage

    fun intersect(installed: PackageInstallerOptions): PackageInstallerOptions {
        val effectiveNoDelete = disableDeletePackage && installed.disableDeletePackage
        return copy(
            disableInstallerAd = disableInstallerAd && installed.disableInstallerAd,
            disableDeletePackage = effectiveNoDelete,
            lockDeleteCheckBox = effectiveNoDelete && lockDeleteCheckBox,
            disableScanApk = disableScanApk && installed.disableScanApk,
            alwaysAllowPermission = alwaysAllowPermission && installed.alwaysAllowPermission,
            skipWarnPage = skipWarnPage && installed.skipWarnPage,
        )
    }
}

internal enum class DeleteCheckBoxAction {
    NONE,
    UNCHECK_ONLY,
    UNCHECK_AND_LOCK,
}

internal fun resolveDeleteCheckBoxAction(
    options: PackageInstallerOptions,
    isConfigurationChanged: Boolean,
): DeleteCheckBoxAction = when {
    !options.needsNoDelete -> DeleteCheckBoxAction.NONE
    options.lockDeleteCheckBox -> DeleteCheckBoxAction.UNCHECK_AND_LOCK
    isConfigurationChanged -> DeleteCheckBoxAction.NONE
    else -> DeleteCheckBoxAction.UNCHECK_ONLY
}

internal data class ScanResultSnapshot(
    val scanAppResult: Int,
    val checkSafeInstallResult: Int,
    val isNext: Boolean,
    val isClicked: Boolean,
    val forbidInstall: Boolean,
    val isFinishingOrDestroyed: Boolean,
)

internal class InstallFlowState {
    var advancedToConfirm: Boolean = false
        private set

    fun onShowScanApp() {
        advancedToConfirm = false
    }

    fun markAdvancedToConfirm() {
        advancedToConfirm = true
    }

    fun shouldSkipRedundantShowResult(options: PackageInstallerOptions): Boolean =
        options.skipWarnPage && advancedToConfirm

    fun shouldResetClickedOnConfigChange(
        options: PackageInstallerOptions,
        isConfigurationChanged: Boolean,
    ): Boolean = options.skipWarnPage && isConfigurationChanged

    fun shouldAutoAdvanceToConfirm(
        options: PackageInstallerOptions,
        snapshot: ScanResultSnapshot,
    ): Boolean =
        options.skipWarnPage &&
            !advancedToConfirm &&
            !snapshot.isFinishingOrDestroyed &&
            snapshot.scanAppResult != 0 &&
            snapshot.checkSafeInstallResult != 0 &&
            snapshot.scanAppResult != PackageInstallerConstants.SCAN_APP_BAD &&
            !snapshot.forbidInstall &&
            snapshot.isNext &&
            !snapshot.isClicked
}

internal fun shouldEnqueueCompletionMessage(
    hasScanOkQueued: Boolean,
    hasSafeCheckQueued: Boolean,
): Boolean = !hasScanOkQueued && !hasSafeCheckQueued

internal fun resolvePermsManageType(requestedType: Int, alwaysAllow: Boolean): Int =
    if (alwaysAllow) PackageInstallerConstants.PERMS_MANAGE_TYPE_TRUST else requestedType

internal fun resolvePermissionDialogId(dialogId: Int, alwaysAllow: Boolean): Int =
    if (alwaysAllow && dialogId == PackageInstallerConstants.DLG_PERMS) {
        PackageInstallerConstants.DLG_PERMS_ALL_ALLOW
    } else {
        dialogId
    }

internal data class V2ColorPalette(
    val surfaceContainerHigh: Int,
    val primary: Int,
    val onPrimary: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outlineVariant: Int,
)

internal fun isV2DarkColorFixActive(nativeStyle: Boolean, darkColorFix: Boolean): Boolean =
    nativeStyle && darkColorFix

internal fun shouldClearDialogChildBackground(
    backgroundColor: Int,
    hostSurfaceContainerHigh: Int,
    darkSurfaceContainerHigh: Int,
): Boolean =
    hostSurfaceContainerHigh != darkSurfaceContainerHigh &&
        backgroundColor == hostSurfaceContainerHigh

internal fun remapV2TextColor(
    currentColor: Int,
    host: V2ColorPalette,
    dark: V2ColorPalette,
): Int = when {
    currentColor == host.onSurface && host.onSurface != dark.onSurface -> dark.onSurface
    currentColor == host.onSurfaceVariant && host.onSurfaceVariant != dark.onSurfaceVariant ->
        dark.onSurfaceVariant
    currentColor == host.primary && host.primary != dark.primary -> dark.primary
    else -> currentColor
}

internal fun remapV2ButtonTextColor(
    currentColor: Int,
    host: V2ColorPalette,
    dark: V2ColorPalette,
): Int = when {
    currentColor == host.onPrimary && host.onPrimary != dark.onPrimary -> dark.onPrimary
    currentColor == host.primary && host.primary != dark.primary -> dark.primary
    else -> currentColor
}
