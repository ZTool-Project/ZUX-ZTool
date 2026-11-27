package com.qimian233.ztool.dexindex.indexer

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.dexindex.base.DexIndexer
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/** Offline DexKit indexer for the ZUI Launcher scope (`com.zui.launcher`). */
class LauncherDexIndexer : DexIndexer {

    override val scopePackage: String = ScopeKeys.LAUNCHER.packageName

    override fun index(bridge: DexKitBridge, context: Context): JsonObject {
        val modules = JsonObject()
        modules.add(DexIndexConstants.ModuleKeys.CLEAN_GLOBAL_SEARCH, indexCleanGlobalSearch(bridge))
        modules.add(DexIndexConstants.ModuleKeys.DISABLE_FORCE_STOP, indexDisableForceStop(bridge))
        modules.add(DexIndexConstants.ModuleKeys.ZUI_LAUNCHER_HOTSEAT, indexZuiLauncherHotseat(bridge))
        modules.add(DexIndexConstants.ModuleKeys.DISABLE_DOCK_BACKGROUND, indexDisableDockBackground(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.DISABLE_RECENT_APPS_DISPLAY,
            indexDisableRecentAppsDisplay(bridge)
        )
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_BATCH_UNINSTALL, indexBatchUninstall(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.LAUNCHER_FORCE_FREEFORM_ENTRY,
            indexLauncherFreeformEntry(bridge)
        )
        modules.add(
            DexIndexConstants.ModuleKeys.LAUNCHER_FREEFORM_SWIPE_TRIGGER,
            indexLauncherFreeformSwipeTrigger(bridge)
        )
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_HIDE_BLUE_POINT, indexBluePointRemoval(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_BLUR_GUARD,
            indexBigFolderBlurGuard(bridge)
        )
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_GRID_METRICS, indexLauncherGridMetrics(bridge))
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_ALIGN, indexBigFolderAlign(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_EXTERNAL_TITLE,
            indexBigFolderExternalTitle(bridge)
        )
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_NO_LABEL_MODE, indexLauncherNoLabelMode(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.LAUNCHER_DRAWER_NO_LABEL_MODE,
            indexLauncherDrawerNoLabelMode(bridge)
        )
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_WIDE_GRID, indexLauncherWideGrid(bridge))
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_ICON_SCALE_OVERRIDE, indexIconScaleOverride(bridge))
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_APP_ICON_UNMASK, indexLauncherAppIconUnmask(bridge))
        modules.add(DexIndexConstants.ModuleKeys.LAUNCHER_ICON_PACK, indexLauncherIconPack(bridge))
        return modules
    }

    private fun indexCleanGlobalSearch(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        val viewClass = try {
            findClassByName(bridge, "com.zui.launcher.globalsearch.GlobalSearchView")
                ?: bridge.findMethod {
                    matcher {
                        name = "saveHierarchyState"
                        usingStrings("key_word_saved_key", "ui_mode_saved_key")
                    }
                }.singleOrNull()?.declaredClass
                ?: findClassByName(bridge, "com.zui.launcher.GlobalSearchView")
        } catch (t: Throwable) {
            Log.w(TAG, "CleanGlobalSearch: view class query failed", t)
            null
        } ?: return out

        out.addProperty(DexIndexConstants.Keys.CLEAN_SEARCH_CLASS, viewClass.name)
        Log.i(TAG, "CleanGlobalSearch: class = ${viewClass.name}")

        var hotWordViewClass: ClassData? = null
        var dataMethod: MethodData? = null
        try {
            hotWordViewClass = findClassByName(bridge, "com.zui.launcher.globalsearch.HotWordView")
                ?: viewClass.fields
                    .filter { !Modifier.isStatic(it.modifiers) }
                    .mapNotNull { runCatching { it.type }.getOrNull() }
                    .distinctBy { it.name }
                    .singleOrNull { cls ->
                        cls.superClass?.name == "android.widget.LinearLayout" &&
                            cls.methods.any { m ->
                                isNormalInstanceMethod(m) &&
                                    m.returnTypeName == "void" &&
                                    m.paramCount == 3 &&
                                    m.paramTypeNames.sorted() == listOf("boolean", "java.util.List", viewClass.name).sorted()
                            }
                    }

            val setHotDataMethod = hotWordViewClass?.methods?.singleOrNull { m ->
                isNormalInstanceMethod(m) &&
                    m.returnTypeName == "void" &&
                    m.paramCount == 3 &&
                    m.paramTypeNames.sorted() == listOf("boolean", "java.util.List", viewClass.name).sorted()
            }

            dataMethod = if (hotWordViewClass != null && setHotDataMethod != null) {
                bridge.findMethod {
                    matcher {
                        declaredClass = viewClass.name
                        modifiers = Modifier.STATIC
                        returnType = "void"
                        paramCount = 2
                        addInvoke {
                            declaredClass = hotWordViewClass.name
                            name = setHotDataMethod.name
                            returnType = "void"
                            paramCount = 3
                        }
                    }
                }.singleOrNull { md ->
                    md.paramTypeNames.sorted() == listOf(viewClass.name, "java.util.List").sorted()
                }
            } else {
                null
            } ?: bridge.findMethod {
                matcher {
                    declaredClass = viewClass.name
                    returnType = "void"
                    paramTypes("java.util.List")
                }
            }.singleOrNull { isNormalInstanceMethod(it) }

            if (dataMethod != null) {
                val params = dataMethod.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.HOTWORD_DATA_METHOD, dataMethod.name)
                out.addProperty(DexIndexConstants.Keys.HOTWORD_DATA_PARAMS, params)
                Log.i(TAG, "CleanGlobalSearch: data = ${dataMethod.name}($params)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CleanGlobalSearch: data method query failed", t)
        }

        try {
            val hotWordField = if (hotWordViewClass != null) {
                viewClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == hotWordViewClass.name
                }
            } else {
                dataMethod?.usingFields
                    ?.map { it.field }
                    ?.distinctBy { "${it.className}#${it.name}" }
                    ?.singleOrNull {
                        it.className == viewClass.name &&
                            !Modifier.isStatic(it.modifiers) &&
                            it.typeName != "boolean"
                    }
            }
            if (hotWordField != null) {
                out.addProperty(DexIndexConstants.Keys.CLEAN_SEARCH_HOTWORD_FIELD, hotWordField.name)
                Log.i(TAG, "CleanGlobalSearch: hotWordField = ${hotWordField.name}")

                // Only emit legacy init method when onFinishInflate does not touch hotWordField.
                val inflatedInline = bridge.findMethod {
                    matcher {
                        declaredClass = viewClass.name
                        name = "onFinishInflate"
                        paramTypes()
                        usingFields {
                            add {
                                declaredClass = viewClass.name
                                name = hotWordField.name
                            }
                        }
                    }
                }.isNotEmpty()
                if (!inflatedInline) {
                    val legacyInit = bridge.findMethod {
                        matcher {
                            declaredClass = viewClass.name
                            returnType = "void"
                            paramTypes()
                            usingFields {
                                add {
                                    declaredClass = viewClass.name
                                    name = hotWordField.name
                                }
                            }
                        }
                    }.singleOrNull { isNormalInstanceMethod(it) && it.name != "onFinishInflate" }
                    if (legacyInit != null) {
                        out.addProperty(DexIndexConstants.Keys.HOTWORD_INIT_METHOD, legacyInit.name)
                        Log.i(TAG, "CleanGlobalSearch: legacy init = ${legacyInit.name}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CleanGlobalSearch: hotWord field/init query failed", t)
        }

        try {
            val hintMethod = bridge.findMethod {
                matcher {
                    declaredClass = viewClass.name
                    returnType = "void"
                    paramTypes()
                    addInvoke {
                        declaredClass = "com.zui.launcher.globalsearch.HotWordDownLoad"
                        name = "getHintHotWord"
                    }
                }
            }.singleOrNull { isNormalInstanceMethod(it) } ?: bridge.findMethod {
                matcher {
                    declaredClass = viewClass.name
                    returnType = "void"
                    paramTypes()
                    addInvoke {
                        declaredClass = "android.widget.TextView"
                        name = "setHint"
                        paramTypes("java.lang.CharSequence")
                    }
                }
            }.singleOrNull { isNormalInstanceMethod(it) }
            if (hintMethod != null) {
                out.addProperty(DexIndexConstants.Keys.HOTWORD_HINT_METHOD, hintMethod.name)
                Log.i(TAG, "CleanGlobalSearch: hint = ${hintMethod.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CleanGlobalSearch: hint query failed", t)
        }

        return out
    }

    private fun indexDisableForceStop(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            // Omit searchPackages because R8 relocates OverviewUtilities out of com.zui.launcher.
            val expectedParamsSorted = listOf("android.content.Context", "int", "java.lang.String")
            val forceStop = bridge.findMethod {
                matcher {
                    modifiers = Modifier.STATIC
                    returnType = "void"
                    paramCount = 3
                    usingStrings("removeAppProcess: skip alive-protected pkg=")
                }
            }.singleOrNull { md ->
                md.paramTypeNames.sorted() == expectedParamsSorted
            } ?: bridge.findMethod {
                matcher {
                    declaredClass = "com.zui.launcher.util.OverviewUtilities"
                    paramTypes("android.content.Context", "java.lang.String", "int")
                    returnType = "void"
                }
            }.singleOrNull { md ->
                md.name != "removeAppProcess"
            }

            if (forceStop != null) {
                val params = forceStop.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.FORCE_STOP_CLASS, forceStop.className)
                out.addProperty(DexIndexConstants.Keys.FORCE_STOP_METHOD, forceStop.name)
                out.addProperty(DexIndexConstants.Keys.FORCE_STOP_PARAMS, params)
                Log.i(TAG, "DisableForceStop: helper = ${forceStop.className}.${forceStop.name}($params)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableForceStop: helper query failed", t)
        }

        try {
            val dismissMethod = bridge.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("com.android.systemui.shared.recents.model.Task")
                    usingStrings(
                        "Launcher_Recents_Stop_Task",
                        "removeAppProcess: skip alive-protected pkg="
                    )
                }
            }.singleOrNull { isNormalInstanceMethod(it) }
            if (dismissMethod != null) {
                val params = dismissMethod.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.FORCE_STOP_DISMISS_CLASS, dismissMethod.className)
                out.addProperty(DexIndexConstants.Keys.FORCE_STOP_DISMISS_METHOD, dismissMethod.name)
                out.addProperty(DexIndexConstants.Keys.FORCE_STOP_DISMISS_PARAMS, params)
                Log.i(TAG, "DisableForceStop: dismiss = ${dismissMethod.className}.${dismissMethod.name}($params)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableForceStop: dismissTasks query failed", t)
        }
        return out
    }

    private fun indexZuiLauncherHotseat(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val legacyCheck = bridge.findMethod {
                matcher {
                    declaredClass = "com.android.launcher3.model.LoaderCursor"
                    paramTypes(ITEM_INFO_CLASS)
                    returnType = "boolean"
                }
            }.singleOrNull { md ->
                isNormalInstanceMethod(md) && md.name != "checkItemPlacement"
            }
            if (legacyCheck != null) {
                out.addProperty(DexIndexConstants.Keys.LOADER_CURSOR_B_METHOD, legacyCheck.name)
                Log.i(TAG, "ZuiLauncherHotseatHook: legacy LoaderCursor method = ${legacyCheck.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ZuiLauncherHotseatHook: LoaderCursor query failed", t)
        }

        var calcClassName: String? = null
        try {
            val createProfile = bridge.findMethod {
                matcher {
                    modifiers = Modifier.STATIC
                    returnType = "com.android.launcher3.deviceprofile.HotseatProfile"
                    paramCount = 8
                }
            }.singleOrNull { md ->
                md.paramTypeNames.sorted() == CREATE_HOTSEAT_PROFILE_PARAMS_SORTED
            }
            if (createProfile != null) {
                calcClassName = createProfile.className
                val params = createProfile.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.HOTSEAT_CALC_CLASS, createProfile.className)
                out.addProperty(DexIndexConstants.Keys.HOTSEAT_CREATE_PROFILE_METHOD, createProfile.name)
                out.addProperty(DexIndexConstants.Keys.HOTSEAT_CREATE_PROFILE_PARAMS, params)
                Log.i(TAG, "ZuiLauncherHotseatHook: createHotseatProfile = ${createProfile.className}.${createProfile.name}($params)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ZuiLauncherHotseatHook: createHotseatProfile query failed", t)
        }

        try {
            val recalcMethod = bridge.findMethod {
                matcher {
                    if (calcClassName != null) declaredClass = calcClassName
                    modifiers = Modifier.STATIC
                    returnType = "com.android.launcher3.deviceprofile.HotseatWithBorderAndSpace"
                    paramCount = 8
                }
            }.singleOrNull { md ->
                md.paramTypeNames.sorted() == RECALC_HOTSEAT_PARAMS_SORTED
            }
            if (recalcMethod != null) {
                if (calcClassName == null) {
                    out.addProperty(DexIndexConstants.Keys.HOTSEAT_CALC_CLASS, recalcMethod.className)
                }
                val params = recalcMethod.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.HOTSEAT_RECALC_METHOD, recalcMethod.name)
                out.addProperty(DexIndexConstants.Keys.HOTSEAT_RECALC_PARAMS, params)
                Log.i(TAG, "ZuiLauncherHotseatHook: recalc = ${recalcMethod.className}.${recalcMethod.name}($params)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ZuiLauncherHotseatHook: recalc query failed", t)
        }
        return out
    }

    /**
     * Indexes ZuiHotseat dock background Paint fields, split-line View field, and blur provider.
     * See docs/research/zui_launcher_dock_background.md.
     */
    private fun indexDisableDockBackground(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        val zuiHotseat = "com.zui.launcher.uiextend.ZuiHotseat"
        val cellLayout = "com.android.launcher3.CellLayout"

        try {
            val names = bridge.findField {
                searchPackages(scopePackage)
                matcher {
                    declaredClass(zuiHotseat)
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

        try {
            val setWallpaperMethod = bridge.findMethod {
                matcher {
                    declaredClass = zuiHotseat
                    usingStrings("needSetWallpaperColor = ")
                }
            }.singleOrNull { isNormalMethod(it) }

            val splitLineViewField = setWallpaperMethod?.usingFields
                ?.map { it.field }
                ?.distinctBy { "${it.className}#${it.name}" }
                ?.singleOrNull {
                    it.className == zuiHotseat &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.typeName == "android.view.View"
                }
            if (splitLineViewField != null) {
                out.addProperty(DexIndexConstants.Keys.DOCK_SPLIT_LINE_VIEW_FIELD, splitLineViewField.name)
                Log.i(TAG, "DisableDockBackground: splitLineViewField = ${splitLineViewField.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableDockBackground: split-line View field query failed", t)
        }

        try {
            val cellLayoutClass = findClassByName(bridge, cellLayout)
            val blurProviderClass = cellLayoutClass?.interfaces?.singleOrNull { iface ->
                val normalMethods = iface.methods.filter { isNormalMethod(it) }
                normalMethods.size == 2 &&
                    normalMethods.any { it.returnTypeName == "java.util.List" && it.paramCount == 0 } &&
                    normalMethods.any {
                        it.returnTypeName != "void" &&
                            it.returnTypeName != "java.util.List" &&
                            it.paramCount == 0
                    }
            }
            if (blurProviderClass != null) {
                out.addProperty(DexIndexConstants.Keys.DOCK_BLUR_PROVIDER_CLASS, blurProviderClass.name)
                Log.i(TAG, "DisableDockBackground: blurProviderClass = ${blurProviderClass.name}")

                val candidates = bridge.findMethod {
                    matcher {
                        declaredClass = LAUNCHER_CLASS
                        returnType = "void"
                        paramTypes(blurProviderClass.name)
                    }
                }.filter { isNormalInstanceMethod(it) }

                val addBlurMethod = candidates.singleOrNull { candidate ->
                    bridge.findMethod {
                        matcher {
                            declaredClass = LAUNCHER_CLASS
                            name = "setupViews"
                            paramTypes()
                            addInvoke {
                                declaredClass = LAUNCHER_CLASS
                                name = candidate.name
                                returnType = "void"
                                paramTypes(blurProviderClass.name)
                            }
                        }
                    }.isNotEmpty()
                }
                if (addBlurMethod != null) {
                    out.addProperty(DexIndexConstants.Keys.DOCK_ADD_BLUR_METHOD, addBlurMethod.name)
                    Log.i(TAG, "DisableDockBackground: addBlurMethod = ${addBlurMethod.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableDockBackground: blur provider query failed", t)
        }
        return out
    }

    private fun indexDisableRecentAppsDisplay(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        val controllerClass = try {
            bridge.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("java.lang.String", "java.io.PrintWriter")
                    usingStrings(" TaskbarRecentAppsController:", "\tcanShowRecentApps=")
                }
            }.singleOrNull()?.declaredClass
        } catch (t: Throwable) {
            Log.w(TAG, "DisableRecentAppsDisplay: controller query failed", t)
            null
        } ?: return out

        out.addProperty(DexIndexConstants.Keys.RECENT_APPS_CONTROLLER_CLASS, controllerClass.name)
        Log.i(TAG, "DisableRecentAppsDisplay: controller = ${controllerClass.name}")

        try {
            val onChangedMethod = bridge.findMethod {
                matcher {
                    declaredClass = controllerClass.name
                    returnType = "boolean"
                    paramTypes()
                }
            }.singleOrNull { isNormalInstanceMethod(it) }
            if (onChangedMethod != null) {
                out.addProperty(DexIndexConstants.Keys.RECENT_APPS_ON_CHANGED_METHOD, onChangedMethod.name)
                Log.i(TAG, "DisableRecentAppsDisplay: onChanged = ${onChangedMethod.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableRecentAppsDisplay: onChanged query failed", t)
        }
        return out
    }

    private fun indexBatchUninstall(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        val panelClassName = "com.zui.launcher.uiextend.ZuiEditModePanel"
        val panelClass = try {
            findClassByName(bridge, panelClassName)
        } catch (t: Throwable) {
            Log.w(TAG, "BatchUninstall: panel class query failed", t)
            null
        } ?: return out

        try {
            val selectedField = bridge.findMethod {
                matcher {
                    declaredClass = panelClassName
                    name = "getSelectedCount"
                    paramTypes()
                    returnType = "int"
                }
            }.singleOrNull()
                ?.usingFields
                ?.map { it.field }
                ?.distinctBy { "${it.className}#${it.name}" }
                ?.singleOrNull {
                    it.className == panelClassName &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.typeName == "java.util.ArrayList"
                }
            if (selectedField != null) {
                out.addProperty(DexIndexConstants.Keys.BATCH_UNINSTALL_SELECTED_FIELD, selectedField.name)
                Log.i(TAG, "BatchUninstall: selectedField = ${selectedField.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BatchUninstall: selectedField query failed", t)
        }

        try {
            val launcherField = panelClass.fields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.typeName == LAUNCHER_CLASS
            }
            if (launcherField != null) {
                out.addProperty(DexIndexConstants.Keys.BATCH_UNINSTALL_LAUNCHER_FIELD, launcherField.name)
                Log.i(TAG, "BatchUninstall: launcherField = ${launcherField.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BatchUninstall: launcherField query failed", t)
        }

        try {
            val updateUiMethod = bridge.findMethod {
                matcher {
                    declaredClass = panelClassName
                    paramTypes()
                    returnType = "void"
                    addInvoke {
                        declaredClass = "android.content.res.Resources"
                        name = "getQuantityString"
                    }
                }
            }.singleOrNull { isNormalInstanceMethod(it) }
            if (updateUiMethod != null) {
                out.addProperty(DexIndexConstants.Keys.BATCH_UNINSTALL_UPDATE_UI_METHOD, updateUiMethod.name)
                Log.i(TAG, "BatchUninstall: updateUiMethod = ${updateUiMethod.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BatchUninstall: updateUiMethod query failed", t)
        }

        try {
            val clearMethod = bridge.findMethod {
                matcher {
                    declaredClass = panelClassName
                    paramTypes()
                    returnType = "void"
                    addInvoke {
                        declaredClass = "java.util.ArrayList"
                        name = "clear"
                        paramTypes()
                    }
                }
            }.singleOrNull { isNormalInstanceMethod(it) }
            if (clearMethod != null) {
                out.addProperty(DexIndexConstants.Keys.BATCH_UNINSTALL_CLEAR_METHOD, clearMethod.name)
                Log.i(TAG, "BatchUninstall: clearMethod = ${clearMethod.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BatchUninstall: clearMethod query failed", t)
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
            val params = pkgSupport.paramTypeNames.joinToString(",")
            out.addProperty(DexIndexConstants.Keys.FREEFORM_UTILS_CLASS, pkgSupport.className)
            out.addProperty(DexIndexConstants.Keys.FREEFORM_PKG_SUPPORT_METHOD, pkgSupport.name)
            out.addProperty(DexIndexConstants.Keys.FREEFORM_PKG_SUPPORT_PARAMS, params)
            Log.i(TAG, "LauncherFreeformEntry: pkgSupport = ${pkgSupport.className}.${pkgSupport.name}($params)")
        }

        if (utilsClassName != null) {
            val taskSupport = findTaskSupportMethod(bridge, utilsClassName)
            if (taskSupport != null) {
                val params = taskSupport.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.FREEFORM_TASK_SUPPORT_METHOD, taskSupport.name)
                out.addProperty(DexIndexConstants.Keys.FREEFORM_TASK_SUPPORT_PARAMS, params)
                Log.i(TAG, "LauncherFreeformEntry: taskSupport = ${taskSupport.name}($params)")
            }
        }

        val denySet = findDenySetMethod(bridge, utilsClassName)
        if (denySet != null) {
            val params = denySet.paramTypeNames.joinToString(",")
            out.addProperty(DexIndexConstants.Keys.FREEFORM_DENY_SET_METHOD, denySet.name)
            out.addProperty(DexIndexConstants.Keys.FREEFORM_DENY_SET_PARAMS, params)
            Log.i(TAG, "LauncherFreeformEntry: denySet = ${denySet.name}($params)")
        }

        val swipeInit = findSwipeHandlerInit(bridge)
        if (swipeInit != null) {
            val owner = swipeInit.declaredClass
            if (owner != null) {
                val initParams = swipeInit.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.FREEFORM_SWIPE_HANDLER_CLASS, owner.name)
                out.addProperty(DexIndexConstants.Keys.FREEFORM_SWIPE_INIT_PARAMS, initParams)
                Log.i(TAG, "LauncherFreeformEntry: swipeHandler = ${owner.name}($initParams)")

                try {
                    val supportField = owner.methods
                        .filter { it.returnTypeName == "float" && it.paramCount == 0 }
                        .flatMap { it.usingFields }
                        .map { it.field }
                        .distinctBy { "${it.className}#${it.name}" }
                        .singleOrNull { it.className == owner.name && it.typeName == "boolean" }
                        ?: bridge.findMethod {
                            matcher {
                                declaredClass = owner.name
                                returnType = "void"
                                paramCount = 0
                                usingStrings("onLongSwipeDisableUi------")
                            }
                        }.singleOrNull()?.usingFields
                            ?.map { it.field }
                            ?.distinctBy { "${it.className}#${it.name}" }
                            ?.singleOrNull { it.className == owner.name && it.typeName == "boolean" }
                    if (supportField != null) {
                        out.addProperty(DexIndexConstants.Keys.FREEFORM_SWIPE_SUPPORT_FIELD, supportField.name)
                        Log.i(TAG, "LauncherFreeformEntry: supportField = ${supportField.name}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "LauncherFreeformEntry: supportField query failed", t)
                }

                try {
                    val taskInfoField = findFieldInHierarchy(owner, ABS_SWIPE_UP_HANDLER) {
                        !Modifier.isStatic(it.modifiers) && it.typeName == TASK_INFO
                    }
                    if (taskInfoField != null) {
                        out.addProperty(DexIndexConstants.Keys.FREEFORM_SWIPE_TASK_INFO_FIELD, taskInfoField.name)
                        Log.i(TAG, "LauncherFreeformEntry: taskInfoField = ${taskInfoField.name}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "LauncherFreeformEntry: taskInfoField query failed", t)
                }
            }
        }

        if (utilsClassName != null) {
            try {
                val denySetSource = denySet ?: swipeInit
                val staticDenyField = denySetSource?.usingFields
                    ?.map { it.field }
                    ?.distinctBy { "${it.className}#${it.name}" }
                    ?.singleOrNull {
                        it.className == utilsClassName &&
                            Modifier.isStatic(it.modifiers) &&
                            it.typeName == HASH_SET
                    }
                    ?: bridge.findField {
                        matcher {
                            declaredClass(utilsClassName)
                            modifiers = Modifier.STATIC
                            type = HASH_SET
                            readMethods { add { usingStrings(STATIC_DENY_RES_NAME) } }
                        }
                    }.singleOrNull()
                    ?: pkgSupport.declaredClass?.fields?.singleOrNull {
                        Modifier.isStatic(it.modifiers) && it.typeName == HASH_SET
                    }
                if (staticDenyField != null) {
                    out.addProperty(DexIndexConstants.Keys.FREEFORM_STATIC_DENY_FIELD, staticDenyField.name)
                    Log.i(TAG, "LauncherFreeformEntry: staticDenyField = ${staticDenyField.name}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LauncherFreeformEntry: staticDenyField query failed", t)
            }
        }
        return out
    }

    private fun findPkgSupportMethod(bridge: DexKitBridge): MethodData? = try {
        val byManagerInvoke = bridge.findMethod {
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
            isNormalMethod(md) && md.paramTypeNames.sorted() == PKG_SUPPORT_PARAM_TYPES_SORTED
        }
        byManagerInvoke ?: bridge.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 5
                usingStrings("isPackageSupportZuiFreeform componentName:")
            }
        }.singleOrNull { md ->
            isNormalMethod(md) && md.paramTypeNames.sorted() == PKG_SUPPORT_PARAM_TYPES_SORTED
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: pkgSupport query failed", t)
        null
    }

    private fun findTaskSupportMethod(bridge: DexKitBridge, utilsClassName: String): MethodData? = try {
        val candidates = bridge.findMethod {
            matcher {
                declaredClass = utilsClassName
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 2
            }
        }.filter { md ->
            isNormalMethod(md) && md.paramTypeNames.sorted() == TASK_SUPPORT_PARAM_TYPES_SORTED
        }
        candidates.singleOrNull() ?: candidates.singleOrNull { md ->
            md.usingFields.any {
                it.field.className == TASK_INFO &&
                    (it.field.name == "topActivityType" || it.field.name == "supportsMultiWindow")
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: taskSupport query failed", t)
        null
    }

    private fun findDenySetMethod(bridge: DexKitBridge, utilsClassName: String?): MethodData? = try {
        val candidates = bridge.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 2
                usingStrings(STATIC_DENY_RES_NAME)
            }
        }.filter { md ->
            isNormalMethod(md) && md.paramTypeNames.sorted() == DENY_SET_PARAM_TYPES_SORTED
        }
        if (utilsClassName != null) {
            candidates.singleOrNull { it.className == utilsClassName } ?: candidates.singleOrNull()
        } else {
            candidates.singleOrNull()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: denySet query failed", t)
        null
    }

    private fun findSwipeHandlerInit(bridge: DexKitBridge): MethodData? = try {
        fun queryByAnchor(anchor: String): MethodData? = bridge.findMethod {
            matcher {
                name = "<init>"
                usingStrings(anchor)
            }
        }.singleOrNull { md ->
            val owner = md.declaredClass
            owner != null && isSubclassOf(owner, ABS_SWIPE_UP_HANDLER)
        }
        // Primary anchor lives in the handler ctor itself even when deny-set helpers are inlined.
        queryByAnchor(SWIPE_FLOATING_LOG_ANCHOR) ?: queryByAnchor(STATIC_DENY_RES_NAME)
    } catch (t: Throwable) {
        Log.w(TAG, "LauncherFreeformEntry: swipeHandler query failed", t)
        null
    }

    private fun indexLauncherFreeformSwipeTrigger(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        val handlerClass = try {
            findSwipeHandlerInit(bridge)?.declaredClass
                ?: findClassByName(bridge, LAUNCHER_SWIPE_HANDLER_V2)
                    ?.superClass
                    ?.takeIf { it.name != ABS_SWIPE_UP_HANDLER }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherFreeformSwipeTrigger: handler class query failed", t)
            null
        }

        if (handlerClass != null) {
            out.addProperty(DexIndexConstants.Keys.SWIPE_TRIGGER_HANDLER_CLASS, handlerClass.name)
            Log.i(TAG, "LauncherFreeformSwipeTrigger: handlerClass = ${handlerClass.name}")

            try {
                val uiLongSwipeMethod = bridge.findMethod {
                    matcher {
                        declaredClass = handlerClass.name
                        name = "isUiLongSwipeMode"
                        returnType = "boolean"
                        paramTypes()
                    }
                }.singleOrNull()
                val uiLongSwipeField = uiLongSwipeMethod?.usingFields
                    ?.map { it.field }
                    ?.distinctBy { "${it.className}#${it.name}" }
                    ?.singleOrNull {
                        it.className == handlerClass.name &&
                            !Modifier.isStatic(it.modifiers) &&
                            it.typeName == "boolean"
                    }
                if (uiLongSwipeField != null) {
                    out.addProperty(
                        DexIndexConstants.Keys.SWIPE_TRIGGER_UI_LONG_SWIPE_FIELD,
                        uiLongSwipeField.name
                    )
                    Log.i(
                        TAG,
                        "LauncherFreeformSwipeTrigger: uiLongSwipeField = ${uiLongSwipeField.name}"
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LauncherFreeformSwipeTrigger: uiLongSwipeField query failed", t)
            }

            try {
                val longSwipeViewField = handlerClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == LONG_SWIPE_VIEW_CLASS
                }
                if (longSwipeViewField != null) {
                    out.addProperty(
                        DexIndexConstants.Keys.SWIPE_TRIGGER_LONG_SWIPE_VIEW_FIELD,
                        longSwipeViewField.name
                    )
                    Log.i(
                        TAG,
                        "LauncherFreeformSwipeTrigger: longSwipeViewField = ${longSwipeViewField.name}"
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LauncherFreeformSwipeTrigger: longSwipeViewField query failed", t)
            }
        }

        try {
            val viewClass = findClassByName(bridge, LONG_SWIPE_VIEW_CLASS)
            if (viewClass != null) {
                val updateMethod = viewClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf("float", "int")
                }
                if (updateMethod != null) {
                    out.addProperty(
                        DexIndexConstants.Keys.SWIPE_TRIGGER_LONG_SWIPE_VIEW_UPDATE_METHOD,
                        updateMethod.name
                    )
                    Log.i(
                        TAG,
                        "LauncherFreeformSwipeTrigger: updateMethod = ${updateMethod.name}"
                    )
                }

                val floatVoidMethods = viewClass.methods.filter {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf("float")
                }
                val setPercentMethod = bridge.findMethod {
                    matcher {
                        declaredClass = LONG_SWIPE_VIEW_CLASS
                        returnType = "void"
                        paramTypes("float")
                        usingStrings("percent:")
                    }
                }.singleOrNull { isNormalInstanceMethod(it) }
                    ?: floatVoidMethods.singleOrNull()
                if (setPercentMethod != null) {
                    out.addProperty(
                        DexIndexConstants.Keys.SWIPE_TRIGGER_LONG_SWIPE_VIEW_SET_PERCENT_METHOD,
                        setPercentMethod.name
                    )
                    Log.i(
                        TAG,
                        "LauncherFreeformSwipeTrigger: setPercentMethod = ${setPercentMethod.name}"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherFreeformSwipeTrigger: LongSwipeView query failed", t)
        }

        return out
    }

    private fun indexBluePointRemoval(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val md = bridge.findMethod {
                matcher {
                    modifiers = Modifier.STATIC
                    paramTypes("android.view.View")
                    returnType = "boolean"
                    usingStrings("new_update_apps_versions")
                }
            }.singleOrNull { isNormalMethod(it) }
            if (md != null) {
                out.addProperty(DexIndexConstants.Keys.BLUE_POINT_CLASS, md.className)
                out.addProperty(DexIndexConstants.Keys.BLUE_POINT_IS_PACKAGE_NEW_METHOD, md.name)
                Log.i(TAG, "BluePointRemoval: ${md.className}.${md.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BluePointRemoval: query failed", t)
        }
        return out
    }

    private fun indexBigFolderBlurGuard(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val fiClass = findClassByName(bridge, FOLDER_ICON_CLASS)
            val blurMethod = bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramCount = 0
                    returnType = "void"
                    addInvoke {
                        name = "setClipBounds"
                    }
                }
            }.singleOrNull { isNormalMethod(it) }
            if (blurMethod != null) {
                out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DATA_METHOD, blurMethod.name)
                Log.i(TAG, "BigFolderBlurGuard: blurData = ${blurMethod.name}")
            }

            val addProvider = bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramCount = 0
                    returnType = "void"
                    addInvoke {
                        declaredClass = LAUNCHER_CLASS
                        name = "addBlurAreaProvider"
                    }
                }
            }.singleOrNull { isNormalMethod(it) }

            if (blurMethod != null && addProvider != null) {
                val blurBools = fieldsOfTypeOn(blurMethod, FOLDER_ICON_CLASS, "boolean")
                val providerBools = fieldsOfTypeOn(addProvider, FOLDER_ICON_CLASS, "boolean")
                val dragFlag = blurBools.intersect(providerBools).singleOrNull()
                if (dragFlag != null) {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DRAG_FLAG_FIELD, dragFlag)
                    Log.i(TAG, "BigFolderBlurGuard: dragFlag = $dragFlag")
                }
            }

            val actField = fiClass?.fields?.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.typeName == ACTIVITY_CONTEXT_CLASS
            }
            if (actField != null) {
                out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_BLUR_ACTIVITY_FIELD, actField.name)
                Log.i(TAG, "BigFolderBlurGuard: activityField = ${actField.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderBlurGuard: FolderIcon query failed", t)
        }

        try {
            val dcClass = findClassByName(bridge, DRAG_CONTROLLER_CLASS)
            if (dcClass != null) {
                val dragObjMethod = dcClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.paramCount == 0 &&
                        it.returnTypeName == DRAG_OBJECT_CLASS
                }
                if (dragObjMethod != null) {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DRAG_OBJECT_METHOD, dragObjMethod.name)
                    Log.i(TAG, "BigFolderBlurGuard: dragObjectMethod = ${dragObjMethod.name}")
                }
                val dragObjField = dcClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == DRAG_OBJECT_CLASS
                }
                if (dragObjField != null) {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DRAG_OBJECT_FIELD, dragObjField.name)
                    Log.i(TAG, "BigFolderBlurGuard: dragObjectField = ${dragObjField.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderBlurGuard: DragController query failed", t)
        }
        return out
    }

    private fun indexLauncherGridMetrics(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        var wsClass: ClassData? = null
        var folderClass: ClassData? = null

        try {
            wsClass = bridge.findMethod {
                matcher {
                    name = "toString"
                    paramCount = 0
                    usingStrings("WorkspaceProfile(iconScale=")
                }
            }.mapNotNull { it.declaredClass }.distinctBy { it.name }.singleOrNull()
                ?: findClassByName(bridge, "com.android.launcher3.deviceprofile.WorkspaceProfile")

            folderClass = bridge.findMethod {
                matcher {
                    name = "toString"
                    paramCount = 0
                    usingStrings("FolderProfile(labelTextSizePx=")
                }
            }.mapNotNull { it.declaredClass }.distinctBy { it.name }.singleOrNull()
                ?: findClassByName(bridge, "com.android.launcher3.deviceprofile.FolderProfile")

            if (wsClass != null) {
                out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WORKSPACE_PROFILE_CLASS, wsClass.name)
                Log.i(TAG, "LauncherGridMetrics: workspaceProfileClass = ${wsClass.name}")
            }
            if (folderClass != null) {
                out.addProperty(DexIndexConstants.Keys.GRID_METRICS_FOLDER_PROFILE_CLASS, folderClass.name)
                Log.i(TAG, "LauncherGridMetrics: folderProfileClass = ${folderClass.name}")
            }

            val dpClass = findClassByName(bridge, DEVICE_PROFILE_CLASS)
            if (dpClass != null && wsClass != null) {
                dpClass.fields.singleOrNull { !Modifier.isStatic(it.modifiers) && it.typeName == wsClass.name }
                    ?.let {
                        out.addProperty(DexIndexConstants.Keys.GRID_METRICS_DP_WORKSPACE_PROFILE_FIELD, it.name)
                        Log.i(TAG, "LauncherGridMetrics: dpWorkspaceProfileField = ${it.name}")
                    }
                dpClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) && it.paramCount == 0 && it.returnTypeName == wsClass.name
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_DP_GET_WORKSPACE_PROFILE_METHOD, it.name)
                    Log.i(TAG, "LauncherGridMetrics: dpGetWorkspaceProfileMethod = ${it.name}")
                }
                dpClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf(wsClass.name)
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_DP_SET_WORKSPACE_PROFILE_METHOD, it.name)
                    Log.i(TAG, "LauncherGridMetrics: dpSetWorkspaceProfileMethod = ${it.name}")
                }
            }
            if (dpClass != null && folderClass != null) {
                dpClass.fields.singleOrNull { !Modifier.isStatic(it.modifiers) && it.typeName == folderClass.name }
                    ?.let {
                        out.addProperty(DexIndexConstants.Keys.GRID_METRICS_DP_FOLDER_PROFILE_FIELD, it.name)
                        Log.i(TAG, "LauncherGridMetrics: dpFolderProfileField = ${it.name}")
                    }
                dpClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) && it.paramCount == 0 && it.returnTypeName == folderClass.name
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_DP_GET_FOLDER_PROFILE_METHOD, it.name)
                    Log.i(TAG, "LauncherGridMetrics: dpGetFolderProfileMethod = ${it.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherGridMetrics: profile class query failed", t)
        }

        if (wsClass != null) {
            try {
                val wsName = wsClass.name
                val wsSetInsets = bridge.findMethod {
                    matcher {
                        declaredClass = WORKSPACE_CLASS
                        name = "setInsets"
                        paramTypes("android.graphics.Rect")
                        returnType = "void"
                    }
                }.singleOrNull()
                val fiMarginMethod = bridge.findMethod {
                    matcher {
                        declaredClass = FOLDER_ICON_CLASS
                        paramCount = 0
                        returnType = "void"
                        addInvoke {
                            declaredClass = BUBBLE_TEXT_VIEW_CLASS
                            name = "requestLayout"
                        }
                    }
                }.singleOrNull { isNormalMethod(it) }
                val fiInflateIcon = bridge.findMethod {
                    matcher {
                        declaredClass = FOLDER_ICON_CLASS
                        modifiers = Modifier.STATIC
                        returnType = FOLDER_ICON_CLASS
                        paramCount = 4
                    }
                }.singleOrNull { md ->
                    md.paramTypeNames == listOf("int", ACTIVITY_CONTEXT_CLASS, "android.view.ViewGroup", FOLDER_INFO_CLASS)
                }
                val fiBigFolderPadTop = bridge.findMethod {
                    matcher {
                        declaredClass = FOLDER_ICON_CLASS
                        paramCount = 0
                        returnType = "int"
                        addInvoke {
                            declaredClass = "android.graphics.Paint"
                            name = "setTextSize"
                        }
                    }
                }.singleOrNull { isNormalMethod(it) }
                val containerGetCellContentH = bridge.findMethod {
                    matcher {
                        declaredClass = CONTAINER_CLASS
                        paramCount = 0
                        returnType = "int"
                        addInvoke {
                            name = "getMeasuredHeight"
                        }
                    }
                }.singleOrNull { isNormalMethod(it) }
                val containerMeasureChild = bridge.findMethod {
                    matcher {
                        declaredClass = CONTAINER_CLASS
                        paramTypes("android.view.View")
                        returnType = "void"
                        addInvoke {
                            declaredClass = "android.view.View"
                            name = "setPadding"
                        }
                    }
                }.singleOrNull { isNormalMethod(it) }
                val famCreateNewView = bridge.findMethod {
                    matcher {
                        declaredClass = FOLDER_ANIM_MGR_CLASS
                        paramTypes(ITEM_INFO_CLASS)
                        returnType = "android.view.View"
                    }
                }.singleOrNull { isNormalMethod(it) }
                val secDragLayerOnMeasure = bridge.findMethod {
                    matcher {
                        declaredClass = "com.android.launcher3.secondarydisplay.SecondaryDragLayer"
                        name = "onMeasure"
                        paramTypes("int", "int")
                        returnType = "void"
                    }
                }.singleOrNull()

                val insetsRects = fieldsOfTypeOn(wsSetInsets, wsName, "android.graphics.Rect")
                val fiMarginRects = fieldsOfTypeOn(fiMarginMethod, wsName, "android.graphics.Rect")
                val cellLayoutPadField = insetsRects.intersect(fiMarginRects).singleOrNull()
                cellLayoutPadField?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_CELL_LAYOUT_PADDING_FIELD, it)
                    Log.i(TAG, "LauncherGridMetrics: wsCellLayoutPaddingField = $it")
                    (fiMarginRects - it).singleOrNull()?.let { wp ->
                        out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_WIDGET_PADDING_FIELD, wp)
                        Log.i(TAG, "LauncherGridMetrics: wsWidgetPaddingField = $wp")
                    }
                }

                fieldsOfTypeOn(containerMeasureChild, wsName, "android.graphics.Point").singleOrNull()?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_BORDER_SPACE_FIELD, it)
                    Log.i(TAG, "LauncherGridMetrics: wsBorderSpaceField = $it")
                }

                val cellContentInts = fieldsOfTypeOn(containerGetCellContentH, wsName, "int")
                val inflateInts = fieldsOfTypeOn(fiInflateIcon, wsName, "int")
                val iconSizeField = cellContentInts.intersect(inflateInts).singleOrNull()
                iconSizeField?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_ICON_SIZE_FIELD, it)
                    Log.i(TAG, "LauncherGridMetrics: wsIconSizeField = $it")
                }
                val cellHeightField = if (iconSizeField != null) (cellContentInts - iconSizeField).singleOrNull() else null
                cellHeightField?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_CELL_HEIGHT_FIELD, it)
                    Log.i(TAG, "LauncherGridMetrics: wsCellHeightField = $it")
                }

                val marginInts = fieldsOfTypeOn(fiMarginMethod, wsName, "int")
                if (iconSizeField != null && cellHeightField != null) {
                    (marginInts - iconSizeField - cellHeightField).singleOrNull()?.let {
                        out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_ICON_DRAWABLE_PADDING_FIELD, it)
                        Log.i(TAG, "LauncherGridMetrics: wsIconDrawablePaddingField = $it")
                    }
                }

                fieldsOfTypeOn(fiBigFolderPadTop, wsName, "int").singleOrNull()?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_ICON_TEXT_SIZE_FIELD, it)
                    Log.i(TAG, "LauncherGridMetrics: wsIconTextSizeField = $it")
                }

                val edgeMarginField = fieldsOfTypeOn(famCreateNewView, wsName, "int").singleOrNull()
                val secMeasureInts = fieldsOfTypeOn(secDragLayerOnMeasure, wsName, "int")
                if (iconSizeField != null && edgeMarginField != null) {
                    (secMeasureInts - iconSizeField - edgeMarginField).singleOrNull()?.let {
                        out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_DESIRED_H_MARGIN_FIELD, it)
                        Log.i(TAG, "LauncherGridMetrics: wsDesiredHMarginField = $it")
                    }
                }

                val insetsInts = fieldsOfTypeOn(wsSetInsets, wsName, "int")
                val measureChildInts = fieldsOfTypeOn(containerMeasureChild, wsName, "int")
                if (insetsInts.isNotEmpty()) {
                    (measureChildInts - insetsInts).singleOrNull()?.let {
                        out.addProperty(DexIndexConstants.Keys.GRID_METRICS_WS_CELL_Y_PADDING_FIELD, it)
                        Log.i(TAG, "LauncherGridMetrics: wsCellYPaddingField = $it")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LauncherGridMetrics: workspace fields query failed", t)
            }
        }

        if (folderClass != null) {
            try {
                val fName = folderClass.name
                val createFolderAdaptive = bridge.findMethod {
                    matcher {
                        declaredClass = "com.android.launcher3.dragndrop.FolderAdaptiveIcon"
                        modifiers = Modifier.STATIC
                        paramTypes(ACTIVITY_CONTEXT_CLASS, "int", "android.graphics.Point")
                    }
                }.singleOrNull()
                val pbSetup = bridge.findMethod {
                    matcher {
                        declaredClass = PREVIEW_BG_CLASS
                        paramTypes(
                            "android.content.Context",
                            ACTIVITY_CONTEXT_CLASS,
                            "android.view.View",
                            "int",
                            "int",
                            "int",
                            "int"
                        )
                        returnType = "void"
                    }
                }.singleOrNull()
                val folderIconSizeField = fieldsOfTypeOn(createFolderAdaptive, fName, "int").singleOrNull()
                folderIconSizeField?.let {
                    out.addProperty(DexIndexConstants.Keys.GRID_METRICS_FOLDER_ICON_SIZE_FIELD, it)
                    Log.i(TAG, "LauncherGridMetrics: folderIconSizeField = $it")
                    val pbInts = fieldsOfTypeOn(pbSetup, fName, "int")
                    (pbInts - it).singleOrNull()?.let { offsetY ->
                        out.addProperty(DexIndexConstants.Keys.GRID_METRICS_FOLDER_ICON_OFFSET_Y_FIELD, offsetY)
                        Log.i(TAG, "LauncherGridMetrics: folderIconOffsetYField = $offsetY")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LauncherGridMetrics: folder fields query failed", t)
            }
        }
        return out
    }

    private fun indexBigFolderAlign(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()

        try {
            val pbClass = findClassByName(bridge, PREVIEW_BG_CLASS)
            if (pbClass != null) {
                val pbGetBounds = pbClass.methods.singleOrNull {
                    isNormalMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf("android.graphics.Rect", "int", "int")
                }
                val pbGetRadius = pbClass.methods.singleOrNull {
                    isNormalMethod(it) &&
                        it.paramCount == 0 &&
                        it.returnTypeName == "int" &&
                        fieldsOfTypeOn(it, PREVIEW_BG_CLASS, "android.content.Context").isNotEmpty()
                }
                val boundsInts = fieldsOfTypeOn(pbGetBounds, PREVIEW_BG_CLASS, "int")
                val radiusInts = fieldsOfTypeOn(pbGetRadius, PREVIEW_BG_CLASS, "int")
                val previewSize = boundsInts.intersect(radiusInts).singleOrNull()
                previewSize?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_FIELD, it)
                    Log.i(TAG, "BigFolderAlign: pbPreviewSizeField = $it")
                }

                val getOffsetX = pbClass.methods.singleOrNull {
                    it.name == "getOffsetX" && it.paramCount == 0 && it.returnTypeName == "int"
                }
                val getOffsetY = pbClass.methods.singleOrNull {
                    it.name == "getOffsetY" && it.paramCount == 0 && it.returnTypeName == "int"
                }
                val offX = fieldsOfTypeOn(getOffsetX, PREVIEW_BG_CLASS, "int").intersect(boundsInts).singleOrNull()
                val offY = fieldsOfTypeOn(getOffsetY, PREVIEW_BG_CLASS, "int").intersect(boundsInts).singleOrNull()
                offX?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_OFFSET_X_FIELD, it)
                    Log.i(TAG, "BigFolderAlign: pbOffsetXField = $it")
                }
                offY?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_OFFSET_Y_FIELD, it)
                    Log.i(TAG, "BigFolderAlign: pbOffsetYField = $it")
                }
                if (previewSize != null && offX != null && offY != null) {
                    (boundsInts - previewSize - offX - offY).singleOrNull()?.let {
                        out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_Y_FIELD, it)
                        Log.i(TAG, "BigFolderAlign: pbPreviewSizeYField = $it")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderAlign: PreviewBackground query failed", t)
        }

        try {
            val ruleClass = findClassByName(bridge, LAYOUT_RULE_CLASS)
            if (ruleClass != null) {
                val getPos = ruleClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf("int", "float[]") &&
                        fieldsOfTypeOn(it, LAYOUT_RULE_CLASS, "int").isNotEmpty()
                }
                val scaleMethod = ruleClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "float" &&
                        (it.paramTypeNames == listOf("boolean") || it.paramTypeNames == listOf("int", "boolean"))
                }

                val computeMethod = ruleClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) && it.paramCount == 9 && it.returnTypeName == PREVIEW_PARAMS_CLASS
                } ?: ruleClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf("int", "int", "float[]", "int", "int", "int", "int", "int")
                }
                if (computeMethod != null) {
                    val params = computeMethod.paramTypeNames.joinToString(",")
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_COMPUTE_METHOD, computeMethod.name)
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_COMPUTE_PARAMS, params)
                    Log.i(TAG, "BigFolderAlign: ruleCompute = ${computeMethod.name}($params)")
                }

                val baselineScale = fieldsOfTypeOn(scaleMethod, LAYOUT_RULE_CLASS, "float").singleOrNull()
                val posFloats = fieldsOfTypeOn(getPos, LAYOUT_RULE_CLASS, "float")
                val allRuleFloats = ruleClass.fields.filter {
                    !Modifier.isStatic(it.modifiers) && it.typeName == "float"
                }.map { it.name }.toSet()
                val famMethods = findClassByName(bridge, FOLDER_ANIM_MGR_CLASS)?.methods.orEmpty()
                val famRuleFloats = famMethods.flatMap { fieldsOfTypeOn(it, LAYOUT_RULE_CLASS, "float") }.toSet()
                val iconSizeField = famRuleFloats.singleOrNull()
                iconSizeField?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_ICON_SIZE_FIELD, it)
                    Log.i(TAG, "BigFolderAlign: ruleIconSizeField = $it")
                }
                if (baselineScale != null && iconSizeField != null) {
                    (posFloats - baselineScale - iconSizeField).singleOrNull()?.let {
                        out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_BG_WIDTH_FIELD, it)
                        Log.i(TAG, "BigFolderAlign: ruleBgWidthField = $it")
                    }
                }
                (allRuleFloats - posFloats).singleOrNull()?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_BG_HEIGHT_FIELD, it)
                    Log.i(TAG, "BigFolderAlign: ruleBgHeightField = $it")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderAlign: LayoutRule query failed", t)
        }

        try {
            val bfcClass = bridge.findMethod {
                matcher { usingStrings("BigFolderConfig") }
            }.mapNotNull { it.declaredClass }.distinctBy { it.name }.singleOrNull()
            if (bfcClass != null) {
                out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_CLASS, bfcClass.name)
                Log.i(TAG, "BigFolderAlign: bfcClass = ${bfcClass.name}")

                bfcClass.methods.singleOrNull {
                    it.name != "<clinit>" && Modifier.isStatic(it.modifiers) &&
                        it.returnTypeName == "int" && it.paramTypeNames == listOf("int", "int", "int[]")
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_CHILD_COUNT_METHOD, it.name)
                    Log.i(TAG, "BigFolderAlign: bfcChildCountMethod = ${it.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderAlign: BigFolderConfig query failed", t)
        }

        try {
            val fiClass = findClassByName(bridge, FOLDER_ICON_CLASS)
            if (fiClass != null) {
                fiClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == FOLDER_INFO_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_INFO_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: fiInfoField = ${it.name}")
                }
                fiClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == BUBBLE_TEXT_VIEW_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: fiLabelField = ${it.name}")
                }
                fiClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == PREVIEW_BG_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_BG_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: fiBgField = ${it.name}")
                }
                fiClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == LAYOUT_RULE_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LAYOUT_RULE_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: fiLayoutRuleField = ${it.name}")
                }
                fiClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == PREVIEW_ITEM_MGR_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_PIM_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: fiPimField = ${it.name}")
                }
            }
            val fiLabelMethod = bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramCount = 0
                    returnType = "void"
                    addInvoke {
                        declaredClass = BUBBLE_TEXT_VIEW_CLASS
                        name = "requestLayout"
                    }
                }
            }.singleOrNull { isNormalMethod(it) }
            fiLabelMethod?.let {
                out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_METHOD, it.name)
                Log.i(TAG, "BigFolderAlign: fiLabelMethod = ${it.name}")
            }
            fieldsOfTypeOn(fiLabelMethod, FOLDER_ICON_CLASS, "int").singleOrNull()?.let {
                out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_NAME_TOP_MARGIN_FIELD, it)
                Log.i(TAG, "BigFolderAlign: fiNameTopMarginField = $it")
            }
            bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramCount = 0
                    returnType = "int"
                    addInvoke {
                        declaredClass = "android.graphics.Paint"
                        name = "setTextSize"
                    }
                }
            }.singleOrNull { isNormalMethod(it) }?.let {
                out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_PAD_TOP_METHOD, it.name)
                Log.i(TAG, "BigFolderAlign: fiPadTopMethod = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderAlign: FolderIcon query failed", t)
        }

        try {
            val pimClass = findClassByName(bridge, PREVIEW_ITEM_MGR_CLASS)
            if (pimClass != null) {
                pimClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == FOLDER_ICON_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_ICON_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: pimIconField = ${it.name}")
                }
                pimClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf(
                            "android.graphics.Canvas",
                            "java.util.ArrayList",
                            "android.graphics.PointF",
                            "boolean",
                            "android.graphics.Path",
                            "boolean"
                        )
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_DRAW_PARAMS_METHOD, it.name)
                    Log.i(TAG, "BigFolderAlign: pimDrawParamsMethod = ${it.name}")
                }
                val checkClickMethod = pimClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == PREVIEW_PARAMS_CLASS &&
                        it.paramTypeNames == listOf(
                            FOLDER_ICON_CLASS,
                            "float",
                            "float",
                            "android.graphics.Rect",
                            "int"
                        )
                }
                checkClickMethod?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_CHECK_CLICK_METHOD, it.name)
                    Log.i(TAG, "BigFolderAlign: pimCheckClickMethod = ${it.name}")
                }
                fieldsOfTypeOn(checkClickMethod, PREVIEW_ITEM_MGR_CLASS, "java.util.ArrayList").singleOrNull()?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_FIRST_PAGE_FIELD, it)
                    Log.i(TAG, "BigFolderAlign: pimFirstPageField = $it")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderAlign: PreviewItemManager query failed", t)
        }

        try {
            val famClass = findClassByName(bridge, FOLDER_ANIM_MGR_CLASS)
            if (famClass != null) {
                famClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == FOLDER_ICON_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_ICON_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: famIconField = ${it.name}")
                }
                famClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == PREVIEW_PARAMS_CLASS
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_TMP_PARAMS_FIELD, it.name)
                    Log.i(TAG, "BigFolderAlign: famTmpParamsField = ${it.name}")
                }
                val computeBigAnim = famClass.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramCount == 15 &&
                        it.paramTypeNames[8] == "float" &&
                        it.paramTypeNames[10] == "float" &&
                        it.paramTypeNames[14] == "android.animation.AnimatorSet"
                }
                if (computeBigAnim != null) {
                    val params = computeBigAnim.paramTypeNames.joinToString(",")
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_COMPUTE_BIG_ANIM_METHOD, computeBigAnim.name)
                    out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_COMPUTE_BIG_ANIM_PARAMS, params)
                    Log.i(TAG, "BigFolderAlign: famComputeBigAnim = ${computeBigAnim.name}($params)")

                    fieldsOfTypeOn(computeBigAnim, PREVIEW_ITEM_MGR_CLASS, "int").singleOrNull()?.let {
                        out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_ICON_SIZE_FIELD, it)
                        Log.i(TAG, "BigFolderAlign: pimIconSizeField = $it")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderAlign: FolderAnimationManager query failed", t)
        }
        return out
    }

    private fun indexBigFolderExternalTitle(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val fiClass = findClassByName(bridge, FOLDER_ICON_CLASS)
            fiClass?.fields?.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.typeName == ACTIVITY_CONTEXT_CLASS
            }?.let {
                out.addProperty(DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_ACTIVITY_FIELD, it.name)
                Log.i(TAG, "BigFolderExternalTitle: fiActivityField = ${it.name}")
            }

            bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramCount = 0
                    returnType = "void"
                    addInvoke { name = "setTextSize" }
                }
            }.singleOrNull { isNormalMethod(it) }?.let {
                out.addProperty(
                    DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_NAME_SIZE_METHOD,
                    it.name
                )
                Log.i(TAG, "BigFolderExternalTitle: fiSetNameSizeMethod = ${it.name}")
            }

            bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramCount = 0
                    returnType = "void"
                    addInvoke { name = "setScaleX" }
                }
            }.singleOrNull { isNormalMethod(it) }?.let {
                out.addProperty(
                    DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_NAME_SCALE_METHOD,
                    it.name
                )
                Log.i(TAG, "BigFolderExternalTitle: fiSetNameScaleMethod = ${it.name}")
            }

            bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramCount = 0
                    returnType = "void"
                    addInvoke { name = "setMargins" }
                }
            }.singleOrNull { isNormalMethod(it) }?.let {
                out.addProperty(
                    DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_ICON_MARGIN_METHOD,
                    it.name
                )
                Log.i(TAG, "BigFolderExternalTitle: fiSetIconMarginMethod = ${it.name}")
            }

            bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ICON_CLASS
                    paramTypes("int")
                    returnType = "void"
                    addInvoke { name = "getAttrColor" }
                }
            }.singleOrNull { isNormalMethod(it) }?.let {
                out.addProperty(
                    DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_NAME_COLOR_METHOD,
                    it.name
                )
                Log.i(TAG, "BigFolderExternalTitle: fiSetNameColorMethod = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderExternalTitle: FolderIcon query failed", t)
        }

        try {
            val listenerClass = bridge.findMethod {
                searchPackages("com.android.launcher3.folder")
                matcher {
                    name = "onAnimationUpdate"
                    paramTypes("android.animation.ValueAnimator")
                    returnType = "void"
                    addInvoke { name = "setTranslationY" }
                    addInvoke { name = "setTranslationX" }
                }
            }.mapNotNull { it.declaredClass }
                .distinctBy { it.name }
                .singleOrNull { cls ->
                    val pbFieldCount = cls.fields.count {
                        !Modifier.isStatic(it.modifiers) && it.typeName == PREVIEW_BG_CLASS
                    }
                    val fiFieldCount = cls.fields.count {
                        !Modifier.isStatic(it.modifiers) && it.typeName == FOLDER_ICON_CLASS
                    }
                    pbFieldCount == 1 && fiFieldCount == 1
                }
            if (listenerClass != null) {
                out.addProperty(
                    DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_RESIZE_LABEL_LISTENER_CLASS,
                    listenerClass.name
                )
                val pbField = listenerClass.fields.single {
                    !Modifier.isStatic(it.modifiers) && it.typeName == PREVIEW_BG_CLASS
                }
                val fiField = listenerClass.fields.single {
                    !Modifier.isStatic(it.modifiers) && it.typeName == FOLDER_ICON_CLASS
                }
                out.addProperty(
                    DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_RESIZE_LABEL_PB_FIELD,
                    pbField.name
                )
                out.addProperty(
                    DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_RESIZE_LABEL_FI_FIELD,
                    fiField.name
                )
                Log.i(
                    TAG,
                    "BigFolderExternalTitle: resizeListener = ${listenerClass.name}(${pbField.name}, ${fiField.name})"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BigFolderExternalTitle: resize listener query failed", t)
        }
        return out
    }

    private fun indexLauncherNoLabelMode(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            bridge.findMethod {
                matcher {
                    declaredClass = FOLDER_ANIM_MGR_CLASS
                    paramTypes(ITEM_INFO_CLASS)
                    returnType = "android.view.View"
                }
            }.singleOrNull { isNormalMethod(it) }?.let {
                out.addProperty(DexIndexConstants.Keys.NO_LABEL_FOLDER_ANIM_CREATE_METHOD, it.name)
                Log.i(TAG, "LauncherNoLabelMode: folderAnimCreate = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherNoLabelMode: folderAnimCreate query failed", t)
        }

        try {
            val popupImpl = bridge.findMethod {
                matcher { usingStrings("ZuiPopupContainerWithArrow") }
            }.mapNotNull { it.declaredClass }.distinctBy { it.name }.singleOrNull {
                isSubclassOf(it, "com.android.launcher3.popup.PopupContainerWithArrow")
            }
            popupImpl?.let {
                out.addProperty(DexIndexConstants.Keys.NO_LABEL_POPUP_IMPL_CLASS, it.name)
                Log.i(TAG, "LauncherNoLabelMode: popupImpl = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherNoLabelMode: popupImpl query failed", t)
        }

        try {
            findClassByName(bridge, $$"com.android.launcher3.util.MultiPropertyFactory$FloatBiFunction")
                ?.methods?.singleOrNull {
                    it.returnTypeName == "float" && it.paramTypeNames == listOf("float", "float")
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.NO_LABEL_FLOAT_BI_FUNC_METHOD, it.name)
                    Log.i(TAG, "LauncherNoLabelMode: floatBiFunc = ${it.name}")
                }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherNoLabelMode: floatBiFunc query failed", t)
        }

        try {
            val famClass = findClassByName(bridge, FOLDER_ANIM_MGR_CLASS)
            val folderClass = findClassByName(bridge, FOLDER_CLASS)
            val createSetMethod = famClass?.methods?.singleOrNull {
                isNormalInstanceMethod(it) &&
                    it.returnTypeName == "android.animation.AnimatorSet" &&
                    it.paramTypeNames == listOf("boolean")
            }
            createSetMethod?.let {
                out.addProperty(DexIndexConstants.Keys.NO_LABEL_FOLDER_ANIM_CREATE_SET_METHOD, it.name)
                Log.i(TAG, "LauncherNoLabelMode: folderAnimCreateSet = ${it.name}")
            }
            famClass?.fields?.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.typeName == FOLDER_CLASS
            }?.let {
                out.addProperty(DexIndexConstants.Keys.NO_LABEL_FOLDER_ANIM_FOLDER_FIELD, it.name)
                Log.i(TAG, "LauncherNoLabelMode: folderAnimFolderField = ${it.name}")
            }
            val flagField = createSetMethod?.usingFields
                ?.map { it.field }
                ?.distinctBy { "${it.className}#${it.name}" }
                ?.singleOrNull {
                    it.className == FOLDER_CLASS &&
                        Modifier.isStatic(it.modifiers) &&
                        !Modifier.isFinal(it.modifiers) &&
                        it.typeName == "boolean"
                }
                ?: folderClass?.fields?.singleOrNull {
                    Modifier.isStatic(it.modifiers) &&
                        !Modifier.isFinal(it.modifiers) &&
                        it.typeName == "boolean"
                }
            flagField?.let {
                out.addProperty(DexIndexConstants.Keys.NO_LABEL_FOLDER_NAME_ANIM_FLAG_FIELD, it.name)
                Log.i(TAG, "LauncherNoLabelMode: folderNameAnimFlagField = ${it.name}")
            }
            folderClass?.fields?.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.typeName == BUBBLE_TEXT_VIEW_CLASS
            }?.let {
                out.addProperty(DexIndexConstants.Keys.NO_LABEL_FOLDER_NAME_DELEGATE_FIELD, it.name)
                Log.i(TAG, "LauncherNoLabelMode: folderNameDelegateField = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherNoLabelMode: folder title fade query failed", t)
        }
        return out
    }

    private fun indexLauncherDrawerNoLabelMode(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            bridge.findMethod {
                matcher {
                    declaredClass = ACTIVE_ICON_VIEW_CLASS
                    paramTypes(ITEM_INFO_WITH_ICON_CLASS)
                    returnType = "void"
                    addInvoke {
                        name = "removeAllViews"
                    }
                }
            }.singleOrNull { isNormalMethod(it) }?.let {
                out.addProperty(DexIndexConstants.Keys.DRAWER_NO_LABEL_ACTIVE_BIND_ITEM_METHOD, it.name)
                Log.i(TAG, "LauncherDrawerNoLabelMode: activeBindItem = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherDrawerNoLabelMode: activeBindItem query failed", t)
        }

        try {
            findClassByName(bridge, BUBBLE_TEXT_VIEW_CLASS)?.methods?.singleOrNull {
                it.name == "applyFromItemInfoWithIcon" && it.paramTypeNames == listOf(ITEM_INFO_WITH_ICON_CLASS)
            }?.let {
                out.addProperty(DexIndexConstants.Keys.DRAWER_NO_LABEL_BUBBLE_BIND_ITEM_METHOD, it.name)
                Log.i(TAG, "LauncherDrawerNoLabelMode: bubbleBindItem = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherDrawerNoLabelMode: bubbleBindItem query failed", t)
        }
        return out
    }

    private fun indexLauncherWideGrid(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val container = findClassByName(bridge, CONTAINER_CLASS)
            if (container != null) {
                container.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf("int", "int", "int", "int", "android.graphics.Point")
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.WIDE_GRID_SET_CELL_DIMS_METHOD, it.name)
                    Log.i(TAG, "LauncherWideGrid: setCellDimensions = ${it.name}")
                }

                val getAppWidgetScale = container.methods.singleOrNull {
                    isNormalInstanceMethod(it) &&
                        it.paramTypeNames == listOf("android.view.View") &&
                        it.returnTypeName == "android.graphics.PointF"
                }
                val nonFinalInt = { f: FieldData ->
                    f.className == CONTAINER_CLASS && !Modifier.isStatic(f.modifiers) &&
                        !Modifier.isFinal(f.modifiers) && f.typeName == "int"
                }
                val sizeInts = getAppWidgetScale?.usingFields?.map { it.field }
                    ?.filter(nonFinalInt)?.map { it.name }?.toSet().orEmpty()
                if (sizeInts.size == 2) {
                    val joined = sizeInts.sorted().joinToString(",")
                    out.addProperty(DexIndexConstants.Keys.WIDE_GRID_CONTAINER_CELL_SIZE_FIELDS, joined)
                    Log.i(TAG, "LauncherWideGrid: cellSizeFields = $joined")
                }

                val viewVoidInts = container.methods.filter {
                    isNormalInstanceMethod(it) &&
                        it.paramTypeNames == listOf("android.view.View") &&
                        it.returnTypeName == "void"
                }.flatMap { it.usingFields.map { uf -> uf.field } }
                    .filter(nonFinalInt).map { it.name }.toSet()
                val countX = (viewVoidInts - sizeInts).singleOrNull()
                countX?.let {
                    out.addProperty(DexIndexConstants.Keys.WIDE_GRID_CONTAINER_COUNT_X_FIELD, it)
                    Log.i(TAG, "LauncherWideGrid: countXField = $it")
                }

                val allNonFinalInts = container.fields.filter(nonFinalInt).map { it.name }.toSet()
                if (sizeInts.size == 2 && countX != null) {
                    (allNonFinalInts - sizeInts - countX).singleOrNull()?.let {
                        out.addProperty(DexIndexConstants.Keys.WIDE_GRID_CONTAINER_COUNT_Y_FIELD, it)
                        Log.i(TAG, "LauncherWideGrid: countYField = $it")
                    }
                }
                container.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == "android.graphics.Point"
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.WIDE_GRID_CONTAINER_BORDER_SPACE_FIELD, it.name)
                    Log.i(TAG, "LauncherWideGrid: borderSpaceField = ${it.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherWideGrid: container query failed", t)
        }

        try {
            val lpClass = findClassByName(bridge, "com.android.launcher3.celllayout.CellLayoutLayoutParams")
                ?: findClassByName(bridge, $$"com.android.launcher3.CellLayout$LayoutParams")
            if (lpClass != null) {
                out.addProperty(DexIndexConstants.Keys.WIDE_GRID_CELL_LP_CLASS, lpClass.name)
                Log.i(TAG, "LauncherWideGrid: cellLpClass = ${lpClass.name}")
                lpClass.fields.singleOrNull { it.name == "isLockedToGrid" && it.typeName == "boolean" }?.let {
                    out.addProperty(DexIndexConstants.Keys.WIDE_GRID_CELL_LP_LOCKED_FIELD, it.name)
                    Log.i(TAG, "LauncherWideGrid: cellLpLockedField = ${it.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherWideGrid: CellLayoutLayoutParams query failed", t)
        }
        return out
    }

    private fun indexIconScaleOverride(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            findClassByName(bridge, "com.android.launcher3.Utilities")?.methods?.singleOrNull {
                it.name == "getCustomedIconScale" && Modifier.isStatic(it.modifiers) &&
                    it.returnTypeName == "float" && it.paramTypeNames == listOf("android.content.Context")
            }?.let {
                out.addProperty(DexIndexConstants.Keys.ICON_SCALE_LEGACY_UTILS_METHOD, it.name)
                Log.i(TAG, "IconScaleOverride: legacyUtilsMethod = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "IconScaleOverride: legacy utils query failed", t)
        }

        try {
            val initGrid = bridge.findMethod {
                matcher {
                    usingStrings("launcher_icon_size_scale")
                    paramTypes("java.lang.String")
                    returnType = "void"
                }
            }.singleOrNull { isNormalMethod(it) }

            if (initGrid != null) {
                val idpClass = initGrid.declaredClass
                out.addProperty(DexIndexConstants.Keys.ICON_SCALE_IDP_CLASS, initGrid.className)
                out.addProperty(DexIndexConstants.Keys.ICON_SCALE_IDP_INIT_GRID_METHOD, initGrid.name)
                Log.i(TAG, "IconScaleOverride: initGrid = ${initGrid.className}.${initGrid.name}")

                val initGridFloats = initGrid.usingFields
                    .map { it.field }
                    .filter { it.className == initGrid.className && !Modifier.isStatic(it.modifiers) && it.typeName == "float" }
                    .map { it.name }
                    .toSet()

                val iconCacheInit = findClassByName(bridge, "com.android.launcher3.icons.IconCache")?.methods
                    ?.firstOrNull { it.name == "<init>" && it.paramCount > 0 }
                val cacheFloats = iconCacheInit?.usingFields
                    ?.map { it.field }
                    ?.filter { it.className == initGrid.className && !Modifier.isStatic(it.modifiers) && it.typeName == "float" }
                    ?.map { it.name }
                    ?.toSet().orEmpty()

                initGridFloats.intersect(cacheFloats).singleOrNull()?.let {
                    out.addProperty(DexIndexConstants.Keys.ICON_SCALE_IDP_CUSTOM_SCALE_FIELD, it)
                    Log.i(TAG, "IconScaleOverride: customScaleField = $it")
                }

                if (idpClass != null) {
                    val dpClass = findClassByName(bridge, DEVICE_PROFILE_CLASS)
                    val dpInit = dpClass?.methods?.singleOrNull {
                        it.name == "<init>" && it.paramCount > 0 && it.paramTypeNames.firstOrNull() == idpClass.name
                    }
                    if (dpInit != null) {
                        val params = dpInit.paramTypeNames.joinToString(",")
                        out.addProperty(DexIndexConstants.Keys.ICON_SCALE_DP_CLASS, dpClass.name)
                        out.addProperty(DexIndexConstants.Keys.ICON_SCALE_DP_INIT_PARAMS, params)
                        Log.i(TAG, "IconScaleOverride: dpInit = ${dpClass.name}($params)")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "IconScaleOverride: initGrid query failed", t)
        }
        return out
    }

    private fun indexLauncherAppIconUnmask(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        var factoryClass: ClassData? = null
        var badged: MethodData? = null
        var bitmapInfo: ClassData? = null

        try {
            val badgedCandidates = bridge.findMethod {
                matcher {
                    paramCount = 2
                    addInvoke {
                        declaredClass = "android.content.pm.PackageManager"
                        name = "getUserBadgedIcon"
                    }
                }
            }.filter { md ->
                isNormalMethod(md) &&
                    md.paramTypeNames.firstOrNull() == "android.graphics.drawable.Drawable" &&
                    md.declaredClass?.interfaces?.any { it.name == "java.lang.AutoCloseable" } == true
            }

            factoryClass = badgedCandidates.mapNotNull { it.declaredClass }
                .distinctBy { it.name }
                .singleOrNull() ?: return out
            val badgedZui = bridge.findMethod {
                matcher {
                    declaredClass = factoryClass.name
                    paramCount = 2
                    addInvoke { name = "getZuiThemeAdaptiveIconDrawable" }
                }
            }.singleOrNull { md ->
                isNormalMethod(md) &&
                    md.paramTypeNames.firstOrNull() == "android.graphics.drawable.Drawable"
            }
            badged = badgedCandidates.singleOrNull { md ->
                md.className == factoryClass.name && md.name != badgedZui?.name
            } ?: return out

            val optionsClassName = badged.paramTypeNames[1]
            bitmapInfo = findClassByName(bridge, badged.returnTypeName) ?: return out

            val badgedParams = badged.paramTypeNames.joinToString(",")
            out.addProperty(DexIndexConstants.Keys.UNMASK_FACTORY_CLASS, factoryClass.name)
            out.addProperty(DexIndexConstants.Keys.UNMASK_OPTIONS_CLASS, optionsClassName)
            out.addProperty(DexIndexConstants.Keys.UNMASK_BADGED_METHOD, badged.name)
            badgedZui?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_BADGED_ZUI_METHOD, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: badgedZui = ${it.name}")
            }
            out.addProperty(DexIndexConstants.Keys.UNMASK_BADGED_PARAMS, badgedParams)
            out.addProperty(DexIndexConstants.Keys.UNMASK_BITMAP_INFO_CLASS, bitmapInfo.name)
            Log.i(TAG, "LauncherAppIconUnmask: factory = ${factoryClass.name}, badged = ${badged.name}($badgedParams), bitmapInfo = ${bitmapInfo.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherAppIconUnmask: BaseIconFactory query failed", t)
            return out
        }

        try {
            val sizeField = badged.usingFields.map { it.field }
                .distinctBy { "${it.className}#${it.name}" }
                .singleOrNull {
                    it.className == factoryClass.name && !Modifier.isStatic(it.modifiers) && it.typeName == "int"
                }
            sizeField?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_SIZE_FIELD, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: sizeField = ${it.name}")
            }
            val sizeMethod = bridge.findMethod {
                matcher {
                    declaredClass = factoryClass.name
                    returnType = "int"
                    paramCount = 0
                }
            }.singleOrNull { md ->
                isNormalMethod(md) &&
                    (sizeField == null || md.usingFields.any { f -> f.field.name == sizeField.name })
            }
            sizeMethod?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_SIZE_METHOD, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: sizeMethod = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherAppIconUnmask: iconBitmapSize query failed", t)
        }

        try {
            val lowResProbe = bridge.findMethod {
                matcher {
                    declaredClass = bitmapInfo.name
                    returnType = "boolean"
                    paramCount = 0
                }
            }.filter { md ->
                isNormalMethod(md) &&
                    md.usingFields.any { it.field.className == bitmapInfo.name && it.field.typeName == "android.graphics.Bitmap" }
            }
            val iconField = lowResProbe.flatMap { it.usingFields }
                .map { it.field }
                .distinctBy { "${it.className}#${it.name}" }
                .singleOrNull {
                    it.className == bitmapInfo.name &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.typeName == "android.graphics.Bitmap"
                }
            iconField?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_BITMAP_ICON_FIELD, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: bitmapIconField = ${it.name}")
            }

            val withFlagsMethod = bridge.findMethod {
                matcher {
                    declaredClass = bitmapInfo.name
                    returnType = bitmapInfo.name
                    paramCount = 1
                }
            }.singleOrNull { md ->
                isNormalInstanceMethod(md) && !md.paramTypeNames[0].startsWith("java.")
            }
            val flagsField = withFlagsMethod?.usingFields?.map { it.field }
                ?.distinctBy { "${it.className}#${it.name}" }
                ?.singleOrNull { it.className == bitmapInfo.name && !Modifier.isStatic(it.modifiers) && it.typeName == "int" }
            flagsField?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_FLAGS_FIELD, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: flagsField = ${it.name}")
            }

            val creationFlagsField = bitmapInfo.fields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
                    it.typeName == "int" && it.name != flagsField?.name
            }
            creationFlagsField?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_CREATION_FLAGS_FIELD, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: creationFlagsField = ${it.name}")
            }

            val colorField = bitmapInfo.fields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                    it.typeName == "int" && it.name != flagsField?.name
            }
            colorField?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_COLOR_FIELD, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: colorField = ${it.name}")
            }

            val extenderClass = bridge.findClass {
                matcher {
                    className("${bitmapInfo.name}$", StringMatchType.StartsWith)
                }
            }.singleOrNull { Modifier.isInterface(it.modifiers) }
            extenderClass?.let {
                out.addProperty(DexIndexConstants.Keys.UNMASK_EXTENDER_CLASS, it.name)
                Log.i(TAG, "LauncherAppIconUnmask: extenderClass = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherAppIconUnmask: BitmapInfo query failed", t)
        }

        try {
            val themedCreator = bridge.findMethod {
                matcher {
                    paramTypes("android.graphics.drawable.AdaptiveIconDrawable", bitmapInfo.name, factoryClass.name)
                }
            }.singleOrNull { isNormalMethod(it) }
                ?: bridge.findMethod {
                    matcher { paramCount = 4 }
                }.singleOrNull { md ->
                    isNormalMethod(md) &&
                        md.paramTypeNames.take(3) == listOf(
                            "android.graphics.drawable.AdaptiveIconDrawable",
                            bitmapInfo.name,
                            factoryClass.name
                        )
                }
            if (themedCreator != null) {
                val controllerClassName = themedCreator.className
                val themedBitmapClassName = themedCreator.returnTypeName
                val createParams = themedCreator.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.UNMASK_THEME_CONTROLLER_CLASS, controllerClassName)
                out.addProperty(DexIndexConstants.Keys.UNMASK_THEMED_BITMAP_CLASS, themedBitmapClassName)
                out.addProperty(DexIndexConstants.Keys.UNMASK_CREATE_THEMED_METHOD, themedCreator.name)
                out.addProperty(DexIndexConstants.Keys.UNMASK_CREATE_THEMED_PARAMS, createParams)
                Log.i(
                    TAG,
                    "LauncherAppIconUnmask: themeController = $controllerClassName.${themedCreator.name}($createParams)"
                )

                factoryClass.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == controllerClassName
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.UNMASK_THEME_CONTROLLER_FIELD, it.name)
                    Log.i(TAG, "LauncherAppIconUnmask: themeControllerField = ${it.name}")
                }

                bridge.findMethod {
                    matcher { declaredClass = factoryClass.name; returnType = controllerClassName; paramCount = 0 }
                }.singleOrNull { isNormalMethod(it) }?.let {
                    out.addProperty(DexIndexConstants.Keys.UNMASK_THEME_CONTROLLER_METHOD, it.name)
                    Log.i(TAG, "LauncherAppIconUnmask: themeControllerMethod = ${it.name}")
                }

                bitmapInfo.fields.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.typeName == themedBitmapClassName
                }?.let {
                    out.addProperty(DexIndexConstants.Keys.UNMASK_THEMED_BITMAP_FIELD, it.name)
                    Log.i(TAG, "LauncherAppIconUnmask: themedBitmapField = ${it.name}")
                }

                bridge.findMethod {
                    matcher { declaredClass = bitmapInfo.name; paramTypes(themedBitmapClassName); returnType = "void" }
                }.singleOrNull { isNormalMethod(it) }?.let {
                    out.addProperty(DexIndexConstants.Keys.UNMASK_THEMED_BITMAP_SETTER, it.name)
                    Log.i(TAG, "LauncherAppIconUnmask: themedBitmapSetter = ${it.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherAppIconUnmask: themeController query failed", t)
        }
        return out
    }

    private fun indexLauncherIconPack(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        val provider = try {
            bridge.findMethod {
                matcher { usingStrings("Unable to load clock drawable info") }
            }.mapNotNull { it.declaredClass }.distinctBy { it.name }.singleOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherIconPack: provider query failed", t)
            null
        } ?: return out

        out.addProperty(DexIndexConstants.Keys.ICON_PACK_PROVIDER_CLASS, provider.name)
        Log.i(TAG, "LauncherIconPack: provider = ${provider.name}")

        try {
            bridge.findMethod {
                matcher { usingStrings("Unable to parse icon map") }
            }.mapNotNull { it.declaredClass }
                .distinctBy { it.name }
                .singleOrNull { it.superClass?.name == provider.name }
                ?.let {
                    out.addProperty(DexIndexConstants.Keys.ICON_PACK_SUBCLASS, it.name)
                    Log.i(TAG, "LauncherIconPack: subclass = ${it.name}")
                }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherIconPack: subclass query failed", t)
        }

        try {
            provider.fields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.typeName == "android.content.Context"
            }?.let {
                out.addProperty(DexIndexConstants.Keys.ICON_PACK_CONTEXT_FIELD, it.name)
                Log.i(TAG, "LauncherIconPack: contextField = ${it.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherIconPack: contextField query failed", t)
        }

        try {
            bridge.findMethod {
                matcher {
                    declaredClass = provider.name
                    returnType = "android.graphics.drawable.Drawable"
                    paramCount = 4
                }
            }.singleOrNull { md ->
                isNormalMethod(md) &&
                    md.paramTypeNames.sorted() == listOf(
                        "android.content.pm.ApplicationInfo",
                        "android.content.pm.PackageItemInfo",
                        "boolean",
                        "int"
                    )
            }?.let { md ->
                val params = md.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.ICON_PACK_UNIFIED_METHOD, md.name)
                out.addProperty(DexIndexConstants.Keys.ICON_PACK_UNIFIED_PARAMS, params)
                Log.i(TAG, "LauncherIconPack: unified = ${md.name}($params)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherIconPack: unified getIcon query failed", t)
        }

        try {
            bridge.findMethod {
                matcher {
                    declaredClass = provider.name
                    returnType = "android.graphics.drawable.Drawable"
                    paramCount = 4
                }
            }.singleOrNull { md ->
                isNormalMethod(md) &&
                    md.paramTypeNames.sorted() == listOf(
                        "boolean",
                        "int",
                        "java.lang.String",
                        "java.util.function.Supplier"
                    )
            }?.let { md ->
                val params = md.paramTypeNames.joinToString(",")
                out.addProperty(DexIndexConstants.Keys.ICON_PACK_OVERRIDES_METHOD, md.name)
                out.addProperty(DexIndexConstants.Keys.ICON_PACK_OVERRIDES_PARAMS, params)
                Log.i(TAG, "LauncherIconPack: overrides = ${md.name}($params)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherIconPack: overrides getIcon query failed", t)
        }

        try {
            // Modifier.isFinal distinguishes getStateForApp from overridable getApplicationInfoHash(ApplicationInfo).
            val stateMethod = bridge.findMethod {
                matcher {
                    declaredClass = provider.name
                    paramTypes("android.content.pm.ApplicationInfo")
                }
            }.singleOrNull {
                isNormalMethod(it) && Modifier.isFinal(it.modifiers)
            }
            if (stateMethod != null) {
                out.addProperty(DexIndexConstants.Keys.ICON_PACK_STATE_METHOD, stateMethod.name)
                Log.i(TAG, "LauncherIconPack: stateMethod = ${stateMethod.name}")
                bridge.findMethod {
                    matcher {
                        declaredClass = stateMethod.returnTypeName
                        paramTypes("java.lang.String[]")
                        returnType = stateMethod.returnTypeName
                    }
                }.singleOrNull { isNormalMethod(it) }?.let {
                    out.addProperty(DexIndexConstants.Keys.ICON_PACK_APPEND_METHOD, it.name)
                    Log.i(TAG, "LauncherIconPack: appendMethod = ${it.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherIconPack: stateMethod query failed", t)
        }
        return out
    }

    private fun findClassByName(bridge: DexKitBridge, name: String): ClassData? =
        bridge.findClass { matcher { className(name) } }.singleOrNull()

    private fun isNormalMethod(md: MethodData): Boolean =
        md.name != "<init>" && md.name != "<clinit>"

    private fun isNormalInstanceMethod(md: MethodData): Boolean =
        isNormalMethod(md) && !Modifier.isStatic(md.modifiers)

    private fun fieldsOfTypeOn(md: MethodData?, owner: String, type: String): Set<String> =
        md?.usingFields
            ?.map { it.field }
            ?.filter { it.className == owner && !Modifier.isStatic(it.modifiers) && it.typeName == type }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

    private fun findFieldInHierarchy(
        start: ClassData,
        stopAtSuper: String,
        predicate: (FieldData) -> Boolean
    ): FieldData? {
        var current: ClassData? = start
        while (current != null && current.name != "java.lang.Object") {
            val found = current.fields.singleOrNull(predicate)
            if (found != null) return found
            if (current.name == stopAtSuper) break
            current = current.superClass
        }
        return null
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
        const val LAUNCHER_SWIPE_HANDLER_V2 = "com.android.quickstep.LauncherSwipeHandlerV2"
        const val LONG_SWIPE_VIEW_CLASS = "com.zui.launcher.gesture.swipeview.LongSwipeView"
        const val STATIC_DENY_RES_NAME = "config_ovcDcv_StaticDenySet"
        const val SWIPE_FLOATING_LOG_ANCHOR = "supportSwipeFloatingWindow:"
        const val TASK_INFO = "android.app.TaskInfo"
        const val HASH_SET = "java.util.HashSet"

        const val LAUNCHER_CLASS = "com.android.launcher3.Launcher"
        const val ACTIVITY_CONTEXT_CLASS = "com.android.launcher3.views.ActivityContext"
        const val WORKSPACE_CLASS = "com.android.launcher3.Workspace"
        const val CONTAINER_CLASS = "com.android.launcher3.ShortcutAndWidgetContainer"
        const val DEVICE_PROFILE_CLASS = "com.android.launcher3.DeviceProfile"
        const val FOLDER_ICON_CLASS = "com.android.launcher3.folder.FolderIcon"
        const val FOLDER_CLASS = "com.android.launcher3.folder.Folder"
        const val FOLDER_INFO_CLASS = "com.android.launcher3.model.data.FolderInfo"
        const val FOLDER_ANIM_MGR_CLASS = "com.android.launcher3.folder.FolderAnimationManager"
        const val PREVIEW_BG_CLASS = "com.android.launcher3.folder.PreviewBackground"
        const val LAYOUT_RULE_CLASS = "com.android.launcher3.folder.ClippedFolderIconLayoutRule"
        const val PREVIEW_PARAMS_CLASS = "com.android.launcher3.folder.PreviewItemDrawingParams"
        const val PREVIEW_ITEM_MGR_CLASS = "com.android.launcher3.folder.PreviewItemManager"
        const val DRAG_CONTROLLER_CLASS = "com.android.launcher3.dragndrop.DragController"
        const val DRAG_OBJECT_CLASS = $$"com.android.launcher3.DropTarget$DragObject"
        const val BUBBLE_TEXT_VIEW_CLASS = "com.android.launcher3.BubbleTextView"
        const val ACTIVE_ICON_VIEW_CLASS = "com.zui.launcher.ActiveIconView"
        const val ITEM_INFO_CLASS = "com.android.launcher3.model.data.ItemInfo"
        const val ITEM_INFO_WITH_ICON_CLASS = "com.android.launcher3.model.data.ItemInfoWithIcon"

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
        val CREATE_HOTSEAT_PROFILE_PARAMS_SORTED = listOf(
            "boolean",
            "boolean",
            "com.android.launcher3.InvariantDeviceProfile",
            $$"com.android.launcher3.InvariantDeviceProfile$DisplayOptionSpec",
            "com.android.launcher3.deviceprofile.DeviceProperties",
            "com.android.launcher3.deviceprofile.HotseatProfileInitialValues",
            "com.android.launcher3.deviceprofile.WorkspaceProfile",
            "int"
        ).sorted()
        val RECALC_HOTSEAT_PARAMS_SORTED = listOf(
            "boolean",
            "boolean",
            "com.android.launcher3.InvariantDeviceProfile",
            "com.android.launcher3.deviceprofile.DeviceProperties",
            "com.android.launcher3.deviceprofile.HotseatProfileInitialValues",
            "com.android.launcher3.deviceprofile.WorkspaceProfile",
            "int",
            "int"
        ).sorted()
    }
}
