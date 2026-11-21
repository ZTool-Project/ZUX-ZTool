package com.qimian233.ztool.hook.modules.packageinstaller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.TextView
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** Switches ZUI PackageInstaller to AOSP mode and applies dialog styling. */
// See docs/research/packageinstaller_refactor_audit.md.
@SuppressLint("PrivateApi")
class PackageInstallerStyleHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.PACKAGE_INSTALLER_STYLE_HOOK.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.PACKAGE_INSTALLER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        hookInstallationRestrictions(classLoader)
        hookActivityStyles(classLoader)
        doNotShowWarnTextHook(classLoader)
    }

    private fun hookInstallationRestrictions(classLoader: ClassLoader) {
        try {
            val utilsClass = classLoader.loadClass("com.android.packageinstaller.extra.Utils")
            val isCTSandGTS1 = findMethod(utilsClass, "isCTSandGTS", String::class.java)
            hookWithId(isCTSandGTS1, "pi_native_is_cts_gts_1") { chain ->
                if (isEnabled()) true else chain.proceed()
            }

            val isCTSandGTS2 =
                findMethod(utilsClass, "isCTSandGTS", String::class.java, Intent::class.java)
            hookWithId(isCTSandGTS2, "pi_native_is_cts_gts_2") { chain ->
                if (isEnabled()) true else chain.proceed()
            }

            logger.info("Successfully hooked native installer CTS/GTS checks")
        } catch (t: Throwable) {
            logger.error("Failed to hook native installer CTS/GTS checks", t)
        }
    }

    private fun hookActivityStyles(classLoader: ClassLoader) {
        try {
            val styleClass = classLoader.loadClass($$"com.android.packageinstaller.R$style")
            val themeAlertDialogActivity =
                findField(styleClass, "Theme_AlertDialogActivity").getInt(null)
            val onCreate = findMethod(Activity::class.java, "onCreate", Bundle::class.java)

            hookWithId(onCreate, "pi_native_activity_on_create") { chain ->
                val activity = chain.thisObject as? Activity
                if (isEnabled() &&
                    activity != null &&
                    activity.packageName == ScopeKeys.PACKAGE_INSTALLER.packageName
                ) {
                    try {
                        activity.setTheme(themeAlertDialogActivity)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            activity.setTranslucent(true)
                        }
                        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
                        activity.window.setWindowAnimations(0)
                    } catch (t: Throwable) {
                        logger.error("Error while modifying Activity style", t)
                    }
                }
                chain.proceed()
            }

            logger.info("Successfully hooked Activity style modification")
        } catch (t: Throwable) {
            logger.error("Failed to hook Activity style modification", t)
        }
    }

    private fun doNotShowWarnTextHook(classLoader: ClassLoader) {
        val warnTextViewId = runCatching {
            val idClass = classLoader.loadClass($$"com.android.packageinstaller.R$id")
            findField(idClass, "install_confirm_question_warning").getInt(null)
        }.getOrNull()
        if (warnTextViewId == null) {
            logger.debug("install_confirm_question_warning not present on host; skipping warn-text hook")
            return
        }

        try {
            val activityClass = classLoader.loadClass("com.android.packageinstaller.PackageInstallerActivity")
            val startInstallConfirm = findMethod(activityClass, "startInstallConfirm")
            val mDialogField = findField(activityClass, "mDialog")

            hookWithId(startInstallConfirm, "pi_native_start_install_confirm") { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@hookWithId result
                val dialog = runCatching {
                    mDialogField.get(chain.thisObject) as? AlertDialog
                }.getOrNull()
                dialog?.findViewById<TextView>(warnTextViewId)?.visibility = View.GONE
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook doNotShowWarnTextHook", t)
        }
    }
}
