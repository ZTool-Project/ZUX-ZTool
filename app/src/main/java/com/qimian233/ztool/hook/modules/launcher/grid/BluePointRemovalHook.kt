package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import android.view.View
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * Suppresses the blue "newly updated" dot on app icons by forcing
 * BluePoint.isPackageNew(View) to always return false.
 */
@SuppressLint("PrivateApi")
class BluePointRemovalHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_HIDE_BLUE_POINT.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val loader: ClassLoader = param.defaultClassLoader
            val className = DexIndexStore.string(
                xposed,
                ScopeKeys.LAUNCHER.packageName,
                DexIndexConstants.ModuleKeys.LAUNCHER_HIDE_BLUE_POINT,
                DexIndexConstants.Keys.BLUE_POINT_CLASS
            ) ?: "com.zui.launcher.BluePoint"
            val methodName = DexIndexStore.string(
                xposed,
                ScopeKeys.LAUNCHER.packageName,
                DexIndexConstants.ModuleKeys.LAUNCHER_HIDE_BLUE_POINT,
                DexIndexConstants.Keys.BLUE_POINT_IS_PACKAGE_NEW_METHOD
            ) ?: "isPackageNew"

            val bluePointClass: Class<*> = loader.loadClass(className)
            val isPackageNewMethod: Method = findMethod(
                bluePointClass,
                methodName,
                View::class.java
            )
            hookWithId(isPackageNewMethod, "is_package_new") {
                false
            }
            logger.info("BluePoint removal hook installed on $className#$methodName")
        } catch (e: Throwable) {
            logger.error("Exception caught in blue point removal hook: ", e)
        }
    }
}
