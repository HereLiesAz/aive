package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class CapillaryNamesTest {
    private val scene = TissueScene(
        capillaries = emptyList(),
        providerOf = mapOf(
            "review" to ProviderCatalog.ANTHROPIC_ID,
            "contract" to ProviderCatalog.ANTHROPIC_ID,
            "implement" to ProviderCatalog.GEMINI_ID,
        ),
        egg = null,
    )

    @Test
    fun aCapillaryFeedsTheTasksAssignedToItsProviderInIdOrder() {
        assertEquals(listOf("contract", "review"), scene.fedBy(ProviderCatalog.ANTHROPIC_ID))
        assertEquals(listOf("implement"), scene.fedBy(ProviderCatalog.GEMINI_ID))
        assertEquals(emptyList(), scene.fedBy("nobody"))
    }

    @Test
    fun aCapillaryCarriesItsProvidersCatalogNameElseItsId() {
        assertEquals("Claude", capillaryName(ProviderCatalog.ANTHROPIC_ID))
        assertEquals("Gemini", capillaryName(ProviderCatalog.GEMINI_ID))
        assertEquals("some-local-model", capillaryName("some-local-model"))
    }

    @Test
    fun theCreaturesFedAreNamedOnceEachInOrder() {
        assertEquals("Architect, Engineer", capillaryFeeds(listOf("Architect", "Engineer", "Architect")))
        assertEquals("Engineer", capillaryFeeds(listOf(" Engineer ", "")))
        assertEquals("", capillaryFeeds(emptyList()))
    }

    @Test
    fun aCapillarysNameIsNeverUpsideDown() {
        assertEquals(0f, capillaryLabelAngle(0f))
        assertEquals(30f, capillaryLabelAngle(30f))
        assertEquals(90f, capillaryLabelAngle(90f))
        assertEquals(90f, capillaryLabelAngle(-90f))
        assertEquals(0f, capillaryLabelAngle(180f))
        assertEquals(0f, capillaryLabelAngle(-180f))
        assertEquals(-30f, capillaryLabelAngle(150f))
        assertEquals(30f, capillaryLabelAngle(-150f))
        assertEquals(10f, capillaryLabelAngle(370f))
    }
}
