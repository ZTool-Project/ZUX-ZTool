package com.qimian233.ztool.dexindex.indexer

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.dexindex.base.DexIndexer
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.FieldsMatcher

/** Builds offline member indexes for SystemUI hooks. */
class SystemUiDexIndexer : DexIndexer {

    override val scopePackage: String = ScopeKeys.SYSTEM_UI.packageName

    override fun index(bridge: DexKitBridge, context: Context): JsonObject {
        val modules = JsonObject()
        modules.add(DexIndexConstants.ModuleKeys.NO_CHARGE_ANIMATION, indexNoChargeAnimation(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER,
            indexNetworkSpeedHandler(bridge)
        )
        modules.add(
            DexIndexConstants.ModuleKeys.VOLUME_SLIDER_PERCENTAGE,
            indexVolumeSliderPercentage(bridge)
        )
        return modules
    }

    private fun indexNoChargeAnimation(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val classData = bridge.findClass(
                FindClass.create()
                    .searchPackages(scopePackage)
                    .matcher(
                        ClassMatcher.create()
                            .className("com.android.keyguard.lockscreen.charge.ChargingAnimationController")
                            .fields(
                                FieldsMatcher.create()
                                    .add(FieldMatcher.create().type("android.os.Handler"))
                            )
                    )
            ).singleOrNull()

            if (classData != null) {
                val fields = classData.fields
                var handlerFieldName: String? = null
                for (fd in fields) {
                    val ft = fd.typeName
                    if (ft == "android.os.Handler" || ft.endsWith(".Handler") || ft.contains("$")) {
                        handlerFieldName = fd.name
                        break
                    }
                }
                if (handlerFieldName == null) {
                    for (fd in fields) {
                        val ft = fd.typeName
                        if (!ft.startsWith("java.") && !ft.startsWith("android.") && !isPrimitiveType(ft)) {
                            handlerFieldName = fd.name
                            break
                        }
                    }
                }
                if (handlerFieldName != null) {
                    out.addProperty(DexIndexConstants.Keys.HANDLER_FIELD_NAME, handlerFieldName)
                    Log.i(TAG, "NoChargeAnimation: handler field = $handlerFieldName")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NoChargeAnimation: field discovery failed", t)
        }
        return out
    }

    private fun indexNetworkSpeedHandler(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val matches = bridge.findClass(
                FindClass.create()
                    .searchPackages(scopePackage)
                    .matcher(ClassMatcher.create().superClass("android.os.Handler"))
            )
            for (cd in matches) {
                val name = cd.name
                if (name.startsWith("com.android.systemui.zui.NetworkSpeedView$") ||
                    name.startsWith("com.android.systemui.statusbar.view.NetworkSpeedView$")) {
                    out.addProperty(DexIndexConstants.Keys.HANDLER_INNER_CLASS, name)
                    Log.i(TAG, "SystemUINetworkSpeedHook: handler inner class = $name")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SystemUINetworkSpeedHook: handler class query failed", t)
        }
        return out
    }

    private fun isPrimitiveType(typeName: String): Boolean {
        return typeName == "boolean" || typeName == "byte" || typeName == "char"
                || typeName == "short" || typeName == "int" || typeName == "long"
                || typeName == "float" || typeName == "double" || typeName == "void"
    }

    private fun indexVolumeSliderPercentage(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val method = bridge.findMethod {
                searchPackages(scopePackage)
                matcher {
                    name("onProgressChanged")
                    paramTypes("android.widget.SeekBar", "int", "boolean")
                    returnType = "void"
                }
            }.firstOrNull {
                it.declaredClass?.name?.startsWith(TOGGLE_SLIDER_VIEW_PREFIX) == true
            }
            val listenerClass = method?.declaredClass?.name
            if (listenerClass != null) {
                out.addProperty(DexIndexConstants.Keys.VOLUME_SLIDER_PROGRESS_LISTENER_CLASS, listenerClass)
                Log.i(TAG, "VolumeSliderPercentageHook: listener class = $listenerClass")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VolumeSliderPercentageHook: listener query failed", t)
        }
        return out
    }

    private companion object {
        const val TAG = "SystemUiDexIndexer"
        const val TOGGLE_SLIDER_VIEW_PREFIX = "com.android.systemui.settings.ToggleSliderView$"
    }
}
