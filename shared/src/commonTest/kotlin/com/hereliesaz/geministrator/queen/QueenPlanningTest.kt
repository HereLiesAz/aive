package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueenPlanningTest {
    private val dispute = Dispute(
        question = "Run the step before its review?",
        options = listOf(DisputeOption("wait", "Wait for the review", safe = true), DisputeOption("skip", "Skip the gate", safe = false)),
        facts = mapOf("f1" to "The step changes files."),
    )

    @Test
    fun theQueenStaffsAGenericPlannerAndReviewer() {
        val staffing = staffQueen(BuiltInRoles.all)
        assertEquals(BuiltInRoles.Planner.id, staffing.staff?.planner?.id)
        assertEquals(BuiltInRoles.Reviewer.id, staffing.staff?.reviewer?.id)
        assertTrue(staffing.missing.isEmpty())
    }

    @Test
    fun aDisabledPlannerIsReportedMissingWithHowToFixIt() {
        val roles = BuiltInRoles.all.map { if (it.id == BuiltInRoles.Planner.id) it.copy(enabled = false) else it }
        val staffing = staffQueen(roles)
        assertNull(staffing.staff)
        assertEquals(listOf(Missing(MissingKind.NoPlanner, "Planner", MissingFix.EnableRole)), staffing.missing)
    }

    @Test
    fun aPlannerThatCanWriteBreaksTheRules() {
        val writer = BuiltInRoles.ImplementationEngineer.copy(id = BuiltInRoles.Planner.id)
        val roles = BuiltInRoles.all.map { if (it.id == BuiltInRoles.Planner.id) writer else it }
        val staffing = staffQueen(roles)
        assertNull(staffing.staff)
        assertEquals(MissingKind.PairBroken, staffing.missing.single().kind)
    }

    @Test
    fun theQueenIsNeverHerOwnReviewer() {
        val problems = PlanningIntegrity.violations(BuiltInRoles.Planner, BuiltInRoles.Orchestrator, BuiltInRoles.Orchestrator)
        assertTrue(problems.any { "queen" in it })
        assertTrue(PlanningIntegrity.violations(BuiltInRoles.Planner, BuiltInRoles.Reviewer, BuiltInRoles.Orchestrator).isEmpty())
    }

    @Test
    fun noSourceAndSameSourceAreMissingThings() {
        assertEquals(listOf(Missing(MissingKind.NoSource, null, MissingFix.ConnectSource)), missingForPlanning(emptyList(), null, null))
        assertEquals(MissingKind.SameSource, missingForPlanning(listOf("a"), null, null).single().kind)
        assertTrue(missingForPlanning(listOf("a", "b"), null, null).isEmpty())
        assertTrue(missingForPlanning(listOf("a"), "a", "a").isEmpty(), "A keeper's pin is her choice")
    }

    @Test
    fun aValidSafeRulingThatCitesAFactIsTaken() {
        val ruled = acceptRuling(dispute, "RULING: wait\nREASON: The step changes files [f1].")
        assertEquals("wait", ruled?.optionId)
        assertTrue(ruled!!.byModel)
    }

    @Test
    fun aRulingThatRelaxesAGateOrCitesNothingOrNamesAnUnknownOptionIsRefused() {
        assertNull(acceptRuling(dispute, "RULING: skip\nREASON: Faster [f1]."))
        assertNull(acceptRuling(dispute, "RULING: wait\nREASON: Seems fine."))
        assertNull(acceptRuling(dispute, "RULING: invent\nREASON: Because [f1]."))
        assertNull(acceptRuling(dispute, "no ruling at all"))
    }

    @Test
    fun anInvalidOrUnreachableModelFallsBackToTheRules() = runBlocking {
        val bad = settleDispute(dispute, QueenModel { "RULING: skip\nREASON: [f1]" })
        assertEquals("wait", (bad as DisputeOutcome.Ruled).optionId)
        assertTrue(!bad.byModel)
        val down = settleDispute(dispute, QueenModel { error("offline") })
        assertEquals("wait", (down as DisputeOutcome.Ruled).optionId)
        val twoSafe = dispute.copy(options = dispute.options.map { it.copy(safe = true) })
        assertTrue(settleDispute(twoSafe, null) is DisputeOutcome.NeedsKeeper)
    }

    @Test
    fun anOldPlanReviewerIdIsAnAliasOfTheReviewer() {
        assertTrue("plan-reviewer" in BuiltInRoles.ReviewerAliases)
        assertEquals(RoleDefinitionId("reviewer"), BuiltInRoles.Reviewer.id)
    }
}
