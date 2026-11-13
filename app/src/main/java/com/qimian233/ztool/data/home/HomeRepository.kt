package com.qimian233.ztool.data.home

import android.content.Context
import android.os.Build
import android.util.Log
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.ModuleActivationProbe
import com.qimian233.ztool.R
import com.qimian233.ztool.utils.ModulePreferencesUtils
import com.qimian233.ztool.utils.ConfigUpgrade
import com.qimian233.ztool.utils.SystemInfoProbe
import com.qimian233.ztool.viewmodel.UpdateInfo
import androidx.core.content.edit
import com.qimian233.ztool.XposedServiceBridge
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.update.ReleaseFeedClient
import com.qimian233.ztool.data.update.ReleaseSelector
import com.qimian233.ztool.data.update.UpdatePreferenceKeys

class HomeRepository(
    private val context: Context,
    private val shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance(),
    private val moduleActiveChecker: () -> Boolean = ModuleActivationProbe::isModuleActive
) {
    private var cachedKernelVersion = ""
    private var cachedRootSource = ""
    private var cachedFrameworkVersion = ""
    private var cachedCurrentSlot = ""
    private var cachedRomRegion = ""
    private var cachedIsZuxOsDevice = true
    private var lastSystemInfoUpdate = 0L

    fun checkEnvironment(): EnvironmentStatus {
        val moduleActive = isModuleActive()
        val rootAvailable = shellExecutor.checkRootAccess().isSuccess
        return EnvironmentStatus(
            moduleActive = moduleActive,
            rootAvailable = rootAvailable
        )
    }

    fun updateModuleStatus(): ModuleStatus {
        val version = SystemInfoProbe.appVersionLabel(context)
        if (cachedRootSource.isEmpty() || isSystemInfoCacheExpired()) {
            cachedRootSource = SystemInfoProbe.rootSource(context, shellExecutor)
        }
        if (cachedFrameworkVersion.isEmpty() || isSystemInfoCacheExpired()) {
            val framework = SystemInfoProbe.frameworkInfo()
            cachedFrameworkVersion = if (framework == null) {
                context.getString(R.string.page_home_unknown_framework)
            } else {
                SystemInfoProbe.formatFramework(context, framework)
            }
        }
        return ModuleStatus(
            moduleVersion = version,
            rootSource = cachedRootSource,
            frameworkVersion = cachedFrameworkVersion,
            apiVersion = XposedServiceBridge.getApiVersion()
        )
    }

    fun updateSystemInfo(): SystemInfo {
        val unknown = context.getString(R.string.common_unknown)
        val deviceModel = Build.MODEL.ifBlank { unknown }
        val androidVersion = Build.VERSION.RELEASE.ifBlank { unknown }
            .let { if (it == unknown) it else context.getString(R.string.page_home_android_version_prefix, it) }
        val buildVersion = Build.DISPLAY.ifBlank { unknown }

        if (cachedKernelVersion.isEmpty() || isSystemInfoCacheExpired()) {
            cachedKernelVersion = SystemInfoProbe.kernelVersion()
        }
        if (cachedCurrentSlot.isEmpty() || isSystemInfoCacheExpired()) {
            cachedCurrentSlot = getCurrentBootSlot()
        }
        if (cachedRomRegion.isEmpty() || isSystemInfoCacheExpired()) {
            cachedRomRegion = SystemInfoProbe.romRegion(context, shellExecutor)
        }
        if (isSystemInfoCacheExpired()) {
            cachedIsZuxOsDevice = isZuxOsBuild(Build.DISPLAY)
        }

        lastSystemInfoUpdate = System.currentTimeMillis()

        return SystemInfo(
            deviceModel = deviceModel,
            androidVersion = androidVersion,
            buildVersion = buildVersion,
            kernelVersion = cachedKernelVersion.ifBlank { unknown },
            currentSlot = cachedCurrentSlot.ifBlank { unknown },
            romRegion = cachedRomRegion.ifBlank { unknown },
            isZuxOsDevice = cachedIsZuxOsDevice
        )
    }

    fun shouldRefreshSystemInfo(): Boolean = isSystemInfoCacheExpired()

    fun isNonZuxOsWarningDismissed(): Boolean {
        return context.getSharedPreferences(PREF_NAME_HOME_UI, Context.MODE_PRIVATE)
            .getBoolean(KEY_NON_ZUXOS_WARNING_DISMISSED, false)
    }

    fun dismissNonZuxOsWarning() {
        context.getSharedPreferences(PREF_NAME_HOME_UI, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_NON_ZUXOS_WARNING_DISMISSED, true) }
    }

    fun isAutoCheckUpdateEnabled(): Boolean {
        return ModulePreferencesUtils(context)
            .loadBooleanSetting(PreferenceKeys.AUTO_CHECK_UPDATE.name, true)
    }

    fun isGhProxyUpdateEnabled(): Boolean {
        return ModulePreferencesUtils(context).loadBooleanSetting(
            PreferenceKeys.USE_GH_PROXY_FOR_UPDATE.name,
            PreferenceKeys.USE_GH_PROXY_FOR_UPDATE.default
        )
    }

    fun checkConfigUpgrade(): Boolean = ConfigUpgrade.configUpgrader(context)

    fun checkAppUpdate(): UpdateCheckResult {
        val currentVersionCode = getCurrentVersionCode()
        val receivePrerelease = isPrereleaseChannelEnabled()
        val ignoredVersion = context
            .getSharedPreferences(UpdatePreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(UpdatePreferenceKeys.KEY_IGNORE_VERSION, 0)

        return try {
            val entry = ReleaseSelector.select(
                entries = ReleaseFeedClient(useGhProxy = isGhProxyUpdateEnabled()).load(),
                installedCode = currentVersionCode,
                receivePrerelease = receivePrerelease,
                ignoredCode = ignoredVersion
            )
            if (entry == null) {
                Log.i(TAG, "No update available: installed=$currentVersionCode prerelease=$receivePrerelease")
                UpdateCheckResult.Success(null)
            } else {
                Log.i(TAG, "Update available: ${entry.tag} (c${entry.versionCode}) on ${entry.channel}")
                UpdateCheckResult.Success(
                    UpdateInfo(
                        versionName = entry.tag,
                        versionCode = entry.versionCode,
                        changelog = entry.notes,
                        downloadUrl = entry.releaseUrl,
                        hasMedia = entry.hasMedia
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check update: ${e.message}")
            UpdateCheckResult.Failure(describeUpdateCheckError(e))
        }
    }

    fun isPrereleaseChannelEnabled(): Boolean {
        return context.getSharedPreferences(UpdatePreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(UpdatePreferenceKeys.KEY_RECEIVE_PRERELEASE, false)
    }

    fun setPrereleaseChannelEnabled(enabled: Boolean) {
        context.getSharedPreferences(UpdatePreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(UpdatePreferenceKeys.KEY_RECEIVE_PRERELEASE, enabled) }
    }

    private fun describeUpdateCheckError(error: Exception?): String {
        if (error == null) return context.getString(R.string.common_unknown)
        return error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    fun ignoreUpdate(versionCode: Int) {
        context
            .getSharedPreferences(UpdatePreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(UpdatePreferenceKeys.KEY_IGNORE_VERSION, versionCode)
            }
    }

    fun executeReboot(command: String): RebootResult {
        val result = shellExecutor.executeRootCommand(command, 5)
        return RebootResult(
            success = result.isSuccess,
            error = result.error
        )
    }

    fun restartAfterConfigUpgrade(): RebootResult {
        val result = shellExecutor.executeRootCommand("su -c reboot", 3)
        return RebootResult(
            success = result.isSuccess,
            error = result.error
        )
    }

    fun clearShellCache() {
        shellExecutor.clearCache()
    }

    private fun getCurrentVersionCode(): Int {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    }

    private fun getCurrentBootSlot(): String {
        val result = shellExecutor.executeRootCommand("getprop ro.boot.slot_suffix", 3)
        return if (result.isSuccess && !result.output.isBlank()) {
            when (result.output.trim()) {
                "_a" -> context.getString(R.string.page_home_slot_a)
                "_b" -> context.getString(R.string.page_home_slot_b)
                else -> context.getString(R.string.common_unknown)
            }
        } else {
            context.getString(R.string.common_unknown)
        }
    }

    private fun isSystemInfoCacheExpired(): Boolean {
        return System.currentTimeMillis() - lastSystemInfoUpdate > SYSTEM_INFO_CACHE_DURATION
    }

    private fun isModuleActive(): Boolean {
        return moduleActiveChecker()
    }

    companion object {
        private const val TAG = "HomeRepository"
        private const val PREF_NAME_HOME_UI = "home_ui_prefs"
        private const val KEY_NON_ZUXOS_WARNING_DISMISSED = "non_zuxos_warning_dismissed"
        private const val SYSTEM_INFO_CACHE_DURATION = 60_000L

        /** Identifiers recognized (after lowercasing) as a correct ROM if contained in any build-mark group. */
        private val ROM_IDENTIFIERS = listOf("zui", "zuxos", "helloui")

        /** TB model prefix: TB + 3 digits + optional 2 letters, e.g. TB710FU / TB324ZC / TB710. */
        private val TB_MODEL_PREFIX = Regex("^tb\\d{3}([a-z]{2})?$")

        /**
         * Judge whether the build marks come from a ZUXOS/ZUI-family ROM.
         * Splits by underscore, lowercases and strips spaces per entry, then matches ROM
         * identifiers (zui/zuxos/helloui) first; if none match, the build is still treated as
         * a correct device when the first group is a Lenovo TB model code (TB710FU, TB324ZC, TB710, etc.).
         */
        fun isZuxOsBuild(buildDisplay: String): Boolean {
            val entries = buildDisplay.split("_").map { it.lowercase().replace(" ", "") }
            val foundIdentifier = entries.any { entry -> ROM_IDENTIFIERS.any { entry.contains(it) } }
            if (foundIdentifier) return true

            val firstEntry = entries.firstOrNull() ?: return false
            return TB_MODEL_PREFIX.matches(firstEntry)
        }

    }
}

data class EnvironmentStatus(
    val moduleActive: Boolean,
    val rootAvailable: Boolean
)

data class ModuleStatus(
    val moduleVersion: String,
    val rootSource: String,
    val frameworkVersion: String,
    val apiVersion: Int
)

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val buildVersion: String,
    val kernelVersion: String,
    val currentSlot: String,
    val romRegion: String,
    val isZuxOsDevice: Boolean
)

data class RebootResult(
    val success: Boolean,
    val error: String
)

/** Update check result: success (possibly no update) or failure (with a reason). */
sealed class UpdateCheckResult {
    data class Success(val updateInfo: UpdateInfo?) : UpdateCheckResult()
    data class Failure(val reason: String) : UpdateCheckResult()
}
