package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroundSwatchesTest {
    @Test
    fun everyGroundIsFoundByItsSavedName() {
        Azphalt.grounds.forEach { ground -> assertEquals(ground, Azphalt.groundNamed(ground.name)) }
    }

    @Test
    fun anUnknownOrMissingNameIsNoGround() {
        assertNull(Azphalt.groundNamed("Chartreuse"))
        assertNull(Azphalt.groundNamed("teal"))
        assertNull(Azphalt.groundNamed(null))
    }

    @Test
    fun theSwatchesAreSixDistinctGrounds() {
        assertEquals(6, Azphalt.grounds.size)
        assertEquals(6, Azphalt.grounds.map { it.name }.toSet().size)
    }
}
