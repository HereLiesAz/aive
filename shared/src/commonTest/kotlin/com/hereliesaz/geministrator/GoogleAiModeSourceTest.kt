package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Gemini in Google Search is a Source the keeper adds on purpose, and only where the platform has a browser view. */
class GoogleAiModeSourceTest {
    @Test
    fun itIsListedOnlyWhereTheBrowserViewExistsAndNeverAsAKeylessDefault() {
        ProviderCatalog.googleAiModeAvailable = false
        try {
            assertNull(ProviderCatalog.entries.firstOrNull { it.id == ProviderCatalog.GOOGLE_AI_MODE_ID })
            ProviderCatalog.googleAiModeAvailable = true
            val entry = assertNotNull(ProviderCatalog.entry(ProviderCatalog.GOOGLE_AI_MODE_ID))
            assertTrue(entry.keyOptional)
            // Not built in, so the first launch neither connects it nor offers it as a free first Source.
            assertFalse(ProviderCatalog.builtInEntries.any { it.id == entry.id })
            assertFalse(keylessSources().any { it.id == entry.id })
            assertFalse(ProviderCatalog.firstRunFreeEntries().any { it.id == entry.id })
            assertEquals("google-ai-mode", entry.id)
        } finally {
            ProviderCatalog.googleAiModeAvailable = false
        }
    }
}
