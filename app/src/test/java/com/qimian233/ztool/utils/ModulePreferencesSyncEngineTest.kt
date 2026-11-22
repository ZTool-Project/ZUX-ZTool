package com.qimian233.ztool.utils

import android.content.SharedPreferences
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.utils.ModulePreferencesSyncEngine.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulePreferencesSyncEngineTest {

    @Test
    fun upgradeBootstrap_emptyLocalWithPopulatedRemote_hydratesLocalWithoutRemoteWrites() {
        val local = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences()
        val remote = InMemorySharedPreferences(
            mapOf(
                PreferenceKeys.DISABLE_FLAG_SECURE.name to true,
                PreferenceKeys.CUSTOM_LAUNCHER_ROW.name to 5,
                PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_FORMAT.name to "HH:mm:ss"
            )
        )
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.BOOTSTRAPPED_FROM_REMOTE, outcome)
        assertEquals(0, remote.commitCount)
        assertEquals(1, local.commitCount)
        assertTrue(meta.getBoolean(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED, false))
        assertEquals(remote.all, local.all)
        assertTrue(engine.loadBooleanSetting(PreferenceKeys.DISABLE_FLAG_SECURE.name, false))
        assertEquals(5, engine.loadIntegerSetting(PreferenceKeys.CUSTOM_LAUNCHER_ROW.name, 4))
        assertEquals("HH:mm:ss", engine.loadStringSetting(PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_FORMAT.name, ""))
    }

    @Test
    fun upgradeBootstrap_localOnlyHasIsConfigUpgraded_stillHydratesFromRemote() {
        val local = InMemorySharedPreferences(
            mapOf(PreferenceKeys.IS_CONFIG_UPGRADED.name to true)
        )
        val meta = InMemorySharedPreferences()
        val remote = InMemorySharedPreferences(
            mapOf(
                PreferenceKeys.IS_CONFIG_UPGRADED.name to true,
                PreferenceKeys.DISABLE_FLAG_SECURE.name to true,
                PreferenceKeys.LOG_LEVEL.name to 5
            )
        )
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.BOOTSTRAPPED_FROM_REMOTE, outcome)
        assertEquals(0, remote.commitCount)
        assertEquals(remote.all, local.all)
    }

    @Test
    fun upgradeBootstrap_preBindGhProxySelection_mergesOntoRemoteAndPushesOnlyDiff() {
        val local = InMemorySharedPreferences(
            mapOf(PreferenceKeys.USE_GH_PROXY_FOR_UPDATE.name to true)
        )
        val meta = InMemorySharedPreferences()
        val remote = InMemorySharedPreferences(
            mapOf(
                PreferenceKeys.DISABLE_FLAG_SECURE.name to true,
                PreferenceKeys.CUSTOM_LAUNCHER_ROW.name to 6
            )
        )
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.BOOTSTRAPPED_FROM_REMOTE, outcome)
        assertEquals(1, remote.commitCount)
        assertEquals(true, local.all[PreferenceKeys.DISABLE_FLAG_SECURE.name])
        assertEquals(6, local.all[PreferenceKeys.CUSTOM_LAUNCHER_ROW.name])
        assertEquals(true, local.all[PreferenceKeys.USE_GH_PROXY_FOR_UPDATE.name])
        assertEquals(local.all, remote.all)
    }

    @Test
    fun freshInstall_bothEmpty_marksBootstrappedWithoutRemoteWrites() {
        val local = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences()
        val remote = InMemorySharedPreferences()
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.ALREADY_IN_SYNC, outcome)
        assertTrue(meta.getBoolean(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED, false))
        assertEquals(0, local.commitCount)
        assertEquals(0, remote.commitCount)
    }

    @Test
    fun reconcile_alreadyInSync_performsZeroRemoteCommits() {
        val initial = mapOf(
            PreferenceKeys.DISABLE_FLAG_SECURE.name to true,
            PreferenceKeys.CUSTOM_LAUNCHER_ROW.name to 5
        )
        val local = InMemorySharedPreferences(initial)
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        val remote = InMemorySharedPreferences(initial)
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.ALREADY_IN_SYNC, outcome)
        assertEquals(0, remote.commitCount)
    }

    @Test
    fun reconcile_diffSyncOnBind_pushesAddedUpdatedTypeChangedAndRemovedKeysInOneCommit() {
        val local = InMemorySharedPreferences(
            mapOf(
                "added_key" to "hello",
                "updated_key" to true,
                "type_changed_key" to 1.0f,
                "unchanged_key" to 42
            )
        )
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        val remote = InMemorySharedPreferences(
            mapOf(
                "updated_key" to false,
                "type_changed_key" to 1,
                "unchanged_key" to 42,
                "removed_key" to "stale"
            )
        )
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.PUSHED_DIFF_TO_REMOTE, outcome)
        assertEquals(1, remote.commitCount)
        assertEquals(local.all, remote.all)
        assertTrue(remote.all["type_changed_key"] is Float)
        assertFalse(remote.contains("removed_key"))
    }

    @Test
    fun writeThrough_updatesLocalAndRemoteForAllMutators() {
        val local = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        val remote = InMemorySharedPreferences()
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        assertTrue(engine.saveBooleanSetting("b", true))
        engine.saveStringSetting("s", "abc")
        engine.saveIntegerSetting("i", 7)
        engine.saveFloatSetting("f", 2.5f)
        assertEquals(local.all, remote.all)
        assertEquals(true, engine.readRawSetting("b"))
        assertEquals("abc", engine.loadStringSetting("s", ""))
        assertEquals(7, engine.loadIntegerSetting("i", 0))
        assertEquals(2.5f, engine.loadFloatSetting("f", 0f), 0.0001f)

        assertTrue(engine.removeSetting("s"))
        assertFalse(remote.contains("s"))
        assertFalse(local.contains("s"))

        assertTrue(engine.clearAllSettings())
        assertTrue(local.all.isEmpty())
        assertTrue(remote.all.isEmpty())
    }

    @Test
    fun offlineWritesFollowedByBind_diffSyncPushesPendingChanges() {
        val local = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        var remote: InMemorySharedPreferences? = null
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        engine.saveBooleanSetting("flag_a", true)
        engine.saveIntegerSetting("count_a", 9)

        remote = InMemorySharedPreferences(mapOf("obsolete" to "x"))
        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.PUSHED_DIFF_TO_REMOTE, outcome)
        assertEquals(1, remote.commitCount)
        assertEquals(true, local.all["flag_a"])
        assertEquals(9, local.all["count_a"])
        assertEquals(local.all, remote.all)
    }

    @Test
    fun clearAllSettingsOffline_preservesBootstrappedMarkerAndClearsRemoteOnBind() {
        val local = InMemorySharedPreferences(mapOf("k1" to true))
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        var remote: InMemorySharedPreferences? = null
        val remoteStore = InMemorySharedPreferences(mapOf("k1" to true, "k2" to "v2"))
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        assertTrue(engine.clearAllSettings())
        assertTrue(meta.getBoolean(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED, false))
        assertTrue(local.all.isEmpty())

        remote = remoteStore
        val outcome = engine.reconcileWithRemote()

        assertEquals(SyncOutcome.PUSHED_DIFF_TO_REMOTE, outcome)
        assertTrue(local.all.isEmpty())
        assertTrue(remoteStore.all.isEmpty())
    }

    @Test
    fun writeConfigToSharedPrefs_stripsPrefixCoercesTypesAndUsesSingleBatchCommitPerStore() {
        val local = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        val remote = InMemorySharedPreferences()
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val input: Map<String, Any> = mapOf(
            "module_enabled_${PreferenceKeys.DISABLE_FLAG_SECURE.name}" to "true",
            PreferenceKeys.CUSTOM_LAUNCHER_ROW.name to 7.0,
            PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_SIZE.name to 18.5,
            "custom_extra" to "ok"
        )

        assertTrue(engine.writeConfigToSharedPrefs(input))

        assertEquals(1, local.commitCount)
        assertEquals(1, remote.commitCount)
        assertEquals(true, local.all[PreferenceKeys.DISABLE_FLAG_SECURE.name])
        assertEquals(7, local.all[PreferenceKeys.CUSTOM_LAUNCHER_ROW.name])
        assertEquals(18.5f, local.all[PreferenceKeys.CUSTOM_STATUSBAR_CLOCK_TEXT_SIZE.name])
        assertEquals("ok", local.all["custom_extra"])
        assertEquals(local.all, remote.all)
    }

    @Test
    fun lazyBootstrapGuardOnRead_hydratesFromRemoteBeforeReturning() {
        val local = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences()
        val remote = InMemorySharedPreferences(
            mapOf(PreferenceKeys.DISABLE_FLAG_SECURE.name to true)
        )
        var bound = false
        val engine = ModulePreferencesSyncEngine(local, meta, { if (bound) remote else null })

        assertFalse(engine.loadBooleanSetting(PreferenceKeys.DISABLE_FLAG_SECURE.name, false))

        bound = true
        assertTrue(engine.loadBooleanSetting(PreferenceKeys.DISABLE_FLAG_SECURE.name, false))
        assertTrue(meta.getBoolean(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED, false))
        assertEquals(true, local.all[PreferenceKeys.DISABLE_FLAG_SECURE.name])
    }

    @Test
    fun typeCoercionSelfRepairOnRead_repairsBothLocalAndRemote() {
        val local = InMemorySharedPreferences(
            mapOf(PreferenceKeys.DISABLE_FLAG_SECURE.name to "true")
        )
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        val remote = InMemorySharedPreferences(
            mapOf(PreferenceKeys.DISABLE_FLAG_SECURE.name to "true")
        )
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val value = engine.loadBooleanSetting(PreferenceKeys.DISABLE_FLAG_SECURE.name, false)

        assertTrue(value)
        assertEquals(true, local.all[PreferenceKeys.DISABLE_FLAG_SECURE.name])
        assertEquals(true, remote.all[PreferenceKeys.DISABLE_FLAG_SECURE.name])
    }

    @Test
    fun remoteCommitThrows_localWriteStillSucceedsWithoutThrowing() {
        val local = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences(
            mapOf(ModulePreferencesSyncEngine.KEY_LOCAL_SSOT_BOOTSTRAPPED to true)
        )
        val remote = InMemorySharedPreferences(throwOnCommit = true)
        val engine = ModulePreferencesSyncEngine(local, meta, { remote })

        val saved = engine.saveBooleanSetting("k", true)

        assertTrue(saved)
        assertEquals(true, local.all["k"])
        assertNull(remote.all["k"])
    }

    private class InMemorySharedPreferences(
        initial: Map<String, Any?> = emptyMap(),
        var throwOnCommit: Boolean = false
    ) : SharedPreferences {

        private val data = LinkedHashMap<String, Any?>(initial)
        private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        var commitCount: Int = 0
            private set

        @Synchronized
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

        @Synchronized
        override fun getString(key: String?, defValue: String?): String? {
            val v = data[key] ?: return defValue
            return v as String
        }

        @Suppress("UNCHECKED_CAST")
        @Synchronized
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val v = data[key] ?: return defValues
            return LinkedHashSet(v as Set<String>)
        }

        @Synchronized
        override fun getInt(key: String?, defValue: Int): Int {
            val v = data[key] ?: return defValue
            return v as Int
        }

        @Synchronized
        override fun getLong(key: String?, defValue: Long): Long {
            val v = data[key] ?: return defValue
            return v as Long
        }

        @Synchronized
        override fun getFloat(key: String?, defValue: Float): Float {
            val v = data[key] ?: return defValue
            return v as Float
        }

        @Synchronized
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            val v = data[key] ?: return defValue
            return v as Boolean
        }

        @Synchronized
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        @Synchronized
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners.add(listener)
        }

        @Synchronized
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners.remove(listener)
        }

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private var clearRequested = false
            private val deleted = LinkedHashSet<String>()
            private val updated = LinkedHashMap<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key == null) return@apply
                if (value == null) remove(key) else {
                    deleted.remove(key)
                    updated[key] = value
                }
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                if (key == null) return@apply
                if (values == null) remove(key) else {
                    deleted.remove(key)
                    updated[key] = LinkedHashSet(values)
                }
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key == null) return@apply
                deleted.remove(key)
                updated[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key == null) return@apply
                deleted.remove(key)
                updated[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key == null) return@apply
                deleted.remove(key)
                updated[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key == null) return@apply
                deleted.remove(key)
                updated[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key == null) return@apply
                deleted.add(key)
                updated.remove(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
                deleted.clear()
                updated.clear()
            }

            override fun commit(): Boolean {
                if (throwOnCommit) {
                    throw RuntimeException("Simulated remote commit failure")
                }
                val listenerSnapshot: List<SharedPreferences.OnSharedPreferenceChangeListener>
                synchronized(this@InMemorySharedPreferences) {
                    commitCount++
                    if (clearRequested) {
                        data.clear()
                    }
                    for (key in deleted) {
                        data.remove(key)
                    }
                    data.putAll(updated)
                    listenerSnapshot = listeners.toList()
                }
                for (listener in listenerSnapshot) {
                    for (key in deleted) {
                        listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                    }
                    for (key in updated.keys) {
                        listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
