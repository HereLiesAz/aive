package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the queen, the nanny and the antagonist read about a creature: what it is made of, and only the numbers it has. */
class RoleReportTest {
    private val engineer = BuiltInRoles.ImplementationEngineer

    @Test
    fun aReportNamesWhatTheRoleIsMadeOfAndLeavesOutWhatIsNotKnown() {
        val bare = roleReport(engineer, echoHundredths = null, trophicHundredths = null, traits = null, synapseCapacity = null)
        assertTrue(bare.startsWith("Implementation Engineer [implementation-engineer]"))
        assertTrue("authorities Implement" in bare && "needs RepositoryRead, RepositoryWrite" in bare)
        // An unreported number is not printed as zero.
        assertFalse("Echo" in bare || "trophic" in bare || "tier" in bare || "synapse" in bare)
    }

    @Test
    fun theNumbersItHasEarnedAreShownAsPlainCounts() {
        val child = engineer.copy(id = RoleDefinitionId("child"), parents = listOf(engineer.id, RoleDefinitionId("b")),
            toolCeiling = listOf("search", "read"), preferredProviderId = AgentProviderId("acme"))
        val traits = OperationalTraits(ModelTier.Dusk, Tolerance.Wide, null, SignalEfficiency.Fast)
        val line = roleReport(child, echoHundredths = 3_450, trophicHundredths = 1_000, traits = traits, synapseCapacity = 3)
        assertTrue("Echo 34" in line, line)
        assertTrue("trophic reserve 10 (starving)" in line, line)
        assertTrue("tier Dusk; tolerance Wide; signal Fast" in line, line)
        assertFalse("resilience" in line) // unreported stays out
        assertTrue("tools limited to read, search" in line && "prefers source acme" in line && "born of 2 parents" in line, line)
        assertTrue("synapse capacity 3" in line)
    }

    @Test
    fun everyRoleGetsAReportEvenWithNoNumbers() {
        val reports = roleReports(listOf(engineer, BuiltInRoles.QaEngineer), echo = mapOf(engineer.id to 500),
            trophic = emptyMap(), traits = emptyMap())
        assertEquals(setOf(engineer.id, BuiltInRoles.QaEngineer.id), reports.keys)
        assertTrue("Echo 5" in reports.getValue(engineer.id))
        assertFalse("Echo" in reports.getValue(BuiltInRoles.QaEngineer.id))
    }
}
