package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Guards FolderIcon's big-folder blur update against a null drag object while
 * the workspace builds a long-press drag preview. Disable only if the host is fixed.
 */
@SuppressLint("PrivateApi")
class LauncherBigFolderBlurGuardHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_BIG_FOLDER_BLUR_GUARD.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    @Volatile
    private var reported = false

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_BLUR_GUARD)

        fun readKey(key: String): String? =
            module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

        val refs = try {
            val folderIcon = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            val launcher = classLoader.loadClass("com.android.launcher3.Launcher")
            val dragController = classLoader.loadClass("com.android.launcher3.dragndrop.DragController")

            val blurDataName = readKey(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DATA_METHOD)
            val dragFlagName = readKey(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DRAG_FLAG_FIELD)
            val activityFieldName = readKey(DexIndexConstants.Keys.BIG_FOLDER_BLUR_ACTIVITY_FIELD)
            val dragObjMethodName = readKey(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DRAG_OBJECT_METHOD)
            val dragObjFieldName = readKey(DexIndexConstants.Keys.BIG_FOLDER_BLUR_DRAG_OBJECT_FIELD)

            val blurData = resolveFirstMethod(folderIcon, blurDataName, "updateBlurAreaData$1", "A")
            val dragFlag = resolveFirstField(folderIcon, dragFlagName, "isBeingDragged", "S")
            val activityContext = resolveFirstField(folderIcon, activityFieldName, "mActivity", "b")
            val dragObjMethod = when {
                dragObjMethodName != null -> runCatching { findMethod(dragController, dragObjMethodName) }.getOrNull()
                module == null -> runCatching { findMethod(dragController, "getDragObject") }.getOrNull()
                else -> null
            }
            val dragObjField = when {
                dragObjFieldName != null -> runCatching { findField(dragController, dragObjFieldName) }.getOrNull()
                dragObjMethod == null -> runCatching { findField(dragController, "mDragObject") }.getOrNull()
                else -> null
            }
            if (dragObjMethod == null && dragObjField == null) {
                throw NoSuchMethodException("DragController drag object getter/field not found")
            }

            Refs(
                blurData = blurData,
                dragFlag = dragFlag,
                activityContext = activityContext,
                launcherGetDragController = findMethod(launcher, "getDragController"),
                dragControllerGetDragObject = dragObjMethod,
                dragControllerDragObjectField = dragObjField
            )
        } catch (t: Throwable) {
            logger.error("BigFolderBlurGuard: resolve failed, not installed", t)
            return
        }

        hookWithId(refs.blurData, "big_folder_blur_guard") { chain ->
            val icon = chain.thisObject
            var flipped = false
            try {
                if (refs.dragFlag.getBoolean(icon)) {
                    val context = refs.activityContext.get(icon)
                    val dragController = context?.let { refs.launcherGetDragController.invoke(it) }
                    val dragObject = dragController?.let { dc ->
                        refs.dragControllerGetDragObject?.invoke(dc)
                            ?: refs.dragControllerDragObjectField?.get(dc)
                    }
                    if (dragObject == null) {
                        // No drag is in flight, so the idle branch is the correct one; the host
                        // would otherwise read through the null drag object.
                        refs.dragFlag.setBoolean(icon, false)
                        flipped = true
                    }
                }
            } catch (t: Throwable) {
                logger.debug("BigFolderBlurGuard: state check failed: $t")
            }
            try {
                chain.proceed()
            } catch (t: Throwable) {
                // This is a cosmetic blur-rect update; a null dereference must not kill the UI.
                if (!isNullPointer(t)) throw t
                if (!reported) {
                    reported = true
                    logger.warn("BigFolderBlurGuard: null drag object suppressed: $t")
                }
            } finally {
                if (flipped) {
                    try {
                        refs.dragFlag.setBoolean(icon, true)
                    } catch (_: Throwable) {
                    }
                }
            }
            null
        }
        logger.debug("BigFolderBlurGuard installed")
    }

    private fun resolveFirstField(owner: Class<*>, indexed: String?, vararg fallbacks: String): Field {
        if (indexed != null) return findField(owner, indexed)
        for (name in fallbacks) {
            runCatching { return findField(owner, name) }
        }
        throw NoSuchFieldException("${fallbacks.joinToString()} in $owner")
    }

    private fun resolveFirstMethod(owner: Class<*>, indexed: String?, vararg fallbacks: String): Method {
        if (indexed != null) return findMethod(owner, indexed)
        for (name in fallbacks) {
            runCatching { return findMethod(owner, name) }
        }
        throw NoSuchMethodException("${fallbacks.joinToString()} in $owner")
    }

    /** True when the throwable or its cause chain is a NullPointerException. */
    private fun isNullPointer(t: Throwable): Boolean {
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 4) {
            if (cause is NullPointerException) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    /** Reflection handles of the guarded call. */
    private class Refs(
        val blurData: Method,
        val dragFlag: Field,
        val activityContext: Field,
        val launcherGetDragController: Method,
        val dragControllerGetDragObject: Method?,
        val dragControllerDragObjectField: Field?
    )
}
