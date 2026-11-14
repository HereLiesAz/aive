package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.QUEEN_DISPLAY_NAME
import com.hereliesaz.geministrator.domain.keeperName
import com.hereliesaz.geministrator.domain.roleDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

class RoleDisplayNameTest {
    @Test
    fun theOrchestratorIsTheQueenOnScreenOnly() {
        assertEquals(QUEEN_DISPLAY_NAME, BuiltInRoles.Orchestrator.keeperName())
        assertEquals("Queen", roleDisplayName("orchestrator", "Orchestrator"))
        assertEquals("Queen", roleDisplayName("Orchestrator"))
        // Saved data and logic keep the stored id and name.
        assertEquals("orchestrator", BuiltInRoles.Orchestrator.id.value)
        assertEquals("Orchestrator", BuiltInRoles.Orchestrator.name)
    }

    @Test
    fun everyOtherRoleKeepsItsName() {
        assertEquals(BuiltInRoles.Architect.name, BuiltInRoles.Architect.keeperName())
        assertEquals("Test Runner", roleDisplayName("Test Runner"))
    }
}
