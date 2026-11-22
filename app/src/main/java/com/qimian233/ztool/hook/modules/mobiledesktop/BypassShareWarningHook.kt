package com.qimian233.ztool.hook.modules.mobiledesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.core.content.edit
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Bypasses the nearby-share exposure warning dialog in Super Interconnect
 * (FileUnion): enables directly from the QS tile without collapsing Quick
 * Settings, and auto-confirms in-app warning dialogs.
 */
class BypassShareWarningHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.BYPASS_SHARE_WARNING.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.MOBILE_DESKTOP.packageName)

    @SuppressLint("DiscouragedApi")
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val scope = ScopeKeys.MOBILE_DESKTOP.packageName
        val moduleKey = DexIndexConstants.ModuleKeys.BYPASS_SHARE_WARNING

        val managerClassName = DexIndexStore.string(
            xposed, scope, moduleKey, DexIndexConstants.Keys.MANAGER_CLASS
        ) ?: FALLBACK_MANAGER_CLASS
        val managerFactoryMethodName = DexIndexStore.string(
            xposed, scope, moduleKey, DexIndexConstants.Keys.MANAGER_FACTORY_METHOD
        ) ?: FALLBACK_MANAGER_FACTORY_METHOD
        val managerSetMethodName = DexIndexStore.string(
            xposed, scope, moduleKey, DexIndexConstants.Keys.MANAGER_SET_METHOD
        ) ?: FALLBACK_MANAGER_SET_METHOD
        val tileRefreshMethodName = DexIndexStore.string(
            xposed, scope, moduleKey, DexIndexConstants.Keys.TILE_REFRESH_METHOD
        ) ?: FALLBACK_TILE_REFRESH_METHOD

        try {
            val tileClass = classLoader.loadClass(TARGET_CLASS)
            val onClickMethod = tileClass.getDeclaredMethod("onClick")
            hookWithId(onClickMethod, "on_click") { chain ->
                val tile = chain.thisObject
                val context = getApplicationContext(tile) ?: return@hookWithId chain.proceed()
                if (isNearbyShareEnabled(context)) {
                    logger.debug("Nearby share already enabled, keep original disable flow.")
                    return@hookWithId chain.proceed()
                }
                val enabled = setNearbyShareEnabled(
                    tile, context, classLoader,
                    managerClassName, managerFactoryMethodName, managerSetMethodName,
                    tileRefreshMethodName
                )
                if (enabled) {
                    logger.debug("Bypassed warning and enabled nearby share directly from tile.")
                    null
                } else {
                    logger.warn("Direct tile enable failed, falling back to original flow.")
                    chain.proceed()
                }
            }
            logger.info("Installed hook for BaseFileUnionTile.onClick")
        } catch (t: Throwable) {
            logger.error("Failed to hook BaseFileUnionTile.onClick", t)
        }

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
                positiveButton.performClick()
                logger.debug("Auto-confirmed share exposure dialog via $RES_POSITIVE_BUTTON.performClick()")
                result
            }
            logger.info("Installed hook for $DIALOG_CLASS.onCreate")
        } catch (t: Throwable) {
            logger.error("Failed to install dialog auto-confirm hook", t)
        }
    }

    private fun getApplicationContext(receiver: Any?): Context? {
        return try {
            val method = receiver!!.javaClass.getMethod("getApplicationContext")
            method.invoke(receiver) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun isNearbyShareEnabled(context: Context): Boolean {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getBoolean(PREF_KEY1, false) || prefs.getBoolean(PREF_KEY2, false)
        } catch (t: Throwable) {
            logger.error("Failed to read nearby share state", t)
            false
        }
    }

    private fun setNearbyShareEnabled(
        tile: Any?,
        context: Context,
        classLoader: ClassLoader,
        managerClass: String,
        factoryMethod: String,
        setMethod: String,
        tileRefreshMethod: String
    ): Boolean {
        return try {
            val mc = classLoader.loadClass(managerClass)
            val factory = mc.getDeclaredMethod(factoryMethod, Context::class.java)
            val manager = factory.invoke(null, context)
            if (manager == null) {
                logger.warn("Manager factory $managerClass.$factoryMethod returned null")
                return false
            }
            val setter = manager.javaClass.getDeclaredMethod(
                setMethod, Boolean::class.javaPrimitiveType
            )
            setter.isAccessible = true
            setter.invoke(manager, true)
            logger.debug("Enabled nearby share via $managerClass.$factoryMethod/$setMethod")

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(PREF_KEY1, true) }
            context.getSharedPreferences("sp_file_ble", Context.MODE_PRIVATE)
                .edit { putBoolean(PREF_KEY2, true) }

            val refreshMethod = findMethod(tile!!.javaClass, tileRefreshMethod)
            refreshMethod.isAccessible = true
            refreshMethod.invoke(tile)
            true
        } catch (t: Throwable) {
            logger.error("Failed to set nearby share to enable", t)
            false
        }
    }

    private companion object {
        const val TARGET_CLASS = "com.motorola.readyfor.tile.BaseFileUnionTile"
        const val DIALOG_CLASS =
            "com.motorola.readyfor.common.dialog.ActionNoticeCommonDialogActivity"
        const val FALLBACK_MANAGER_CLASS = "com.motorola.mobiledesktop.q76"
        const val FALLBACK_MANAGER_FACTORY_METHOD = "o"
        const val FALLBACK_MANAGER_SET_METHOD = "E"
        const val FALLBACK_TILE_REFRESH_METHOD = "b"
        const val PREFS_NAME = "moto_ble_preference"
        const val PREF_KEY1 = "file_union_transfer_switch"
        const val PREF_KEY2 = "nearby_send_files"
        const val EXTRA_DIALOG_STYLE = "dialog_style"
        const val EXTRA_DIALOG_EVENT = "dialog_event"
        const val STYLE_FILE_SHARE_EXPOSE = 20
        const val EVENT_FILE_SHARE_EXPOSE = 20
        const val RES_POSITIVE_BUTTON = "positiveTv"
    }
}
