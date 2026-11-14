package com.qimian233.ztool.hook.modules.systemui.keyguard

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** Enables Lenovo AOD on devices without the advertised capability. */
@SuppressLint("PrivateApi")
class ForceLenovoAOD : ForcedAodHookModule() {
    override fun getModuleName(): String = PreferenceKeys.FORCE_LENOVO_AOD.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        hookMinimumDozeBrightness(classLoader)
        hookAODChecks()
        // Android 17 inlines Lenovo scheduling into DozeTriggers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            hookAndroid17Scheduling(classLoader)
        } else {
            hookZuiDozeTriggers(classLoader)
        }
    }

    private fun hookAndroid17Scheduling(classLoader: ClassLoader) {
        try {
            val triggersClass = classLoader.loadClass(DOZE_TRIGGERS_CLASS)
            val stateClass = classLoader.loadClass(DOZE_STATE_CLASS)
            val initializedState = stateClass.getField("INITIALIZED").get(null)
            val transition = findMethod(triggersClass, "transitionTo", stateClass, stateClass)
            val zuiTriggersField = findField(triggersClass, "mZuiDozeTriggers")
            val machineField = findField(triggersClass, "mMachine")
            val zuiMachineField = findField(machineField.type, "mZuiDozeMachine")
            val cachedSensorField = findField(zuiMachineField.type, "mCachedMotionSensor")
            val scheduledField = findField(zuiTriggersField.type, "mIsAodScheduled")
            val contextField = findField(zuiTriggersField.type, "mContext")
            val isAodEnabled = findMethod(
                classLoader.loadClass(X_SYSTEM_UTIL_CLASS), "isAodEnabled", Context::class.java
            )

            hookWithId(transition, "lenovo_aod_initialized_sensor_bypass") { chain ->
                val result = chain.proceed()
                if (chain.args[1] != initializedState || !isEnabled()) {
                    return@hookWithId result
                }
                try {
                    val triggers = chain.thisObject
                    val zuiTriggers = zuiTriggersField.get(triggers) ?: return@hookWithId result
                    if (scheduledField.getBoolean(zuiTriggers)) return@hookWithId result
                    val machine = machineField.get(triggers) ?: return@hookWithId result
                    val zuiMachine = zuiMachineField.get(machine) ?: return@hookWithId result
                    if (cachedSensorField.get(zuiMachine) != null) return@hookWithId result
                    val context = contextField.get(zuiTriggers) as? Context
                        ?: return@hookWithId result
                    if (isAodEnabled.invoke(null, context) != true) return@hookWithId result
                    val powerManager = context.getSystemService(PowerManager::class.java)
                    if (powerManager?.isPowerSaveMode == true && Settings.Global.getInt(
                            context.contentResolver, POWER_SAVE_DISABLE_AOD_SETTING, 1
                        ) != 0
                    ) {
                        return@hookWithId result
                    }
                    // Set this only after INITIALIZED, before DOZE consumes it; retain host pulse timing.
                    // See docs/research/lenovo_aod_android17.md.
                    scheduledField.setBoolean(zuiTriggers, true)
                    logger.debug("Scheduled Lenovo AOD without a vendor motion sensor")
                } catch (t: Throwable) {
                    logger.error("Failed to schedule Lenovo AOD without a motion sensor", t)
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook Android 17 Lenovo AOD scheduling", t)
        }
    }

    private fun hookZuiDozeTriggers(classLoader: ClassLoader) {
        try {
            val triggersClass = classLoader.loadClass(ZUI_DOZE_TRIGGERS_CLASS)
            val startAodField = findField(triggersClass, "mIsGoingToStartAOD")
            val ctor = triggersClass
                .getDeclaredConstructor(
                    classLoader.loadClass(DOZE_TRIGGERS_CLASS),
                    Context::class.java
                )
            hookWithId(ctor, "lenovo_aod_legacy_ctor") { chain ->
                val result = chain.proceed()
                startAodField.setBoolean(chain.thisObject, true)
                logger.debug("ZuiDozeTriggers constructed, forced mIsGoingToStartAOD = true")
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook ZuiDozeTriggers: ", t)
        }
    }

    private fun hookAODChecks() {
        try {
            val getIntMethod = Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            hookWithId(getIntMethod, "lenovo_aod_support_property") { chain ->
                val key = chain.args[0] as String
                if ("ro.config.aod.support" == key) {
                    logger.debug("Bypassed ro.config.aod.support check")
                    return@hookWithId 1
                }
                chain.proceed()
            }

            @SuppressLint("DiscouragedPrivateApi") val getIntForUserMethod =
                Settings.System::class.java
                    .getDeclaredMethod(
                        "getIntForUser",
                        ContentResolver::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
            hookWithId(
                getIntForUserMethod,
                "lenovo_aod_enabled_setting"
            ) { chain ->
                val setting = chain.args[1] as String
                if ("always_on_display" == setting) {
                    logger.debug("Bypassed always_on_display setting check")
                    return@hookWithId 1
                }
                chain.proceed()
            }
        } catch (t: Exception) {
            logger.error("Failed to hook AOD checks: ", t)
        }
    }

    companion object {
        private const val ZUI_DOZE_TRIGGERS_CLASS = "com.android.systemui.doze.ZuiDozeTriggers"
        private const val DOZE_TRIGGERS_CLASS = "com.android.systemui.doze.DozeTriggers"
        private const val DOZE_STATE_CLASS = $$"com.android.systemui.doze.DozeMachine$State"
        private const val X_SYSTEM_UTIL_CLASS = "com.android.systemui.utils.XSystemUtil"
        private const val POWER_SAVE_DISABLE_AOD_SETTING =
            "zui_power_save_disable_aod_notification_enabled"
    }
}
