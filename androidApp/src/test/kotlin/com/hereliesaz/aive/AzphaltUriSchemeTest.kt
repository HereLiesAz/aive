package com.hereliesaz.aive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AzphaltUriSchemeTest {
    @Test
    fun onlyContentUrisAreOpened() {
        assertTrue(isAcceptableAzphaltUriScheme("content"))
        assertFalse(isAcceptableAzphaltUriScheme("file"))
        assertFalse(isAcceptableAzphaltUriScheme("http"))
        assertFalse(isAcceptableAzphaltUriScheme(null))
    }
}
