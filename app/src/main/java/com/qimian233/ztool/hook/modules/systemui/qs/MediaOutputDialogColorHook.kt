package com.qimian233.ztool.hook.modules.systemui.qs

import android.annotation.SuppressLint
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.UserHandle
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Binds the playing media session to the output dialog launched by a broadcast and switches the
 * dialog to its album-art palette.
 *
 * `MediaOutputDialogReceiver` always passes `useSystemColors = true` plus whatever package name the
 * sender supplied, so every broadcast launch is stuck with the system colour scheme. Wrapping
 * `onReceive` marks those launches; `createAndShow` then substitutes the active session package and
 * flips both dialog flags.
 */
@SuppressLint("PrivateApi")
class MediaOutputDialogColorHook : AppHookModule() {

    // Intra-thread flag: the receiver calls createAndShow synchronously on the same thread
    private val broadcastLaunch = ThreadLocal.withInitial { false }

    private val receiverContext = ThreadLocal<Context?>()

    override fun getModuleName(): String = PreferenceKeys.MEDIA_OUTPUT_DIALOG_COLOR.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    // The base class falls back to "disabled" for an unset key; this feature is on by default
    override fun isEnabled(): Boolean = runCatching {
        xposed.getRemotePreferences(PREFS_NAME)
            .getBoolean(getModuleName(), PreferenceKeys.MEDIA_OUTPUT_DIALOG_COLOR.default)
    }.getOrDefault(PreferenceKeys.MEDIA_OUTPUT_DIALOG_COLOR.default)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val receiverOk = hookReceiver(classLoader)
        val createOk = hookCreateAndShow(classLoader)
        if (receiverOk && createOk) {
            logger.info("MediaOutputDialogColorHook installed")
        } else {
            logger.warn(
                "MediaOutputDialogColorHook partial: receiver=$receiverOk createAndShow=$createOk"
            )
        }
    }

    /**
     * Marks the broadcast dispatch and keeps the receiver context for the session query; both are
     * consumed synchronously by the createAndShow hook.
     */
    private fun hookReceiver(classLoader: ClassLoader): Boolean {
        return try {
            val receiverClass = classLoader.loadClass(RECEIVER_CLASS)
            val onReceive = findMethod(
                receiverClass, "onReceive",
                Context::class.java, android.content.Intent::class.java
            )
            hookWithId(onReceive, "media_output_color_receiver") { chain ->
                broadcastLaunch.set(true)
                receiverContext.set(chain.args.getOrNull(0) as? Context)
                try {
                    chain.proceed()
                } finally {
                    broadcastLaunch.set(false)
                    receiverContext.remove()
                }
            }
            true
        } catch (t: Throwable) {
            logger.error("Hook MediaOutputDialogReceiver.onReceive failed", t)
            false
        }
    }

    /**
     * Applies the session package and the album-art colour choice to broadcast launches only;
     * in-app callers keep the arguments they built.
     */
    private fun hookCreateAndShow(classLoader: ClassLoader): Boolean {
        val managerClass = try {
            classLoader.loadClass(MANAGER_CLASS)
        } catch (t: Throwable) {
            logger.error("$MANAGER_CLASS not found", t)
            return false
        }
        val layout = findCreateAndShow(classLoader, managerClass) ?: return false
        hookWithId(layout.method, "media_output_color_create_and_show") { chain ->
            if (broadcastLaunch.get() != true) return@hookWithId chain.proceed()
            val updated = chain.args.toMutableList()
            findActiveMediaPackage(chain.thisObject)?.let {
                updated[layout.pkgIndex] = it
                logger.debug("Media output dialog bound to $it")
            }
            layout.metadataIndex?.let { updated[it] = true }
            layout.systemColorsIndex?.let { updated[it] = false }
            chain.proceed(updated.toTypedArray())
        }
        return true
    }

    /**
     * Resolves createAndShow for the current firmware: eight arguments with UserHandle first on
     * Android 17, six arguments with the package name first on older builds.
     */
    private fun findCreateAndShow(
        classLoader: ClassLoader,
        managerClass: Class<*>
    ): CreateAndShowLayout? {
        val controllerClass = try {
            classLoader.loadClass(CONTROLLER_CLASS)
        } catch (t: Throwable) {
            logger.error("$CONTROLLER_CLASS not found", t)
            return null
        }
        val switchingTypeClass = runCatching {
            classLoader.loadClass(SWITCHING_TYPE_CLASS)
        }.getOrNull()
        if (switchingTypeClass != null) {
            val method = runCatching {
                findMethod(
                    managerClass, "createAndShow",
                    UserHandle::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    controllerClass,
                    Boolean::class.javaPrimitiveType!!,
                    android.media.session.MediaSession.Token::class.java,
                    switchingTypeClass,
                    Boolean::class.javaPrimitiveType!!
                )
            }.getOrNull()
            if (method != null) return CreateAndShowLayout(method, 1, 4, 7)
        }
        val legacy = runCatching {
            findMethod(
                managerClass, "createAndShow",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                controllerClass,
                Boolean::class.javaPrimitiveType!!,
                UserHandle::class.java,
                android.media.session.MediaSession.Token::class.java
            )
        }.getOrNull() ?: run {
            logger.error("MediaOutputDialogManager.createAndShow not found")
            return null
        }
        return CreateAndShowLayout(legacy, 0, 3, null)
    }

    /**
     * Package name of the media session the user is listening to: a playing session wins, then a
     * buffering one, then any active session. Uses the hidden getActiveSessionsForUser (SystemUI
     * holds MEDIA_CONTENT_CONTROL).
     */
    private fun findActiveMediaPackage(manager: Any?): String? {
        val context = resolveContext(manager) ?: run {
            logger.warn("Media output dialog context unavailable; keeping the sender package")
            return null
        }
        return try {
            val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val user = android.os.Process.myUserHandle()
            val method = MediaSessionManager::class.java.methods.firstOrNull {
                it.name == "getActiveSessionsForUser"
            } ?: run {
                logger.warn("getActiveSessionsForUser not found on this firmware")
                return null
            }
            @Suppress("UNCHECKED_CAST")
            val sessions = method.invoke(sm, null, user) as List<MediaController>
            val preferred = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            (preferred ?: sessions.firstOrNull())?.packageName
        } catch (t: Throwable) {
            logger.warn("findActiveMediaPackage failed: ${t.message}")
            null
        }
    }

    /**
     * Context for the session query: the receiver's own context on the broadcast path, then the
     * manager context (legacy layout) or the last delegate's context (Android 17 layout).
     */
    private fun resolveContext(manager: Any?): Context? {
        receiverContext.get()?.let { return it }
        if (manager == null) return null
        contextFieldOf(manager.javaClass)?.let { field ->
            (runCatching { field.get(manager) }.getOrNull() as? Context)?.let { return it }
        }
        val delegate = runCatching {
            findField(manager.javaClass, "mediaOutputDialogDelegate").get(manager)
        }.getOrNull()
        if (delegate != null) {
            contextFieldOf(delegate.javaClass)?.let { field ->
                (runCatching { field.get(delegate) }.getOrNull() as? Context)?.let { return it }
            }
        }
        return null
    }

    private fun contextFieldOf(owner: Class<*>): Field? {
        var type: Class<*>? = owner
        while (type != null) {
            type.declaredFields.firstOrNull {
                Context::class.java.isAssignableFrom(it.type)
            }?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        return null
    }

    /** Argument layout of the resolved MediaOutputDialogManager.createAndShow. */
    private class CreateAndShowLayout(
        val method: Method,
        val pkgIndex: Int,
        val metadataIndex: Int?,
        val systemColorsIndex: Int?
    )

    private companion object {
        const val RECEIVER_CLASS =
            "com.android.systemui.media.dialog.MediaOutputDialogReceiver"
        const val MANAGER_CLASS =
            "com.android.systemui.media.dialog.MediaOutputDialogManager"
        const val SWITCHING_TYPE_CLASS =
            "com.android.systemui.media.dialog.MediaSwitchingType"
        const val CONTROLLER_CLASS =
            $$"com.android.systemui.animation.DialogTransitionAnimator$Controller"
    }
}
