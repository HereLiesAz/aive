package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopProviderCredentialStoreTest {
    @Test
    fun aKeylessLinkNeedsNoKeychainAndClearsCleanly() {
        val store = DesktopProviderCredentialStore()
        val keyless = ProviderCatalog.builtInEntries.first { it.keyOptional }.id
        try {
            // Holds no secret, so it is kept even where no keychain exists (a bare Linux box).
            store.write(keyless, ProviderCatalog.ANONYMOUS_CREDENTIAL)
            assertEquals(ProviderCatalog.ANONYMOUS_CREDENTIAL, store.read(keyless))
            store.clear(keyless)
            assertNull(store.read(keyless))
        } finally {
            store.clear(keyless)
        }
    }
}
