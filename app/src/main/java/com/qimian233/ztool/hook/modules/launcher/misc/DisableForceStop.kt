package com.qimian233.ztool.hook.modules.launcher.misc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList

/**
 * Prevents ZUI Launcher from killing protected background apps when dismissing recents tasks.
 *
 * Adapts across legacy OverviewUtilities, Android 17+ R8-relocated kill helpers, and
 * Quickstep RecentsDismissUtils Clear-All paths while preserving whitelist semantics.
 */
@SuppressLint("PrivateApi")
class DisableForceStop : AppHookModule() {

    private var whiteList: Array<String> = arrayOf()

    override fun getModuleName(): String = PreferenceKeys.DISABLE_FORCE_STOP.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val packageName = param.packageName
        val sdkVersion = getSDKVersion()
        whiteList = getWhiteListPackages()
        logger.trace("Current Android SDK: $sdkVersion, target package name: $packageName")
        logger.trace("White list enabled, app in whitelist: ${whiteList.size}")

        val module = if (sdkVersion >= 36 && ScopeKeys.LAUNCHER.packageName == packageName) {
            DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
                ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
                ?.getAsJsonObject(DexIndexConstants.ModuleKeys.DISABLE_FORCE_STOP)
        } else {
            null
        }

        if (sdkVersion >= 36) {
            hookForAndroid16Plus(classLoader, packageName, module)
        } else {
            hookForAndroid15Minus(classLoader, packageName)
        }
    }

    private fun hookForAndroid16Plus(
        classLoader: ClassLoader,
        packageName: String,
        module: JsonObject?
    ) {
        if (ScopeKeys.LAUNCHER.packageName == packageName) {
            hookZuiLauncherAndroid16(classLoader, module)
        } else if ("com.android.launcher3" == packageName) {
            hookBaseLauncherAndroid16()
        }
    }

    private fun isWhiteListEnabled(): Boolean {
        return try {
            remotePreferences.getBoolean(PreferenceKeys.FORCE_STOP_WHITE_LIST_ENABLE.name, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun getWhiteListPackages(): Array<String> {
        val value = try {
            remotePreferences.getString(PreferenceKeys.FORCE_STOP_WHITE_LIST.name, "")
        } catch (_: Throwable) {
            ""
        }
        if (value.isNullOrEmpty()) return arrayOf()
        return value.split(",").toTypedArray()
    }

    private fun isProtectedPackage(packageName: String): Boolean {
        if (!isWhiteListEnabled()) return true
        for (pkg in whiteList) {
            if (pkg == packageName) return true
        }
        return false
    }

    private fun hookForAndroid15Minus(classLoader: ClassLoader, packageName: String) {
        try {
            if (ScopeKeys.LAUNCHER.packageName == packageName || "com.android.launcher3" == packageName) {
                hookLegacyLauncher(classLoader)
            }
            logger.info("Android 15- Hook applied, whitelist protection enabled")
        } catch (t: Throwable) {
            logger.error("Android 15- Hook failed!", t)
        }
    }

    private fun hookZuiLauncherAndroid16(classLoader: ClassLoader, module: JsonObject?) {
        hookForceStopHelper(classLoader, module)
        hookDismissTasks(classLoader, module)
        hookLegacyOverviewUtilities(classLoader)
    }

    private fun hookForceStopHelper(classLoader: ClassLoader, module: JsonObject?) {
        try {
            val helperClassName = readString(module, DexIndexConstants.Keys.FORCE_STOP_CLASS)
                ?: LEGACY_OVERVIEW_UTILITIES_CLASS
            val helperMethodName = readString(module, DexIndexConstants.Keys.FORCE_STOP_METHOD)
                ?: "c"
            val helperParams = parseParamTypes(
                classLoader,
                readString(module, DexIndexConstants.Keys.FORCE_STOP_PARAMS)
            ) ?: arrayOf(
                Context::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            val helperClass = classLoader.loadClass(helperClassName)
            val helperMethod = helperClass.getDeclaredMethod(helperMethodName, *helperParams)
            val paramTypes = helperMethod.parameterTypes
            val pkgIndex = paramTypes.indexOfFirst { it == String::class.java }
            val uidIndex = paramTypes.indexOfFirst { it == Int::class.javaPrimitiveType }

            hookWithId(helperMethod, "hook_165") { chain ->
                val pkgName = if (pkgIndex >= 0) chain.args.getOrNull(pkgIndex) as? String else null
                val uid = if (uidIndex >= 0) chain.args.getOrNull(uidIndex) as? Int ?: -1 else -1
                if (pkgName != null && isProtectedPackage(pkgName)) {
                    logger.trace("Android 16+: Blocked forced killing app in whitelist: $pkgName (UID: $uid)")
                    return@hookWithId null
                }
                logger.trace("Android 16+: Allow forced killing app: $pkgName")
                chain.proceed()
            }
            logger.info("DisableForceStop: hooked $helperClassName#$helperMethodName")
        } catch (t: Throwable) {
            logger.warn("DisableForceStop: force-stop helper hook failed: ${t.message}")
        }
    }

    private fun hookDismissTasks(classLoader: ClassLoader, module: JsonObject?) {
        try {
            val dismissClassName = readString(module, DexIndexConstants.Keys.FORCE_STOP_DISMISS_CLASS)
                ?: DEFAULT_DISMISS_UTILS_CLASS
            val dismissMethodName = readString(module, DexIndexConstants.Keys.FORCE_STOP_DISMISS_METHOD)
                ?: DEFAULT_DISMISS_METHOD
            val taskClass = classLoader.loadClass(TASK_CLASS)
            val dismissParams = parseParamTypes(
                classLoader,
                readString(module, DexIndexConstants.Keys.FORCE_STOP_DISMISS_PARAMS)
            ) ?: arrayOf(taskClass)

            val dismissClass = classLoader.loadClass(dismissClassName)
            val dismissMethod = dismissClass.getDeclaredMethod(dismissMethodName, *dismissParams)

            val taskKeyField = findField(taskClass, "key")
            val taskKeyClass = taskKeyField.type
            val getPackageNameMethod = taskKeyClass.getMethod("getPackageName")
            val taskIdField = findField(taskKeyClass, "id")

            val amwClass = classLoader.loadClass(ACTIVITY_MANAGER_WRAPPER_CLASS)
            val amwGetInstanceMethod = amwClass.getMethod("getInstance")
            val amwRemoveTaskMethod = amwClass.getMethod("removeTask", Int::class.javaPrimitiveType)

            hookWithId(dismissMethod, "dismiss_tasks_protect") { chain ->
                val task = chain.args.getOrNull(0) ?: return@hookWithId chain.proceed()
                val key = runCatching { taskKeyField.get(task) }.getOrNull()
                    ?: return@hookWithId chain.proceed()
                val pkgName = runCatching { getPackageNameMethod.invoke(key) as? String }.getOrNull()
                val taskId = runCatching { taskIdField.getInt(key) }.getOrNull()

                if (pkgName != null && pkgName !in CLOSE_SAVE_PACKAGES && isProtectedPackage(pkgName)) {
                    if (taskId != null) {
                        runCatching {
                            val amw = amwGetInstanceMethod.invoke(null)
                            amwRemoveTaskMethod.invoke(amw, taskId)
                        }.onFailure {
                            logger.warn("Clear-All: removeTask($taskId) failed for $pkgName: ${it.message}")
                        }
                    }
                    logger.trace("Clear-All: removed task without killing protected app: $pkgName")
                    return@hookWithId null
                }
                chain.proceed()
            }
            logger.info("DisableForceStop: hooked $dismissClassName#$dismissMethodName")
        } catch (t: Throwable) {
            logger.debug("DisableForceStop: dismissTasks hook skipped: ${t.message}")
        }
    }

    private fun hookLegacyOverviewUtilities(classLoader: ClassLoader) {
        val overviewUtilitiesClass = try {
            classLoader.loadClass(LEGACY_OVERVIEW_UTILITIES_CLASS)
        } catch (t: Throwable) {
            logger.debug("DisableForceStop: legacy OverviewUtilities absent: ${t.message}")
            return
        }

        try {
            val removeAppProcessMethod: Method = overviewUtilitiesClass.getDeclaredMethod(
                "removeAppProcess",
                Context::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            hookWithId(removeAppProcessMethod, "remove_app_process_1") { chain ->
                val pkgName = chain.args[2] as String
                val uid = chain.args[3] as Int
                if (isProtectedPackage(pkgName)) {
                    logger.trace("Android 16: Avoid killing app in whitelist: $pkgName (UID: $uid)")
                    return@hookWithId null
                }
                logger.trace("Android 16: Allow killing app: $pkgName")
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.debug("DisableForceStop: legacy removeAppProcess absent: ${t.message}")
        }

        try {
            val removeAllMethod: Method = overviewUtilitiesClass.getDeclaredMethod(
                "removeAllRunningAppProcesses",
                Context::class.java,
                ArrayList::class.java,
                Boolean::class.javaPrimitiveType
            )
            hookWithId(removeAllMethod, "remove_all_1") { chain ->
                val tasks = chain.args[1] as ArrayList<*>?
                if (tasks != null) {
                    val totalTasks = tasks.size
                    var protectedCount = 0
                    for (task in tasks) {
                        try {
                            val pkgName = getPackageNameFromTask(task)
                            if (pkgName != null && isProtectedPackage(pkgName)) {
                                protectedCount++
                                logger.trace("Android 16: Whitelist APP detected when performing batch kill: $pkgName")
                            }
                        } catch (_: Exception) {
                        }
                    }
                    if (protectedCount > 0) {
                        logger.trace("Android 16: Blocking kill operation, $protectedCount whitelisted app(s) in batch kill list")
                        return@hookWithId null
                    }
                    logger.trace("Android 16: $totalTasks APP(s) are allowed to be killed.")
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.debug("DisableForceStop: legacy removeAllRunningAppProcesses absent: ${t.message}")
        }

        try {
            val asyncTaskClass = findInnerClass(classLoader)
            if (asyncTaskClass != null) {
                val doInBackgroundMethod: Method =
                    asyncTaskClass.getDeclaredMethod("doInBackground", arrayOf<Void>().javaClass)
                val tasksField = asyncTaskClass.getDeclaredField("tasks").apply { isAccessible = true }
                hookWithId(doInBackgroundMethod, "do_in_background") { chain ->
                    try {
                        val thisObject = chain.thisObject
                        val tasks = tasksField.get(thisObject)
                        if (tasks is ArrayList<*>) {
                            for (task in tasks) {
                                try {
                                    val pkgName = getPackageNameFromTask(task)
                                    if (pkgName != null && isProtectedPackage(pkgName)) {
                                        logger.trace("Android 16: Whitelist app detected in async task, count: $pkgName, blocking async task")
                                        return@hookWithId null
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    } catch (_: Exception) {
                        logger.warn("Android 16: Unable to check async task, blocking it by default")
                        return@hookWithId null
                    }
                    logger.trace("Android 16: Allowed to perform async kill")
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            logger.debug("DisableForceStop: legacy AsyncTask hook absent: ${t.message}")
        }
    }

    private fun hookBaseLauncherAndroid16() {
        logger.warn("Android 16 logic not implemented yet!")
    }

    @SuppressLint("PrivateApi")
    private fun hookLegacyLauncher(classLoader: ClassLoader) {
        try {
            logger.info("Start hooking legacy Launcher with whitelist enabled.")
            val amwclass = try {
                classLoader.loadClass(ACTIVITY_MANAGER_WRAPPER_CLASS)
            } catch (_: ClassNotFoundException) {
                null
            }

            if (amwclass != null) {
                logger.info("Found ActivityManagerWrapper class, starting hook...")

                val removeAllMethod: Method = amwclass.getDeclaredMethod(
                    "removeAllRunningAppProcesses", Context::class.java, ArrayList::class.java
                )
                hookWithId(removeAllMethod, "remove_all_2") { chain ->
                    val tasks = chain.args[1] as ArrayList<*>?
                    if (tasks != null) {
                        var protectedCount = 0
                        for (task in tasks) {
                            try {
                                val pkgName = getPackageNameFromTask(task)
                                if (pkgName != null && isProtectedPackage(pkgName)) {
                                    protectedCount++
                                }
                            } catch (_: Exception) {
                            }
                        }
                        if (protectedCount > 0) {
                            logger.trace("Legacy architecture: batch kill includes $protectedCount whitelisted app(s), blocking kill")
                            return@hookWithId null
                        }
                    }
                    chain.proceed()
                }

                val removeAppProcessMethod: Method = amwclass.getDeclaredMethod(
                    "removeAppProcess", Context::class.java, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType
                )
                hookWithId(removeAppProcessMethod, "remove_app_process_2") { chain ->
                    val pkgName = chain.args[2] as String
                    if (isProtectedPackage(pkgName)) {
                        logger.trace("Legacy architecture: blocked killing whitelisted app: $pkgName")
                        return@hookWithId null
                    }
                    chain.proceed()
                }
                logger.info("ActivityManagerWrapper hook completed [OK], whitelist mechanism active")
            } else {
                logger.warn("ActivityManagerWrapper class not found, trying other hook points...")
            }
        } catch (e: Exception) {
            logger.error("Failed to hook legacy launcher", e)
        }
    }

    private fun getPackageNameFromTask(task: Any?): String? {
        if (task == null) return null
        try {
            // Primary: Task.key.getPackageName()
            try {
                val keyField = findField(task.javaClass, "key")
                val key = keyField.get(task)
                if (key != null) {
                    val pkg = key.javaClass.getMethod("getPackageName").invoke(key)
                    if (pkg is String) return pkg
                }
            } catch (_: Throwable) {
            }

            // Legacy probes
            try {
                val componentNameField: Field = task.javaClass.getDeclaredField("componentName")
                componentNameField.isAccessible = true
                val componentName = componentNameField.get(task)
                if (componentName != null) {
                    val packageNameObj = componentName.javaClass.getMethod("getPackageName").invoke(componentName)
                    if (packageNameObj is String) return packageNameObj
                }
            } catch (_: NoSuchFieldException) {
            }

            try {
                val packageNameField: Field = task.javaClass.getDeclaredField("packageName")
                packageNameField.isAccessible = true
                val packageNameFieldVal = packageNameField.get(task)
                if (packageNameFieldVal is String) return packageNameFieldVal
            } catch (_: NoSuchFieldException) {
            }

            try {
                val baseActivityInfoField: Field = task.javaClass.getDeclaredField("baseActivityInfo")
                baseActivityInfoField.isAccessible = true
                val baseActivityInfo = baseActivityInfoField.get(task)
                if (baseActivityInfo != null) {
                    val packageNameField: Field =
                        baseActivityInfo.javaClass.getDeclaredField("packageName")
                    packageNameField.isAccessible = true
                    val packageNameObj = packageNameField.get(baseActivityInfo)
                    if (packageNameObj is String) return packageNameObj
                }
            } catch (_: NoSuchFieldException) {
            }

            try {
                val taskDescriptionField: Field = task.javaClass.getDeclaredField("taskDescription")
                taskDescriptionField.isAccessible = true
                val taskDescription = taskDescriptionField.get(task)
                if (taskDescription != null) {
                    val packageNameObj = taskDescription.javaClass.getMethod("getPackageName").invoke(taskDescription)
                    if (packageNameObj is String) return packageNameObj
                }
            } catch (_: NoSuchFieldException) {
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun findInnerClass(classLoader: ClassLoader): Class<*>? {
        for (suffix in 'a'..'e') {
            try {
                val cls = classLoader.loadClass("$LEGACY_OVERVIEW_UTILITIES_CLASS$$suffix")
                try {
                    cls.getDeclaredMethod("doInBackground", arrayOf<Void>().javaClass)
                    logger.info("Found inner class: ${cls.name}")
                    return cls
                } catch (_: NoSuchMethodException) {
                }
            } catch (_: ClassNotFoundException) {
            }
        }
        for (i in 1..5) {
            try {
                val cls = classLoader.loadClass("$LEGACY_OVERVIEW_UTILITIES_CLASS$$i")
                try {
                    cls.getDeclaredMethod("doInBackground", arrayOf<Void>().javaClass)
                    logger.info("Found inner class: ${cls.name}")
                    return cls
                } catch (_: NoSuchMethodException) {
                }
            } catch (_: ClassNotFoundException) {
            }
        }
        return null
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun parseParamTypes(classLoader: ClassLoader, raw: String?): Array<Class<*>>? {
        if (raw == null) return null
        return runCatching {
            raw.split(",").map { resolveType(classLoader, it.trim()) }.toTypedArray()
        }.getOrNull()
    }

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

    private fun getSDKVersion(): Int {
        return try {
            Build.VERSION.SDK_INT
        } catch (t: Throwable) {
            logger.error("Failed to fetch SDK level, use default.", t)
            Build.VERSION_CODES.BASE
        }
    }

    private companion object {
        const val LEGACY_OVERVIEW_UTILITIES_CLASS = "com.zui.launcher.util.OverviewUtilities"
        const val DEFAULT_DISMISS_UTILS_CLASS = "com.android.quickstep.views.RecentsDismissUtils"
        const val DEFAULT_DISMISS_METHOD = "dismissTasks"
        const val TASK_CLASS = "com.android.systemui.shared.recents.model.Task"
        const val ACTIVITY_MANAGER_WRAPPER_CLASS = "com.android.systemui.shared.system.ActivityManagerWrapper"
        val CLOSE_SAVE_PACKAGES = setOf("com.kingsoft.moffice_eng", "com.autodesk.autocad")
    }
}
