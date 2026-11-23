package com.qimian233.ztool.hook.modules.systemui.wallpaper

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.os.IBinder
import android.view.View
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import kotlin.math.abs

/**
 * Dispatches shade expansion fraction via WallpaperManager.setWallpaperZoomOut
 * and suppresses ZUI's post-animation boolean wallpaper zoom trigger.
 */
@SuppressLint("PrivateApi")
class ShadeWallpaperZoomFollowHook : AppHookModule() {

    private var lastSentFraction = 0f
    private var cachedWallpaperManager: WallpaperManager? = null

    override fun getModuleName(): String = PreferenceKeys.SHADE_WALLPAPER_ZOOM_FOLLOW.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (param.packageName != ScopeKeys.SYSTEM_UI.packageName) return
        val classLoader = param.defaultClassLoader

        try {
            val animUtilsClass = classLoader.loadClass(QS_WALLPAPER_ANIM_UTILS)
            val setZoomMethod = findMethod(
                animUtilsClass,
                "setWallpaperScaleZoom",
                View::class.java,
                Boolean::class.javaPrimitiveType
            )
            hookWithId(setZoomMethod, "shade_wallpaper_zoom_suppress_zui") { chain ->
                if (!isEnabled()) {
                    return@hookWithId chain.proceed()
                }
                null
            }
            logger.info("Hooked QsWallpaperAnimUtils.setWallpaperScaleZoom")
        } catch (t: Throwable) {
            logger.warn("Failed to hook QsWallpaperAnimUtils.setWallpaperScaleZoom: ${t.message}")
        }

        try {
            val npvcClass = classLoader.loadClass(NOTIFICATION_PANEL_VIEW_CONTROLLER)
            val updateExpansionMethod = findMethod(npvcClass, "updateExpansionAndVisibility")
            val viewField = findField(npvcClass, "mView")
            val barStateField = findField(npvcClass, "mBarState")
            val expandedFractionField = findField(npvcClass, "mExpandedFraction")
            val setWallpaperZoomOutMethod = findMethod(
                WallpaperManager::class.java,
                "setWallpaperZoomOut",
                IBinder::class.java,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }

            hookWithId(updateExpansionMethod, "shade_wallpaper_zoom_follow_expansion") { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@hookWithId result
                val npvc = chain.thisObject ?: return@hookWithId result
                try {
                    val view = viewField.get(npvc) as? View ?: return@hookWithId result
                    if (!view.isAttachedToWindow) return@hookWithId result
                    val token = view.windowToken ?: return@hookWithId result
                    val barState = barStateField.getInt(npvc)
                    val rawFraction = expandedFractionField.getFloat(npvc)
                    // Lockscreen keeps mExpandedFraction at 1.0f; only track shade in SHADE state (0).
                    val target = if (barState == SHADE_BAR_STATE && !rawFraction.isNaN()) {
                        rawFraction.coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val shouldSend = (target == 0f && lastSentFraction != 0f) ||
                        (target == 1f && lastSentFraction != 1f) ||
                        abs(target - lastSentFraction) >= FRACTION_DELTA_THRESHOLD
                    if (shouldSend) {
                        val wallpaperManager = cachedWallpaperManager
                            ?: view.context.getSystemService(WallpaperManager::class.java)
                                ?.also { cachedWallpaperManager = it }
                            ?: return@hookWithId result
                        setWallpaperZoomOutMethod.invoke(wallpaperManager, token, target)
                        lastSentFraction = target
                    }
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

    companion object {
        private const val QS_WALLPAPER_ANIM_UTILS = "com.android.systemui.util.QsWallpaperAnimUtils"
        private const val NOTIFICATION_PANEL_VIEW_CONTROLLER =
            "com.android.systemui.shade.NotificationPanelViewController"
        private const val SHADE_BAR_STATE = 0
        private const val FRACTION_DELTA_THRESHOLD = 0.0005f
    }
}
