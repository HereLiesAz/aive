package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.BuiltInRoles
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The roles a run is made with use the keeper's selected generation, and fall back to the generic parent. */
class RoleResolutionTest {
    private val planner = BuiltInRoles.Planner
    private val worker = BuiltInRoles.Worker

    private fun revision(role: com.hereliesaz.geministrator.domain.RoleDefinition, version: Int, wording: String, status: TemplateStatus = TemplateStatus.Approved, at: Long = 10) =
        RoleTemplates.base(role).let { base ->
            TemplateRevision(
                kind = TemplateKind.Role, templateId = role.id.value, version = version, parentVersion = 1, generation = 1,
                mutation = setOf(TunableField.Wording), body = base.body.copy(wording = wording, budgetTokens = 5_000L), locked = base.locked,
                before = role.instructions, after = wording, fitness = "f", proposalId = "p$version", status = status, adoptedAtEpochMillis = at,
            )
        }

    @AfterTest
    fun reset() {
        LaunchRoles.store = null
    }

    @Test
    fun aSelectedRevisionIsWhatTheRunUsesAndOtherRolesAreUntouched() {
        val resolved = RoleTemplates.resolveAll(listOf(planner, worker), listOf(revision(planner, 2, "Plan in five steps.")))
        assertEquals("Plan in five steps.", resolved[0].instructions)
        assertEquals(5_000L, resolved[0].tokenBudget)
        assertEquals(planner.capabilitiesRequired, resolved[0].capabilitiesRequired)
        assertEquals(planner.authorities, resolved[0].authorities)
        assertSame(worker, resolved[1])
    }

    @Test
    fun aRetiredOrCandidateRevisionFallsBackToTheGenericParent() {
        val retired = revision(planner, 2, "Retired.", status = TemplateStatus.Reverted)
        val candidate = revision(planner, 3, "Candidate.", status = TemplateStatus.Candidate)
        assertEquals(listOf(planner), RoleTemplates.resolveAll(listOf(planner), listOf(retired, candidate)))
    }

    @Test
    fun theLatestSelectionWinsAndAKeepersOwnEditStillWins() {
        val older = revision(planner, 2, "Older.", at = 10)
        val newer = revision(planner, 3, "Newer.", at = 20)
        assertEquals("Newer.", RoleTemplates.resolveAll(listOf(planner), listOf(older, newer)).single().instructions)
        val edited = planner.copy(instructions = "The keeper wrote this.")
        assertEquals(listOf(edited), RoleTemplates.resolveAll(listOf(edited), listOf(older, newer)))
    }

    @Test
    fun aRevisionWhoseLockedFieldsNoLongerMatchTheRoleIsNeverUsed() {
        val forged = revision(planner, 2, "Forged.").copy(locked = RoleTemplates.base(planner).locked + ("capabilities" to "ShellExecution"))
        assertEquals(listOf(planner), RoleTemplates.resolveAll(listOf(planner), listOf(forged)))
    }

    @Test
    fun launchRolesReadTheStoreAndNoRevisionsLeavesThemAsTheyAre() = runTest {
        val store = InMemoryTemplateStore()
        store.adopt(revision(planner, 2, "Plan in five steps."))
        LaunchRoles.store = store
        assertEquals("Plan in five steps.", LaunchRoles.resolve(listOf(planner)).single().instructions)
        LaunchRoles.store = InMemoryTemplateStore()
        assertEquals(listOf(planner), LaunchRoles.resolve(listOf(planner)))
    }
}
