package com.qimian233.ztool.dexindex.indexer

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.dexindex.base.DexIndexer
import com.qimian233.ztool.dexindex.base.SystemUiDexTargets
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.result.ClassData

/** Builds offline member indexes for SystemUI hooks. */
class SystemUiDexIndexer : DexIndexer {
    override val scopePackage: String = ScopeKeys.SYSTEM_UI.packageName

    override fun index(bridge: DexKitBridge, context: Context): JsonObject = JsonObject().apply {
        add(DexIndexConstants.ModuleKeys.NO_CHARGE_ANIMATION, indexNoChargeAnimation(bridge))
        add(DexIndexConstants.ModuleKeys.SYSTEMUI_NETWORK_SPEED_DOUBLELAYER, indexNetworkSpeedHandler(bridge))
        add(DexIndexConstants.ModuleKeys.VOLUME_SLIDER_PERCENTAGE, indexVolumeSliderPercentage(bridge))
    }

    private fun indexNoChargeAnimation(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        for (name in SystemUiDexTargets.CHARGING_CONTROLLERS) {
            try {
                val controller = findClass(bridge, name) ?: continue
                val field = controller.fields.firstOrNull {
                    runCatching {
                        isAssignableTo(it.type, HANDLER) && declaresHandlerCallback(it.type)
                    }.getOrDefault(false)
                } ?: continue
                out.addProperty(DexIndexConstants.Keys.TARGET_CLASS, name)
                out.addProperty(DexIndexConstants.Keys.HANDLER_FIELD_NAME, field.name)
                Log.i(TAG, "NoChargeAnimation: $name handler field = ${field.name}")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "NoChargeAnimation: field discovery failed for $name", t)
            }
        }
        return out
    }

    private fun indexNetworkSpeedHandler(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        for (name in SystemUiDexTargets.NETWORK_SPEED_VIEWS) {
            try {
                val view = findClass(bridge, name) ?: continue
                val candidates = view.fields.mapNotNull { runCatching { it.type }.getOrNull() } + bridge.findClass(
                    FindClass.create().searchPackages(scopePackage).matcher(ClassMatcher.create().superClass(HANDLER))
                ).filter { it.name.startsWith("$name$") }
                val handler = candidates.firstOrNull { candidate ->
                    runCatching {
                        isAssignableTo(candidate, HANDLER) && declaresHandlerCallback(candidate) &&
                            candidate.fields.any { it.name == $$"this$0" && isAssignableTo(it.type, name) }
                    }.getOrDefault(false)
                } ?: continue
                out.addProperty(DexIndexConstants.Keys.HANDLER_INNER_CLASS, handler.name)
                Log.i(TAG, "SystemUINetworkSpeedHook: handler class = ${handler.name}")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "SystemUINetworkSpeedHook: handler query failed for $name", t)
            }
        }
        return out
    }

    private fun indexVolumeSliderPercentage(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val slider = findClass(bridge, SystemUiDexTargets.TOGGLE_SLIDER_VIEW) ?: return out
            val methods = bridge.findMethod {
                searchPackages(scopePackage)
                matcher {
                    name("onProgressChanged")
                    paramTypes("android.widget.SeekBar", "int", "boolean")
                    returnType = "void"
                }
            }
            val candidates = methods.mapNotNull { it.declaredClass }.filter {
                runCatching {
                    isAssignableTo(it, $$"android.widget.SeekBar$OnSeekBarChangeListener")
                }.getOrDefault(false)
            }
            val listener = candidates.firstOrNull { it.name.startsWith("${slider.name}$") }
                ?: candidates.firstOrNull { candidate -> slider.fields.any { it.typeName == candidate.name } }
            if (listener != null) {
                out.addProperty(DexIndexConstants.Keys.VOLUME_SLIDER_PROGRESS_LISTENER_CLASS, listener.name)
                Log.i(TAG, "VolumeSliderPercentageHook: listener class = ${listener.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VolumeSliderPercentageHook: listener query failed", t)
        }
        return out
    }

    private fun findClass(bridge: DexKitBridge, name: String): ClassData? = bridge.findClass(
        FindClass.create().matcher(ClassMatcher.create().className(name))
    ).singleOrNull()

    private fun declaresHandlerCallback(type: ClassData): Boolean = type.methods.any {
        it.name == "handleMessage" && it.paramTypeNames == listOf("android.os.Message") &&
            it.returnTypeName == "void"
    }

    private fun isAssignableTo(type: ClassData, name: String): Boolean {
        var current: ClassData? = type
        while (current != null) {
            if (current.name == name || current.interfaces.any { isAssignableTo(it, name) }) return true
            current = current.superClass
        }
        return false
    }

    private companion object {
        const val TAG = "SystemUiDexIndexer"
        const val HANDLER = "android.os.Handler"
    }
}
