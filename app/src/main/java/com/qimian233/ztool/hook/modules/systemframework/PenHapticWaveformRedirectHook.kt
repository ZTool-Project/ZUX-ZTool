package com.qimian233.ztool.hook.modules.systemframework

import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.pen.PenHapticWaveformIds
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Rewrites pen haptic waveform ids in system_server from the user rule table, e.g.
 * play the pencil waveform for every Lenovo Pen request. Covers
 * setHapticContinuousParams / setHapticImpactParams and their Android 17 *WithStatus siblings.
 * See docs/research/lenovo-pen-haptic/waveform-kinds.md.
 */
class PenHapticWaveformRedirectHook : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.PEN_HAPTIC_WAVEFORM_REDIRECT.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val rules = PenHapticWaveformIds.parseRules(
            remotePreferences.getString(
                PreferenceKeys.PEN_HAPTIC_WAVEFORM_REDIRECT_RULES.name,
                PreferenceKeys.PEN_HAPTIC_WAVEFORM_REDIRECT_RULES.default
            ).orEmpty()
        )
        if (rules.isEmpty()) {
            logger.info("No pen haptic waveform redirect rules configured")
            return
        }

        try {
            val serviceClass = param.classLoader.loadClass(SERVICE_CLASS)
            hookContinuousParams(
                serviceClass,
                "setHapticContinuousParams",
                CONTINUOUS_HOOK_ID,
                rules
            )
            hookImpactParams(
                serviceClass,
                "setHapticImpactParams",
                IMPACT_HOOK_ID,
                rules
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                hookContinuousParams(
                    serviceClass,
                    "setHapticContinuousParamsWithStatus",
                    CONTINUOUS_WITH_STATUS_HOOK_ID,
                    rules
                )
                hookImpactParams(
                    serviceClass,
                    "setHapticImpactParamsWithStatus",
                    IMPACT_WITH_STATUS_HOOK_ID,
                    rules
                )
            }
            logger.info("Pen haptic waveform redirect installed, table: $rules")
        } catch (t: Throwable) {
            logger.error("Failed to install pen haptic waveform redirect", t)
        }
    }

    private fun hookContinuousParams(
        serviceClass: Class<*>,
        methodName: String,
        hookId: String,
        rules: Map<Int, Int>
    ) {
        val method = findMethod(
            serviceClass,
            methodName,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        hookWithId(method, hookId) { chain ->
            val target = rules[chain.args[0] as Int]
            if (target == null) {
                chain.proceed()
            } else {
                chain.proceed(arrayOf<Any?>(target, chain.args[1], chain.args[2]))
            }
        }
    }

    private fun hookImpactParams(
        serviceClass: Class<*>,
        methodName: String,
        hookId: String,
        rules: Map<Int, Int>
    ) {
        val method = findMethod(
            serviceClass,
            methodName,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        hookWithId(method, hookId) { chain ->
            val target = rules[chain.args[0] as Int]
            if (target == null) {
                chain.proceed()
            } else {
                chain.proceed(
                    arrayOf<Any?>(target, chain.args[1], chain.args[2], chain.args[3])
                )
            }
        }
    }

    private companion object {
        const val SERVICE_CLASS =
            "com.zui.server.input.styluspen.pen.bluetooth.haptic.ZuiPenHapticService"
        const val CONTINUOUS_HOOK_ID = "pen_haptic_waveform_redirect_continuous"
        const val IMPACT_HOOK_ID = "pen_haptic_waveform_redirect_impact"
        const val CONTINUOUS_WITH_STATUS_HOOK_ID =
            "pen_haptic_waveform_redirect_continuous_status"
        const val IMPACT_WITH_STATUS_HOOK_ID =
            "pen_haptic_waveform_redirect_impact_status"
    }
}
