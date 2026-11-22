package com.qimian233.ztool.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.qimian233.ztool.XposedServiceBridge
import com.qimian233.ztool.data.keys.PreferenceKeys
import java.util.TreeMap

/**
 * Local-first SharedPreferences wrapper for `xposed_module_config`.
 *
 * Reads and writes the app-local file as the single source of truth, mirrors
 * mutations to LSPosed remote preferences when bound, and reconciles diffs
 * through [onServiceBind].
 */
class ModulePreferencesUtils(
    context: Context
) {
    private val appContext: Context = context.applicationContext ?: context
    private val engine: ModulePreferencesSyncEngine = getOrCreateEngine(appContext)

    /** True when the LSPosed remote preferences service is currently reachable. */
    val isRemoteSyncAvailable: Boolean
        get() = engine.isRemoteAvailable()

    /** True when [featureName] is present in local preferences. */
    fun containsSetting(featureName: String): Boolean = engine.containsSetting(featureName)

    /** Returns the raw stored value for [featureName], or null if absent. */
    fun readRawSetting(featureName: String): Any? = engine.readRawSetting(featureName)

    // Boolean

    fun loadBooleanSetting(featureName: String, defaultValue: Boolean): Boolean =
        engine.loadBooleanSetting(featureName, defaultValue)

    fun saveBooleanSetting(featureName: String, value: Boolean): Boolean =
        engine.saveBooleanSetting(featureName, value)

    // String

    fun loadStringSetting(featureName: String, defaultValue: String): String =
        engine.loadStringSetting(featureName, defaultValue)

    fun saveStringSetting(featureName: String, value: String) {
        engine.saveStringSetting(featureName, value)
    }

    // Int

    fun saveIntegerSetting(featureName: String, value: Int) {
        engine.saveIntegerSetting(featureName, value)
    }

    fun loadIntegerSetting(featureName: String, defaultValue: Int): Int =
        engine.loadIntegerSetting(featureName, defaultValue)

    // Float

    fun saveFloatSetting(featureName: String, value: Float) {
        engine.saveFloatSetting(featureName, value)
    }

    fun loadFloatSetting(featureName: String, defaultValue: Float): Float =
        engine.loadFloatSetting(featureName, defaultValue)

    /** Removes [featureName] from local preferences and mirrors the deletion to remote. */
    fun removeSetting(featureName: String): Boolean = engine.removeSetting(featureName)

    // Batch operations

    fun clearAllSettings() {
        engine.clearAllSettings()
    }

    fun getAllSettings(): Map<String, Any> = engine.getAllSettings()

    // JSON serialization

    fun getAllSettingsAsJSON(): String? {
        return try {
            val gson = GsonBuilder()
                .setPrettyPrinting()
                .create()
            gson.toJson(getAllSettings())
        } catch (_: Exception) {
            null
        }
    }

    // Config restore

    fun writeJSONToSharedPrefs(jsonString: String) {
        val mapToWrite = jsonToHashMap(jsonString)
        writeConfigToSharedPrefs(mapToWrite)
    }

    /**
     * Writes [mapToWrite] into SharedPreferences in one local commit and one
     * remote commit, inferring types via [PreferenceKeys].
     */
    fun writeConfigToSharedPrefs(mapToWrite: Map<String, Any>) {
        engine.writeConfigToSharedPrefs(mapToWrite)
    }

    companion object {
        private const val PREFS_NAME = "xposed_module_config"
        private const val SYNC_META_PREFS_NAME = "ztool_prefs_sync_meta"
        private const val TAG = "ZToolXposedModulePrefsUtils"

        private val engineLock = Any()

        @Volatile
        private var sharedEngine: ModulePreferencesSyncEngine? = null

        private fun getOrCreateEngine(appContext: Context): ModulePreferencesSyncEngine {
            return sharedEngine ?: synchronized(engineLock) {
                sharedEngine ?: createEngine(appContext).also { sharedEngine = it }
            }
        }

        private fun createEngine(appContext: Context): ModulePreferencesSyncEngine {
            val localPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val metaPrefs = appContext.getSharedPreferences(SYNC_META_PREFS_NAME, Context.MODE_PRIVATE)
            return ModulePreferencesSyncEngine(
                localPrefs = localPrefs,
                metaPrefs = metaPrefs,
                remotePrefsProvider = { XposedServiceBridge.currentService?.getRemotePreferences(PREFS_NAME) },
                log = { level, msg, err ->
                    when (level) {
                        ModulePreferencesSyncEngine.LOG_DEBUG ->
                            if (err != null) Log.d(TAG, msg, err) else Log.d(TAG, msg)
                        ModulePreferencesSyncEngine.LOG_INFO ->
                            if (err != null) Log.i(TAG, msg, err) else Log.i(TAG, msg)
                        ModulePreferencesSyncEngine.LOG_WARN ->
                            if (err != null) Log.w(TAG, msg, err) else Log.w(TAG, msg)
                        else ->
                            if (err != null) Log.e(TAG, msg, err) else Log.e(TAG, msg)
                    }
                }
            )
        }

        /** Reconciles local SSOT and remote preferences when XposedService binds. */
        internal fun onServiceBind(context: Context): ModulePreferencesSyncEngine.SyncOutcome {
            val appContext = context.applicationContext ?: context
            return getOrCreateEngine(appContext).reconcileWithRemote()
        }

        fun getAllSettingsAsJSON(context: Context): String? {
            return try {
                val utils = ModulePreferencesUtils(context)
                val result = utils.getAllSettingsAsJSON()
                Log.d(TAG, "Successfully converted sharedprefs to json string")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert sharedprefs to json string$e")
                null
            }
        }

        fun restoreConfig(context: Context, jsonToRestore: String) {
            try {
                val utils = ModulePreferencesUtils(context)
                utils.writeJSONToSharedPrefs(jsonToRestore)
                Log.d(TAG, "Successfully restored config from file.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore config from file, $e")
            }
        }

        fun jsonToHashMap(jsonString: String): Map<String, Any> {
            return try {
                val gson = Gson()
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(jsonString, type)
                processMapValues(map)
            } catch (e: Exception) {
                Log.e("JsonToMapConverter", "JSON conversion failed", e)
                emptyMap()
            }
        }

        private fun processMapValues(map: Map<String, Any>): Map<String, Any> {
            val processedMap = TreeMap<String, Any>()
            for ((key, value) in map) {
                when (value) {
                    is Double -> {
                        processedMap[key] = if (value % 1 == 0.0) value.toInt() else value
                    }
                    is Number -> {
                        processedMap[key] = if (value.toDouble() % 1 == 0.0)
                            value.toInt() else value.toFloat()
                    }
                    else -> processedMap[key] = value
                }
            }
            return processedMap
        }

        fun coerceFloatValue(value: Any?): Float? {
            return when (value) {
                is Number -> value.toFloat()
                is Boolean -> if (value) 1.0f else 0.0f
                is String -> value.toFloatOrNull()
                else -> null
            }
        }

        fun coerceIntegerValue(value: Any?): Int? {
            return when (value) {
                is Number -> {
                    val doubleValue = value.toDouble()
                    if (doubleValue % 1 == 0.0) value.toInt() else null
                }
                is Boolean -> if (value) 1 else 0
                is String -> {
                    value.toIntOrNull()
                        ?: value.toFloatOrNull()?.let {
                            if (it % 1 == 0.0f) it.toInt() else null
                        }
                }
                else -> null
            }
        }

        fun coerceBooleanValue(value: Any?): Boolean? {
            return when (value) {
                is Boolean -> value
                is Number -> {
                    val doubleValue = value.toDouble()
                    if (doubleValue == 0.0 || doubleValue == 1.0) doubleValue == 1.0 else null
                }
                is String -> {
                    val trimmed = value.trim()
                    when {
                        trimmed.equals("true", ignoreCase = true) || trimmed == "1" -> true
                        trimmed.equals("false", ignoreCase = true) || trimmed == "0" -> false
                        else -> null
                    }
                }
                else -> null
            }
        }

        fun coerceStringValue(value: Any?): String? = value?.toString()
    }
}
