package com.qimian233.ztool.hook.modules.systemui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.res.Configuration
import android.graphics.Rect
import com.qimian233.ztool.data.freeform.FreeformEdgeBubbleConfig
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.ceil

/**
 * Widens the horizontal drag-end limit to the configured edge-bubble trigger ratio.
 *
 * ZUI's valid drag area keeps roughly half of the window width on screen; widening it lets
 * freeform windows hang offscreen past the trigger ratio.
 */
// See docs/research/freeform_edge_bubble_android17.md
@SuppressLint("PrivateApi")
class FreeformEdgeBubbleDragLimitHook : AppHookModule() {

    private class CommonReflect(
        val configGetter: Method?,
        val configField: Field?,
        val windowConfigField: Field?,
        val windowConfigGetter: Method?,
        val windowConfigBounds: Method,
        val displayIdField: Field?,
        val displayIdGetter: Method?,
        val scaleXField: Field?,
        val scaleSurfaceBoundsField: Field?
    )

    private class TargetReflect(
        val taskInfoField: Field,
        val displayControllerField: Field,
        val getDisplayLayout: Method,
        val displayWidthField: Field
    )

    override fun getModuleName(): String = PreferenceKeys.FREEFORM_EDGE_BUBBLE.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        try {
            install(param)
        } catch (t: Throwable) {
            logger.error("edge bubble drag limit install failed", t)
        }
    }

    private fun install(param: PackageLoadedParam) {
        val common = resolveCommonReflect() ?: return
        val triggerPercent = readTriggerPercent()
        val loader = param.defaultClassLoader

        var installedCount = 0
        // Android 17 (WindowDecoration2 + CaptionController):
        // OvTabletCaptionHandleController.calculateValidDragArea() delegates to
        // calculateValidDragAreaForTaskWidth(int), which resize-end also calls directly.
        if (hookTarget(
                loader = loader,
                className = TABLET_CAPTION_CONTROLLER_CLASS,
                methodName = "calculateValidDragAreaForTaskWidth",
                paramTypes = arrayOf(Int::class.javaPrimitiveType),
                taskInfoFieldName = "taskInfo",
                displayControllerFieldName = "displayController",
                hookId = "freeform_edge_bubble_drag_limit_tablet",
                explicitWidthArg = 0,
                common = common,
                triggerPercent = triggerPercent
            )
        ) {
            installedCount++
        }
        if (hookTarget(
                loader = loader,
                className = OV_DESKTOP_HEADER_CONTROLLER_CLASS,
                methodName = "calculateValidDragArea",
                paramTypes = emptyArray(),
                taskInfoFieldName = "taskInfo",
                displayControllerFieldName = "displayController",
                hookId = "freeform_edge_bubble_drag_limit_ov_desktop",
                explicitWidthArg = -1,
                common = common,
                triggerPercent = triggerPercent
            )
        ) {
            installedCount++
        }
        if (hookTarget(
                loader = loader,
                className = APP_HEADER_CONTROLLER_CLASS,
                methodName = "calculateValidDragArea",
                paramTypes = emptyArray(),
                taskInfoFieldName = "taskInfo",
                displayControllerFieldName = "displayController",
                hookId = "freeform_edge_bubble_drag_limit_app_header",
                explicitWidthArg = -1,
                common = common,
                triggerPercent = triggerPercent
            )
        ) {
            installedCount++
        }
        // Android <= 16 fallback: DesktopModeWindowDecoration.calculateValidDragArea().
        if (hookTarget(
                loader = loader,
                className = LEGACY_DECOR_CLASS,
                methodName = "calculateValidDragArea",
                paramTypes = emptyArray(),
                taskInfoFieldName = "mTaskInfo",
                displayControllerFieldName = "mDisplayController",
                hookId = "freeform_edge_bubble_drag_limit",
                explicitWidthArg = -1,
                common = common,
                triggerPercent = triggerPercent,
                missingIsDebug = installedCount > 0
            )
        ) {
            installedCount++
        }

        if (installedCount == 0) {
            logger.warn("no drag area target found; edge bubble drag limit inactive")
        } else {
            logger.info("edge bubble drag limit installed on $installedCount target(s)")
        }
    }

    private fun resolveCommonReflect(): CommonReflect? {
        val taskInfoClass = ActivityManager.RunningTaskInfo::class.java
        val configGetter = try {
            taskInfoClass.getMethod("getConfiguration")
        } catch (_: Throwable) {
            null
        }
        val configField = try {
            taskInfoClass.getField("configuration")
        } catch (_: Throwable) {
            null
        }
        if (configGetter == null && configField == null) {
            logger.warn("RunningTaskInfo configuration not found")
            return null
        }
        val windowConfigField = try {
            Configuration::class.java.getField("windowConfiguration")
        } catch (_: Throwable) {
            null
        }
        val windowConfigGetter = try {
            Configuration::class.java.getMethod("getWindowConfiguration")
        } catch (_: Throwable) {
            null
        }
        if (windowConfigField == null && windowConfigGetter == null) {
            logger.warn("Configuration.windowConfiguration not found")
            return null
        }
        val windowConfigBounds = try {
            (windowConfigField?.type ?: windowConfigGetter!!.returnType).getMethod("getBounds")
        } catch (t: Throwable) {
            logger.warn("WindowConfiguration.getBounds not found: ${t.message}")
            return null
        }
        val displayIdField = try {
            taskInfoClass.getField("displayId")
        } catch (_: Throwable) {
            null
        }
        val displayIdGetter = try {
            taskInfoClass.getMethod("getDisplayId")
        } catch (_: Throwable) {
            null
        }
        if (displayIdField == null && displayIdGetter == null) {
            logger.warn("RunningTaskInfo displayId not found")
            return null
        }
        val scaleXField = try {
            taskInfoClass.getField("scaleX")
        } catch (_: Throwable) {
            null
        }
        val scaleSurfaceBoundsField = try {
            taskInfoClass.getField("scaleSurfaceBounds")
        } catch (_: Throwable) {
            null
        }
        return CommonReflect(
            configGetter, configField,
            windowConfigField, windowConfigGetter, windowConfigBounds,
            displayIdField, displayIdGetter,
            scaleXField, scaleSurfaceBoundsField
        )
    }

    private fun readTriggerPercent(): Int =
        FreeformEdgeBubbleConfig.normalizeTriggerPercent(
            try {
                remotePreferences.getInt(
                    PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT.name,
                    PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT.default
                )
            } catch (_: Throwable) {
                PreferenceKeys.FREEFORM_EDGE_BUBBLE_TRIGGER_PERCENT.default
            }
        )

    private fun hookTarget(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramTypes: Array<Class<*>?>,
        taskInfoFieldName: String,
        displayControllerFieldName: String,
        hookId: String,
        explicitWidthArg: Int,
        common: CommonReflect,
        triggerPercent: Int,
        missingIsDebug: Boolean = false
    ): Boolean {
        val targetClass = try {
            loader.loadClass(className)
        } catch (t: Throwable) {
            if (missingIsDebug) {
                logger.debug("$className not found: ${t.message}")
            } else {
                logger.warn("$className not found: ${t.message}")
            }
            return false
        }
        val targetMethod = try {
            findMethod(targetClass, methodName, *paramTypes)
        } catch (t: Throwable) {
            if (missingIsDebug) {
                logger.debug("$className.$methodName not found: ${t.message}")
            } else {
                logger.warn("$className.$methodName not found: ${t.message}")
            }
            return false
        }
        val target = resolveTargetReflect(targetClass, className, taskInfoFieldName, displayControllerFieldName)
            ?: return false

        hookWithId(targetMethod, hookId) { chain ->
            widenDragAreaResult(chain, explicitWidthArg, target, common, triggerPercent)
        }
        return true
    }

    private fun resolveTargetReflect(
        targetClass: Class<*>,
        className: String,
        taskInfoFieldName: String,
        displayControllerFieldName: String
    ): TargetReflect? {
        val taskInfoField = try {
            findField(targetClass, taskInfoFieldName)
        } catch (t: Throwable) {
            logger.warn("$className.$taskInfoFieldName not found: ${t.message}")
            return null
        }
        val displayControllerField = try {
            findField(targetClass, displayControllerFieldName)
        } catch (t: Throwable) {
            logger.warn("$className.$displayControllerFieldName not found: ${t.message}")
            return null
        }
        val getDisplayLayout = try {
            displayControllerField.type.getMethod("getDisplayLayout", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            logger.warn("DisplayController.getDisplayLayout not found: ${t.message}")
            return null
        }
        val displayWidthField = try {
            getDisplayLayout.returnType.getField("mWidth")
        } catch (t: Throwable) {
            logger.warn("DisplayLayout.mWidth not found: ${t.message}")
            return null
        }
        return TargetReflect(taskInfoField, displayControllerField, getDisplayLayout, displayWidthField)
    }

    private fun widenDragAreaResult(
        chain: XposedInterface.Chain,
        explicitWidthArg: Int,
        target: TargetReflect,
        common: CommonReflect,
        triggerPercent: Int
    ): Any? {
        val result = chain.proceed()
        val area = result as? Rect ?: return result
        // Host returns an empty Rect() when DisplayLayout is unavailable; leave it untouched.
        if (area.isEmpty) return area
        val owner = chain.thisObject ?: return area
        val taskInfo = target.taskInfoField.get(owner) as? ActivityManager.RunningTaskInfo
            ?: return area
        val width = if (explicitWidthArg >= 0) {
            chain.getArg(explicitWidthArg) as? Int ?: 0
        } else {
            resolveTaskWidth(taskInfo, common)
        }
        if (width <= 0) return area
        val displayId = resolveDisplayId(taskInfo, common) ?: return area
        val displayController = target.displayControllerField.get(owner) ?: return area
        val displayLayout = try {
            target.getDisplayLayout.invoke(displayController, displayId)
        } catch (_: Throwable) {
            null
        } ?: return area
        val displayWidth = try {
            target.displayWidthField.getInt(displayLayout)
        } catch (_: Throwable) {
            return area
        }
        if (displayWidth <= 0) return area
        val triggerPx = ceil(width * triggerPercent / 100.0).toInt() + TRIGGER_SLACK_PX
        area.left = minOf(area.left, -triggerPx)
        area.right = maxOf(area.right, displayWidth + triggerPx - width)
        return area
    }

    private fun resolveTaskWidth(
        taskInfo: ActivityManager.RunningTaskInfo,
        common: CommonReflect
    ): Int {
        val scaleX = try {
            common.scaleXField?.getFloat(taskInfo) ?: 0f
        } catch (_: Throwable) {
            0f
        }
        if (scaleX > 0f) {
            val scaledBounds = try {
                common.scaleSurfaceBoundsField?.get(taskInfo) as? Rect
            } catch (_: Throwable) {
                null
            }
            if (scaledBounds != null && !scaledBounds.isEmpty) {
                return scaledBounds.width()
            }
        }
        val configuration = try {
            common.configGetter?.invoke(taskInfo) ?: common.configField?.get(taskInfo)
        } catch (_: Throwable) {
            null
        } as? Configuration ?: return 0
        val windowConfig = try {
            common.windowConfigField?.get(configuration)
                ?: common.windowConfigGetter?.invoke(configuration)
        } catch (_: Throwable) {
            null
        } ?: return 0
        return try {
            (common.windowConfigBounds.invoke(windowConfig) as? Rect)?.width() ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun resolveDisplayId(
        taskInfo: ActivityManager.RunningTaskInfo,
        common: CommonReflect
    ): Int? = try {
        common.displayIdField?.getInt(taskInfo)
            ?: (common.displayIdGetter?.invoke(taskInfo) as? Int)
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val LEGACY_DECOR_CLASS = "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration"
        const val TABLET_CAPTION_CONTROLLER_CLASS =
            "com.android.wm.shell.windowdecor.caption.OvTabletCaptionHandleController"
        const val OV_DESKTOP_HEADER_CONTROLLER_CLASS =
            "com.android.wm.shell.windowdecor.caption.OvDesktopModeAppHeaderController"
        const val APP_HEADER_CONTROLLER_CLASS =
            "com.android.wm.shell.windowdecor.caption.AppHeaderController"
        /** Extra overshoot so the >= trigger comparison stays reachable after rounding. */
        const val TRIGGER_SLACK_PX = 64
    }
}
