package com.qimian233.ztool.hook.modules.systemframework

import android.content.Intent
import android.content.pm.ResolveInfo
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.resolver.ResolverRules
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/** Bypasses ResolverActivity when Open-With block rules leave exactly one eligible target. */
class ResolverSingleTargetHook : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.RESOLVER_MENU_ENHANCE.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val helperClass = param.classLoader.loadClass("com.android.server.pm.ResolveIntentHelper")
            val chooseBestMethod = helperClass.declaredMethods.firstOrNull { method ->
                method.name == "chooseBestActivity" && method.parameterCount == 8
            } ?: throw NoSuchMethodException("ResolveIntentHelper.chooseBestActivity(8) not found")

            hookWithId(chooseBestMethod, "resolver_single_target_direct_launch") { chain ->
                val result = chain.proceed() as? ResolveInfo ?: return@hookWithId null
                val chosenName = result.activityInfo?.name ?: return@hookWithId result
                if (!chosenName.endsWith("ResolverActivity")) return@hookWithId result

                val prefs = remotePreferences
                if (!prefs.getBoolean(PreferenceKeys.RESOLVER_MENU_ENHANCE.name, false)) {
                    return@hookWithId result
                }
                if (!prefs.getBoolean(
                        PreferenceKeys.RESOLVER_OPEN_AUTO_LAUNCH_SINGLE.name,
                        PreferenceKeys.RESOLVER_OPEN_AUTO_LAUNCH_SINGLE.default
                    )
                ) {
                    return@hookWithId result
                }

                val blocked = ResolverRules.decodeList(
                    prefs.getString(
                        PreferenceKeys.RESOLVER_OPEN_BLOCKED.name,
                        PreferenceKeys.RESOLVER_OPEN_BLOCKED.default
                    )
                ).toHashSet()
                if (blocked.isEmpty()) return@hookWithId result

                val intent = chain.args[1] as? Intent ?: return@hookWithId result
                if (intent.action == Intent.ACTION_CHOOSER || intent.component != null) {
                    return@hookWithId result
                }

                val candidates = (chain.args[5] as? List<*>)?.filterIsInstance<ResolveInfo>()
                    ?: return@hookWithId result
                if (candidates.size <= 1) return@hookWithId result

                val top = candidates[0]
                val topGroup = candidates.filter { candidate ->
                    candidate.priority == top.priority &&
                        candidate.preferredOrder == top.preferredOrder &&
                        candidate.isDefault == top.isDefault
                }
                val eligible = topGroup.filter { candidate ->
                    val ai = candidate.activityInfo ?: return@filter false
                    !ResolverRules.isComponentBlocked(ai.packageName, ai.name, blocked)
                }
                if (eligible.size == 1) {
                    logger.debug(
                        "Single target after block filter, bypassing ResolverActivity: " +
                            "${eligible[0].activityInfo?.packageName}/${eligible[0].activityInfo?.name}"
                    )
                    return@hookWithId eligible[0]
                }
                result
            }
            logger.info("Hooked ResolveIntentHelper.chooseBestActivity")
        } catch (t: Throwable) {
            logger.error("Failed hooking ResolveIntentHelper.chooseBestActivity", t)
        }
    }
}
