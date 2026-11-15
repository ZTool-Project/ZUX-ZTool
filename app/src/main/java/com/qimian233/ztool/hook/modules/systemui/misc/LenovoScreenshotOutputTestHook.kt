package com.qimian233.ztool.hook.modules.systemui.misc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qimian233.ztool.BuildConfig
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import kotlin.concurrent.thread

/** Exports completed Lenovo screenshot tiles for development-only verification. */
class LenovoScreenshotOutputTestHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.TEST_HOOK.name
    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (!BuildConfig.DEBUG || android.os.Build.VERSION.SDK_INT != 37) return
        if (BuildConfig.APPLICATION_ID == ScopeKeys.ZTOOL.packageName) return
        val callbackClass = param.defaultClassLoader.loadClass(
            "com.motorola.android.systemui.quickscreenshot.ui.LongScreenshotOverlay\$3"
        )
        val callback = findMethod(callbackClass, "onScreenShotUpdate", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val outer = findField(callbackClass, "this\$0")
        hookWithId(callback, "lenovo_test_export") { chain ->
            val result = chain.proceed()
            if (chain.getArg(2) == true) {
                val overlay = outer.get(chain.thisObject)!!
                // The preview receives queued tiles after the binder callback returns.
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        val preview = findField(overlay.javaClass, "mScrollView").get(overlay)!!
                        val drawable = findField(preview.javaClass, "mBitmapArrayDrawable").get(preview)!!
                        val snapshot = synchronized(drawable) {
                            val tiles = findField(drawable.javaClass, "mBitmapList").get(drawable) as List<*>
                            val bitmaps = tiles.map { it as Bitmap }
                            require(bitmaps.isNotEmpty()) { "No completed screenshot tiles" }
                            val width = bitmaps.first().width
                            require(bitmaps.all { it.width == width }) { "Tile widths differ" }
                            Bitmap.createBitmap(width, bitmaps.sumOf { it.height }, Bitmap.Config.ARGB_8888).also {
                                val canvas = Canvas(it)
                                var top = 0f
                                bitmaps.forEach { tile ->
                                    canvas.drawBitmap(tile, 0f, top, null)
                                    top += tile.height
                                }
                            }
                        }
                        val context = findField(overlay.javaClass, "mContext").get(overlay) as Context
                        val file = File(context.filesDir, "lenovo-screenshot-probe-${System.currentTimeMillis()}.png")
                        thread(name = "LenovoScreenshotExport") {
                            try {
                                file.outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                Log.i(TAG, "export=${file.absolutePath} size=${snapshot.width}x${snapshot.height}")
                            } catch (error: Exception) {
                                Log.e(TAG, "Final preview write failed", error)
                            } finally {
                                snapshot.recycle()
                            }
                        }
                    }.onFailure { Log.e(TAG, "Final preview export failed", it) }
                }, 1000L)
            }
            result
        }
        Log.i(TAG, "Installed preview export")
    }

    companion object {
        private const val TAG = "ZToolLenovoScrollTest"
    }
}
