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
}
