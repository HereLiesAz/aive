package com.hereliesaz.geministrator.azphalt

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AzphaltDowngradeGuardTest {
    @Test
    fun olderVersionIsRefusedNewerAndEqualAndFreshAreAllowed() {
        assertFailsWith<IllegalArgumentException> { requireNotDowngrade("p", "2.0.0", "1.9.9") }
        assertFailsWith<IllegalArgumentException> { requireNotDowngrade("p", "1.10", "1.9.9") }
        requireNotDowngrade("p", "1.0.0", "1.0.0")
        requireNotDowngrade("p", "1.0.0", "1.0.1")
        requireNotDowngrade("p", null, "0.0.1")
        requireNotDowngrade("p", "weird", "1.0.0")
    }

    @Test
    fun preReleasesRankBelowTheReleaseAndUnreadableVersionsAreNotUpgrades() {
        assertFailsWith<IllegalArgumentException> { requireNotDowngrade("p", "1.0.0", "1.0.0-rc1") }
        requireNotDowngrade("p", "1.0.0-rc1", "1.0.0")
        requireNotDowngrade("p", "1.0.0-rc1", "1.0.0-rc2")
        assertFailsWith<IllegalArgumentException> { requireNotDowngrade("p", "1.0.0", "v2") }
    }
}
