package com.qimian233.ztool.utils

import android.content.SharedPreferences
import com.qimian233.ztool.data.keys.PreferenceKeys
import java.util.TreeMap

/**
 * Local-first preference synchronization engine for `xposed_module_config`.
 *
 * Treats [localPrefs] as the single source of truth, mirrors every write to
 * [remotePrefsProvider] when available, and reconciles diffs on service bind.
 */
internal class ModulePreferencesSyncEngine(
    private val localPrefs: SharedPreferences,
    private val metaPrefs: SharedPreferences,
    private val remotePrefsProvider: () -> SharedPreferences?,
    private val lock: Any = Any(),
    private val log: (level: Int, msg: String, err: Throwable?) -> Unit = { _, _, _ -> }
) {

    enum class SyncOutcome {
        REMOTE_UNAVAILABLE,
        BOOTSTRAPPED_FROM_REMOTE,
        PUSHED_DIFF_TO_REMOTE,
        ALREADY_IN_SYNC
    }

    @Volatile
    private var bootstrappedCache: Boolean =
        metaPrefs.getBoolean(KEY_LOCAL_SSOT_BOOTSTRAPPED, false)

    fun isRemoteAvailable(): Boolean = safeRemotePrefs() != null

    /**
     * Reconciles local SSOT and remote preferences once the LSPosed service is bound.
     * On first bind after upgrade, hydrates an empty/stub local store from remote first.
     */
    fun reconcileWithRemote(): SyncOutcome = synchronized(lock) {
        val remote = safeRemotePrefs() ?: return SyncOutcome.REMOTE_UNAVAILABLE
        val bootstrapped = bootstrappedCache ||
            metaPrefs.getBoolean(KEY_LOCAL_SSOT_BOOTSTRAPPED, false)
        if (bootstrapped) {
            bootstrappedCache = true
        }

        if (!bootstrapped) {
            val localAll = localPrefs.all
            val remoteAll = readAllSafely(remote)
            val hasMeaningfulLocal = localAll.keys.any { it !in PRE_BIND_WRITABLE_KEYS }

            if (!hasMeaningfulLocal && remoteAll.isNotEmpty()) {
                val merged = LinkedHashMap<String, Any?>(remoteAll.size + localAll.size)
                merged.putAll(remoteAll)
                for ((key, value) in localAll) {
                    if (key != PreferenceKeys.IS_CONFIG_UPGRADED.name || !merged.containsKey(key)) {
                        merged[key] = value
                    }
                }
                val localCommitted = writeMapToPrefs(localPrefs, merged, clearFirst = true)
                if (!localCommitted) {
                    log(LOG_ERROR, "Bootstrap commit to local preferences failed", null)
                    return SyncOutcome.REMOTE_UNAVAILABLE
                }
                markBootstrapped()
                pushDiffToRemote(localPrefs.all, remote)
                log(LOG_INFO, "Bootstrapped ${merged.size} settings from remote to local SSOT", null)
                return SyncOutcome.BOOTSTRAPPED_FROM_REMOTE
            }

            markBootstrapped()
        }

        val changed = pushDiffToRemote(localPrefs.all, remote)
        return if (changed) SyncOutcome.PUSHED_DIFF_TO_REMOTE else SyncOutcome.ALREADY_IN_SYNC
    }

    fun containsSetting(key: String): Boolean {
        ensureBootstrappedIfRemoteReady()
        return localPrefs.all.containsKey(key)
    }

    fun readRawSetting(key: String): Any? {
        ensureBootstrappedIfRemoteReady()
        return localPrefs.all[key]
    }

    fun loadBooleanSetting(key: String, defaultValue: Boolean): Boolean {
        ensureBootstrappedIfRemoteReady()
        return try {
            val value = localPrefs.getBoolean(key, defaultValue)
            log(LOG_DEBUG, "Loading $key: $value", null)
            value
        } catch (e: ClassCastException) {
            val storedValue = localPrefs.all[key]
            val repaired = ModulePreferencesUtils.coerceBooleanValue(storedValue)
            if (repaired != null) {
                log(
                    LOG_WARN,
                    "Repairing illegal boolean setting type for $key: ${storedValue?.javaClass?.simpleName}",
                    null
                )
                saveBooleanSetting(key, repaired)
                repaired
            } else {
                log(
                    LOG_ERROR,
                    "Illegal boolean setting value for $key, using default: $defaultValue",
                    e
                )
                defaultValue
            }
        }
    }

    fun saveBooleanSetting(key: String, value: Boolean): Boolean =
        writeSingle(key, value) { putBoolean(key, value) }

    fun loadStringSetting(key: String, defaultValue: String): String {
        ensureBootstrappedIfRemoteReady()
        return try {
            localPrefs.getString(key, defaultValue) ?: defaultValue
        } catch (e: ClassCastException) {
            val storedValue = localPrefs.all[key]
            val repaired = ModulePreferencesUtils.coerceStringValue(storedValue)
            if (repaired != null) {
                log(
                    LOG_WARN,
                    "Repairing illegal string setting type for $key: ${storedValue?.javaClass?.simpleName}",
                    null
                )
                saveStringSetting(key, repaired)
                repaired
            } else {
                log(LOG_ERROR, "Illegal string setting value for $key, using default", e)
                defaultValue
            }
        }
    }

    fun saveStringSetting(key: String, value: String): Boolean =
        writeSingle(key, value) { putString(key, value) }

    fun loadIntegerSetting(key: String, defaultValue: Int): Int {
        ensureBootstrappedIfRemoteReady()
        return try {
            localPrefs.getInt(key, defaultValue)
        } catch (e: ClassCastException) {
            val storedValue = localPrefs.all[key]
            val repaired = ModulePreferencesUtils.coerceIntegerValue(storedValue)
            if (repaired != null) {
                log(
                    LOG_WARN,
                    "Repairing illegal integer setting type for $key: ${storedValue?.javaClass?.simpleName}",
                    null
                )
                saveIntegerSetting(key, repaired)
                repaired
            } else {
                log(
                    LOG_ERROR,
                    "Illegal integer setting value for $key, using default: $defaultValue",
                    e
                )
                defaultValue
            }
        }
    }

    fun saveIntegerSetting(key: String, value: Int): Boolean =
        writeSingle(key, value) { putInt(key, value) }

    fun loadFloatSetting(key: String, defaultValue: Float): Float {
        ensureBootstrappedIfRemoteReady()
        return try {
            localPrefs.getFloat(key, defaultValue)
        } catch (e: ClassCastException) {
            val storedValue = localPrefs.all[key]
            val repaired = ModulePreferencesUtils.coerceFloatValue(storedValue)
            if (repaired != null) {
                log(
                    LOG_WARN,
                    "Repairing illegal float setting type for $key: ${storedValue?.javaClass?.simpleName}",
                    null
                )
                saveFloatSetting(key, repaired)
                repaired
            } else {
                log(
                    LOG_ERROR,
                    "Illegal float setting value for $key, using default: $defaultValue",
                    e
                )
                defaultValue
            }
        }
    }

    fun saveFloatSetting(key: String, value: Float): Boolean =
        writeSingle(key, value) { putFloat(key, value) }

    fun removeSetting(key: String): Boolean =
        writeSingle(key, "<removed>") { remove(key) }

    /** Clears local and remote settings without resetting bootstrap metadata. */
    fun clearAllSettings(): Boolean = synchronized(lock) {
        ensureBootstrappedIfRemoteReady()
        val localSuccess = try {
            localPrefs.edit().clear().commit()
        } catch (t: Throwable) {
            log(LOG_ERROR, "Failed to clear local settings", t)
            false
        }
        if (localSuccess) {
            val remote = safeRemotePrefs()
            if (remote != null) {
                try {
                    if (!remote.edit().clear().commit()) {
                        log(LOG_WARN, "Remote clear commit returned false", null)
                    }
                } catch (t: Throwable) {
                    log(LOG_ERROR, "Failed to clear remote settings", t)
                }
            }
        }
        localSuccess
    }

    fun getAllSettings(): Map<String, Any> {
        ensureBootstrappedIfRemoteReady()
        return try {
            @Suppress("UNCHECKED_CAST")
            val allEntries = TreeMap<String, Any>().apply {
                putAll(localPrefs.all as Map<String, Any>)
            }
            log(LOG_DEBUG, "Successfully read all settings, entries: ${allEntries.size}", null)
            allEntries
        } catch (e: Exception) {
            log(LOG_ERROR, "Failed to read all settings", e)
            emptyMap()
        }
    }

    /**
     * Resolves types via [PreferenceKeys], then writes the batch to local and remote
     * in a single transaction per store.
     */
    fun writeConfigToSharedPrefs(mapToWrite: Map<String, Any>): Boolean {
        val resolved = LinkedHashMap<String, Any>(mapToWrite.size)
        for ((key, value) in mapToWrite) {
            try {
                // Strip the legacy module_enabled_ prefix from older backups.
                val cleanKey = key.replace("module_enabled_", "")
                log(
                    LOG_DEBUG,
                    "Processing key: $cleanKey, value: $value, type: ${value.javaClass.simpleName}",
                    null
                )
                when {
                    PreferenceKeys.isFloatKey(cleanKey) -> {
                        val floatValue = ModulePreferencesUtils.coerceFloatValue(value)
                        if (floatValue != null) {
                            resolved[cleanKey] = floatValue
                        } else {
                            log(LOG_WARN, "Invalid float key value, skip: $cleanKey = $value", null)
                        }
                    }
                    PreferenceKeys.isIntKey(cleanKey) -> {
                        val intValue = ModulePreferencesUtils.coerceIntegerValue(value)
                        if (intValue != null) {
                            resolved[cleanKey] = intValue
                        } else {
                            log(LOG_WARN, "Invalid integer key value, skip: $cleanKey = $value", null)
                        }
                    }
                    PreferenceKeys.isBooleanKey(cleanKey) -> {
                        val booleanValue = ModulePreferencesUtils.coerceBooleanValue(value)
                        if (booleanValue != null) {
                            resolved[cleanKey] = booleanValue
                        } else {
                            log(LOG_WARN, "Invalid boolean key value, skip: $cleanKey = $value", null)
                        }
                    }
                    value is String -> resolved[cleanKey] = value
                    value is Int -> resolved[cleanKey] = value
                    value is Boolean -> resolved[cleanKey] = value
                    value is Float -> resolved[cleanKey] = value
                    value is Double -> resolved[cleanKey] = value.toFloat()
                    else -> resolved[cleanKey] = value.toString()
                }
            } catch (e: Exception) {
                log(LOG_ERROR, "Failed to resolve key: $key, value: $value, error: ${e.message}", e)
            }
        }
        if (resolved.isEmpty()) return true

        return synchronized(lock) {
            ensureBootstrappedIfRemoteReady()
            val localCommitted = try {
                writeMapToPrefs(localPrefs, resolved, clearFirst = false)
            } catch (t: Throwable) {
                log(LOG_ERROR, "Failed to batch-commit local settings", t)
                false
            }
            if (localCommitted) {
                val remote = safeRemotePrefs()
                if (remote != null) {
                    try {
                        if (!writeMapToPrefs(remote, resolved, clearFirst = false)) {
                            log(LOG_WARN, "Remote batch commit returned false", null)
                        }
                    } catch (t: Throwable) {
                        log(LOG_ERROR, "Failed to batch-commit remote settings", t)
                    }
                }
            }
            localCommitted
        }
    }

    private fun ensureBootstrappedIfRemoteReady() {
        if (bootstrappedCache) return
        if (safeRemotePrefs() == null) return
        reconcileWithRemote()
    }

    private fun markBootstrapped() {
        val committed = try {
            metaPrefs.edit().putBoolean(KEY_LOCAL_SSOT_BOOTSTRAPPED, true).commit()
        } catch (t: Throwable) {
            log(LOG_ERROR, "Failed to persist bootstrap marker", t)
            false
        }
        if (committed) {
            bootstrappedCache = true
        }
    }

    private fun safeRemotePrefs(): SharedPreferences? {
        return try {
            remotePrefsProvider()
        } catch (t: Throwable) {
            log(LOG_WARN, "Failed to get remote preferences", t)
            null
        }
    }

    private fun readAllSafely(prefs: SharedPreferences): Map<String, Any?> {
        return try {
            prefs.all
        } catch (t: Throwable) {
            log(LOG_WARN, "Failed to read remote preferences map", t)
            emptyMap()
        }
    }

    private fun pushDiffToRemote(localMap: Map<String, *>, remote: SharedPreferences): Boolean {
        return try {
            val remoteMap = remote.all
            if (localMap.isEmpty()) {
                if (remoteMap.isEmpty()) return false
                val cleared = remote.edit().clear().commit()
                if (!cleared) {
                    log(LOG_WARN, "Remote clear diff commit returned false", null)
                }
                return cleared
            }

            val toRemove = remoteMap.keys.filter { !localMap.containsKey(it) }
            val toPut = localMap.entries.filter { (key, value) ->
                value != null && (!remoteMap.containsKey(key) || !valuesEqualWithType(remoteMap[key], value))
            }

            if (toRemove.isEmpty() && toPut.isEmpty()) {
                return false
            }

            val editor = remote.edit()
            for (key in toRemove) {
                editor.remove(key)
            }
            for ((key, value) in toPut) {
                if (value != null) {
                    putAny(editor, key, value)
                }
            }
            val committed = editor.commit()
            if (!committed) {
                log(
                    LOG_WARN,
                    "Remote diff commit returned false (removed=${toRemove.size}, put=${toPut.size})",
                    null
                )
            } else {
                log(
                    LOG_INFO,
                    "Diff-synced to remote preferences (removed=${toRemove.size}, put=${toPut.size})",
                    null
                )
            }
            committed
        } catch (t: Throwable) {
            log(LOG_ERROR, "Failed to diff-sync preferences to remote", t)
            false
        }
    }

    private inline fun writeSingle(
        key: String,
        valueForLog: Any?,
        crossinline mutate: SharedPreferences.Editor.() -> Unit
    ): Boolean = synchronized(lock) {
        ensureBootstrappedIfRemoteReady()
        val localSuccess = try {
            val editor = localPrefs.edit()
            mutate(editor)
            editor.commit()
        } catch (t: Throwable) {
            log(LOG_ERROR, "Failed to write local setting: $key", t)
            false
        }
        if (localSuccess) {
            val remote = safeRemotePrefs()
            if (remote != null) {
                try {
                    val editor = remote.edit()
                    mutate(editor)
                    if (!editor.commit()) {
                        log(LOG_WARN, "Remote commit returned false ($key = $valueForLog)", null)
                    }
                } catch (t: Throwable) {
                    log(LOG_ERROR, "Failed to mirror setting to remote ($key)", t)
                }
            }
        }
        log(LOG_DEBUG, "Saved $key: $valueForLog, localSuccess: $localSuccess", null)
        localSuccess
    }

    private fun writeMapToPrefs(
        prefs: SharedPreferences,
        entries: Map<String, Any?>,
        clearFirst: Boolean
    ): Boolean {
        val editor = prefs.edit()
        if (clearFirst) {
            editor.clear()
        }
        for ((key, value) in entries) {
            if (value != null) {
                putAny(editor, key, value)
            }
        }
        return editor.commit()
    }

    private fun valuesEqualWithType(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a is Set<*> && b is Set<*>) {
            return a == b
        }
        return a.javaClass == b.javaClass && a == b
    }

    private fun putAny(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> editor.putString(key, value.toString())
        }
    }

    companion object {
        const val KEY_LOCAL_SSOT_BOOTSTRAPPED = "local_ssot_bootstrapped"

        const val LOG_DEBUG = 3
        const val LOG_INFO = 4
        const val LOG_WARN = 5
        const val LOG_ERROR = 6

        /**
         * Keys that may be written before XposedService binds during startup/OOBE
         * and must not block one-time remote-to-local upgrade hydration.
         */
        private val PRE_BIND_WRITABLE_KEYS = setOf(
            PreferenceKeys.IS_CONFIG_UPGRADED.name,
            PreferenceKeys.USE_GH_PROXY_FOR_UPDATE.name
        )
    }
}
