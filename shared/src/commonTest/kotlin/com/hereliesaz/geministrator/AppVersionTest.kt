package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertTrue

class AppVersionTest {
    /** The Keeper's room shows this line first; a build that lost it would show nothing to tell builds apart. */
    @Test
    fun theBuildCarriesItsVersion() {
        assertTrue(APP_VERSION_NAME.isNotBlank())
        // MAJOR.MINOR.PATCH.BUILD, or "dev" for a build given no version.
        assertTrue(APP_VERSION_NAME == "dev" || APP_VERSION_NAME.split('.').size == 4, APP_VERSION_NAME)
    }
}
