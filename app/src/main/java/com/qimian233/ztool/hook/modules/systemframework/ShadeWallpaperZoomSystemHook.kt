package com.qimian233.ztool.hook.modules.systemframework

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.SurfaceControl
import android.view.WindowManager
import com.qimian233.ztool.data.keys.BoolKey
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.StringKey
import com.qimian233.ztool.data.keys.WallpaperZoomModes
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field
import java.util.function.Consumer

/**
 * Applies centered wallpaper scale in real time when SystemUI or Launcher
 * dispatches expansion progress through WallpaperController.setWallpaperZoomOut.
 */
@SuppressLint("PrivateApi")
class ShadeWallpaperZoomSystemHook : SystemHookModule() {

    @Volatile
    private var lastProgress = 0f

    @Volatile
    private var cachedZoomSize = DEFAULT_ZOOM_SIZE

    @Volatile
    private var lastZoomSizeFetchMs = 0L

    override fun getModuleName(): String = PreferenceKeys.SHADE_WALLPAPER_ZOOM_FOLLOW.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun isEnabled(): Boolean = try {
        val prefs = remotePreferences
        prefs.getBoolean(
            PreferenceKeys.SHADE_WALLPAPER_ZOOM_FOLLOW.name,
            PreferenceKeys.SHADE_WALLPAPER_ZOOM_FOLLOW.default
        ) || prefs.getBoolean(
            PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM.name,
            PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM.default
        )
    } catch (_: Throwable) {
        false
    }

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        try {
            val wallpaperControllerClass = classLoader.loadClass(WALLPAPER_CONTROLLER)
            val windowStateClass = classLoader.loadClass(WINDOW_STATE)

            val setWallpaperZoomOutMethod = findMethod(
                wallpaperControllerClass,
                "setWallpaperZoomOut",
                windowStateClass,
                Float::class.javaPrimitiveType
            )
            val mDisplayContentField = findField(wallpaperControllerClass, "mDisplayContent")
            val mServiceField = findField(wallpaperControllerClass, "mService")
            val mZuiAnimatorField = optionalField(wallpaperControllerClass, "mZuiWallpaperZoomAnimator")

            val forAllWindowsMethod = findMethod(
                mDisplayContentField.type,
                "forAllWindows",
                Consumer::class.java,
                Boolean::class.javaPrimitiveType
            )
            val mContextField = findField(mServiceField.type, "mContext")
            val mHandlerField = optionalField(mServiceField.type, "mH")

            val mAttrsField = findField(windowStateClass, "mAttrs")
            val mIsWallpaperField = findField(windowStateClass, "mIsWallpaper")
            val mRequestedWidthField = findField(windowStateClass, "mRequestedWidth")
            val mRequestedHeightField = findField(windowStateClass, "mRequestedHeight")
            val mHScaleField = findField(windowStateClass, "mHScale")
            val mXOffsetField = findField(windowStateClass, "mXOffset")
            val mYOffsetField = findField(windowStateClass, "mYOffset")
            val getParentFrameMethod = findMethod(windowStateClass, "getParentFrame")
            val getSurfaceControlMethod = findMethod(windowStateClass, "getSurfaceControl")
            val isVisibleMethod = findMethod(windowStateClass, "isVisible")

            hookWithId(setWallpaperZoomOutMethod, "shade_wallpaper_zoom_wm_apply") { chain ->
                if (!isEnabled()) {
                    return@hookWithId chain.proceed()
                }
                val win = chain.getArg(0) ?: return@hookWithId chain.proceed()
                val attrs = mAttrsField.get(win) as? WindowManager.LayoutParams
                    ?: return@hookWithId chain.proceed()
                val isShadeSource = attrs.type == TYPE_NOTIFICATION_SHADE &&
                    isContinuousEnabled(
                        PreferenceKeys.SHADE_WALLPAPER_ZOOM_FOLLOW,
                        PreferenceKeys.SHADE_WALLPAPER_ZOOM_MODE
                    )
                val isLauncherSource = attrs.type == TYPE_BASE_APPLICATION &&
                    attrs.packageName == ScopeKeys.LAUNCHER.packageName &&
                    !(attrs.title?.toString()?.contains("WidgetPicker") == true) &&
                    isContinuousEnabled(
                        PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM,
                        PreferenceKeys.LAUNCHER_QUICKSTEP_WALLPAPER_ZOOM_MODE
                    )
                if (!isShadeSource && !isLauncherSource) {
                    return@hookWithId chain.proceed()
                }

                val controller = chain.thisObject ?: return@hookWithId null
                val progress = WallpaperZoomModes.decodeProgress(chain.getArg(1) as Float)
                val service = mServiceField.get(controller)

                val now = SystemClock.uptimeMillis()
                val transitioningFromZero = lastProgress == 0f && progress > 0f
                if (transitioningFromZero || now - lastZoomSizeFetchMs >= ZOOM_SIZE_CACHE_TTL_MS) {
                    val context = service?.let { mContextField.get(it) as? Context }
                    if (context != null) {
                        cachedZoomSize = try {
                            Settings.System.getFloat(
                                context.contentResolver,
                                SETTING_WALLPAPER_ZOOM_SIZE,
                                DEFAULT_ZOOM_SIZE
                            )
                        } catch (_: Throwable) {
                            DEFAULT_ZOOM_SIZE
                        }
                        lastZoomSizeFetchMs = now
                    }
                }
                val zoomSize = cachedZoomSize
                lastProgress = progress

                val animator = mZuiAnimatorField?.get(controller) as? ValueAnimator
                if (animator != null && animator.isStarted) {
                    val handler = service?.let { mHandlerField?.get(it) as? Handler }
                    handler?.post {
                        if (animator.isStarted) {
                            animator.removeAllUpdateListeners()
                            animator.cancel()
                        }
                    }
                }

                val displayContent = mDisplayContentField.get(controller) ?: return@hookWithId null
                SurfaceControl.Transaction().use { transaction ->
                    var updated = false
                    val consumer = Consumer<Any> { wpWin ->
                        if (!mIsWallpaperField.getBoolean(wpWin)) return@Consumer
                        if (progress > 0f && !(isVisibleMethod.invoke(wpWin) as Boolean)) {
                            return@Consumer
                        }
                        val sc = getSurfaceControlMethod.invoke(wpWin) as? SurfaceControl
                            ?: return@Consumer
                        if (!sc.isValid) return@Consumer

                        var w = mRequestedWidthField.getInt(wpWin)
                        var h = mRequestedHeightField.getInt(wpWin)
                        if (w <= 0 || h <= 0) {
                            val parentFrame = getParentFrameMethod.invoke(wpWin) as? Rect
                                ?: return@Consumer
                            w = parentFrame.width()
                            h = parentFrame.height()
                        }
                        val rawHScale = mHScaleField.getFloat(wpWin)
                        val baseScale = if (rawHScale > 0f) rawHScale else 1f
                        val xOffset = mXOffsetField.getInt(wpWin)
                        val yOffset = mYOffsetField.getInt(wpWin)

                        val targetScale = baseScale + progress * zoomSize
                        val posX = -((progress * zoomSize * w) / 2f) + xOffset
                        val posY = -((progress * zoomSize * h) / 2f) + yOffset
                        transaction.setPosition(sc, posX, posY)
                        transaction.setScale(sc, targetScale, targetScale)
                        updated = true
                    }
                    forAllWindowsMethod.invoke(displayContent, consumer, true)
                    if (updated) {
                        transaction.apply()
                    }
                }
                null
            }
            logger.info("ShadeWallpaperZoomSystemHook installed")
        } catch (t: Throwable) {
            logger.error("Failed to install ShadeWallpaperZoomSystemHook", t)
        }
    }

    private fun isContinuousEnabled(switchKey: BoolKey, modeKey: StringKey): Boolean = try {
        val prefs = remotePreferences
        if (!prefs.getBoolean(switchKey.name, switchKey.default)) {
            false
        } else {
            WallpaperZoomModes.isContinuous(prefs.getString(modeKey.name, modeKey.default))
        }
    } catch (_: Throwable) {
        false
    }

    private fun optionalField(startClass: Class<*>, name: String): Field? =
        try {
            findField(startClass, name)
        } catch (_: NoSuchFieldException) {
            null
        }

    companion object {
        private const val WALLPAPER_CONTROLLER = "com.android.server.wm.WallpaperController"
        private const val WINDOW_STATE = "com.android.server.wm.WindowState"
        private const val TYPE_BASE_APPLICATION = 1
        private const val TYPE_NOTIFICATION_SHADE = 2040
        private const val SETTING_WALLPAPER_ZOOM_SIZE = "wallpaper_rate_1"
        private const val DEFAULT_ZOOM_SIZE = 0.1f
        private const val ZOOM_SIZE_CACHE_TTL_MS = 5000L
    }
}
