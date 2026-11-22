package com.qimian233.ztool.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.utils.ModulePreferencesUtils

/**
 * Backing preference files the editor can address. The xposed module config is
 * stored locally and mirrored to LSPosed remote preferences through
 * [ModulePreferencesUtils]; the theme file is local-only SharedPreferences.
 */
enum class PreferenceEditorTarget(val prefsFileName: String) {
    XPOSED_MODULE_CONFIG("xposed_module_config"),
    UI_THEME_PREFERENCES("ztool_ui_theme_preferences")
}

enum class PreferenceEditorFailureReason { TYPE_ERROR, KEY_NOT_FOUND, OTHER }

/** Result of one editor operation, rendered verbatim by the result card. */
sealed interface PreferenceEditOutcome {
    /** Read: the raw stored value with its actual Java type. */
    data class Read(
        val key: String,
        val value: Any?,
        val typeName: String,
        val viaRemote: Boolean?
    ) : PreferenceEditOutcome

    data class Write(
        val key: String,
        val writtenTypeName: String,
        val viaRemote: Boolean?
    ) : PreferenceEditOutcome

    data class Delete(
        val key: String,
        val viaRemote: Boolean?
    ) : PreferenceEditOutcome

    data class ListAll(val entries: Map<String, Any>) : PreferenceEditOutcome

    data class Failure(
        val reason: PreferenceEditorFailureReason,
        val message: String?
    ) : PreferenceEditOutcome
}

/**
 * Read/write/list/delete access to the editor target preference files.
 *
 * Type discipline:
 * - xposed_module_config: registered [PreferenceKeys] are clamped to their
 *   declared type; unregistered keys write as Strings after user confirmation.
 * - ztool_ui_theme_preferences: [THEME_KEY_TYPES] maps known theme keys to
 *   their stored type; unknown keys write as Strings after user confirmation.
 */
class PreferenceEditorRepository(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val moduleUtils = ModulePreferencesUtils(appContext)

    /** True when LSPosed remote sync is currently reachable for xposed_module_config. */
    val xposedViaRemote: Boolean
        get() = moduleUtils.isRemoteSyncAvailable

    fun isRegisteredKey(target: PreferenceEditorTarget, key: String): Boolean = when (target) {
        PreferenceEditorTarget.XPOSED_MODULE_CONFIG -> isXposedKeyRegistered(key)
        PreferenceEditorTarget.UI_THEME_PREFERENCES -> THEME_KEY_TYPES.containsKey(key)
    }

    fun read(target: PreferenceEditorTarget, key: String): PreferenceEditOutcome = when (target) {
        PreferenceEditorTarget.XPOSED_MODULE_CONFIG -> readXposed(key)
        PreferenceEditorTarget.UI_THEME_PREFERENCES -> readTheme(key)
    }

    /**
     * Write with type clamping. [allowUnregisteredAsString] must only be true
     * after the caller has confirmed the unregistered-key warning with the user.
     */
    fun write(
        target: PreferenceEditorTarget,
        key: String,
        rawValue: String,
        allowUnregisteredAsString: Boolean
    ): PreferenceEditOutcome = when (target) {
        PreferenceEditorTarget.XPOSED_MODULE_CONFIG ->
            writeXposedOutcome(key, rawValue, allowUnregisteredAsString)
        PreferenceEditorTarget.UI_THEME_PREFERENCES ->
            writeThemeOutcome(key, rawValue, allowUnregisteredAsString)
    }

    fun delete(target: PreferenceEditorTarget, key: String): PreferenceEditOutcome = when (target) {
        PreferenceEditorTarget.XPOSED_MODULE_CONFIG -> deleteXposed(key)
        PreferenceEditorTarget.UI_THEME_PREFERENCES -> deleteTheme(key)
    }

    fun listAll(target: PreferenceEditorTarget): PreferenceEditOutcome = when (target) {
        PreferenceEditorTarget.XPOSED_MODULE_CONFIG -> listAllXposed()
        PreferenceEditorTarget.UI_THEME_PREFERENCES -> listAllTheme()
    }

    // Xposed module config (local SSOT + remote sync via ModulePreferencesUtils)

    private fun readXposed(key: String): PreferenceEditOutcome = try {
        if (!moduleUtils.containsSetting(key)) {
            PreferenceEditOutcome.Failure(
                PreferenceEditorFailureReason.KEY_NOT_FOUND,
                "key \"$key\" does not exist"
            )
        } else {
            val value = moduleUtils.readRawSetting(key)
            PreferenceEditOutcome.Read(
                key = key,
                value = value,
                typeName = value?.javaClass?.simpleName ?: "null",
                viaRemote = moduleUtils.isRemoteSyncAvailable
            )
        }
    } catch (e: Exception) {
        PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
    }

    private fun writeXposedOutcome(
        key: String,
        rawValue: String,
        allowUnregisteredAsString: Boolean
    ): PreferenceEditOutcome = try {
        val writtenType = writeXposed(key, rawValue, allowUnregisteredAsString)
            ?: return PreferenceEditOutcome.Failure(
                PreferenceEditorFailureReason.TYPE_ERROR,
                "value \"$rawValue\" does not match the registered type of \"$key\""
            )
        if (!moduleUtils.containsSetting(key)) {
            PreferenceEditOutcome.Failure(
                PreferenceEditorFailureReason.OTHER,
                "commit did not persist the key"
            )
        } else {
            PreferenceEditOutcome.Write(
                key = key,
                writtenTypeName = writtenType,
                viaRemote = moduleUtils.isRemoteSyncAvailable
            )
        }
    } catch (e: Exception) {
        PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
    }

    private fun deleteXposed(key: String): PreferenceEditOutcome = try {
        if (!moduleUtils.containsSetting(key)) {
            PreferenceEditOutcome.Failure(
                PreferenceEditorFailureReason.KEY_NOT_FOUND,
                "key \"$key\" does not exist"
            )
        } else if (moduleUtils.removeSetting(key)) {
            PreferenceEditOutcome.Delete(
                key = key,
                viaRemote = moduleUtils.isRemoteSyncAvailable
            )
        } else {
            PreferenceEditOutcome.Failure(
                PreferenceEditorFailureReason.OTHER,
                "commit returned false"
            )
        }
    } catch (e: Exception) {
        PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
    }

    private fun listAllXposed(): PreferenceEditOutcome = try {
        PreferenceEditOutcome.ListAll(java.util.TreeMap(moduleUtils.getAllSettings()))
    } catch (e: Exception) {
        PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
    }

    private fun isXposedKeyRegistered(key: String): Boolean =
        PreferenceKeys.isBooleanKey(key) || PreferenceKeys.isIntKey(key) ||
            PreferenceKeys.isFloatKey(key) ||
            PreferenceKeys.findStringKey(key) != null

    private fun writeXposed(
        key: String,
        rawValue: String,
        allowUnregisteredAsString: Boolean
    ): String? = when {
        PreferenceKeys.isBooleanKey(key) -> {
            val coerced = ModulePreferencesUtils.coerceBooleanValue(rawValue) ?: return null
            moduleUtils.saveBooleanSetting(key, coerced)
            "Boolean"
        }
        PreferenceKeys.isIntKey(key) -> {
            val coerced = ModulePreferencesUtils.coerceIntegerValue(rawValue) ?: return null
            moduleUtils.saveIntegerSetting(key, coerced)
            "Int"
        }
        PreferenceKeys.isFloatKey(key) -> {
            val coerced = ModulePreferencesUtils.coerceFloatValue(rawValue) ?: return null
            moduleUtils.saveFloatSetting(key, coerced)
            "Float"
        }
        isXposedKeyRegistered(key) -> {
            moduleUtils.saveStringSetting(key, rawValue)
            "String"
        }
        allowUnregisteredAsString -> {
            moduleUtils.saveStringSetting(key, rawValue)
            "String"
        }
        else -> null
    }

    // Theme local prefs (shim type map)

    private fun readTheme(key: String): PreferenceEditOutcome {
        val prefs = themePrefs() ?: return storageFailure(PreferenceEditorTarget.UI_THEME_PREFERENCES)
        return try {
            if (!prefs.all.containsKey(key)) {
                PreferenceEditOutcome.Failure(
                    PreferenceEditorFailureReason.KEY_NOT_FOUND,
                    "key \"$key\" does not exist"
                )
            } else {
                val value = prefs.all[key]
                PreferenceEditOutcome.Read(
                    key = key,
                    value = value,
                    typeName = value?.javaClass?.simpleName ?: "null",
                    viaRemote = null
                )
            }
        } catch (e: Exception) {
            PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
        }
    }

    private fun writeThemeOutcome(
        key: String,
        rawValue: String,
        allowUnregisteredAsString: Boolean
    ): PreferenceEditOutcome {
        val prefs = themePrefs() ?: return storageFailure(PreferenceEditorTarget.UI_THEME_PREFERENCES)
        return try {
            val writtenType = writeTheme(prefs, key, rawValue, allowUnregisteredAsString)
                ?: return PreferenceEditOutcome.Failure(
                    PreferenceEditorFailureReason.TYPE_ERROR,
                    "value \"$rawValue\" does not match the registered type of \"$key\""
                )
            if (!prefs.contains(key)) {
                PreferenceEditOutcome.Failure(
                    PreferenceEditorFailureReason.OTHER,
                    "commit did not persist the key"
                )
            } else {
                PreferenceEditOutcome.Write(
                    key = key,
                    writtenTypeName = writtenType,
                    viaRemote = null
                )
            }
        } catch (e: Exception) {
            PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
        }
    }

    private fun deleteTheme(key: String): PreferenceEditOutcome {
        val prefs = themePrefs() ?: return storageFailure(PreferenceEditorTarget.UI_THEME_PREFERENCES)
        return try {
            if (!prefs.all.containsKey(key)) {
                PreferenceEditOutcome.Failure(
                    PreferenceEditorFailureReason.KEY_NOT_FOUND,
                    "key \"$key\" does not exist"
                )
            } else {
                @Suppress("ApplySharedPref")
                val success = prefs.edit().remove(key).commit()
                if (success) {
                    PreferenceEditOutcome.Delete(key = key, viaRemote = null)
                } else {
                    PreferenceEditOutcome.Failure(
                        PreferenceEditorFailureReason.OTHER,
                        "commit returned false"
                    )
                }
            }
        } catch (e: Exception) {
            PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
        }
    }

    private fun listAllTheme(): PreferenceEditOutcome {
        val prefs = themePrefs() ?: return storageFailure(PreferenceEditorTarget.UI_THEME_PREFERENCES)
        return try {
            PreferenceEditOutcome.ListAll(java.util.TreeMap(prefs.all))
        } catch (e: Exception) {
            PreferenceEditOutcome.Failure(PreferenceEditorFailureReason.OTHER, e.message)
        }
    }

    private fun writeTheme(
        prefs: SharedPreferences,
        key: String,
        rawValue: String,
        allowUnregisteredAsString: Boolean
    ): String? {
        val declaredType = THEME_KEY_TYPES[key]
            ?: return if (allowUnregisteredAsString) {
                prefs.edit(commit = true) { putString(key, rawValue) }
                "String"
            } else {
                null
            }
        return when (declaredType) {
            ThemeKeyType.STRING -> {
                prefs.edit(commit = true) { putString(key, rawValue) }
                "String"
            }
            ThemeKeyType.BOOLEAN -> {
                val coerced = ModulePreferencesUtils.coerceBooleanValue(rawValue) ?: return null
                prefs.edit(commit = true) { putBoolean(key, coerced) }
                "Boolean"
            }
            ThemeKeyType.LONG -> {
                val coerced = ModulePreferencesUtils.coerceIntegerValue(rawValue) ?: return null
                prefs.edit(commit = true) { putLong(key, coerced.toLong()) }
                "Long"
            }
        }
    }

    private fun themePrefs(): SharedPreferences? = try {
        appContext.getSharedPreferences(
            PreferenceEditorTarget.UI_THEME_PREFERENCES.prefsFileName,
            Context.MODE_PRIVATE
        )
    } catch (_: Exception) {
        null
    }

    private fun storageFailure(target: PreferenceEditorTarget): PreferenceEditOutcome =
        PreferenceEditOutcome.Failure(
            PreferenceEditorFailureReason.OTHER,
            "cannot open preference file \"${target.prefsFileName}\""
        )

    private enum class ThemeKeyType { STRING, BOOLEAN, LONG }

    companion object {
        /**
         * Shim registry of every theme key written by ThemePreferencesRepository,
         * mapped to its stored type. Keep in sync when theme settings add fields.
         */
        private val THEME_KEY_TYPES: Map<String, ThemeKeyType> = mapOf(
            "frontend_style" to ThemeKeyType.STRING,
            "theme_mode" to ThemeKeyType.STRING,
            "material_color_spec" to ThemeKeyType.STRING,
            "material_palette" to ThemeKeyType.STRING,
            "material_palette_mode" to ThemeKeyType.STRING,
            "dynamic_color_enabled" to ThemeKeyType.BOOLEAN,
            "amoled_black_enabled" to ThemeKeyType.BOOLEAN,
            "use_alternative_icon" to ThemeKeyType.BOOLEAN,
            "predictive_back_gesture_enabled" to ThemeKeyType.BOOLEAN,
            "manual_color_enabled" to ThemeKeyType.BOOLEAN,
            "manual_seed_color" to ThemeKeyType.LONG,
            "enable_floating_bottom_bar" to ThemeKeyType.BOOLEAN,
            "enable_floating_bottom_bar_blur" to ThemeKeyType.BOOLEAN
        )
    }
}
