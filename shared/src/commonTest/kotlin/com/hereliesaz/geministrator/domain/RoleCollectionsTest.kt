package com.hereliesaz.geministrator.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleCollectionsTest {
    @Test
    fun emptyStorageResolvesToDefaultBuiltInOrder() {
        val resolved = resolveRoleCollection(emptyList())

        assertEquals(aiveBuiltInRoles.map { it.id }, activeRoles(resolved).map { it.id })
        assertTrue(activeRoles(resolved).any { it.id == HallMonitorRole.id })
    }

    @Test
    fun savedCollectionPreservesOrderAndTombstonesRemovedRoles() {
        val custom = RoleDefinition(
            id = RoleDefinitionId("custom-builder"),
            name = "Custom Builder",
            description = "Builds things",
            instructions = "Implement the requested change.",
            authorities = setOf(RoleAuthority.Implement),
        )
        val active = listOf(custom, BuiltInRoles.Orchestrator, BuiltInRoles.QaEngineer)
        val stored = roleCollectionEntries(active, emptyList())
        val resolved = resolveRoleCollection(stored)

        // The Antagonist is a required agent, so it joins the active roster though the saved collection left it out.
        assertEquals(active.map { it.id } + BuiltInRoles.Antagonist.id, activeRoles(resolved).map { it.id })
        val removedDefault = resolved.first { it.id == BuiltInRoles.ImplementationEngineer.id }
        assertFalse(removedDefault.enabled)
        assertTrue(resolved.any { it.id == custom.id && it.enabled })
        assertFalse(resolved.first { it.id == HallMonitorRole.id }.enabled)
    }

    @Test
    fun resetRestoresDefaultsAndKeepsCustomRoleForHistoricalRuns() {
        val custom = RoleDefinition(
            id = RoleDefinitionId("custom-reviewer"),
            name = "Custom Reviewer",
            description = "Reviews",
            instructions = "Review independently.",
            authorities = setOf(RoleAuthority.ReviewCode),
        )
        val customized = roleCollectionEntries(listOf(custom), emptyList())
        val reset = defaultRoleCollectionEntries(customized)
        val resolved = resolveRoleCollection(reset)

        assertEquals(aiveBuiltInRoles.map { it.id }, activeRoles(resolved).map { it.id })
        assertFalse(resolved.first { it.id == custom.id }.enabled)
    }

    @Test
    fun theAntagonistIsAlwaysInTheActiveRosterAndKeepsTheKeepersEdits() {
        val edited = BuiltInRoles.Antagonist.copy(keeperInstructions = "Be thorough.", enabled = false)
        val resolved = resolveRoleCollection(roleCollectionEntries(listOf(BuiltInRoles.Orchestrator), listOf(edited)))
        val antagonist = activeRoles(resolved).single { it.id == BuiltInRoles.Antagonist.id }
        assertEquals("Be thorough.", antagonist.keeperInstructions)
        // Nothing stored at all: still there.
        assertTrue(activeRoles(resolveRoleCollection(emptyList())).any { it.id == BuiltInRoles.Antagonist.id })
    }
}
