package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.queen.CallDecision
import com.hereliesaz.geministrator.queen.GateReason
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reviewer is skipped when the gate says so, ideas beyond the requirements are held, and the planner's Source is passed on. */
class PlanReviewGateTest {
    private val pm = BuiltInRoles.ProductManager.id.value
    private fun plan(vararg ids: String) = OrchestrationPlan(ids.map { OrchestrationPlanStep(it, it, "Do $it", pm, emptyList()) })
    private val packet = OrchestrationPacket(
        objective = "Ship it",
        availableAgents = listOf(OrchestrationRole(pm, "Product Manager", emptyList())),
        instruction = "Plan.",
    )

    private class Planner(private val reworks: MutableList<OrchestrationPlan>) : OrchestrationAgentRuntime {
        override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = error("not asked")
        override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = reworks.removeAt(0)
    }

    private class Reviewer(private val verdicts: MutableList<PlanVerdict>) : PlanReviewer {
        val requests = mutableListOf<PlanReviewRequest>()
        override suspend fun review(request: PlanReviewRequest): PlanVerdict {
            requests += request
            return verdicts.removeAt(0)
        }
    }

    private suspend fun review(
        planner: Planner,
        reviewer: Reviewer,
        gate: (suspend (Int) -> CallDecision)? = null,
        source: () -> String? = { null },
    ) = reviewUntilApproved(
        packet, plan("a"), planner, reviewer, "Reviewer", "Check it.", pinnedProviderId = null,
        plannerSource = source, reviewGate = gate,
    )

    @Test
    fun aSkippedReviewerLeavesTheChecklistAsTheWholeReview() = runBlocking {
        val reviewer = Reviewer(mutableListOf())
        val outcome = review(Planner(mutableListOf()), reviewer, gate = { CallDecision.ChecklistOnly(GateReason.NoRoom) })

        assertEquals(GateReason.NoRoom, outcome.skipped)
        assertTrue(outcome.approved)
        assertTrue(reviewer.requests.isEmpty())
        assertEquals(1, outcome.rounds.size)
        assertTrue(planReviewNotes("Reviewer", outcome).single().contains("checklist was the whole review"))
    }

    @Test
    fun aReviewerSkippedAfterARejectionDoesNotApproveTheReworkedPlanUnseen() = runBlocking {
        val reviewer = Reviewer(mutableListOf(PlanVerdict.Reject("Add a test.")))
        val seen = mutableListOf<Int>()
        val outcome = review(
            Planner(mutableListOf(plan("b"))), reviewer,
            gate = { calls -> seen += calls; if (calls < 3) CallDecision.Allow else CallDecision.ChecklistOnly(GateReason.UnpricedUnderCap) },
        )

        assertEquals(listOf(1, 3), seen)
        assertEquals(GateReason.UnpricedUnderCap, outcome.skipped)
        assertFalse(outcome.approved)
        assertEquals(plan("b"), outcome.plan)
        assertEquals(1, outcome.rounds.size)
    }

    @Test
    fun noGateAlwaysAsksAndAnAllowAsks() = runBlocking {
        val asked = Reviewer(mutableListOf(PlanVerdict.Approve("Fine.")))
        val outcome = review(Planner(mutableListOf()), asked, gate = { CallDecision.Allow })

        assertTrue(outcome.approved)
        assertEquals(null, outcome.skipped)
        assertEquals(1, asked.requests.size)
    }

    @Test
    fun aRefusedCallIsAlsoASkip() = runBlocking {
        val outcome = review(Planner(mutableListOf()), Reviewer(mutableListOf()), gate = { CallDecision.Refuse(GateReason.CallLimit) })
        assertEquals(GateReason.CallLimit, outcome.skipped)
    }

    @Test
    fun theReviewerIsToldWhichSourceDraftedThePlan() = runBlocking {
        val reviewer = Reviewer(mutableListOf(PlanVerdict.Approve("Fine.")))
        review(Planner(mutableListOf()), reviewer, source = { "gemini" })
        assertEquals("gemini", reviewer.requests.single().plannerSourceId)
    }

    @Test
    fun ideasBeyondTheRequirementsAreReadFromSuggestLinesAndKeptApartFromTheReasons() {
        val verdict = parsePlanVerdict(
            "VERDICT: APPROVE\nFine.\nSUGGEST | AddCheck | Add a test | Catches a regression | a\nSUGGEST | Bogus | x | y | -\nSUGGEST | Other | Rename it | Clearer | -",
        )
        assertEquals("Fine.", verdict?.reasons)
        assertEquals(listOf(PlanSuggestionKind.AddCheck, PlanSuggestionKind.Other), verdict?.suggestions?.map { it.kind })
        assertEquals("a", verdict?.suggestions?.first()?.stepId)
    }

    @Test
    fun theOutcomeKeepsAtMostThreeDistinctIdeasAcrossRounds() {
        fun idea(n: Int) = PlanSuggestion(PlanSuggestionKind.AddCheck, "idea $n", "why", null)
        val rounds = listOf(
            PlanReviewRound(plan("a"), PlanVerdict.Reject("No.", listOf(idea(1), idea(2)))),
            PlanReviewRound(plan("a"), PlanVerdict.Approve("Yes.", listOf(idea(2), idea(3), idea(4)), answeredBy = "openai")),
        )
        val outcome = PlanReviewOutcome(plan("a"), rounds)

        assertEquals(listOf("idea 2", "idea 3", "idea 4"), outcome.suggestions.map { it.text })
        assertEquals("openai", outcome.reviewerSource)
    }

    @Test
    fun theReviewPromptAsksForIdeasAndSaysTheyNeverChangeThePlan() {
        val prompt = planReviewPrompt(PlanReviewRequest(packet, plan("a"), "Reviewer", "Check it.", null))
        assertTrue("SUGGEST | kind" in prompt && "never change this plan" in prompt)
        assertTrue(PlanSuggestionKind.entries.all { it.name in prompt })
    }
}
