package com.qimian233.ztool.hook.modules.packageinstaller

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Shares Lenovo OEM PackageInstaller hooks and coordinates scan and warning flows. */
// See docs/research/packageinstaller_refactor_audit.md.
@SuppressLint("PrivateApi", "DiscouragedApi")
class LenovoPackageInstallerHook : AppHookModule() {

    private data class ScanHost(
        val activityExtraClass: Class<*>,
        val handlerField: Field,
        val scanAppResultField: Field,
        val checkSafeInstallResultField: Field,
        val showResultIfFinishMethod: Method,
        val isBindField: Field?,
        val isConnectField: Field?,
        val isScanBeginField: Field?,
        val isNextField: Field?,
        val isClickedField: Field?,
        val forbidInstallField: Field?,
        val isConfigChangedField: Field?,
        val gotoSearchField: Field?,
        val interceptPromptField: Field?,
        val installTypeField: Field?,
        val targetUrlField: Field?,
        val verifyTypeField: Field?,
        val safeInstallSettingContainerField: Field?,
        val onVerifySuccessMethod: Method?,
        val handleDirectInstallMethod: Method?,
        val onDirectInstallMethod: Method?,
    )

    private val flowSessions = Collections.synchronizedMap(WeakHashMap<Activity, InstallFlowState>())
    private var installed = PackageInstallerOptions()

    override fun getModuleName(): String = PreferenceKeys.DISABLE_SCAN_APK.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.PACKAGE_INSTALLER.packageName)

    override fun isEnabled(): Boolean = readOptions().enabled

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val requested = readOptions()
        if (!requested.enabled) return

        val loader = param.defaultClassLoader
        val scanHost = if (requested.needsScanFlow) {
            runCatching { resolveScanHost(loader) }
                .onFailure { logger.error("PackageInstallerExtra resolution failed", it) }
                .getOrNull()
        } else {
            null
        }

        val scanReady = requested.disableScanApk && scanHost != null &&
            installGroup("scan") { handles -> installScanSkip(scanHost, handles) }
        val warnReady = requested.skipWarnPage && scanHost != null &&
            installGroup("warn") { handles -> installWarnSkip(scanHost, handles) }
        val permsReady = requested.needsPermissions &&
            installGroup("permissions") { handles -> installPermissions(loader, handles) }
        val adReady = requested.needsAdBlock &&
            installGroup("ad block") { handles -> installAdBlock(loader, handles) }
        val noDeleteReady = requested.needsNoDelete &&
            installGroup("no delete") { handles -> installNoDelete(loader, handles) }

        installed = requested.copy(
            disableScanApk = requested.disableScanApk && scanReady,
            skipWarnPage = requested.skipWarnPage && warnReady,
            alwaysAllowPermission = requested.alwaysAllowPermission && permsReady,
            disableInstallerAd = requested.disableInstallerAd && adReady,
            disableDeletePackage = requested.disableDeletePackage && noDeleteReady,
        )
        logger.info(
            "Lenovo PackageInstaller installed: scan=${installed.disableScanApk}, " +
                "warn=${installed.skipWarnPage}, perms=${installed.alwaysAllowPermission}, " +
                "ad=${installed.disableInstallerAd}, noDelete=${installed.disableDeletePackage}"
        )
    }

    private fun installGroup(
        name: String,
        install: (MutableList<XposedInterface.HookHandle>) -> Unit,
    ): Boolean {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        return try {
            install(handles)
            true
        } catch (failure: Throwable) {
            handles.forEach { runCatching { it.unhook() } }
            logger.error("PackageInstaller $name hooks unavailable", failure)
            false
        }
    }

    private fun resolveScanHost(loader: ClassLoader): ScanHost {
        val cls = loader.loadClass(CLASS_ACTIVITY_EXTRA)
        return ScanHost(
            activityExtraClass = cls,
            handlerField = findField(cls, "mHander"),
            scanAppResultField = findField(cls, "mScanAppResult"),
            checkSafeInstallResultField = findField(cls, "mCheckSafeInstallResult"),
            showResultIfFinishMethod = findMethod(cls, "showResultIfFinish"),
            isBindField = runCatching { findField(cls, "isBind") }.getOrNull(),
            isConnectField = runCatching { findField(cls, "isConnect") }.getOrNull(),
            isScanBeginField = runCatching { findField(cls, "isScanBegin") }.getOrNull(),
            isNextField = runCatching { findField(cls, "isNext") }.getOrNull(),
            isClickedField = runCatching { findField(cls, "isClicked") }.getOrNull(),
            forbidInstallField = runCatching { findField(cls, "forbidInstall") }.getOrNull(),
            isConfigChangedField = runCatching { findField(cls, "isConfigurationChanged") }.getOrNull(),
            gotoSearchField = runCatching { findField(cls, "gotoSearch") }.getOrNull(),
            interceptPromptField = runCatching { findField(cls, "interceptPrompt") }.getOrNull(),
            installTypeField = runCatching { findField(cls, "mInstallType") }.getOrNull(),
            targetUrlField = runCatching { findField(cls, "mTargetUrl") }.getOrNull(),
            verifyTypeField = runCatching { findField(cls, "verifyType") }.getOrNull(),
            safeInstallSettingContainerField =
                runCatching { findField(cls, "safeInstallSettingContainer") }.getOrNull(),
            onVerifySuccessMethod = runCatching { findMethod(cls, "onVerifySuccess") }.getOrNull(),
            handleDirectInstallMethod =
                runCatching { findMethod(cls, "handleDirectInstallInFindSameAppCase") }.getOrNull(),
            onDirectInstallMethod = runCatching { findMethod(cls, "onDirectInstall") }.getOrNull(),
        )
    }

    private fun installScanSkip(host: ScanHost, handles: MutableList<XposedInterface.HookHandle>) {
        val cls = host.activityExtraClass
        handles += hookWithId(findMethod(cls, "bindSafeService"), "pi_oem_bind_safe_service") { chain ->
            if (!options().disableScanApk) return@hookWithId chain.proceed()
            val activity = chain.thisObject ?: return@hookWithId chain.proceed()
            host.isBindField?.setBoolean(activity, true)
            host.isConnectField?.setBoolean(activity, true)
            host.isScanBeginField?.setBoolean(activity, true)
            host.scanAppResultField.setInt(activity, PackageInstallerConstants.SCAN_APP_OK)
            enqueueCompletion(host, activity, PackageInstallerConstants.SCAN_APP_OK)
            logger.debug("Skipped bindSafeService and marked scan safe")
            null
        }
        handles += hookWithId(findMethod(cls, "startScanApps"), "pi_oem_start_scan_apps") { chain ->
            if (!options().disableScanApk) return@hookWithId chain.proceed()
            val activity = chain.thisObject ?: return@hookWithId chain.proceed()
            host.isScanBeginField?.setBoolean(activity, true)
            host.scanAppResultField.setInt(activity, PackageInstallerConstants.SCAN_APP_OK)
            enqueueCompletion(host, activity, PackageInstallerConstants.SCAN_APP_OK)
            logger.debug("Skipped startScanApps and marked scan safe")
            null
        }
        runCatching { findMethod(cls, "startTime") }.getOrNull()?.let { startTime ->
            handles += hookWithId(startTime, "pi_oem_start_time") { chain ->
                if (options().disableScanApk) null else chain.proceed()
            }
        }
    }

    private fun installWarnSkip(host: ScanHost, handles: MutableList<XposedInterface.HookHandle>) {
        val cls = host.activityExtraClass
        handles += hookWithId(findMethod(cls, "findSafeResource"), "pi_oem_find_safe_resource") { chain ->
            if (!options().skipWarnPage) return@hookWithId chain.proceed()
            val activity = chain.thisObject ?: return@hookWithId chain.proceed()
            clearCloudWarningState(host, activity)
            enqueueCompletion(host, activity, PackageInstallerConstants.CHECK_SAFEINSTALL_FINISH)
            logger.debug("Skipped findSafeResource cloud warning check")
            null
        }
        handles += hookWithId(findMethod(cls, "showScanApp"), "pi_oem_show_scan_app") { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as? Activity ?: return@hookWithId result
            val session = flowSession(activity)
            session.onShowScanApp()
            val isConfigChanged = host.isConfigChangedField?.getBoolean(activity) == true
            if (session.shouldResetClickedOnConfigChange(options(), isConfigChanged)) {
                host.isClickedField?.setBoolean(activity, false)
            }
            result
        }
        runCatching { findMethod(cls, "extraView", Intent::class.java) }.getOrNull()?.let { extraView ->
            handles += hookWithId(extraView, "pi_oem_extra_view") { chain ->
                val result = chain.proceed()
                if (options().skipWarnPage) {
                    val activity = chain.thisObject
                    if (activity != null) {
                        (host.safeInstallSettingContainerField?.get(activity) as? View)
                            ?.visibility = View.GONE
                    }
                }
                result
            }
        }
        if (host.onVerifySuccessMethod != null && host.handleDirectInstallMethod != null) {
            handles += hookWithId(
                host.handleDirectInstallMethod,
                "pi_oem_handle_direct_install"
            ) { chain ->
                if (!options().skipWarnPage) return@hookWithId chain.proceed()
                val activity = chain.thisObject ?: return@hookWithId chain.proceed()
                advanceToConfirm(host, activity)
                null
            }
        }
        handles += hookWithId(host.showResultIfFinishMethod, "pi_oem_show_result_if_finish") { chain ->
            val current = options()
            if (!current.skipWarnPage) return@hookWithId chain.proceed()
            val activity = chain.thisObject as? Activity ?: return@hookWithId chain.proceed()
            val session = flowSession(activity)
            if (session.shouldSkipRedundantShowResult(current)) return@hookWithId null
            if (host.checkSafeInstallResultField.getInt(activity) != 0) {
                clearCloudWarningState(host, activity)
            }
            val result = chain.proceed()
            val snapshot = ScanResultSnapshot(
                scanAppResult = host.scanAppResultField.getInt(activity),
                checkSafeInstallResult = host.checkSafeInstallResultField.getInt(activity),
                isNext = host.isNextField?.getBoolean(activity) == true,
                isClicked = host.isClickedField?.getBoolean(activity) == true,
                forbidInstall = host.forbidInstallField?.getBoolean(activity) == true,
                isFinishingOrDestroyed = activity.isFinishing || activity.isDestroyed,
            )
            if (session.shouldAutoAdvanceToConfirm(current, snapshot)) {
                session.markAdvancedToConfirm()
                advanceToConfirm(host, activity)
            }
            result
        }
    }

    private fun installPermissions(
        loader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        val activityExtraClass = loader.loadClass(CLASS_ACTIVITY_EXTRA)
        val appPermsInfoDataClass = loader.loadClass(CLASS_APP_PERMS_INFO_DATA)
        val permissionsAdapterClass = loader.loadClass(CLASS_PERMISSIONS_ADAPTER)
        val permCustomItemClass = loader.loadClass(CLASS_PERM_CUSTOM_ITEM)

        val selectIdField = findField(permissionsAdapterClass, "selectId")
        val itemIndexField = findField(permCustomItemClass, "index")
        val activitySelectIdField = findField(activityExtraClass, "mSelectId")

        val setPermsManageType = findMethod(
            appPermsInfoDataClass,
            "setPermsManageType",
            Int::class.javaPrimitiveType
        )
        handles += hookWithId(setPermsManageType, "pi_oem_set_perms_manage_type") { chain ->
            val requested = chain.args[0] as Int
            val resolved = resolvePermsManageType(requested, options().alwaysAllowPermission)
            if (resolved != requested) {
                chain.proceed(arrayOf<Any?>(resolved))
            } else {
                chain.proceed()
            }
        }

        val startCustomInstallConfirm = findMethod(activityExtraClass, "startCustomInstallConfirm")
        handles += hookWithId(startCustomInstallConfirm, "pi_oem_start_custom_install_confirm") { chain ->
            if (options().alwaysAllowPermission) {
                chain.thisObject?.let {
                    activitySelectIdField.setInt(it, PackageInstallerConstants.PERMS_MANAGE_TYPE_TRUST)
                }
            }
            chain.proceed()
        }

        val ctor = permissionsAdapterClass.getDeclaredConstructor(
            LayoutInflater::class.java,
            ArrayList::class.java,
            Handler::class.java
        ).apply { isAccessible = true }
        handles += hookWithId(ctor, "pi_oem_permissions_adapter_ctor") { chain ->
            val alwaysAllow = options().alwaysAllowPermission
            if (alwaysAllow) {
                @Suppress("UNCHECKED_CAST")
                val items = chain.args[1] as? ArrayList<Any>
                val trustItem = items?.firstOrNull { item ->
                    runCatching {
                        itemIndexField.getInt(item) == PackageInstallerConstants.PERMS_MANAGE_TYPE_TRUST
                    }.getOrDefault(false)
                }
                if (items != null && trustItem != null) {
                    items.clear()
                    items.add(trustItem)
                }
            }
            chain.proceed()
            if (alwaysAllow) {
                chain.thisObject?.let {
                    selectIdField.setInt(it, PackageInstallerConstants.PERMS_MANAGE_TYPE_TRUST)
                }
            }
            null
        }

        runCatching {
            findMethod(activityExtraClass, "showDialogInner", Int::class.javaPrimitiveType)
        }.getOrNull()?.let { showDialogInner ->
            handles += hookWithId(showDialogInner, "pi_oem_show_dialog_inner") { chain ->
                val dialogId = chain.args[0] as Int
                val resolved = resolvePermissionDialogId(dialogId, options().alwaysAllowPermission)
                if (resolved != dialogId) {
                    chain.proceed(arrayOf<Any?>(resolved))
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun installAdBlock(
        loader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        val installSuccessClass = loader.loadClass(CLASS_INSTALL_SUCCESS_EXTRA)
        handles += hookWithId(
            findMethod(installSuccessClass, "initRecommendAppsData"),
            "pi_oem_init_recommend_apps"
        ) { chain ->
            if (options().disableInstallerAd) null else chain.proceed()
        }

        runCatching {
            findMethod(loader.loadClass(CLASS_INSTALL_INSTALLING_EXTRA), "getRecommendApp")
        }.getOrNull()?.let { method ->
            handles += hookWithId(method, "pi_oem_get_recommend_app") { chain ->
                if (options().disableInstallerAd) null else chain.proceed()
            }
        }

        runCatching {
            findMethod(loader.loadClass(CLASS_ACTIVITY_EXTRA), "loadExpressAd")
        }.getOrNull()?.let { method ->
            handles += hookWithId(method, "pi_oem_load_express_ad") { chain ->
                if (options().disableInstallerAd) null else chain.proceed()
            }
        }

        runCatching {
            findMethod(
                loader.loadClass(CLASS_LENOVO_AD_MANAGER),
                "init",
                Context::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()?.let { method ->
            handles += hookWithId(method, "pi_oem_ad_sdk_init") { chain ->
                if (options().disableInstallerAd) false else chain.proceed()
            }
        }
    }

    private fun installNoDelete(
        loader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        val installSuccessClass = loader.loadClass(CLASS_INSTALL_SUCCESS_EXTRA)
        val initView = findMethod(installSuccessClass, "initView")
        val mDeleteApkField = findField(installSuccessClass, "mDeleteApk")
        val delCheckBoxId = runCatching {
            findField(loader.loadClass($$"com.android.packageinstaller.R$id"), "del_check_box")
                .getInt(null)
        }.getOrDefault(0)

        handles += hookWithId(initView, "pi_oem_install_success_init_view") { chain ->
            val result = chain.proceed()
            if (!options().disableDeletePackage) return@hookWithId result
            val instance = chain.thisObject ?: return@hookWithId result
            mDeleteApkField.setBoolean(instance, false)

            val activity = instance as? Activity ?: return@hookWithId result
            val viewId = if (delCheckBoxId != 0) {
                delCheckBoxId
            } else {
                activity.resources.getIdentifier(
                    "del_check_box",
                    "id",
                    ScopeKeys.PACKAGE_INSTALLER.packageName
                )
            }
            if (viewId != 0) {
                val checkBox = activity.findViewById<View?>(viewId) as? CheckBox
                if (checkBox != null) {
                    checkBox.setOnCheckedChangeListener(null)
                    checkBox.isChecked = false
                    checkBox.setOnCheckedChangeListener { buttonView, isChecked ->
                        runCatching { mDeleteApkField.setBoolean(instance, false) }
                        if (isChecked) buttonView.isChecked = false
                    }
                }
            } else {
                logger.warn("CheckBox resource ID 'del_check_box' not found")
            }
            result
        }
    }

    private fun clearCloudWarningState(host: ScanHost, activity: Any) {
        host.checkSafeInstallResultField.setInt(
            activity,
            PackageInstallerConstants.CHECK_SAFEINSTALL_BAD
        )
        host.gotoSearchField?.setInt(activity, PackageInstallerConstants.GOTO_SEARCH_NONE)
        host.interceptPromptField?.setInt(activity, PackageInstallerConstants.INTERCEPT_PROMPT_NONE)
        host.installTypeField?.setInt(activity, PackageInstallerConstants.INSTALL_TYPE_NONE)
        host.targetUrlField?.set(activity, null)
    }

    private fun enqueueCompletion(host: ScanHost, activity: Any, what: Int) {
        val handler = host.handlerField.get(activity) as? Handler ?: return
        val hasScanOk = handler.hasMessages(PackageInstallerConstants.SCAN_APP_OK)
        val hasSafeFinish = handler.hasMessages(PackageInstallerConstants.CHECK_SAFEINSTALL_FINISH)
        if (shouldEnqueueCompletionMessage(hasScanOk, hasSafeFinish)) {
            handler.sendEmptyMessage(what)
        }
    }

    private fun advanceToConfirm(host: ScanHost, activity: Any) {
        if (host.onVerifySuccessMethod != null) {
            host.verifyTypeField?.setInt(activity, PackageInstallerConstants.VERIFY_TYPE_DIRECT_INSTALL)
            host.onVerifySuccessMethod.invoke(activity)
            logger.debug("Advanced past warning page via onVerifySuccess")
            return
        }
        val fallback = host.handleDirectInstallMethod ?: host.onDirectInstallMethod
        if (fallback != null) {
            fallback.invoke(activity)
            logger.debug("Advanced past warning page via ${fallback.name}")
        } else {
            logger.warn("No install confirmation method available on PackageInstallerActivityExtra")
        }
    }

    private fun flowSession(activity: Activity): InstallFlowState =
        synchronized(flowSessions) {
            flowSessions.getOrPut(activity) { InstallFlowState() }
        }

    private fun options(): PackageInstallerOptions = readOptions().intersect(installed)

    private fun readOptions(): PackageInstallerOptions = runCatching {
        val prefs = remotePreferences
        PackageInstallerOptions(
            nativeStyle = prefs.getBoolean(
                PreferenceKeys.PACKAGE_INSTALLER_STYLE_HOOK.name,
                PreferenceKeys.PACKAGE_INSTALLER_STYLE_HOOK.default
            ),
            disableInstallerAd = prefs.getBoolean(
                PreferenceKeys.DISABLE_INSTALLER_AD.name,
                PreferenceKeys.DISABLE_INSTALLER_AD.default
            ),
            disableDeletePackage = prefs.getBoolean(
                PreferenceKeys.PACKAGE_INSTALLER_DISABLE_DELETE.name,
                PreferenceKeys.PACKAGE_INSTALLER_DISABLE_DELETE.default
            ),
            disableScanApk = prefs.getBoolean(
                PreferenceKeys.DISABLE_SCAN_APK.name,
                PreferenceKeys.DISABLE_SCAN_APK.default
            ),
            alwaysAllowPermission = prefs.getBoolean(
                PreferenceKeys.ALWAYS_ALLOW_PERMISSION.name,
                PreferenceKeys.ALWAYS_ALLOW_PERMISSION.default
            ),
            skipWarnPage = prefs.getBoolean(
                PreferenceKeys.SKIP_WARN_PAGE.name,
                PreferenceKeys.SKIP_WARN_PAGE.default
            ),
        )
    }.getOrDefault(PackageInstallerOptions())

    private companion object {
        const val CLASS_ACTIVITY_EXTRA = "com.android.packageinstaller.PackageInstallerActivityExtra"
        const val CLASS_INSTALL_INSTALLING_EXTRA = "com.android.packageinstaller.InstallInstallingExtra"
        const val CLASS_INSTALL_SUCCESS_EXTRA = "com.android.packageinstaller.InstallSuccessExtra"
        const val CLASS_PERMISSIONS_ADAPTER = "com.android.packageinstaller.extra.PermissionsAdapter"
        const val CLASS_APP_PERMS_INFO_DATA = "com.android.packageinstaller.extra.AppPermsInfoData"
        const val CLASS_PERM_CUSTOM_ITEM = "com.android.packageinstaller.extra.PermCustomItem"
        const val CLASS_LENOVO_AD_MANAGER = "com.lenovo.adxsdk.sdk.LenovoAdManager"
    }
}
