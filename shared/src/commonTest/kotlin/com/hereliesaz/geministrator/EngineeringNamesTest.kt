package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineeringNamesTest {
    @Test
    fun offKeepsThePlainName() {
        assertEquals("Nanny", engineeringName("Nanny", ENGINEERING_NANNY, show = false))
    }

    @Test
    fun onPutsTheEngineeringNameBesideIt() {
        assertEquals("Nanny (Hall Monitor)", engineeringName("Nanny", ENGINEERING_NANNY, show = true))
        assertEquals("Orphan (A2A agent)", engineeringName("Orphan", ENGINEERING_ORPHAN, show = true))
        assertEquals("Swarm (workflow)", engineeringName("Swarm", ENGINEERING_SWARM, show = true))
    }
}
