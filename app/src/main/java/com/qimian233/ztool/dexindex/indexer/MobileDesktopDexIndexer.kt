package com.qimian233.ztool.dexindex.indexer

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.dexindex.base.DexIndexer
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * Offline DexKit indexer for `com.motorola.mobiledesktop`, providing the
 * manager and tile-refresh targets needed by BypassShareWarningHook.
 */
class MobileDesktopDexIndexer : DexIndexer {

    override val scopePackage: String = ScopeKeys.MOBILE_DESKTOP.packageName

    override fun index(bridge: DexKitBridge, context: Context): JsonObject {
        val modules = JsonObject()
        modules.add(DexIndexConstants.ModuleKeys.BYPASS_SHARE_WARNING, indexBypassShareWarning(bridge))
        return modules
    }

    private fun indexBypassShareWarning(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        indexBypassManager(bridge, out)
        indexBypassTileRefreshMethod(bridge, out)
        return out
    }

    private fun indexBypassManager(bridge: DexKitBridge, out: JsonObject) {
        try {
            val resolved = findManagerViaTileOnClick(bridge)
                ?: findManagerViaSwitchStrings(bridge)
                ?: findLegacyManager(bridge)
            if (resolved == null) {
                Log.w(TAG, "BypassShareWarningHook: manager discovery returned null")
                return
            }
            val (managerClass, factoryMethod, setMethod) = resolved
            out.addProperty(DexIndexConstants.Keys.MANAGER_CLASS, managerClass.name)
            factoryMethod?.let {
                out.addProperty(DexIndexConstants.Keys.MANAGER_FACTORY_METHOD, it.name)
            }
            setMethod?.let {
                out.addProperty(DexIndexConstants.Keys.MANAGER_SET_METHOD, it.name)
            }
            Log.i(
                TAG,
                "BypassShareWarningHook: manager = ${managerClass.name} / " +
                    "${factoryMethod?.name} / ${setMethod?.name}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "BypassShareWarningHook: manager discovery failed", t)
        }
    }

    private fun findManagerViaTileOnClick(bridge: DexKitBridge): Triple<ClassData, MethodData?, MethodData?>? {
        val onClickMethod = bridge.findMethod {
            matcher {
                declaredClass = TILE_CLASS
                name = "onClick"
                paramTypes()
                returnType = "void"
            }
        }.singleOrNull() ?: return null

        val setMethod = onClickMethod.invokes.singleOrNull { md ->
            val clsName = md.declaredClassName
            !clsName.startsWith("android.") &&
                clsName != TILE_CLASS &&
                !Modifier.isStatic(md.modifiers) &&
                md.paramTypeNames == listOf("boolean") &&
                md.returnTypeName == "void"
        } ?: return null

        val managerClass = setMethod.declaredClass ?: return null
        val factoryMethod = findContextFactoryMethod(managerClass) ?: return null
        return Triple(managerClass, factoryMethod, setMethod)
    }

    private fun findManagerViaSwitchStrings(bridge: DexKitBridge): Triple<ClassData, MethodData?, MethodData?>? {
        val setMethod = bridge.findMethod {
            matcher {
                paramTypes("boolean")
                returnType = "void"
                usingStrings("nearby_send_files", "file_union_transfer_switch")
            }
        }.singleOrNull { !Modifier.isStatic(it.modifiers) } ?: return null

        val managerClass = setMethod.declaredClass ?: return null
        val factoryMethod = findContextFactoryMethod(managerClass) ?: return null
        return Triple(managerClass, factoryMethod, setMethod)
    }

    private fun findLegacyManager(bridge: DexKitBridge): Triple<ClassData, MethodData?, MethodData?>? {
        val managerClass = bridge.findClass {
            searchPackages("com.motorola.mobiledesktop.manager")
            matcher {
                methods {
                    add {
                        modifiers = Modifier.STATIC or Modifier.PUBLIC
                        paramTypes("android.content.Context")
                    }
                    add {
                        paramTypes("boolean")
                        returnType = "void"
                    }
                }
            }
        }.singleOrNull() ?: return null

        val factoryMethod = findContextFactoryMethod(managerClass)
        val setMethod = managerClass.methods.singleOrNull { md ->
            !Modifier.isStatic(md.modifiers) &&
                md.paramTypeNames == listOf("boolean") &&
                md.returnTypeName == "void"
        }
        return Triple(managerClass, factoryMethod, setMethod)
    }

    private fun findContextFactoryMethod(managerClass: ClassData): MethodData? {
        return managerClass.methods.singleOrNull { md ->
            Modifier.isStatic(md.modifiers) &&
                md.paramTypeNames == listOf("android.content.Context") &&
                md.returnTypeName == managerClass.name
        }
    }

    private fun indexBypassTileRefreshMethod(bridge: DexKitBridge, out: JsonObject) {
        try {
            val md = bridge.findMethod {
                matcher {
                    paramTypes()
                    returnType = "void"
                    declaredClass = TILE_CLASS
                    usingStrings("refreshTile")
                }
            }.singleOrNull { it.name != "<clinit>" }
            if (md != null) {
                out.addProperty(DexIndexConstants.Keys.TILE_REFRESH_METHOD, md.name)
                Log.i(TAG, "BypassShareWarningHook: tile refresh method = ${md.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BypassShareWarningHook: tile refresh method query failed", t)
        }
    }

    private companion object {
        const val TAG = "MobileDesktopDexIndexer"
        const val TILE_CLASS = "com.motorola.readyfor.tile.BaseFileUnionTile"
    }
}
