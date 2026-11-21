package com.qimian233.ztool.hook.modules.launcher.dockbar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.View
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * Raises ZUI Launcher's hotseat maximum item limit to 20 across UI, layout profiles, and loader.
 */
@SuppressLint("PrivateApi")
class ZuiLauncherHotseatHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.ZUI_LAUNCHER_HOTSEAT.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    private val warnedTargets: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        val disableDockBar = try {
            remotePreferences.getBoolean(PreferenceKeys.DISABLE_DOCK_BAR.name, false)
        } catch (_: Throwable) {
            false
        }
        if (disableDockBar) {
            logger.warn("Disable dock bar hook enabled, will not expand dock bar.")
            return
        }

        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.ZUI_LAUNCHER_HOTSEAT)

        val loaderCursorBMethod = readString(module, DexIndexConstants.Keys.LOADER_CURSOR_B_METHOD)
        val hotseatCalcClass = readString(module, DexIndexConstants.Keys.HOTSEAT_CALC_CLASS)
        val createProfileMethod = readString(module, DexIndexConstants.Keys.HOTSEAT_CREATE_PROFILE_METHOD)
        val createProfileParams = readString(module, DexIndexConstants.Keys.HOTSEAT_CREATE_PROFILE_PARAMS)
        val recalcMethod = readString(module, DexIndexConstants.Keys.HOTSEAT_RECALC_METHOD)
        val recalcParams = readString(module, DexIndexConstants.Keys.HOTSEAT_RECALC_PARAMS)

        logger.info("Hooking ZUI Launcher hotseat limit")

        try {
            val hasCalcHelper = hookHotseatCalcHelper(
                classLoader,
                hotseatCalcClass,
                createProfileMethod,
                createProfileParams,
                recalcMethod,
                recalcParams
            )
            hookHotseatMaxCount(classLoader, hasCalcHelper)
            hookSpaceChecks(classLoader)
            hookDeviceProfile(classLoader, hasCalcHelper)
            hookAddItemMethods(classLoader)
            hookDatabaseHotseatLimit(classLoader)
            hookLoaderCursorMethods(classLoader, loaderCursorBMethod)
            hookDatabaseOperations(classLoader)
            hookCellLayoutMethods(classLoader)
            logger.info("ZUI Launcher hotseat hooks completed")
        } catch (t: Throwable) {
            logger.error("Error while hooking ZUI Launcher", t)
        }
    }

    /** Hooks the R8-relocated hotseat profile calculation helper (`og.l` on 18.6+). */
    private fun hookHotseatCalcHelper(
        classLoader: ClassLoader,
        calcClassName: String?,
        createMethodName: String?,
        createParamsRaw: String?,
        recalcMethodName: String?,
        recalcParamsRaw: String?
    ): Boolean {
        if (calcClassName == null) return false
        val calcClass = loadClassOrNull(classLoader, calcClassName) ?: return false
        val invClass = loadClassOrNull(classLoader, INVARIANT_DP_CLASS)
        val invDbField = invClass?.let { tryFieldOrNull(it, "numDatabaseHotseatIcons") }
        val invShownField = invClass?.let { tryFieldOrNull(it, "numShownHotseatIcons") }
        var installed = false

        if (createMethodName != null && createParamsRaw != null) {
            val params = parseParamTypes(classLoader, createParamsRaw)
            val createMethod = params?.let { tryGetDeclared(calcClass, createMethodName, *it) }
            if (createMethod != null) {
                val invIdx = params.indexOfFirst { it.name == INVARIANT_DP_CLASS }
                val profileShownField = tryFieldOrNull(createMethod.returnType, "numShownIcons")
                hookWithId(createMethod, "hotseat_create_profile") { chain ->
                    if (invIdx >= 0) {
                        raiseInvHotseatFields(chain.args.getOrNull(invIdx), invDbField, invShownField)
                    }
                    val profile = chain.proceed()
                    if (profile != null && profileShownField != null) {
                        runCatching {
                            if (profileShownField.getInt(profile) != HOTSEAT_ITEM_LIMIT) {
                                profileShownField.setInt(profile, HOTSEAT_ITEM_LIMIT)
                            }
                        }
                    }
                    profile
                }
                installed = true
                logger.info("Hooked $calcClassName#$createMethodName")
            }
        }

        if (recalcMethodName != null && recalcParamsRaw != null) {
            val params = parseParamTypes(classLoader, recalcParamsRaw)
            val recalcMethod = params?.let { tryGetDeclared(calcClass, recalcMethodName, *it) }
            if (recalcMethod != null) {
                val invIdx = params.indexOfFirst { it.name == INVARIANT_DP_CLASS }
                val shownCountArgIdx = params.indices
                    .filter { params[it] == Int::class.javaPrimitiveType }
                    .lastOrNull()
                val withBorderShownField = tryFieldOrNull(recalcMethod.returnType, "numShownIcons")
                hookWithId(recalcMethod, "hotseat_recalc") { chain ->
                    if (invIdx >= 0) {
                        raiseInvHotseatFields(chain.args.getOrNull(invIdx), invDbField, invShownField)
                    }
                    val args = chain.args.toTypedArray()
                    if (shownCountArgIdx != null) {
                        args[shownCountArgIdx] = HOTSEAT_ITEM_LIMIT
                    }
                    val result = chain.proceed(args)
                    if (result != null && withBorderShownField != null) {
                        runCatching {
                            if (withBorderShownField.getInt(result) != HOTSEAT_ITEM_LIMIT) {
                                withBorderShownField.setInt(result, HOTSEAT_ITEM_LIMIT)
                            }
                        }
                    }
                    result
                }
                installed = true
                logger.info("Hooked $calcClassName#$recalcMethodName")
            }
        }
        return installed
    }

    private fun hookHotseatMaxCount(classLoader: ClassLoader, hasCalcHelper: Boolean) {
        val profileClass = loadClassOrNull(classLoader, "com.android.launcher3.deviceprofile.HotseatProfile")
        val getNumShownIcons = profileClass?.let { tryGetDeclared(it, "getNumShownIcons") }
        if (getNumShownIcons != null) {
            hookWithId(getNumShownIcons, "hotseat_num_shown_icons") { chain ->
                chain.proceed()
                logger.debug("Changed hotseat visible icon count to $HOTSEAT_ITEM_LIMIT")
                HOTSEAT_ITEM_LIMIT
            }
            return
        }

        val hotseatClass = loadClassOrNull(classLoader, "com.android.launcher3.Hotseat")
        val getMaxCount = hotseatClass?.let { tryGetDeclared(it, "getMaxCount") }
        if (getMaxCount != null) {
            hookWithId(getMaxCount, "get_max_count") { chain ->
                chain.proceed()
                logger.debug("Changed hotseat max count to $HOTSEAT_ITEM_LIMIT")
                HOTSEAT_ITEM_LIMIT
            }
            return
        }

        if (!hasCalcHelper) {
            warnMissing("Hotseat.getMaxCount / HotseatProfile.getNumShownIcons", "hotseat count limit")
        }
    }

    private fun hookSpaceChecks(classLoader: ClassLoader) {
        try {
            val launcherClass = classLoader.loadClass("com.android.launcher3.Launcher")

            var toastHooked = false
            tryGetDeclared(launcherClass, "showOutOfSpaceToast", Int::class.javaPrimitiveType)?.let { m ->
                hookWithId(m, "show_out_of_space_toast_int") { null }
                toastHooked = true
            }
            tryGetDeclared(launcherClass, "showOutOfSpaceToast", String::class.java)?.let { m ->
                hookWithId(m, "show_out_of_space_toast_str") { null }
                toastHooked = true
            }
            tryGetDeclared(launcherClass, "showOutOfSpaceMessage", Boolean::class.javaPrimitiveType)?.let { m ->
                hookWithId(m, "show_out_of_space") { null }
                toastHooked = true
            }
            if (!toastHooked) {
                warnMissing("Launcher.showOutOfSpaceToast / showOutOfSpaceMessage", "out-of-space suppression")
            }

            val workspaceItemInfoClass =
                classLoader.loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
            val workspaceClass = classLoader.loadClass("com.android.launcher3.Workspace")
            val checkOccupiedMethod =
                tryGetDeclared(
                    launcherClass,
                    "checkOccupiedShortcut",
                    View::class.java,
                    workspaceItemInfoClass,
                    workspaceClass
                ) ?: tryGetDeclared(
                    launcherClass,
                    "checkOccupiedShortcut",
                    View::class.java,
                    workspaceItemInfoClass,
                    workspaceClass,
                    Boolean::class.javaPrimitiveType
                )
            if (checkOccupiedMethod != null) {
                hookWithId(checkOccupiedMethod, "check_occupied") { chain ->
                    chain.proceed()
                    logger.debug("Forced space check pass")
                    true
                }
            } else {
                warnMissing("Launcher.checkOccupiedShortcut", "hotseat shortcut check")
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook space checks", t)
        }
    }

    private fun hookDeviceProfile(classLoader: ClassLoader, hasCalcHelper: Boolean) {
        val deviceProfileClass = loadClassOrNull(classLoader, "com.android.launcher3.DeviceProfile")
        if (deviceProfileClass == null) {
            logger.error("Failed to hook DeviceProfile: class missing")
            return
        }

        val getHotseatColumnSpanMethod = tryGetDeclared(deviceProfileClass, "getHotseatColumnSpan")
        if (getHotseatColumnSpanMethod != null) {
            hookWithId(getHotseatColumnSpanMethod, "get_hotseat_column_span") { chain ->
                chain.proceed()
                HOTSEAT_ITEM_LIMIT
            }
        } else if (!hasCalcHelper) {
            warnMissing("DeviceProfile.getHotseatColumnSpan", "hotseat column span")
        }

        val recalculateWithCount = tryGetDeclared(
            deviceProfileClass,
            "recalculateHotseatWidthAndBorderSpace",
            Int::class.javaPrimitiveType
        )
        if (recalculateWithCount != null) {
            hookWithId(recalculateWithCount, "recalculate") { chain ->
                logger.debug("Forced hotseat recalculation for $HOTSEAT_ITEM_LIMIT icons")
                chain.proceed(arrayOf<Any>(HOTSEAT_ITEM_LIMIT))
            }
            return
        }

        val recalculateMethod = tryGetDeclared(
            deviceProfileClass,
            "recalculateHotseatWidthAndBorderSpace"
        )
        if (recalculateMethod == null) {
            if (!hasCalcHelper) {
                warnMissing("DeviceProfile.recalculateHotseatWidthAndBorderSpace", "hotseat recalculation")
            }
            return
        }
        val numShownField = tryFieldOrNull(deviceProfileClass, "numShownHotseatIcons")
        hookWithId(recalculateMethod, "recalculate") { chain ->
            chain.proceed()
            val deviceProfile = chain.thisObject
            if (numShownField != null && deviceProfile != null) {
                try {
                    numShownField.set(deviceProfile, HOTSEAT_ITEM_LIMIT)
                } catch (t: Throwable) {
                    logger.warn("Failed to raise numShownHotseatIcons: ${t.javaClass.simpleName}")
                }
            }
            logger.debug("Modified DeviceProfile hotseat configuration")
            null
        }
    }

    private fun hookAddItemMethods(classLoader: ClassLoader) {
        try {
            val launcherClass = classLoader.loadClass("com.android.launcher3.Launcher")
            val pendingRequestArgsClass =
                classLoader.loadClass("com.android.launcher3.util.PendingRequestArgs")
            val pendingAddItemInfoClass =
                classLoader.loadClass("com.android.launcher3.PendingAddItemInfo")

            val completeAddMethod = launcherClass.getDeclaredMethod(
                "completeAddShortcut",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                pendingRequestArgsClass
            )
            hookWithId(completeAddMethod, "complete_add") { chain ->
                logger.debug("Preparing to add shortcut to hotseat")
                chain.proceed()
            }

            val addPendingItemMethod = launcherClass.getDeclaredMethod(
                "addPendingItem",
                pendingAddItemInfoClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            hookWithId(addPendingItemMethod, "add_pending_item") { chain ->
                val container = chain.args[1] as Int
                if (container == HOTSEAT_CONTAINER) {
                    logger.debug("Adding item to hotseat, bypassing restriction")
                }
                chain.proceed()
            }

            val itemInfoClass = loadClassOrNull(classLoader, ITEM_INFO_CLASS)
            val containerField = itemInfoClass?.let { tryFieldOrNull(it, "container") }
            val addToWorkspaceMethod = itemInfoClass?.let {
                tryGetDeclared(
                    launcherClass,
                    "addToWorkspace",
                    it,
                    Boolean::class.javaPrimitiveType
                )
            }
            if (addToWorkspaceMethod != null) {
                hookWithId(addToWorkspaceMethod, "add_to_workspace") { chain ->
                    val container = readIntField(chain.args.getOrNull(0), containerField)
                    if (container == HOTSEAT_CONTAINER) {
                        logger.debug("Added item to hotseat workspace")
                    }
                    chain.proceed()
                }
            } else {
                logger.debug("Launcher.addToWorkspace absent, skipped")
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook add item methods", t)
        }
    }

    private fun hookDatabaseHotseatLimit(classLoader: ClassLoader) {
        val invProfileClass = loadClassOrNull(classLoader, INVARIANT_DP_CLASS)
        if (invProfileClass == null) {
            logger.error("Failed to hook database hotseat limit: InvariantDeviceProfile missing")
            return
        }

        val getNumMethod = tryGetDeclared(invProfileClass, "getNumDatabaseHotseatIcons")
        if (getNumMethod != null) {
            hookWithId(getNumMethod, "get_num") { chain ->
                chain.proceed()
                logger.debug("Changed database hotseat count to $HOTSEAT_ITEM_LIMIT")
                HOTSEAT_ITEM_LIMIT
            }
        }

        val numField = tryFieldOrNull(invProfileClass, "numDatabaseHotseatIcons")
        val numShownField = tryFieldOrNull(invProfileClass, "numShownHotseatIcons")
        if (numField == null) {
            warnMissing("InvariantDeviceProfile.numDatabaseHotseatIcons", "database hotseat limit")
            return
        }
        if (Modifier.isStatic(numField.modifiers)) {
            try {
                numField.setInt(null, HOTSEAT_ITEM_LIMIT)
                logger.debug("Directly set numDatabaseHotseatIcons to $HOTSEAT_ITEM_LIMIT")
            } catch (t: Throwable) {
                logger.error("Failed to directly set numDatabaseHotseatIcons", t)
            }
            return
        }

        tryGetDeclared(invProfileClass, "initGrid", String::class.java)?.let { initGrid ->
            hookWithId(initGrid, "hotseat_init_grid") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { raiseInvHotseatFields(it, numField, numShownField) }
                result
            }
        }

        val getDeviceProfile = tryGetDeclared(invProfileClass, "getDeviceProfile", Context::class.java)
        if (getDeviceProfile == null) {
            warnMissing("InvariantDeviceProfile.getDeviceProfile", "database hotseat limit")
            return
        }
        hookWithId(getDeviceProfile, "hotseat_database_count") { chain ->
            chain.thisObject?.let { raiseInvHotseatFields(it, numField, numShownField) }
            chain.proceed()
        }
    }

    private fun hookLoaderCursorMethods(classLoader: ClassLoader, indexedBMethodName: String?) {
        val loaderCursorClass = loadClassOrNull(classLoader, "com.android.launcher3.model.LoaderCursor")
        val itemInfoClass = loadClassOrNull(classLoader, ITEM_INFO_CLASS)
        if (loaderCursorClass == null || itemInfoClass == null) {
            logger.error("Failed to hook LoaderCursor: class missing")
            return
        }
        val containerField = tryFieldOrNull(itemInfoClass, "container")
        val screenIdField = tryFieldOrNull(itemInfoClass, "screenId")

        val placementMethod = tryGetDeclared(loaderCursorClass, "checkItemPlacement", itemInfoClass)
            ?: tryGetDeclared(
                loaderCursorClass,
                "checkItemPlacement",
                itemInfoClass,
                Boolean::class.javaPrimitiveType
            )
        if (placementMethod != null) {
            hookWithId(placementMethod, "check_item_placement") { chain ->
                val itemInfo = chain.args.getOrNull(0)
                val container = readIntField(itemInfo, containerField)
                val screenId = readIntField(itemInfo, screenIdField)
                if (container == HOTSEAT_CONTAINER && screenId != null &&
                    screenId in 0 until HOTSEAT_ITEM_LIMIT
                ) {
                    logger.debug("Forced hotseat placement check pass: $screenId")
                    true
                } else {
                    chain.proceed()
                }
            }
        }

        val dimensionCheck = resolveDimensionCheck(
            loaderCursorClass,
            itemInfoClass,
            placementMethod,
            indexedBMethodName
        )
        if (dimensionCheck != null) {
            hookWithId(dimensionCheck, "hook_289") { chain ->
                val result = chain.proceed()
                val container = readIntField(chain.args.getOrNull(0), containerField)
                if (container == HOTSEAT_CONTAINER) {
                    logger.debug("Bypassed hotseat dimension check")
                    false
                } else {
                    result
                }
            }
        }

        val checkAndAddItemMethod = resolveCheckAndAddItem(classLoader, loaderCursorClass, itemInfoClass)
        if (checkAndAddItemMethod == null) {
            warnMissing("LoaderCursor.checkAndAddItem", "hotseat add log")
        } else {
            hookWithId(checkAndAddItemMethod, "check_and_add_item") { chain ->
                val itemInfo = chain.args.getOrNull(0)
                val container = readIntField(itemInfo, containerField)
                val screenId = readIntField(itemInfo, screenIdField)
                if (container == HOTSEAT_CONTAINER) {
                    logger.debug("checkAndAddItem - hotseat position: $screenId")
                }
                chain.proceed()
            }
        }
    }

    private fun hookDatabaseOperations(classLoader: ClassLoader) {
        val itemInfoClass = loadClassOrNull(classLoader, ITEM_INFO_CLASS)
        if (itemInfoClass == null) {
            logger.error("Failed to hook database operations: ItemInfo missing")
            return
        }

        val launcherModelClass = loadClassOrNull(classLoader, "com.android.launcher3.LauncherModel")
        val legacyMethod = launcherModelClass?.let {
            tryGetDeclared(
                it,
                "addOrMoveItemInDatabase",
                itemInfoClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }
        val writerClass = loadClassOrNull(classLoader, "com.android.launcher3.model.ModelWriter")
        val writerMethod = writerClass?.let {
            tryGetDeclared(
                it,
                "addOrMoveItemInDatabase",
                itemInfoClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }
        val addOrMoveMethod = legacyMethod ?: writerMethod
        if (addOrMoveMethod == null) {
            warnMissing("ModelWriter.addOrMoveItemInDatabase", "hotseat database write log")
            return
        }

        hookWithId(addOrMoveMethod, "add_or_move") { chain ->
            val container = chain.args.getOrNull(1) as? Int
            val screen = chain.args.getOrNull(2) as? Int
            if (container == HOTSEAT_CONTAINER && screen != null && screen >= 5) {
                logger.debug("Database operation - hotseat position: $screen")
            }
            chain.proceed()
        }
    }

    private fun hookCellLayoutMethods(classLoader: ClassLoader) {
        try {
            val cellLayoutClass = classLoader.loadClass("com.android.launcher3.CellLayout")

            val newSigMethod = tryGetDeclared(
                cellLayoutClass,
                "findCellForSpan",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                IntArray::class.java
            )
            val findCellMethod: Method
            val cellXYIdx: Int
            if (newSigMethod != null) {
                findCellMethod = newSigMethod
                cellXYIdx = 2
            } else {
                findCellMethod = cellLayoutClass.getDeclaredMethod(
                    "findCellForSpan",
                    IntArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                cellXYIdx = 0
            }

            hookWithId(findCellMethod, "find_cell") { chain ->
                val result = chain.proceed() as Boolean
                if (!result) {
                    val cellXY = chain.args[cellXYIdx] as IntArray
                    cellXY[0] = 0
                    cellXY[1] = 0
                    logger.debug("Forced cell position found")
                    return@hookWithId true
                }
                true
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook CellLayout", t)
        }
    }

    private fun resolveDimensionCheck(
        loaderCursorClass: Class<*>,
        itemInfoClass: Class<*>,
        placementMethod: Method?,
        indexedBMethodName: String?
    ): Method? {
        if (indexedBMethodName != null) {
            val indexed = tryGetDeclared(loaderCursorClass, indexedBMethodName, itemInfoClass)
            if (indexed != null && indexed != placementMethod) {
                return indexed
            }
        }
        val candidates = loaderCursorClass.declaredMethods.filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == itemInfoClass &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it != placementMethod
        }
        if (candidates.size != 1) {
            logger.debug("Dimension check candidates: ${candidates.size}")
            return null
        }
        candidates[0].isAccessible = true
        return candidates[0]
    }

    private fun resolveCheckAndAddItem(
        classLoader: ClassLoader,
        loaderCursorClass: Class<*>,
        itemInfoClass: Class<*>
    ): Method? {
        val loadedItems = loadClassOrNull(classLoader, "com.android.launcher3.util.IntSparseArrayMap")
        val memoryLogger = loadClassOrNull(classLoader, "com.android.launcher3.model.LoaderMemoryLogger")
        val dataModel = loadClassOrNull(classLoader, "com.android.launcher3.model.BgDataModel")
        if (loadedItems != null && memoryLogger != null && dataModel != null) {
            tryGetDeclared(
                loaderCursorClass,
                "checkAndAddItem",
                itemInfoClass,
                loadedItems,
                memoryLogger,
                dataModel
            )?.let { return it }
        }
        return dataModel?.let {
            tryGetDeclared(loaderCursorClass, "checkAndAddItem", itemInfoClass, it)
        }
    }

    private fun raiseInvHotseatFields(
        profile: Any?,
        numDbField: Field?,
        numShownField: Field?
    ) {
        if (profile == null) return
        try {
            if (numDbField != null && numDbField.getInt(profile) != HOTSEAT_ITEM_LIMIT) {
                numDbField.setInt(profile, HOTSEAT_ITEM_LIMIT)
                logger.debug("Raised database hotseat count to $HOTSEAT_ITEM_LIMIT")
            }
            if (numShownField != null && numShownField.getInt(profile) != HOTSEAT_ITEM_LIMIT) {
                numShownField.setInt(profile, HOTSEAT_ITEM_LIMIT)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to write InvariantDeviceProfile hotseat fields: ${t.javaClass.simpleName}")
        }
    }

    private fun readIntField(target: Any?, field: Field?): Int? {
        if (target == null || field == null) return null
        return try {
            field.getInt(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun parseParamTypes(classLoader: ClassLoader, raw: String): Array<Class<*>>? =
        runCatching {
            raw.split(",").map { resolveType(classLoader, it.trim()) }.toTypedArray()
        }.getOrNull()

    private fun resolveType(classLoader: ClassLoader, name: String): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> if (name.endsWith("[]")) {
            val element = resolveType(classLoader, name.removeSuffix("[]"))
            java.lang.reflect.Array.newInstance(element, 0).javaClass
        } else {
            classLoader.loadClass(name)
        }
    }

    private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (_: Throwable) {
        null
    }

    private fun tryGetDeclared(owner: Class<*>, name: String, vararg params: Class<*>?): Method? = try {
        owner.getDeclaredMethod(name, *params).also { it.isAccessible = true }
    } catch (_: Throwable) {
        null
    }

    private fun tryFieldOrNull(owner: Class<*>, name: String): Field? = try {
        findField(owner, name)
    } catch (_: Throwable) {
        null
    }

    private fun warnMissing(target: String, feature: String) {
        if (warnedTargets.add(target)) {
            logger.warn("$feature: $target is absent in this launcher build, skipped")
        }
    }

    private companion object {
        const val HOTSEAT_ITEM_LIMIT = 20
        const val HOTSEAT_CONTAINER = -101
        const val INVARIANT_DP_CLASS = "com.android.launcher3.InvariantDeviceProfile"
        const val ITEM_INFO_CLASS = "com.android.launcher3.model.data.ItemInfo"
    }
}
