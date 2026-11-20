package com.qimian233.ztool.hook.modules.setting

import android.annotation.SuppressLint
import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Settings-side placeholder matching `SPLIT_SCREEN_MANDATORY` (API <= 36 only).
 */
@SuppressLint("PrivateApi")
class SplitScreenMandatory : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.SPLIT_SCREEN_MANDATORY.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SETTINGS.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.BAKLAVA) return
        // Settings-side hook logic (no additional hooks currently; kept for future extension)
    }
}
