package com.qimian233.ztool.hook.modules.systemui.wallpaper

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.os.IBinder
import android.view.View
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.WallpaperZoomModes
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.WallpaperZoomSpring
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * Controls SystemUI shade wallpaper zoom across four active modes:
 * continuous, continuous with overshoot bounce, gesture-start trigger, or disabled.
 */
@SuppressLint("PrivateApi")
class ShadeWallpaperZoomFollowHook : AppHookModule() {

    private var lastSentProgress = 0f
    private var cachedWallpaperManager: WallpaperManager? = null
    private var setWallpaperZoomOutMethod: Method? = null
    private var latestNpvcRef: WeakReference<Any>? = null
    private var springViewField: Field? = null
    private var springBarStateField: Field? = null

    private val spring = WallpaperZoomSpring { progress ->
        val npvc = latestNpvcRef?.get()
        val viewField = springViewField
        val barStateField = springBarStateField
        if (npvc == null || viewField == null || barStateField == null) {
            cancelSpring()
            return@WallpaperZoomSpring
        }
        try {
            val view = viewField.get(npvc) as? View ?: run {
                cancelSpring()
                return@WallpaperZoomSpring
            }
            val barState = barStateField.getInt(npvc)
            if (!isEnabled() || currentMode() != WallpaperZoomModes.CONTINUOUS_BOUNCE) {
                cancelSpring()
                return@WallpaperZoomSpring
            }
            if (barState != SHADE_BAR_STATE) {
                cancelSpring()
                dispatchZoom(view, 0f)
                return@WallpaperZoomSpring
            }
            dispatchZoom(view, progress)
        } catch (t: Throwable) {
            cancelSpring()
            logger.error("Failed to update shade wallpaper zoom spring", t)
        }
    }

    private fun cancelSpring() {
        spring.cancel()
    }

    override fun getModuleName(): String = PreferenceKeys.SHADE_WALLPAPER_ZOOM_FOLLOW.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (param.packageName != ScopeKeys.SYSTEM_UI.packageName) return
        val classLoader = param.defaultClassLoader

        val setScaleZoomMethod: Method? = try {
            val animUtilsClass = classLoader.loadClass(QS_WALLPAPER_ANIM_UTILS)
            findMethod(
                animUtilsClass,
                "setWallpaperScaleZoom",
                View::class.java,
                Boolean::class.javaPrimitiveType
            ).also { method ->
                hookWithId(method, "shade_wallpaper_zoom_suppress_zui") { chain ->
                    if (!isEnabled()) {
                        return@hookWithId chain.proceed()
                    }
                    val mode = currentMode()
                    if (mode == WallpaperZoomModes.DISABLE || WallpaperZoomModes.isContinuous(mode)) {
                        return@hookWithId null
                    }
                    chain.proceed()
                }
                logger.info("Hooked QsWallpaperAnimUtils.setWallpaperScaleZoom")
            }
        } catch (t: Throwable) {
            logger.warn("Failed to hook QsWallpaperAnimUtils.setWallpaperScaleZoom: ${t.message}")
            null
        }

        try {
            val npvcClass = classLoader.loadClass(NOTIFICATION_PANEL_VIEW_CONTROLLER)
            val updateExpansionMethod = findMethod(npvcClass, "updateExpansionAndVisibility")
            val viewField = findField(npvcClass, "mView")
            val barStateField = findField(npvcClass, "mBarState")
            val expandedFractionField = findField(npvcClass, "mExpandedFraction")
            val overExpansionField = optionalField(npvcClass, "mOverExpansion")
            val overshootAmountField = optionalField(npvcClass, "mPanelFlingOvershootAmount")
            val zuiNpvcField = optionalField(npvcClass, "mZuiNotificationPanelViewController")
            val zuiZoomedField = zuiNpvcField?.type?.let { optionalField(it, "mZuiWallpaperScaleZoomed") }

            springViewField = viewField
            springBarStateField = barStateField
            setWallpaperZoomOutMethod = findMethod(
                WallpaperManager::class.java,
                "setWallpaperZoomOut",
                IBinder::class.java,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }

            try {
                val notifyStartedMethod = findMethod(npvcClass, "notifyExpandingStarted")
                hookWithId(notifyStartedMethod, "shade_wallpaper_zoom_gesture_start") { chain ->
                    val result = chain.proceed()
                    if (!isEnabled() || currentMode() != WallpaperZoomModes.GESTURE_START) {
                        return@hookWithId result
                    }
                    val npvc = chain.thisObject ?: return@hookWithId result
                    try {
                        val barState = barStateField.getInt(npvc)
                        val fraction = expandedFractionField.getFloat(npvc)
                        val view = viewField.get(npvc) as? View
                        if (barState == SHADE_BAR_STATE && !fraction.isNaN() && fraction <= 0.05f &&
                            view != null && setScaleZoomMethod != null
                        ) {
                            setScaleZoomMethod.invoke(null, view, true)
                            val zuiCtrl = zuiNpvcField?.get(npvc)
                            if (zuiCtrl != null) {
                                zuiZoomedField?.setBoolean(zuiCtrl, true)
                            }
                        }
                    } catch (t: Throwable) {
                        logger.error("Failed to trigger gesture-start shade wallpaper zoom", t)
                    }
                    result
                }
            } catch (t: Throwable) {
                logger.warn("Failed to hook notifyExpandingStarted: ${t.message}")
            }

            try {
                val trackingStartedMethod = findMethod(npvcClass, "onTrackingStarted")
                hookWithId(trackingStartedMethod, "shade_wallpaper_zoom_tracking_start") { chain ->
                    val result = chain.proceed()
                    if (isEnabled() && currentMode() == WallpaperZoomModes.CONTINUOUS_BOUNCE) {
                        spring.cancel()
                    }
                    result
                }
            } catch (t: Throwable) {
                logger.warn("Failed to hook onTrackingStarted: ${t.message}")
            }

            try {
                val flingMethod = findMethod(
                    npvcClass,
                    "flingToHeight",
                    Float::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                hookWithId(flingMethod, "shade_wallpaper_zoom_fling") { chain ->
                    val result = chain.proceed()
                    if (!isEnabled()) return@hookWithId result
                    val npvc = chain.thisObject ?: return@hookWithId result
                    try {
                        if (barStateField.getInt(npvc) != SHADE_BAR_STATE) return@hookWithId result
                        val vel = chain.getArg(0) as Float
                        val expand = chain.getArg(1) as Boolean
                        val mode = currentMode()
                        if (mode == WallpaperZoomModes.GESTURE_START) {
                            if (!expand && setScaleZoomMethod != null) {
                                val view = viewField.get(npvc) as? View
                                if (view != null) {
                                    setScaleZoomMethod.invoke(null, view, false)
                                    val zuiCtrl = zuiNpvcField?.get(npvc)
                                    if (zuiCtrl != null) {
                                        zuiZoomedField?.setBoolean(zuiCtrl, false)
                                    }
                                }
                            }
                        } else if (mode == WallpaperZoomModes.CONTINUOUS_BOUNCE) {
                            if (expand) {
                                val view = viewField.get(npvc) as? View ?: return@hookWithId result
                                latestNpvcRef = WeakReference(npvc)
                                val panelHeight = (view.height.takeIf { it > 0 } ?: DEFAULT_PANEL_HEIGHT).toFloat()
                                val normVel = (vel / panelHeight).coerceIn(0.6f, 5.5f) * 0.9f
                                spring.start(from = lastSentProgress, target = 1.0f, velocity = normVel)
                            } else {
                                spring.cancel()
                            }
                        }
                    } catch (t: Throwable) {
                        logger.error("Failed to handle shade fling wallpaper zoom", t)
                    }
                    result
                }
            } catch (t: Throwable) {
                logger.warn("Failed to hook flingToHeight: ${t.message}")
            }

            hookWithId(updateExpansionMethod, "shade_wallpaper_zoom_follow_expansion") { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@hookWithId result
                val mode = currentMode()
                if (!WallpaperZoomModes.isContinuous(mode)) return@hookWithId result
                val npvc = chain.thisObject ?: return@hookWithId result
                try {
                    val view = viewField.get(npvc) as? View ?: return@hookWithId result
                    latestNpvcRef = WeakReference(npvc)
                    val barState = barStateField.getInt(npvc)
                    if (barState != SHADE_BAR_STATE) {
                        spring.cancel()
                        dispatchZoom(view, 0f)
                        return@hookWithId result
                    }
                    if (mode == WallpaperZoomModes.CONTINUOUS_BOUNCE && spring.isRunning) {
                        return@hookWithId result
                    }
                    val rawFraction = expandedFractionField.getFloat(npvc)
                    var target = if (!rawFraction.isNaN()) rawFraction.coerceIn(0f, 1f) else 0f
                    if (mode == WallpaperZoomModes.CONTINUOUS_BOUNCE &&
                        overExpansionField != null &&
                        overshootAmountField != null
                    ) {
                        val overExp = overExpansionField.getFloat(npvc)
                        val overshootPx = overshootAmountField.getFloat(npvc)
                        if (!overExp.isNaN() && !overshootPx.isNaN() && overExp > 0f && overshootPx > 0f) {
                            target += (overExp / overshootPx).coerceIn(0f, 1f) * 0.25f
                        }
                    }
                    dispatchZoom(view, target)
                } catch (t: Throwable) {
                    logger.error("Failed to dispatch shade wallpaper zoom", t)
                }
                result
            }
            logger.info("ShadeWallpaperZoomFollowHook installed")
        } catch (t: Throwable) {
            logger.error("Failed to install ShadeWallpaperZoomFollowHook", t)
        }
    }

    private fun dispatchZoom(view: View, progress: Float) {
        val target = progress.coerceIn(0f, MAX_PROGRESS)
        val shouldSend = (target == 0f && lastSentProgress != 0f) ||
            (target == 1f && lastSentProgress != 1f) ||
            abs(target - lastSentProgress) >= PROGRESS_DELTA_THRESHOLD
        if (!shouldSend || !view.isAttachedToWindow) return
        val token = view.windowToken ?: return
        val method = setWallpaperZoomOutMethod ?: return
        val wallpaperManager = cachedWallpaperManager
            ?: view.context.getSystemService(WallpaperManager::class.java)
                ?.also { cachedWallpaperManager = it }
            ?: return
        method.invoke(wallpaperManager, token, WallpaperZoomModes.encodeProgress(target))
        lastSentProgress = target
    }

    private fun currentMode(): String = try {
        WallpaperZoomModes.normalize(
            remotePreferences.getString(
                PreferenceKeys.SHADE_WALLPAPER_ZOOM_MODE.name,
                PreferenceKeys.SHADE_WALLPAPER_ZOOM_MODE.default
            )
        )
    } catch (_: Throwable) {
        PreferenceKeys.SHADE_WALLPAPER_ZOOM_MODE.default
    }

    private fun optionalField(startClass: Class<*>, name: String): Field? =
        try {
            findField(startClass, name)
        } catch (_: NoSuchFieldException) {
            null
        }

    companion object {
        private const val QS_WALLPAPER_ANIM_UTILS = "com.android.systemui.util.QsWallpaperAnimUtils"
        private const val NOTIFICATION_PANEL_VIEW_CONTROLLER =
            "com.android.systemui.shade.NotificationPanelViewController"
        private const val SHADE_BAR_STATE = 0
        private const val DEFAULT_PANEL_HEIGHT = 1500
        private const val MAX_PROGRESS = 1.55f
        private const val PROGRESS_DELTA_THRESHOLD = 0.0005f
    }
}
