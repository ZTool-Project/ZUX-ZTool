package com.qimian233.ztool.hook.modules.systemui.keyguard

import android.annotation.SuppressLint
import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface

/** Bypasses ZUI's legacy weak face-biometric timeout gates through Android 16. */
// See docs/research/face_auth_four_hour_idle_timeout.md for legacy targets.
@SuppressLint("PrivateApi")
class BypassFaceAuthTimeout : AppHookModule() {

    companion object {
        private val SYSTEMUI_PACKAGE = ScopeKeys.SYSTEM_UI.packageName
        private const val HOOK_ID = "zui_face_auth_timeout_bypass"
        private const val LEGACY_DELEGATE_CLASS = "com.android.keyguard.ZuiFaceAuthDelegate"
        private const val LEGACY_MANAGER_CLASS = "com.android.keyguard.KeyguardFaceUnlockManager"
    }

    private var warnedNoHookPoint = false

    override fun getModuleName(): String = PreferenceKeys.BYPASS_FACE_AUTH_TIMEOUT.name

    override fun getTargetPackages(): Array<String> = arrayOf(SYSTEMUI_PACKAGE)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != SYSTEMUI_PACKAGE) return
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.BAKLAVA) {
            warnNoHookPoint()
            return
        }
        logger.info("Loading module BypassFaceAuthTimeout.")

        val classLoader = param.defaultClassLoader
        val delegateClass = loadClassOrNull(classLoader, LEGACY_DELEGATE_CLASS)
        if (delegateClass == null) {
            warnNoHookPoint()
            return
        }

        try {
            hookLegacyDelegate(classLoader, delegateClass)
        } catch (e: Throwable) {
            logger.error("Failed to hook $LEGACY_DELEGATE_CLASS.checkAndStartFaceDetecting", e)
        }
    }

    private fun hookLegacyDelegate(classLoader: ClassLoader, delegateClass: Class<*>) {
        val managerClass = classLoader.loadClass(LEGACY_MANAGER_CLASS)

        val checkMethod = findMethod(
            delegateClass,
            "checkAndStartFaceDetecting",
            Boolean::class.javaPrimitiveType
        )
        val currentTimeOnField = findField(delegateClass, "currentTimeOn")
        val secureTimeField = findField(delegateClass, "secureTime")
        val additionSecureTimeField = findField(delegateClass, "mAdditionSecureTime")
        val faceUnlockManagerField = findField(delegateClass, "mFaceUnlockManager")
        val securityTimeField = findField(managerClass, "mSecurityTime")

        hookWithId(checkMethod, HOOK_ID) { chain ->
            val delegate = chain.thisObject
            try {
                currentTimeOnField.setLong(delegate, System.currentTimeMillis())
                secureTimeField.setLong(delegate, 0L)
                additionSecureTimeField.setLong(delegate, 0L)
                val manager = faceUnlockManagerField.get(delegate)
                if (securityTimeField.getBoolean(manager)) {
                    logger.info("Cleared sticky mSecurityTime before checkAndStartFaceDetecting")
                    securityTimeField.setBoolean(manager, false)
                }
            } catch (t: Throwable) {
                logger.error("Failed to neutralize face auth timeout bases", t)
            }
            chain.proceed()
            try {
                val manager = faceUnlockManagerField.get(delegate)
                if (securityTimeField.getBoolean(manager)) {
                    logger.info("Cleared mSecurityTime after checkAndStartFaceDetecting")
                    securityTimeField.setBoolean(manager, false)
                }
            } catch (t: Throwable) {
                logger.error("Failed to clear mSecurityTime after check", t)
            }
            null
        }

        logger.info("BypassFaceAuthTimeout: $LEGACY_DELEGATE_CLASS#$HOOK_ID hooked successfully.")
    }

    private fun warnNoHookPoint() {
        if (warnedNoHookPoint) return
        warnedNoHookPoint = true
        logger.warn(
            "BypassFaceAuthTimeout: legacy timeout bypass is unavailable; " +
                "use Skip weak biometric timeout on the System framework feature page."
        )
    }

    private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? =
        try {
            classLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }
}
