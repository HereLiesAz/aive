package com.hereliesaz.aive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidUpdateUiTest {
    @Test
    fun semanticVersionComparisonOnlyAcceptsNewerVersions() {
        assertTrue(isNewerVersion("0.9.6", "0.9.5"))
        assertTrue(isNewerVersion("0.10.0", "0.9.99"))
        assertTrue(isNewerVersion("1.0.0", "0.99.99"))
        assertFalse(isNewerVersion("0.9.5", "0.9.5"))
        assertFalse(isNewerVersion("0.9.4", "0.9.5"))
        assertFalse(isNewerVersion("v0.9.5", "0.9.5"))
    }
}
