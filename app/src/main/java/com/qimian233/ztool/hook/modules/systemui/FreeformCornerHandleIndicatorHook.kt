package com.qimian233.ztool.hook.modules.systemui

import android.app.ActivityManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows the bottom-corner handles after creation or a corner resize, then hides them after 5 s.
 */
@Suppress("PrivateApi")
class FreeformCornerHandleIndicatorHook : AppHookModule() {
    private val layouts = ConcurrentHashMap<Int, View>()
    private val hideTokens = ConcurrentHashMap<Int, Long>()
    private val resizing = ConcurrentHashMap<Int, Boolean>()

    override fun getModuleName(): String =
        PreferenceKeys.SYSTEM_UI_CORNER_HANDLE_AUTO_HIDE.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        try {
            install(param)
        } catch (t: Throwable) {
            logger.error("Corner handle indicator install failed", t)
        }
    }

    private fun install(param: PackageLoadedParam) {
        logger.info("Corner handle indicator install start")
        val loader = param.defaultClassLoader
        val hostClass = loadClass(loader, HOST_CLASS) ?: return
        val setViewMethod = try {
            findMethod(
                hostClass,
                "setView",
                View::class.java,
                WindowManager.LayoutParams::class.java
            )
        } catch (t: Throwable) {
            logger.error("SurfaceControlViewHost.setView not found", t)
            return
        }

        val positionerReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            installA17Positioners(loader)
        } else {
            installLegacyPositioner(loader)
        }
        if (!positionerReady) return

        hookWithId(setViewMethod, "corner_handle_capture") { chain ->
            val view = chain.args.getOrNull(0) as? View
            val params = chain.args.getOrNull(1) as? WindowManager.LayoutParams
            val result = chain.proceed()
            if (view != null && params != null) {
                val title = params.title?.toString().orEmpty()
                val taskId = TASK_PATTERN.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (taskId != null && viewsReady(view)) {
                    layouts[taskId] = view
                    logger.info("Corner handle layout captured: task=$taskId")
                    val left = findView(view, "ovc_wdb_bottom_gesture_left")
                    if (left?.visibility != View.GONE) {
                        whenAttached(view) {
                            show(taskId)
                            scheduleHide(taskId)
                        }
                    }
                }
            }
            result
        }

        logger.info("Corner handle indicator hook installed")
    }

    private fun installLegacyPositioner(loader: ClassLoader): Boolean {
        val positionerClass = loadClass(loader, POSITIONER_CLASS) ?: return false
        val decorationClass = loadClass(loader, DECORATION_CLASS) ?: return false
        val positionerCtrl = field(positionerClass, "mCtrlType") ?: return false
        val positionerDecoration = field(positionerClass, "mDesktopWindowDecoration") ?: return false
        val decorationTaskInfo = field(decorationClass, "mTaskInfo")
            ?: fieldByType(decorationClass, ActivityManager.RunningTaskInfo::class.java)
            ?: run {
                logger.error("RunningTaskInfo field not found")
                return false
            }
        val startMethod = try {
            findMethod(
                positionerClass,
                "onDragPositioningStart",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            logger.error("onDragPositioningStart not found", t)
            return false
        }
        val endMethod = try {
            findMethod(
                positionerClass,
                "onDragPositioningEnd",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            logger.error("onDragPositioningEnd not found", t)
            return false
        }

        hookWithId(startMethod, "corner_handle_resize_show") { chain ->
            val result = chain.proceed()
            val positioner = chain.thisObject
            if (positioner != null) {
                val ctrlType = positionerCtrl.getInt(positioner)
                if (isBottomCorner(ctrlType)) {
                    val taskInfo = taskInfoOf(positioner, positionerDecoration, decorationTaskInfo)
                    if (taskInfo != null) {
                        onCornerResizeStart(taskInfo.taskId)
                    }
                }
            }
            result
        }

        hookWithId(endMethod, "corner_handle_resize_hide") { chain ->
            val result = chain.proceed()
            val positioner = chain.thisObject
            if (positioner != null) {
                val taskInfo = taskInfoOf(positioner, positionerDecoration, decorationTaskInfo)
                if (taskInfo != null) {
                    onCornerResizeEnd(taskInfo.taskId)
                }
            }
            result
        }
        return true
    }

    private fun installA17Positioners(loader: ClassLoader): Boolean {
        val wrapperClass = loadClass(loader, A17_WRAPPER_CLASS) ?: return false
        val getTaskInfoMethod = try {
            findMethod(wrapperClass, "getTaskInfo")
        } catch (t: Throwable) {
            logger.error("WindowDecorationWrapper.getTaskInfo not found", t)
            return false
        }

        val targets = listOf(
            A17_VEILED_POSITIONER_CLASS to "corner_handle_a17_veiled",
            A17_SCALE_POSITIONER_CLASS to "corner_handle_a17_scale",
            A17_RESIZE_POSITIONER_CLASS to "corner_handle_a17_resize"
        )
        var installedAny = false
        for ((className, idPrefix) in targets) {
            if (hookA17Positioner(loader, className, idPrefix, wrapperClass, getTaskInfoMethod)) {
                installedAny = true
            }
        }
        return installedAny
    }

    private fun hookA17Positioner(
        loader: ClassLoader,
        className: String,
        idPrefix: String,
        wrapperClass: Class<*>,
        getTaskInfoMethod: Method
    ): Boolean {
        val positionerClass = loadClass(loader, className) ?: return false
        val decorationField = field(positionerClass, "mDesktopWindowDecoration")
            ?: field(positionerClass, "windowDecoration")
            ?: fieldByType(positionerClass, wrapperClass)
            ?: run {
                logger.error("$className WindowDecorationWrapper field not found")
                return false
            }
        val startMethod = try {
            findMethod(
                positionerClass,
                "onDragPositioningStart",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            logger.error("$className.onDragPositioningStart not found", t)
            return false
        }
        val endMethod = try {
            findMethod(
                positionerClass,
                "onDragPositioningEnd",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            logger.error("$className.onDragPositioningEnd not found", t)
            return false
        }

        hookWithId(startMethod, "${idPrefix}_show") { chain ->
            val ctrlType = chain.args.getOrNull(2) as? Int ?: 0
            val result = chain.proceed()
            val positioner = chain.thisObject
            if (positioner != null && isBottomCorner(ctrlType)) {
                val taskInfo = wrapperTaskInfoOf(positioner, decorationField, getTaskInfoMethod)
                if (taskInfo != null) {
                    onCornerResizeStart(taskInfo.taskId)
                }
            }
            result
        }

        hookWithId(endMethod, "${idPrefix}_hide") { chain ->
            val result = chain.proceed()
            val positioner = chain.thisObject
            if (positioner != null) {
                val taskInfo = wrapperTaskInfoOf(positioner, decorationField, getTaskInfoMethod)
                if (taskInfo != null) {
                    onCornerResizeEnd(taskInfo.taskId)
                }
            }
            result
        }
        return true
    }

    private fun onCornerResizeStart(taskId: Int) {
        resizing[taskId] = true
        hideTokens.remove(taskId)
        show(taskId)
    }

    private fun onCornerResizeEnd(taskId: Int) {
        if (resizing.remove(taskId) == true) {
            show(taskId)
            scheduleHide(taskId)
        }
    }

    private fun isBottomCorner(ctrlType: Int): Boolean =
        (ctrlType and 8) != 0 && (ctrlType and 3) != 0

    private fun taskInfoOf(
        positioner: Any,
        decorationField: Field,
        taskInfoField: Field
    ): ActivityManager.RunningTaskInfo? {
        val decoration = decorationField.get(positioner) ?: return null
        return taskInfoField.get(decoration) as? ActivityManager.RunningTaskInfo
    }

    private fun wrapperTaskInfoOf(
        positioner: Any,
        decorationField: Field,
        getTaskInfoMethod: Method
    ): ActivityManager.RunningTaskInfo? {
        val wrapper = decorationField.get(positioner) ?: return null
        return getTaskInfoMethod.invoke(wrapper) as? ActivityManager.RunningTaskInfo
    }

    private fun show(taskId: Int) {
        setVisibility(taskId, View.VISIBLE)
    }

    private fun hide(taskId: Int) {
        setVisibility(taskId, View.GONE)
    }

    private fun whenAttached(view: View, action: () -> Unit) {
        if (view.isAttachedToWindow && view.handler != null) {
            action()
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                action()
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun setVisibility(taskId: Int, visibility: Int) {
        val layout = layouts[taskId]
        if (layout == null) {
            logger.warn("Corner handle layout missing for task $taskId")
            return
        }
        val left = findView(layout, "ovc_wdb_bottom_gesture_left") ?: return
        val right = findView(layout, "ovc_wdb_bottom_gesture_right")
        val handler: Handler = left.handler ?: layout.handler ?: return
        handler.post {
            left.visibility = visibility
            right?.visibility = visibility
        }
        logger.info("Corner handle visibility=$visibility task=$taskId")
    }

    private fun viewsReady(layout: View): Boolean =
        findView(layout, "ovc_wdb_bottom_gesture_left") != null &&
            findView(layout, "ovc_wdb_bottom_gesture_right") != null

    private fun findView(layout: View, name: String): View? {
        val id = layout.context.resources.getIdentifier(
            name,
            "id",
            layout.context.packageName
        )
        if (id == 0) return null
        return layout.findViewById(id)
    }

    private fun scheduleHide(taskId: Int) {
        val token = SystemClock.uptimeMillis()
        hideTokens[taskId] = token
        val layout = layouts[taskId] ?: return
        val view = findView(layout, "ovc_wdb_bottom_gesture_left") ?: return
        val handler: Handler = view.handler ?: layout.handler ?: return
        logger.info("Corner handle hide scheduled: task=$taskId token=$token")
        handler.postDelayed({
            if (hideTokens[taskId] == token && resizing[taskId] != true) {
                hide(taskId)
            }
        }, HIDE_DELAY_MS)
    }

    private fun field(owner: Class<*>, name: String): Field? =
        try {
            findField(owner, name)
        } catch (_: Throwable) {
            null
        }

    private fun fieldByType(owner: Class<*>, type: Class<*>): Field? {
        var current: Class<*>? = owner
        while (current != null) {
            current.declaredFields.firstOrNull {
                type.isAssignableFrom(it.type)
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        try {
            loader.loadClass(name)
        } catch (t: Throwable) {
            logger.error("$name not found", t)
            null
        }

    private companion object {
        const val HOST_CLASS = "android.view.SurfaceControlViewHost"
        const val POSITIONER_CLASS = "com.android.wm.shell.windowdecor.VeiledResizeTaskPositioner"
        const val DECORATION_CLASS = "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration"
        const val A17_WRAPPER_CLASS = "com.android.wm.shell.windowdecor.WindowDecorationWrapper"
        const val A17_VEILED_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.MultiDisplayVeiledResizeTaskPositioner"
        const val A17_SCALE_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.scale.OvfScaleTaskPositionerDecorator"
        const val A17_RESIZE_POSITIONER_CLASS =
            "com.android.wm.shell.windowdecor.ResizeTaskPositioner"
        const val HIDE_DELAY_MS = 5_000L
        val TASK_PATTERN = Regex("Task=(\\d+)")
    }
}
