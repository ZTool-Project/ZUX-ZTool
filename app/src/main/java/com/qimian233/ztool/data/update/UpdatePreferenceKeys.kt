package com.qimian233.ztool.data.update

/** Keys of the app-local update preferences; deliberately outside xposed_module_config. */
internal object UpdatePreferenceKeys {
    const val PREF_NAME = "update_prefs"
    const val KEY_IGNORE_VERSION = "ignore_version_code"
    const val KEY_RECEIVE_PRERELEASE = "receive_prerelease_updates"
}
