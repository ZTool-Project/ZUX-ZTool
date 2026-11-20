package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/** Reveals hidden and system apps in ZUI's native floating-window and split-screen lists. */
@SuppressLint("PrivateApi")
class UnlockOvCommonAppListHook : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.UNLOCK_OV_COMMON_APP_LIST.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    @Throws(Throwable::class)
    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            logger.info("UnlockOvCommonAppListHook requires API 36+, skipping on API ${Build.VERSION.SDK_INT}")
            return
        }

        val classLoader = param.classLoader
        val compatClass = runCatching {
            classLoader.loadClass(COMPAT_MANAGER_CLASS)
        }.getOrElse {
            logger.warn("$COMPAT_MANAGER_CLASS absent; UnlockOvCommonAppListHook skipped")
            return
        }

        val visibleOvCommon = runCatching {
            findMethod(
                compatClass,
                "visibleOvCommon",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrElse {
            logger.warn("OvCommonCompatManager.visibleOvCommon absent; UnlockOvCommonAppListHook skipped")
            return
        }

        val enableOvBase = OVC_FEATURE_FLAGS_CLASSES.firstNotNullOfOrNull { name ->
            runCatching {
                findMethod(classLoader.loadClass(name), "enableOvBase").apply { isAccessible = true }
            }.getOrNull()
        }

        hookWithId(visibleOvCommon, HOOK_ID) { chain ->
            val pkg = chain.args.getOrNull(0) as? String
            if (pkg.isNullOrEmpty()) return@hookWithId chain.proceed()
            val ovEnabled = runCatching {
                enableOvBase?.invoke(null) as? Boolean ?: true
            }.getOrDefault(true)
            if (!ovEnabled) return@hookWithId false
            true
        }

        logger.info("UnlockOvCommonAppListHook installed")
    }

    private companion object {
        const val HOOK_ID = "ov_common_visible_unlock"
        const val COMPAT_MANAGER_CLASS = "com.android.server.wm.OvCommonCompatManager"
        val OVC_FEATURE_FLAGS_CLASSES = listOf(
            "android.util.OvcFeatureFlags",
            "com.android.server.wm.OvcFeatureFlags"
        )
    }
}
