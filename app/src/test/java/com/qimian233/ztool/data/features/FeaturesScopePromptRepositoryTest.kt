package com.qimian233.ztool.data.features

import com.qimian233.ztool.R
import com.qimian233.ztool.data.keys.ScopeKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesScopePromptRepositoryTest {

    @Test
    fun computeMissingScopePackages_returnsEmptyWhenDisconnected() {
        val result = FeaturesScopePromptRepository.computeMissingScopePackages(
            visibleCards = FeatureCatalog.cards,
            installedPackages = setOf(ScopeKeys.SETTINGS.packageName, ScopeKeys.SYSTEM_UI.packageName),
            systemScopePackages = setOf(ScopeKeys.SYSTEM_SERVER.packageName),
            currentScope = emptySet(),
            isServiceConnected = false
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun computeMissingScopePackages_returnsEmptyWhenInstalledPackagesEmpty() {
        val result = FeaturesScopePromptRepository.computeMissingScopePackages(
            visibleCards = FeatureCatalog.cards,
            installedPackages = emptySet(),
            systemScopePackages = setOf(ScopeKeys.SYSTEM_SERVER.packageName),
            currentScope = emptySet(),
            isServiceConnected = true
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun computeMissingScopePackages_excludesUiComponentsAndDeduplicates() {
        val uiComponentsWithFixedScope = FeatureCard(
            destination = FeatureDestination.UiComponents,
            nameRes = R.string.ui_components_app_name,
            descriptionRes = R.string.ui_components_app_description,
            icon = FeatureIconSource.LocalGlyph(R.drawable.ic_feature_ui_components),
            scope = FeatureScopeSource.Fixed(listOf(ScopeKeys.SOGOU_OEM_IME)),
            availability = FeatureAvailability.AlwaysAvailable
        )
        val cards = listOf(
            FeatureCatalog.card(FeatureDestination.SystemUi),
            FeatureCatalog.card(FeatureDestination.Framework),
            FeatureCatalog.card(FeatureDestination.FreeformHub),
            uiComponentsWithFixedScope
        )
        val installed = setOf(
            ScopeKeys.SYSTEM_UI.packageName,
            ScopeKeys.SETTINGS.packageName,
            ScopeKeys.SOGOU_OEM_IME.packageName
        )

        val result = FeaturesScopePromptRepository.computeMissingScopePackages(
            visibleCards = cards,
            installedPackages = installed,
            systemScopePackages = setOf(ScopeKeys.SYSTEM_SERVER.packageName),
            currentScope = setOf(ScopeKeys.SETTINGS.packageName),
            isServiceConnected = true
        )

        assertEquals(
            listOf(
                ScopeKeys.SYSTEM_UI.packageName,
                ScopeKeys.SYSTEM_SERVER.packageName
            ),
            result
        )
    }

    @Test
    fun shouldShowMissingScopePrompt_reshowsOnlyWhenNewMissingPackageAppears() {
        val missing = listOf(
            ScopeKeys.SYSTEM_UI.packageName,
            ScopeKeys.SETTINGS.packageName
        )

        assertTrue(FeaturesScopePromptRepository.shouldShowMissingScopePrompt(missing, emptySet()))
        assertFalse(
            FeaturesScopePromptRepository.shouldShowMissingScopePrompt(
                missingPackages = missing,
                dismissedPackages = missing.toSet()
            )
        )
        assertFalse(
            FeaturesScopePromptRepository.shouldShowMissingScopePrompt(
                missingPackages = listOf(ScopeKeys.SYSTEM_UI.packageName),
                dismissedPackages = missing.toSet()
            )
        )
        assertTrue(
            FeaturesScopePromptRepository.shouldShowMissingScopePrompt(
                missingPackages = missing + ScopeKeys.LAUNCHER.packageName,
                dismissedPackages = missing.toSet()
            )
        )
        assertFalse(
            FeaturesScopePromptRepository.shouldShowMissingScopePrompt(
                missingPackages = emptyList(),
                dismissedPackages = emptySet()
            )
        )
    }

    @Test
    fun isCardAvailable_respectsAvailabilityContract() {
        val alwaysCard = FeatureCatalog.card(FeatureDestination.Framework)
        val systemUiCard = FeatureCatalog.card(FeatureDestination.SystemUi)

        assertTrue(FeaturesScopePromptRepository.isCardAvailable(alwaysCard, emptySet()))
        assertFalse(FeaturesScopePromptRepository.isCardAvailable(systemUiCard, emptySet()))
        assertTrue(
            FeaturesScopePromptRepository.isCardAvailable(
                systemUiCard,
                setOf(ScopeKeys.SYSTEM_UI.packageName)
            )
        )
    }

    @Test
    fun evaluateScopeDiagnostics_reportsMissingAffectedCardsExtraAndDismissed() {
        val installed = setOf(
            ScopeKeys.SYSTEM_UI.packageName,
            ScopeKeys.SETTINGS.packageName,
            ScopeKeys.LAUNCHER.packageName
        )
        val currentScope = setOf(
            ScopeKeys.SETTINGS.packageName,
            "com.example.zuiapp"
        )
        val cards = listOf(
            FeatureCatalog.card(FeatureDestination.SystemUi),
            FeatureCatalog.card(FeatureDestination.Framework),
            FeatureCatalog.card(FeatureDestination.Ota)
        )

        val diag = FeaturesScopePromptRepository.evaluateScopeDiagnostics(
            allCards = cards,
            installedPackages = installed,
            currentScope = currentScope,
            isServiceConnected = true,
            dismissedPackages = setOf(ScopeKeys.SYSTEM_SERVER.packageName, ScopeKeys.SYSTEM_UI.packageName),
            declaredScope = listOf(
                ScopeKeys.SYSTEM_SERVER.packageName,
                ScopeKeys.SYSTEM_UI.packageName,
                ScopeKeys.SETTINGS.packageName,
                ScopeKeys.OTA.packageName
            )
        )

        assertTrue(diag.isServiceConnected)
        assertTrue(diag.installedPackagesAvailable)
        assertEquals(2, diag.activeScopeCount)
        assertEquals(
            listOf(ScopeKeys.SYSTEM_UI.packageName, ScopeKeys.SYSTEM_SERVER.packageName),
            diag.missingPackages
        )
        assertEquals(2, diag.affectedCards.size)
        assertEquals(FeatureDestination.SystemUi, diag.affectedCards[0].card.destination)
        assertEquals(listOf(ScopeKeys.SYSTEM_UI.packageName), diag.affectedCards[0].missingPackages)
        assertEquals(FeatureDestination.Framework, diag.affectedCards[1].card.destination)
        assertEquals(
            listOf(ScopeKeys.SYSTEM_SERVER.packageName, ScopeKeys.SYSTEM_UI.packageName),
            diag.affectedCards[1].missingPackages
        )
        assertEquals(listOf("com.example.zuiapp"), diag.extraScopePackages)
        assertEquals(
            listOf(ScopeKeys.SYSTEM_UI.packageName, ScopeKeys.SYSTEM_SERVER.packageName),
            diag.dismissedPackages
        )
    }

    @Test
    fun evaluateScopeDiagnostics_returnsEmptyMissingWhenAllRequiredScopesSelected() {
        val installed = setOf(ScopeKeys.SYSTEM_UI.packageName)
        val currentScope = setOf(
            ScopeKeys.SYSTEM_UI.packageName,
            ScopeKeys.SYSTEM_SERVER.packageName
        )
        val diag = FeaturesScopePromptRepository.evaluateScopeDiagnostics(
            allCards = listOf(
                FeatureCatalog.card(FeatureDestination.SystemUi),
                FeatureCatalog.card(FeatureDestination.Framework)
            ),
            installedPackages = installed,
            currentScope = currentScope,
            isServiceConnected = true
        )

        assertTrue(diag.missingPackages.isEmpty())
        assertTrue(diag.affectedCards.isEmpty())
    }

    @Test
    fun evaluateScopeDiagnostics_handlesDisconnectedAndEmptyInstalledPackages() {
        val disconnected = FeaturesScopePromptRepository.evaluateScopeDiagnostics(
            allCards = listOf(FeatureCatalog.card(FeatureDestination.SystemUi)),
            installedPackages = setOf(ScopeKeys.SYSTEM_UI.packageName),
            currentScope = null,
            isServiceConnected = false
        )
        assertFalse(disconnected.isServiceConnected)
        assertTrue(disconnected.missingPackages.isEmpty())
        assertTrue(disconnected.affectedCards.isEmpty())
        assertTrue(disconnected.extraScopePackages.isEmpty())

        val noInstalled = FeaturesScopePromptRepository.evaluateScopeDiagnostics(
            allCards = listOf(FeatureCatalog.card(FeatureDestination.Framework)),
            installedPackages = emptySet(),
            currentScope = setOf(ScopeKeys.SETTINGS.packageName),
            isServiceConnected = true,
            declaredScope = listOf(ScopeKeys.SYSTEM_SERVER.packageName)
        )
        assertTrue(noInstalled.isServiceConnected)
        assertFalse(noInstalled.installedPackagesAvailable)
        assertTrue(noInstalled.missingPackages.isEmpty())
        assertTrue(noInstalled.affectedCards.isEmpty())
        assertEquals(listOf(ScopeKeys.SETTINGS.packageName), noInstalled.extraScopePackages)
    }
}
