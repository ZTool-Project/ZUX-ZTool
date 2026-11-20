package com.qimian233.ztool.hook.modules.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Forces ZUI Launcher's freeform (small window) entry checks to always pass.
 *
 * Uses offline DexIndex targets on Android 17+ to handle R8-obfuscated FreeformUtilities
 * and inlined AbsSwipeUpHandler deny-set checks, while preserving the unobfuscated
 * FreeformUtilities fallback on API <= 36.
 */
@SuppressLint("PrivateApi")
class LauncherFreeformEntryHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_FORCE_FREEFORM_ENTRY.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.LAUNCHER.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val isLegacyApi = Build.VERSION.SDK_INT <= Build.VERSION_CODES.BAKLAVA
        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_FORCE_FREEFORM_ENTRY)

        val indexedUtilsClass = readString(module, DexIndexConstants.Keys.FREEFORM_UTILS_CLASS)
        val utilsClassName = indexedUtilsClass
            ?: if (isLegacyApi) LEGACY_UTILS_CLASS else A17_UNOBFUSCATED_UTILS_CLASS
        val utilsClass = try {
            classLoader.loadClass(utilsClassName)
        } catch (e: ClassNotFoundException) {
            if (!isLegacyApi && indexedUtilsClass == null) {
                logger.warn("FreeformUtilities dex index not ready yet; skipping hook: ${e.message}")
                return
            }
            throw e
        }

        val isUnobfuscatedUtils = isLegacyApi || utilsClassName == A17_UNOBFUSCATED_UTILS_CLASS
        val pkgSupportName = readString(module, DexIndexConstants.Keys.FREEFORM_PKG_SUPPORT_METHOD)
            ?: if (isUnobfuscatedUtils) "isPackageSupportZuiFreeform" else null
        if (pkgSupportName != null) {
            val pkgSupportParams = readParams(classLoader, module, DexIndexConstants.Keys.FREEFORM_PKG_SUPPORT_PARAMS)
                ?: arrayOf(
                    Context::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
            val isPkgSupport = findMethod(utilsClass, pkgSupportName, *pkgSupportParams)
            hookPkgSupport(isPkgSupport)
        } else {
            logger.warn("Missing package freeform support method index for $utilsClassName")
        }

        val taskSupportName = readString(module, DexIndexConstants.Keys.FREEFORM_TASK_SUPPORT_METHOD)
            ?: if (isUnobfuscatedUtils) "isTaskSupportSmallWindow" else null
        if (taskSupportName != null) {
            val taskInfoClass = classLoader.loadClass(TASK_INFO_CLASS)
            val taskSupportParams = readParams(classLoader, module, DexIndexConstants.Keys.FREEFORM_TASK_SUPPORT_PARAMS)
                ?: arrayOf(Context::class.java, taskInfoClass)
            val isTaskSupport = findMethod(utilsClass, taskSupportName, *taskSupportParams)
            hookWithId(isTaskSupport, "launcher_freeform_task_support") { chain ->
                val original = chain.proceed() as Boolean
                logger.debug("isTaskSupportSmallWindow original=$original -> true")
                true
            }
        }

        val denySetName = readString(module, DexIndexConstants.Keys.FREEFORM_DENY_SET_METHOD)
            ?: if (isUnobfuscatedUtils) "inStaticDenyOvcDcvSet" else null
        if (denySetName != null) {
            val denySetParams = readParams(classLoader, module, DexIndexConstants.Keys.FREEFORM_DENY_SET_PARAMS)
                ?: arrayOf(Context::class.java, ComponentName::class.java)
            val inDenySet = findMethod(utilsClass, denySetName, *denySetParams)
            hookWithId(inDenySet, "launcher_freeform_deny_set") { chain ->
                val original = chain.proceed() as Boolean
                logger.debug(
                    "inStaticDenyOvcDcvSet(${chain.getArg(1)}) original=$original -> false"
                )
                false
            }
        }

        val swipeHandlerClassName = readString(module, DexIndexConstants.Keys.FREEFORM_SWIPE_HANDLER_CLASS)
        if (swipeHandlerClassName != null) {
            installSwipeHandlerHook(classLoader, utilsClass, module, swipeHandlerClassName)
        }
    }

    private fun hookPkgSupport(isPkgSupport: Method) {
        val paramTypes = isPkgSupport.parameterTypes
        val intIndices = paramTypes.indices.filter { paramTypes[it] == Int::class.javaPrimitiveType }
        val strIndices = paramTypes.indices.filter { paramTypes[it] == String::class.java }
        val displayIdx = intIndices.getOrNull(0)
        val userIdx = intIndices.getOrNull(1)
        val pkgIdx = strIndices.getOrNull(0)

        hookWithId(isPkgSupport, "launcher_freeform_pkg_support") { chain ->
            val original = chain.proceed() as Boolean
            val pkg = pkgIdx?.let { chain.getArg(it) }
            val userId = userIdx?.let { chain.getArg(it) }
            val displayId = displayIdx?.let { chain.getArg(it) }
            logger.debug(
                "isPackageSupportZuiFreeform($pkg, u$userId, display=$displayId) original=$original -> true"
            )
            true
        }
    }

    private fun installSwipeHandlerHook(
        classLoader: ClassLoader,
        utilsClass: Class<*>,
        module: JsonObject?,
        swipeHandlerClassName: String
    ) {
        val swipeHandlerClass = classLoader.loadClass(swipeHandlerClassName)
        val ctorParams = readParams(classLoader, module, DexIndexConstants.Keys.FREEFORM_SWIPE_INIT_PARAMS)
        val swipeCtor: Constructor<*> = if (ctorParams != null) {
            swipeHandlerClass.getDeclaredConstructor(*ctorParams)
        } else {
            swipeHandlerClass.declaredConstructors.singleOrNull()
                ?: throw NoSuchMethodException("Unique constructor not found in $swipeHandlerClassName")
        }

        val staticDenyField = readString(module, DexIndexConstants.Keys.FREEFORM_STATIC_DENY_FIELD)
            ?.let { runCatching { findField(utilsClass, it) }.getOrNull() }
        clearStaticDenySet(staticDenyField)

        val supportSwipeField = readString(module, DexIndexConstants.Keys.FREEFORM_SWIPE_SUPPORT_FIELD)
            ?.let { runCatching { findField(swipeHandlerClass, it) }.getOrNull() }
        val taskInfoField = readString(module, DexIndexConstants.Keys.FREEFORM_SWIPE_TASK_INFO_FIELD)
            ?.let { runCatching { findField(swipeHandlerClass, it) }.getOrNull() }

        val taskInfoClass = classLoader.loadClass(TASK_INFO_CLASS)
        val getWindowingModeMethod = runCatching { findMethod(taskInfoClass, "getWindowingMode") }.getOrNull()
        val topActivityTypeField = runCatching { findField(taskInfoClass, "topActivityType") }.getOrNull()
        val isSwipeForSplitField = runCatching { findField(swipeHandlerClass, "mIsSwipeForSplit") }.getOrNull()
        val continuingLastField = runCatching { findField(swipeHandlerClass, "mContinuingLastGesture") }.getOrNull()
        val dpField = runCatching { findField(swipeHandlerClass, "mDp") }.getOrNull()
        val devicePropsField = dpField?.type?.let {
            runCatching { findField(it, "mDeviceProperties") }.getOrNull()
        }
        val isLargeScreenField = devicePropsField?.type?.let {
            runCatching { findField(it, "isLargeScreen") }.getOrNull()
        }
        val isLandscapeField = devicePropsField?.type?.let {
            runCatching { findField(it, "isLandscape") }.getOrNull()
        }

        hookWithId(swipeCtor, "launcher_freeform_swipe_init") { chain ->
            clearStaticDenySet(staticDenyField)
            chain.proceed()
            val instance = chain.thisObject ?: return@hookWithId null
            if (supportSwipeField != null && taskInfoField != null && !supportSwipeField.getBoolean(instance)) {
                val taskInfo = taskInfoField.get(instance)
                val isSwipeForSplit = isSwipeForSplitField?.getBoolean(instance) == true
                val continuingLast = continuingLastField?.getBoolean(instance) == true
                val screenEligible = isSwipeScreenEligible(
                    instance,
                    dpField,
                    devicePropsField,
                    isLargeScreenField,
                    isLandscapeField
                )
                if (taskInfo != null && !isSwipeForSplit && !continuingLast && screenEligible) {
                    val windowingMode = getWindowingModeMethod?.invoke(taskInfo) as? Int ?: 0
                    val activityType = topActivityTypeField?.getInt(taskInfo) ?: 0
                    if (windowingMode != WINDOWING_MODE_FREEFORM &&
                        windowingMode != WINDOWING_MODE_MULTI_WINDOW &&
                        activityType != ACTIVITY_TYPE_HOME &&
                        activityType != ACTIVITY_TYPE_RECENTS
                    ) {
                        supportSwipeField.setBoolean(instance, true)
                        logger.debug("AbsSwipeUpHandler swipe floating window original=false -> true")
                    }
                }
            }
            null
        }
    }

    private fun clearStaticDenySet(staticDenyField: Field?) {
        if (staticDenyField == null) return
        val current = staticDenyField.get(null)
        if (current == null) {
            staticDenyField.set(null, HashSet<Any?>())
        } else if (current is MutableSet<*> && current.isNotEmpty()) {
            current.clear()
        }
    }

    private fun isSwipeScreenEligible(
        instance: Any,
        dpField: Field?,
        devicePropsField: Field?,
        isLargeScreenField: Field?,
        isLandscapeField: Field?
    ): Boolean {
        if (dpField == null || devicePropsField == null ||
            isLargeScreenField == null || isLandscapeField == null
        ) {
            return true
        }
        val dp = dpField.get(instance) ?: return true
        val props = devicePropsField.get(dp) ?: return true
        return isLargeScreenField.getBoolean(props) || !isLandscapeField.getBoolean(props)
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun readParams(
        classLoader: ClassLoader,
        module: JsonObject?,
        key: String
    ): Array<Class<*>?>? {
        val raw = readString(module, key) ?: return null
        return raw.split(",").map { resolveType(classLoader, it.trim()) }.toTypedArray()
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

    private companion object {
        const val LEGACY_UTILS_CLASS = "com.zui.launcher.utils.FreeformUtilities"
        const val A17_UNOBFUSCATED_UTILS_CLASS = "com.zui.launcher.util.FreeformUtilities"
        const val TASK_INFO_CLASS = "android.app.TaskInfo"
        const val WINDOWING_MODE_FREEFORM = 5
        const val WINDOWING_MODE_MULTI_WINDOW = 6
        const val ACTIVITY_TYPE_HOME = 2
        const val ACTIVITY_TYPE_RECENTS = 3
    }
}
