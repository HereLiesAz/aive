package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An image-only Source (AI Horde, fal) is run by an executor, not a provider: a saved key is what says it is linked. */
class ImageProviderLinkTest {
    @Test
    fun aSavedKeyOrAnAnonymousLinkLinksAnImageSource() {
        assertEquals(setOf(ProviderCatalog.AI_HORDE_ID), ProviderCatalog.linkedImageProviderIds(mapOf(ProviderCatalog.AI_HORDE_ID to ProviderCatalog.ANONYMOUS_CREDENTIAL)))
        assertEquals(setOf(ProviderCatalog.AI_HORDE_ID), ProviderCatalog.linkedImageProviderIds(mapOf(ProviderCatalog.AI_HORDE_ID to "hordeKey123")))
        assertEquals(setOf(ProviderCatalog.FAL_ID), ProviderCatalog.linkedImageProviderIds(mapOf(ProviderCatalog.FAL_ID to "k")))
    }

    @Test
    fun nothingSavedBlankOrTextOnlyLinksNothing() {
        assertTrue(ProviderCatalog.linkedImageProviderIds(emptyMap()).isEmpty())
        assertTrue(ProviderCatalog.linkedImageProviderIds(mapOf(ProviderCatalog.AI_HORDE_ID to "  ")).isEmpty())
        // A text provider's key does not link an image Source.
        assertTrue(ProviderCatalog.linkedImageProviderIds(mapOf(ProviderCatalog.OPENAI_ID to "sk-x")).isEmpty())
    }
}
