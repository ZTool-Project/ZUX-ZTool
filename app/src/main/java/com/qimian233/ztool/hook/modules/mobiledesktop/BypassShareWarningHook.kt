package com.qimian233.ztool.hook.modules.mobiledesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Bypasses the nearby-share exposure warning dialog in Super Interconnect
 * (FileUnion) by auto-confirming it.
 */
class BypassShareWarningHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.BYPASS_SHARE_WARNING.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.MOBILE_DESKTOP.packageName)

    @SuppressLint("DiscouragedApi")
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        try {
            val dialogClass = classLoader.loadClass(DIALOG_CLASS)
            val onCreateMethod = dialogClass.getDeclaredMethod("onCreate", Bundle::class.java)
            hookWithId(onCreateMethod, "dialog_on_create") { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity ?: return@hookWithId result
                val intent = activity.intent ?: return@hookWithId result
                if (intent.getIntExtra(EXTRA_DIALOG_EVENT, 0) != EVENT_FILE_SHARE_EXPOSE ||
                    intent.getIntExtra(EXTRA_DIALOG_STYLE, 0) != STYLE_FILE_SHARE_EXPOSE
                ) {
                    return@hookWithId result
                }
                val positiveId = activity.resources.getIdentifier(
                    RES_POSITIVE_BUTTON, "id", activity.packageName
                )
                if (positiveId == 0) {
                    logger.warn("Resource id/$RES_POSITIVE_BUTTON not found in ${activity.packageName}")
                    return@hookWithId result
                }
                val positiveButton = activity.findViewById<View>(positiveId)
                if (positiveButton == null) {
                    logger.warn("View id/$RES_POSITIVE_BUTTON not found in $DIALOG_CLASS")
                    return@hookWithId result
                }
                // Triggering the positive button preserves native BT/Wi-Fi setup, permissions, and finish().
                positiveButton.performClick()
                logger.debug("Auto-confirmed share exposure dialog via $RES_POSITIVE_BUTTON.performClick()")
                result
            }
            logger.info("Installed hook for $DIALOG_CLASS.onCreate")
        } catch (t: Throwable) {
            logger.error("Failed to install bypass share warning hook", t)
        }
    }

    private companion object {
        const val DIALOG_CLASS =
            "com.motorola.readyfor.common.dialog.ActionNoticeCommonDialogActivity"
        const val EXTRA_DIALOG_STYLE = "dialog_style"
        const val EXTRA_DIALOG_EVENT = "dialog_event"
        const val STYLE_FILE_SHARE_EXPOSE = 20
        const val EVENT_FILE_SHARE_EXPOSE = 20
        const val RES_POSITIVE_BUTTON = "positiveTv"
    }
}
