package com.qimian233.ztool.data.features

import android.content.Context
import androidx.core.content.edit
import com.qimian233.ztool.data.keys.ScopeKeys

/** Missing LSPosed scopes affecting a single feature card. */
data class FeatureCardMissingScope(
    val card: FeatureCard,
    val missingPackages: List<String>
)

/** Diagnostic snapshot of feature-level LSPosed scope coverage. */
data class FeatureScopeDiagnostic(
    val isServiceConnected: Boolean,
    val installedPackagesAvailable: Boolean,
    val activeScopeCount: Int,
    val missingPackages: List<String>,
    val affectedCards: List<FeatureCardMissingScope>,
    val dismissedPackages: List<String>,
    val extraScopePackages: List<String>
)

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

        fun isCardAvailable(card: FeatureCard, installedPackages: Set<String>): Boolean {
            return when (val availability = card.availability) {
                is FeatureAvailability.RequiresInstalled ->
                    availability.packageName in installedPackages
                FeatureAvailability.AlwaysAvailable -> true
            }
        }

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

        fun evaluateScopeDiagnostics(
            allCards: List<FeatureCard> = FeatureCatalog.cards,
            installedPackages: Set<String>,
            systemScopePackages: Set<String> = setOf(ScopeKeys.SYSTEM_SERVER.packageName),
            currentScope: Set<String>?,
            isServiceConnected: Boolean,
            dismissedPackages: Set<String> = emptySet(),
            declaredScope: List<String> = emptyList()
        ): FeatureScopeDiagnostic {
            val connected = isServiceConnected && currentScope != null
            val effectiveScope = if (connected) currentScope else emptySet()
            val installedAvailable = installedPackages.isNotEmpty()
            val visibleCards = allCards.filter { isCardAvailable(it, installedPackages) }
            val missingPackages = computeMissingScopePackages(
                visibleCards = visibleCards,
                installedPackages = installedPackages,
                systemScopePackages = systemScopePackages,
                currentScope = effectiveScope,
                isServiceConnected = connected
            )
            val affectedCards = if (connected && installedAvailable) {
                visibleCards.mapNotNull { card ->
                    if (card.destination == FeatureDestination.UiComponents) return@mapNotNull null
                    val cardMissing = card.scopes()
                        .asSequence()
                        .map { it.packageName }
                        .distinct()
                        .filter { it in installedPackages || it in systemScopePackages }
                        .filter { it !in effectiveScope }
                        .toList()
                    if (cardMissing.isEmpty()) null else FeatureCardMissingScope(card, cardMissing)
                }
            } else {
                emptyList()
            }
            val extraScopePackages = if (connected) {
                val declaredSet = declaredScope.toSet()
                effectiveScope.filter { it !in declaredSet }.sorted()
            } else {
                emptyList()
            }
            return FeatureScopeDiagnostic(
                isServiceConnected = connected,
                installedPackagesAvailable = installedAvailable,
                activeScopeCount = effectiveScope.size,
                missingPackages = missingPackages,
                affectedCards = affectedCards,
                dismissedPackages = dismissedPackages.sorted(),
                extraScopePackages = extraScopePackages
            )
        }

        fun shouldShowMissingScopePrompt(
            missingPackages: List<String>,
            dismissedPackages: Set<String>
        ): Boolean {
            return missingPackages.isNotEmpty() && missingPackages.any { it !in dismissedPackages }
        }
    }
}
