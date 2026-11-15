package com.qimian233.ztool.hook.modules.systemui.misc

import android.content.res.Configuration
import android.net.Uri
import android.os.Environment
import android.view.View
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.SystemUiDexTargets
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File

/** Replaces the charging animation video with an orientation-specific local file. */
// See docs/research/systemui_charging_animation_android17.md.
class CustomChargeAnimation : AppHookModule() {

    companion object {
        private val SYSTEMUI_PACKAGE = ScopeKeys.SYSTEM_UI.packageName
        private const val CUSTOM_VIDEO_DIR = "/Download/ZTool"
        private const val VIDEO_PORTRAIT = "charging_animation_portrait.mp4"
        private const val VIDEO_LAND = "charging_animation_land.mp4"
    }

    override fun getModuleName(): String = PreferenceKeys.CUSTOM_CHARGE_ANIMATION.name

    override fun getTargetPackages(): Array<String> = arrayOf(SYSTEMUI_PACKAGE)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != SYSTEMUI_PACKAGE) return
        logger.info("Loading module CustomChargeAnimation.")

        try {
            val chargingVideoViewClass = SystemUiDexTargets.loadFirstClass(
                param.defaultClassLoader, SystemUiDexTargets.CHARGING_VIDEO_VIEWS
            )
            val videoViewClass = param.defaultClassLoader.loadClass("android.widget.VideoView")

            // Must hook the two-argument setVideoURI(Uri, Map) version: the single-argument
            // version internally calls this.setVideoURI(uri, null) using its local uri
            // variable, so modifying args[0] would not affect that local variable.
            val setVideoURIMethod = videoViewClass.getDeclaredMethod(
                "setVideoURI",
                Uri::class.java,
                Map::class.java
            )

            hookWithId(setVideoURIMethod, "set_video_uri") {  chain ->
                val thisObject = chain.thisObject
                if (chargingVideoViewClass.isInstance(thisObject)) {
                    val originalUri = chain.args[0] as Uri?
                    val view = thisObject as View
                    val isLandscape =
                        view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val fileName = if (isLandscape) VIDEO_LAND else VIDEO_PORTRAIT
                    val filePath =
                        Environment.getExternalStorageDirectory().path +
                            CUSTOM_VIDEO_DIR + "/" + fileName
                    val file = File(filePath)

                    if (file.exists()) {
                        val customUri = Uri.fromFile(file)
                        logger.debug("CustomChargeAnimation: redirecting " +
                            "original=$originalUri -> $filePath")
                        // Build new args and pass them explicitly to proceed, since in-place
                        // modification of chain.args may be ignored
                        val newArgs = chain.args.toMutableList()
                        newArgs[0] = customUri
                        chain.proceed(newArgs.toTypedArray())
                    } else {
                        logger.warn("CustomChargeAnimation: $fileName not found at $filePath, " +
                            "using default $originalUri.")
                        chain.proceed()
                    }
                } else {
                    chain.proceed()
                }
            }

            logger.info("CustomChargeAnimation: VideoView.setVideoURI(Uri, Map) hooked successfully.")
        } catch (e: Throwable) {
            logger.error("Failed to hook VideoView.setVideoURI", e)
        }
    }
}
