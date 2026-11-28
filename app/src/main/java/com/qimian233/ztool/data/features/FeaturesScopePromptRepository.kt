package com.qimian233.ztool.data.features

import android.content.Context
import androidx.core.content.edit

/** Persists dismissed missing-scope prompts and computes missing scopes for the Features page. */
class FeaturesScopePromptRepository(private val context: Context) {

    fun loadDismissedPackages(): Set<String> {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISMISSED_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun dismissMissingPackages(packages: Collection<String>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_DISMISSED_PACKAGES, HashSet(packages)) }
    }

    companion object {
        private const val PREF_NAME = "features_ui_prefs"
        private const val KEY_DISMISSED_PACKAGES = "missing_scope_dismissed_packages"

        fun computeMissingScopePackages(
            visibleCards: List<FeatureCard>,
            installedPackages: Set<String>,
            systemScopePackages: Set<String>,
            currentScope: Set<String>,
            isServiceConnected: Boolean
        ): List<String> {
            if (!isServiceConnected || installedPackages.isEmpty()) return emptyList()
            return visibleCards
                .asSequence()
                .filter { it.destination != FeatureDestination.UiComponents }
                .flatMap { it.scopes().asSequence() }
                .map { it.packageName }
                .distinct()
                .filter { it in installedPackages || it in systemScopePackages }
                .filter { it !in currentScope }
                .toList()
        }

        fun shouldShowMissingScopePrompt(
            missingPackages: List<String>,
            dismissedPackages: Set<String>
        ): Boolean {
            return missingPackages.isNotEmpty() && missingPackages.any { it !in dismissedPackages }
        }
    }
}
