package com.qimian233.ztool.dexindex.indexer

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.dexindex.base.DexIndexer
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * launcher scope (com.zui.launcher) offline indexer.
 *
 * Migrated as-is from the DexKit queries of these Hooks:
 * - CleanGlobalSearch (hotword init / data population method names)
 * - DisableForceStop (OverviewUtilities force-stop method name)
 * - ZuiLauncherHotseatHook (LoaderCursor size-check method name)
 */
class LauncherDexIndexer : DexIndexer {

    override val scopePackage: String = ScopeKeys.LAUNCHER.packageName

    override fun index(bridge: DexKitBridge, context: Context): JsonObject {
        val modules = JsonObject()
        modules.add(DexIndexConstants.ModuleKeys.CLEAN_GLOBAL_SEARCH, indexCleanGlobalSearch(bridge))
        modules.add(DexIndexConstants.ModuleKeys.DISABLE_FORCE_STOP, indexDisableForceStop(bridge))
        modules.add(DexIndexConstants.ModuleKeys.ZUI_LAUNCHER_HOTSEAT, indexZuiLauncherHotseat(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.DISABLE_DOCK_BACKGROUND,
            indexDisableDockBackground(bridge)
        )
        modules.add(
            DexIndexConstants.ModuleKeys.LAUNCHER_FORCE_FREEFORM_ENTRY,
            indexLauncherFreeformEntry(bridge)
        )
        return modules
    }

    private fun indexCleanGlobalSearch(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        // No-arg void method (HotWordView init); original discoverInitMethods took the first match
        try {
            val methods = bridge.findMethod {
                searchPackages(scopePackage)
                matcher {
                    paramTypes()
                    returnType = "void"
                    declaredClass = "com.zui.launcher.GlobalSearchView"
                }
            }
            // Filter class initializers (the no-arg void query matches <clinit>, which reflection cannot get);
            // iterate and take the first match (DexKit's firstOrNull extension is not recommended for non-unique results)
            for (md in methods) {
                if (md.name != "<clinit>") {
                    out.addProperty(DexIndexConstants.Keys.HOTWORD_INIT_METHOD, md.name)
                    Log.i(TAG, "CleanGlobalSearch: hotword init method = ${md.name}")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CleanGlobalSearch: hotword init query failed", t)
        }

        // (List) → void method (hotword data population E0); original discoverE0Method took singleOrNull
        try {
            val result = bridge.findMethod {
                searchPackages(scopePackage)
                matcher {
                    paramTypes("java.util.List")
                    returnType = "void"
                    declaredClass = "com.zui.launcher.GlobalSearchView"
                }
            }.singleOrNull()
            if (result != null) {
                out.addProperty(DexIndexConstants.Keys.HOTWORD_DATA_METHOD, result.name)
                Log.i(TAG, "CleanGlobalSearch: hotword data method = ${result.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CleanGlobalSearch: hotword data query failed", t)
        }
        return out
    }

    private fun indexDisableForceStop(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        // (Context, String, int) → void, declared in OverviewUtilities, skip removeAppProcess
        try {
            val methods = bridge.findMethod(
                FindMethod.create()
                    .searchPackages(scopePackage)
                    .matcher(
                        MethodMatcher.create()
                            .paramTypes("android.content.Context", "java.lang.String", "int")
                            .returnType("void")
                            .declaredClass("com.zui.launcher.util.OverviewUtilities")
                    )
            )
            for (md in methods) {
                if (md.name != "removeAppProcess") {
                    out.addProperty(DexIndexConstants.Keys.FORCE_STOP_METHOD, md.name)
                    Log.i(TAG, "DisableForceStop: force-stop method = ${md.name}")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableForceStop: force-stop query failed", t)
        }
        return out
    }

    private fun indexZuiLauncherHotseat(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        // (ItemInfo) → boolean, declared in LoaderCursor; ItemInfo is an unobfuscated public class
        try {
            val methods = bridge.findMethod(
                FindMethod.create()
                    .searchPackages("com.android.launcher3")
                    .matcher(
                        MethodMatcher.create()
                            .paramTypes("com.android.launcher3.model.data.ItemInfo")
                            .returnType("boolean")
                            .declaredClass("com.android.launcher3.model.LoaderCursor")
                    )
            )
            for (md in methods) {
                out.addProperty(DexIndexConstants.Keys.LOADER_CURSOR_B_METHOD, md.name)
                Log.i(TAG, "ZuiLauncherHotseatHook: LoaderCursor method = ${md.name}")
                break
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ZuiLauncherHotseatHook: LoaderCursor query failed", t)
        }
        return out
    }

    /**
     * Paint fields of ZuiHotseat that more than one method reads: the dock's own paints are
     * touched by both dispatchDraw and setWallpaperColor, while the edit-mode empty-cell pair
     * is read from dispatchDraw only. Emitted comma-separated because DexIndexStore exposes
     * string values only; see docs/research/zui_launcher_dock_background.md.
     */
    private fun indexDisableDockBackground(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val names = bridge.findField {
                searchPackages(scopePackage)
                matcher {
                    declaredClass("com.zui.launcher.uiextend.ZuiHotseat")
                    type("android.graphics.Paint")
                    readMethods { countMin(2) }
                }
            }.map { it.name }
            if (names.isNotEmpty()) {
                out.addProperty(DexIndexConstants.Keys.DOCK_PAINT_FIELDS, names.joinToString(","))
                Log.i(TAG, "DisableDockBackground: dock paint fields = $names")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableDockBackground: dock paint field query failed", t)
        }
        return out
    }

    /**
     * Locates freeform entry-check targets across unobfuscated and R8-obfuscated Launcher builds.
     * Omits searchPackages because R8 relocates FreeformUtilities and AbsSwipeUpHandler helpers.
     */
    private fun indexLauncherFreeformEntry(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        val pkgSupport = findPkgSupportMethod(bridge)
        val utilsClassName = pkgSupport?.className
        if (pkgSupport != null) {
            out.addProperty(DexIndexConstants.Keys.FREEFORM_UTILS_CLASS, pkgSupport.className)
            out.addProperty(DexIndexConstants.Keys.FREEFORM_PKG_SUPPORT_METHOD, pkgSupport.name)
            out.addProperty(
                DexIndexConstants.Keys.FREEFORM_PKG_SUPPORT_PARAMS,
                pkgSupport.paramTypeNames.joinToString(",")
            )
            Log.i(
                TAG,
                "LauncherFreeformEntry: pkgSupport = ${pkgSupport.className}.${pkgSupport.name}" +
                    "(${pkgSupport.paramTypeNames.joinToString(",")})"
            )
        }

        if (utilsClassName != null) {
            val taskSupport = findTaskSupportMethod(bridge, utilsClassName)
            if (taskSupport != null) {
                out.addProperty(DexIndexConstants.Keys.FREEFORM_TASK_SUPPORT_METHOD, taskSupport.name)
                out.addProperty(
                    DexIndexConstants.Keys.FREEFORM_TASK_SUPPORT_PARAMS,
                    taskSupport.paramTypeNames.joinToString(",")
                )
                Log.i(TAG, "LauncherFreeformEntry: taskSupport = ${taskSupport.name}")
            }
        }

        val denySet = findDenySetMethod(bridge, utilsClassName)
        if (denySet != null) {
            out.addProperty(DexIndexConstants.Keys.FREEFORM_DENY_SET_METHOD, denySet.name)
            out.addProperty(
                DexIndexConstants.Keys.FREEFORM_DENY_SET_PARAMS,
                denySet.paramTypeNames.joinToString(",")
            )
            Log.i(TAG, "LauncherFreeformEntry: denySet = ${denySet.name}")
        }

        val swipeInit = findSwipeHandlerInit(bridge)
        if (swipeInit != null) {
            val owner = swipeInit.declaredClass
            if (owner != null) {
                out.addProperty(DexIndexConstants.Keys.FREEFORM_SWIPE_HANDLER_CLASS, owner.name)
                out.addProperty(
                    DexIndexConstants.Keys.FREEFORM_SWIPE_INIT_PARAMS,
                    swipeInit.paramTypeNames.joinToString(",")
                )
                Log.i(TAG, "LauncherFreeformEntry: swipeHandler = ${owner.name}")

                val supportField = owner.methods
                    .filter { it.returnTypeName == "float" && it.paramCount == 0 }
                    .flatMap { it.usingFields }
                    .map { it.field }
                    .distinctBy { "${it.className}#${it.name}" }
                    .singleOrNull { it.className == owner.name && it.typeName == "boolean" }
                if (supportField != null) {
                    out.addProperty(DexIndexConstants.Keys.FREEFORM_SWIPE_SUPPORT_FIELD, supportField.name)
                }

                val taskInfoField = owner.fields
                    .singleOrNull { !Modifier.isStatic(it.modifiers) && it.typeName == TASK_INFO }
                if (taskInfoField != null) {
                    out.addProperty(DexIndexConstants.Keys.FREEFORM_SWIPE_TASK_INFO_FIELD, taskInfoField.name)
                }
            }
        }

        if (utilsClassName != null) {
            val denySetSource = denySet ?: swipeInit
            val staticDenyField = denySetSource?.usingFields
                ?.map { it.field }
                ?.distinctBy { "${it.className}#${it.name}" }
                ?.singleOrNull {
                    it.className == utilsClassName &&
                        Modifier.isStatic(it.modifiers) &&
                        it.typeName == HASH_SET
                }
                ?: pkgSupport.declaredClass?.fields?.singleOrNull {
                    Modifier.isStatic(it.modifiers) && it.typeName == HASH_SET
                }
            if (staticDenyField != null) {
                out.addProperty(DexIndexConstants.Keys.FREEFORM_STATIC_DENY_FIELD, staticDenyField.name)
                Log.i(TAG, "LauncherFreeformEntry: staticDenyField = ${staticDenyField.name}")
            }
        }
        return out
    }

    private fun findPkgSupportMethod(bridge: DexKitBridge): MethodData? = try {
        bridge.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 5
                usingStrings("com.lenovo.screensplit")
                addInvoke {
                    declaredClass = OV_COMMON_MANAGER
                    name = "supportsOvFreeformOnPkg"
                }
            }
        }.singleOrNull { md ->
            md.paramTypeNames.sorted() == PKG_SUPPORT_PARAM_TYPES_SORTED
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: pkgSupport query failed", t)
        null
    }

    private fun findTaskSupportMethod(bridge: DexKitBridge, utilsClassName: String): MethodData? = try {
        bridge.findMethod {
            matcher {
                declaredClass = utilsClassName
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 2
            }
        }.singleOrNull { md ->
            md.paramTypeNames.sorted() == TASK_SUPPORT_PARAM_TYPES_SORTED
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: taskSupport query failed", t)
        null
    }

    private fun findDenySetMethod(bridge: DexKitBridge, utilsClassName: String?): MethodData? = try {
        bridge.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 2
                usingStrings(STATIC_DENY_RES_NAME)
            }
        }.singleOrNull { md ->
            md.paramTypeNames.sorted() == DENY_SET_PARAM_TYPES_SORTED &&
                (utilsClassName == null || md.className == utilsClassName)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: denySet query failed", t)
        null
    }

    private fun findSwipeHandlerInit(bridge: DexKitBridge): MethodData? = try {
        bridge.findMethod {
            matcher {
                name = "<init>"
                usingStrings(STATIC_DENY_RES_NAME)
            }
        }.singleOrNull { md ->
            val owner = md.declaredClass
            owner != null && isSubclassOf(owner, ABS_SWIPE_UP_HANDLER)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: swipeHandler query failed", t)
        null
    }

    private fun isSubclassOf(cls: ClassData, superName: String): Boolean {
        var current: ClassData? = cls
        while (current != null && current.name != "java.lang.Object") {
            if (current.name == superName) return true
            current = current.superClass
        }
        return false
    }

    private companion object {
        const val TAG = "LauncherDexIndexer"
        const val OV_COMMON_MANAGER = "com.android.internal.ov.OvCommonManager"
        const val ABS_SWIPE_UP_HANDLER = "com.android.quickstep.AbsSwipeUpHandler"
        const val STATIC_DENY_RES_NAME = "config_ovcDcv_StaticDenySet"
        const val TASK_INFO = "android.app.TaskInfo"
        const val HASH_SET = "java.util.HashSet"

        val PKG_SUPPORT_PARAM_TYPES_SORTED = listOf(
            "android.content.Context",
            "int",
            "int",
            "java.lang.String",
            "java.lang.String"
        )
        val TASK_SUPPORT_PARAM_TYPES_SORTED = listOf(
            "android.app.TaskInfo",
            "android.content.Context"
        )
        val DENY_SET_PARAM_TYPES_SORTED = listOf(
            "android.content.ComponentName",
            "android.content.Context"
        )
    }
}
