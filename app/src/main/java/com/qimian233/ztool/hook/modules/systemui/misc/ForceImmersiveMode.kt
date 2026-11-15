package com.qimian233.ztool.hook.modules.systemui.misc

import android.annotation.SuppressLint
import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.modules.systemframework.ImmersiveModePolicy
import io.github.libxposed.api.XposedModuleInterface

/** Keeps SystemUI bar attributes consistent with the forced window-manager policy. */
@SuppressLint("PrivateApi")
class ForceImmersiveMode : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.FORCE_IMMERSIVE_MODE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        val commandQueueClass = param.defaultClassLoader
            .loadClass("com.android.systemui.statusbar.CommandQueue")
        val parameterNames = listOf(
            "int", "int", "[Lcom.android.internal.view.AppearanceRegion;", "boolean",
            "int", "int", "java.lang.String", "[Lcom.android.internal.statusbar.LetterboxDetails;"
        )
        val method = commandQueueClass.declaredMethods.single {
            it.name == "onSystemBarAttributesChanged" &&
                it.parameterTypes.map { type -> type.name } == parameterNames
        }

        // See docs/research/force_immersive_mode.md for the notification/control split.
        hookWithId(method, "force_immersive_systemui_attributes") { chain ->
            if (!isEnabled()) return@hookWithId chain.proceed()
            val args = chain.args.toTypedArray()
            args[4] = ImmersiveModePolicy.TRANSIENT_BY_SWIPE
            args[5] = ImmersiveModePolicy.hideBars(args[5] as Int)
            chain.proceed(args)
        }
        logger.info("Immersive SystemUI attributes hook installed")
    }
}
