package com.qimian233.ztool.hook.modules.systemui.misc

import android.os.Handler
import android.os.Message
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.dexindex.base.SystemUiDexTargets
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore.string
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** Disables lock-screen charging animation messages. */
class NoChargeAnimation : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.NO_CHARGE_ANIMATION.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        handleLoadSystemUi(param.defaultClassLoader)
    }

    fun handleLoadSystemUi(classLoader: ClassLoader) {
        try {
            val indexedClass = string(
                xposed, ScopeKeys.SYSTEM_UI.packageName,
                DexIndexConstants.ModuleKeys.NO_CHARGE_ANIMATION, DexIndexConstants.Keys.TARGET_CLASS
            )
            val indexedField = string(
                xposed, ScopeKeys.SYSTEM_UI.packageName,
                DexIndexConstants.ModuleKeys.NO_CHARGE_ANIMATION, DexIndexConstants.Keys.HANDLER_FIELD_NAME
            )
            val method = SystemUiDexTargets.CHARGING_CONTROLLERS.firstNotNullOfOrNull { name ->
                runCatching {
                    val controller = classLoader.loadClass(name)
                    val fields = buildList {
                        if (indexedField != null && (indexedClass == null || indexedClass == name)) {
                            runCatching { controller.getDeclaredField(indexedField) }.getOrNull()?.let(::add)
                        }
                        runCatching { controller.getDeclaredField("H") }.getOrNull()?.let(::add)
                        addAll(controller.declaredFields)
                    }
                    fields.firstNotNullOfOrNull { field ->
                        runCatching {
                            if (Handler::class.java.isAssignableFrom(field.type)) {
                                field.type.getDeclaredMethod("handleMessage", Message::class.java)
                                    .takeIf { it.returnType == Void.TYPE }
                            } else null
                        }.getOrNull()
                    }
                }.getOrNull()
            } ?: error("ChargingAnimationController Handler not found")
            hookWithId(method, "handle_message") { null }
            logger.info("Charging animation disabled via ${method.declaringClass.name}")
        } catch (e: Exception) {
            logger.error("Error hooking ChargingAnimationController", e)
        }
    }
}
