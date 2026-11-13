package com.qimian233.ztool.hook.modules.systemui.qs

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.Gravity
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Centers the media output dialog launched by a broadcast (media output tile, volume long-press).
 *
 * Android 17 centers that dialog itself, so this module only installs below API 37; binding the
 * playing session and following its album-art palette lives in MediaOutputDialogColorHook.
 */
@SuppressLint("PrivateApi")
class MediaOutputDialogCenterHook : AppHookModule() {

    // Intra-thread flag: only visible within the main-thread call stack, avoiding any cross-thread synchronization
    private val launchViaTileBroadcast = ThreadLocal.withInitial { false }

    override fun getModuleName(): String = PreferenceKeys.MEDIA_OUTPUT_DIALOG_CENTER.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (Build.VERSION.SDK_INT >= 37) {
            logger.info("Media output dialog is centered natively on API 37; hook skipped")
            return
        }
        val classLoader = param.defaultClassLoader
        val receiverOk = hookReceiver(classLoader)
        val gravityOk = hookGetGravity(classLoader) or hookCreateDialog(classLoader)
        if (receiverOk && gravityOk) {
            logger.info("MediaOutputDialogCenterHook installed")
        } else {
            logger.warn(
                "MediaOutputDialogCenterHook partial: receiver=$receiverOk gravity=$gravityOk"
            )
        }
    }

    /** Maintains the flag for the duration of onReceive; both gravity hooks run inside it. */
    private fun hookReceiver(classLoader: ClassLoader): Boolean {
        return try {
            val receiverClass = classLoader.loadClass(RECEIVER_CLASS)
            val onReceive = findMethod(
                receiverClass, "onReceive",
                Context::class.java, android.content.Intent::class.java
            )
            hookWithId(onReceive, "media_output_dialog_receiver") { chain ->
                launchViaTileBroadcast.set(true)
                try {
                    chain.proceed()
                } finally {
                    launchViaTileBroadcast.set(false)
                }
            }
            true
        } catch (t: Throwable) {
            logger.error("Hook MediaOutputDialogReceiver.onReceive failed", t)
            false
        }
    }

    /** Legacy firmware kept the gravity in MediaOutputBaseDialog.getGravity. */
    private fun hookGetGravity(classLoader: ClassLoader): Boolean {
        return try {
            val dialogClass = classLoader.loadClass(BASE_DIALOG_CLASS)
            val getGravity = findMethod(dialogClass, "getGravity")
            hookWithId(getGravity, "media_output_dialog_get_gravity") { chain ->
                if (launchViaTileBroadcast.get() == true) {
                    Gravity.CENTER
                } else {
                    chain.proceed()
                }
            }
            true
        } catch (t: Throwable) {
            logger.debug("MediaOutputBaseDialog.getGravity absent: ${t.message}")
            false
        }
    }

    /**
     * SystemUIDialog.updateWindowSize applies mCustomGravity when the dialog is shown; forcing it on
     * the delegate's dialog covers firmware that keeps a non-centered default.
     */
    private fun hookCreateDialog(classLoader: ClassLoader): Boolean {
        return try {
            val delegateClass = classLoader.loadClass(DELEGATE_CLASS)
            val createDialog = findMethod(delegateClass, "createDialog")
            hookWithId(createDialog, "media_output_dialog_create_dialog") { chain ->
                val dialog = chain.proceed()
                if (launchViaTileBroadcast.get() == true && dialog != null) {
                    try {
                        findField(dialog.javaClass, "mCustomGravity")
                            .setInt(dialog, Gravity.CENTER)
                    } catch (t: Throwable) {
                        logger.warn("SystemUIDialog.mCustomGravity unavailable: ${t.message}")
                    }
                }
                dialog
            }
            true
        } catch (t: Throwable) {
            logger.debug("MediaOutputDialogDelegate.createDialog absent: ${t.message}")
            false
        }
    }

    private companion object {
        const val RECEIVER_CLASS =
            "com.android.systemui.media.dialog.MediaOutputDialogReceiver"
        const val BASE_DIALOG_CLASS =
            "com.android.systemui.media.dialog.MediaOutputBaseDialog"
        const val DELEGATE_CLASS =
            "com.android.systemui.media.dialog.MediaOutputDialogDelegate"
    }
}
