package com.qimian233.ztool.hook.modules.setting

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("PrivateApi", "DiscouragedApi")
class AppInfoHeaderDetailsHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.APP_DETAILS.name

    override fun getTargetPackages(): Array<String> = arrayOf(TARGET_PACKAGE)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val packageName = param.packageName
        if (TARGET_PACKAGE != packageName) {
            return
        }

        val appEntryClass = try {
            classLoader.loadClass(APP_ENTRY_CLASS)
        } catch (_: ClassNotFoundException) {
            null
        }
        if (appEntryClass == null) {
            logger.warn("AppEntry class not found, skip app info header hook.")
            return
        }

        val m: Method = classLoader
            .loadClass(CONTROLLER_CLASS)
            .getDeclaredMethod("setAppLabelAndIcon", PackageInfo::class.java, appEntryClass)
        hookWithId(m, "hook_76") { chain ->
            val result = chain.proceed()
            try {
                val pkgInfo = chain.args[0] as? PackageInfo
                if (pkgInfo == null || pkgInfo.applicationInfo == null) {
                    return@hookWithId result
                }

                val mContextField: Field = findField(chain.thisObject.javaClass, "mContext")
                mContextField.isAccessible = true
                val context = mContextField.get(chain.thisObject) as Context

                val mHeaderField: Field = findField(chain.thisObject.javaClass, "mHeader")
                mHeaderField.isAccessible = true
                val headerPreference = mHeaderField.get(chain.thisObject)

                val summaryView = findSummaryView(context, headerPreference)
                if (summaryView == null) {
                    logger.warn("entity_header_summary not found.")
                    return@hookWithId result
                }

                val appInfo = buildAppInfo(summaryView.context, pkgInfo)
                if (TextUtils.isEmpty(appInfo)) {
                    return@hookWithId result
                }

                val originalSummary = summaryView.text
                val displayText = mergeSummary(originalSummary, appInfo, pkgInfo.packageName)
                summaryView.isSingleLine = false
                summaryView.maxLines = Integer.MAX_VALUE
                summaryView.text = displayText
                summaryView.setOnLongClickListener { v ->
                    copyToClipboard(v.context, displayText)
                    true
                }
            } catch (t: Throwable) {
                logger.error("Failed to update app info header summary", t)
            }
            result
        }
        logger.info("Hooked AppHeaderViewPreferenceController#setAppLabelAndIcon.")
    }

    private fun findSummaryView(context: Context?, headerPreference: Any?): TextView? {
        if (context == null || headerPreference == null) {
            return null
        }

        val summaryId = context.resources.getIdentifier(
            "entity_header_summary", "id", TARGET_PACKAGE
        )
        if (summaryId == 0) {
            return null
        }

        try {
            val findViewById: Method =
                headerPreference.javaClass.getDeclaredMethod("findViewById", Int::class.javaPrimitiveType)
            val headerView = findViewById.invoke(headerPreference, summaryId) as View
            if (headerView is TextView) {
                return headerView
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun loadModuleResources(hostContext: Context): Resources? = try {
        hostContext.createPackageContext(
            MODULE_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY
        ).resources
    } catch (t: Throwable) {
        logger.warn("Failed to load ZTool resources: ${t.message}")
        null
    }

    private fun moduleString(
        moduleResources: Resources?,
        resourceName: String,
        fallback: String
    ): String {
        if (moduleResources == null) return fallback
        return try {
            val resId = moduleResources.getIdentifier(resourceName, "string", MODULE_PACKAGE)
            if (resId != 0) moduleResources.getString(resId) else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun buildAppInfo(context: Context, pkgInfo: PackageInfo): String {
        val moduleResources = loadModuleResources(context)
        val unknownLabel = moduleString(moduleResources, STRING_UNKNOWN_LABEL, FALLBACK_UNKNOWN_LABEL)
        val summaryTemplate = moduleString(moduleResources, STRING_SUMMARY, FALLBACK_SUMMARY)
        val isFallback = moduleResources == null || summaryTemplate == FALLBACK_SUMMARY
        val appInfo: ApplicationInfo = pkgInfo.applicationInfo!!
        return String.format(
            Locale.getDefault(),
            summaryTemplate,
            pkgInfo.packageName,
            appInfo.minSdkVersion,
            appInfo.targetSdkVersion,
            formatTime(pkgInfo.firstInstallTime, unknownLabel, isFallback),
            formatTime(pkgInfo.lastUpdateTime, unknownLabel, isFallback),
            getInstallSource(context, pkgInfo.packageName, unknownLabel)
        )
    }

    private fun mergeSummary(
        originalSummary: CharSequence?,
        appInfo: String,
        packageName: String
    ): String {
        if (TextUtils.isEmpty(originalSummary)) {
            return appInfo
        }
        val original = originalSummary.toString()
        if (original.contains(appInfo)) {
            return original
        }
        if (original.contains(packageName) || original.contains(FALLBACK_MARKER)) {
            return appInfo
        }
        return original + "\n" + appInfo
    }

    private fun formatTime(timeMillis: Long, unknownLabel: String, compact: Boolean): String {
        if (timeMillis <= 0L) {
            return unknownLabel
        }
        val pattern = if (compact) "yyyyMMdd-HHmmss" else "yyyy-MM-dd HH:mm:ss"
        return SimpleDateFormat(pattern, Locale.getDefault())
            .format(Date(timeMillis))
    }

    private fun getInstallSource(
        context: Context,
        packageName: String,
        unknownLabel: String
    ): String {
        try {
            val pm = context.packageManager
            val source: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sourceInfo: InstallSourceInfo = pm.getInstallSourceInfo(packageName)
                firstNonEmpty(
                    sourceInfo.installingPackageName,
                    sourceInfo.initiatingPackageName,
                    sourceInfo.originatingPackageName
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
            if (TextUtils.isEmpty(source)) {
                return unknownLabel
            }

            val label = getApplicationLabel(pm, source!!)
            if (!TextUtils.isEmpty(label)) {
                return "$label ($source)"
            }
            return source
        } catch (_: Throwable) {
            return unknownLabel
        }
    }

    private fun getApplicationLabel(pm: PackageManager, packageName: String): CharSequence? {
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
        } catch (_: Throwable) {
            null
        }
    }

    private fun firstNonEmpty(first: String?, second: String?, third: String?): String? {
        if (!TextUtils.isEmpty(first)) {
            return first
        }
        if (!TextUtils.isEmpty(second)) {
            return second
        }
        return if (TextUtils.isEmpty(third)) null else third
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("app_info", text))
            val message = moduleString(
                loadModuleResources(context),
                STRING_COPIED_MESSAGE,
                FALLBACK_COPIED_MESSAGE
            )
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val MODULE_PACKAGE = "com.qimian233.ztool"
        private val TARGET_PACKAGE = ScopeKeys.SETTINGS.packageName
        private const val CONTROLLER_CLASS =
            "com.android.settings.applications.appinfo.AppHeaderViewPreferenceController"
        private const val APP_ENTRY_CLASS =
            $$"com.android.settingslib.applications.ApplicationsState$AppEntry"

        private const val STRING_SUMMARY = "ztool_app_info_header_summary"
        private const val STRING_UNKNOWN_LABEL = "ztool_app_info_header_unknown_label"
        private const val STRING_COPIED_MESSAGE = "ztool_app_info_header_copied_message"

        private const val FALLBACK_MARKER = "[Fallback]"
        private const val FALLBACK_SUMMARY =
            "min %2\$d, target %3\$d, first installed %4\$s, last updated %5\$s, installed from: %6\$s. $FALLBACK_MARKER"
        private const val FALLBACK_UNKNOWN_LABEL = "Unknown"
        private const val FALLBACK_COPIED_MESSAGE = "Copied to clipboard $FALLBACK_MARKER"
    }
}
