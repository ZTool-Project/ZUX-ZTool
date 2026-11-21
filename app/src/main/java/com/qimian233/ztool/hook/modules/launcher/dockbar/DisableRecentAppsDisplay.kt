package com.qimian233.ztool.hook.modules.launcher.dockbar

import android.annotation.SuppressLint
import android.content.Context
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** Hides recent apps in the launcher taskbar and ZuiHotseat dock. */
@SuppressLint("PrivateApi")
class DisableRecentAppsDisplay : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.DISABLE_RECENT_APPS_DISPLAY.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.DISABLE_RECENT_APPS_DISPLAY)

        val controllerClassName = module?.get(DexIndexConstants.Keys.RECENT_APPS_CONTROLLER_CLASS)
            ?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_CONTROLLER_CLASS
        val onChangedMethodName = module?.get(DexIndexConstants.Keys.RECENT_APPS_ON_CHANGED_METHOD)
            ?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ON_CHANGED_METHOD

        hookTaskbarRecentAppsController(classLoader, controllerClassName, onChangedMethodName)
        hookLegacyTaskbarUtilities(classLoader)
        hookHotseatRecentUsedShow(classLoader)
    }

    private fun hookTaskbarRecentAppsController(
        classLoader: ClassLoader,
        controllerClassName: String,
        onChangedMethodName: String
    ) {
        try {
            val controllerClass = classLoader.loadClass(controllerClassName)
            val onChangedMethod = findMethod(controllerClass, onChangedMethodName)
            val canShowField = runCatching {
                findField(controllerClass, "canShowRecentApps")
            }.getOrNull()
            hookWithId(onChangedMethod, "hook_taskbar_recent_apps") { chain ->
                val instance = chain.thisObject
                if (instance != null && canShowField != null) {
                    runCatching { canShowField.setBoolean(instance, false) }
                }
                chain.proceed()
            }
            logger.info("DisableRecentAppsDisplay: hooked $controllerClassName#$onChangedMethodName")
        } catch (t: Throwable) {
            logger.debug("DisableRecentAppsDisplay: TaskbarRecentAppsController absent: ${t.message}")
        }
    }

    private fun hookLegacyTaskbarUtilities(classLoader: ClassLoader) {
        try {
            val utilsClass = classLoader.loadClass(LEGACY_UTILITIES_CLASS)
            val getter = findMethod(utilsClass, "isTbRecentUsedAppEnable", Context::class.java)
            hookWithId(getter, "hook_utils") { false }
            logger.info("DisableRecentAppsDisplay: hooked legacy Utilities#isTbRecentUsedAppEnable")
        } catch (t: Throwable) {
            logger.debug("DisableRecentAppsDisplay: legacy Utilities absent: ${t.message}")
        }
    }

    private fun hookHotseatRecentUsedShow(classLoader: ClassLoader) {
        try {
            val hotSeatClass = classLoader.loadClass(ZUI_HOTSEAT_CLASS)
            val recentShowSetter = findMethod(
                hotSeatClass,
                "setRecentUsedShow",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(recentShowSetter, "hook_recent_show_setter") { chain ->
                val argList = chain.args.toMutableList()
                argList[0] = false
                chain.proceed(argList.toTypedArray())
            }
            logger.info("DisableRecentAppsDisplay: hooked ZuiHotseat#setRecentUsedShow")
        } catch (t: Throwable) {
            logger.warn("DisableRecentAppsDisplay: failed to hook setRecentUsedShow: ${t.message}")
        }
    }

    private companion object {
        const val DEFAULT_CONTROLLER_CLASS = "com.android.launcher3.taskbar.TaskbarRecentAppsController"
        const val DEFAULT_ON_CHANGED_METHOD = "onRecentsOrHotseatChanged"
        const val LEGACY_UTILITIES_CLASS = "com.android.launcher3.taskbar.Utilities"
        const val ZUI_HOTSEAT_CLASS = "com.zui.launcher.uiextend.ZuiHotseat"
    }
}
