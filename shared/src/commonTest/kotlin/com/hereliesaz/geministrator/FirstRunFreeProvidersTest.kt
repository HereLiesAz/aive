package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunFreeProvidersTest {
    @Test
    fun theFirstRunOffersOnlyKeylessProvidersThatWriteText() {
        val offered = ProviderCatalog.firstRunFreeEntries()
        assertTrue(offered.isNotEmpty())
        assertTrue(offered.all { it.keyOptional })
        assertFalse(offered.any { it.id == ProviderCatalog.AI_HORDE_ID }, "an image generator cannot be the first provider")
        assertFalse(offered.any { it.id == ProviderCatalog.FAL_ID })
    }

    @Test
    fun theMostGenerousGatewayComesFirstAndNoMoreThanThreeAreOffered() {
        val offered = ProviderCatalog.firstRunFreeEntries()
        assertEquals(ProviderCatalog.KILO_ID, offered.first().id)
        assertTrue(offered.size <= 3)
    }
}
