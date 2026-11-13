package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.orchestration.PlanReviewRequest
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The plan reviewer on the linked LLMs: which one answers, and what happens when one cannot. */
class TextGenerationPlanReviewerTest {
    private class FakeApi(private val reply: () -> String) : TextGenerationApi {
        val prompts = mutableListOf<String>()
        override suspend fun generate(prompt: String): TextGenerationResult {
            prompts += prompt
            return TextGenerationResult(text = reply())
        }
    }

    private fun entry(id: String, api: TextGenerationApi) = PlanningApiEntry(id, id.replaceFirstChar(Char::uppercase), api)

    private fun request(pinned: String? = null, plannerSource: String? = null) = PlanReviewRequest(
        packet = OrchestrationPacket(
            objective = "Ship it",
            availableAgents = listOf(OrchestrationRole("engineer", "Engineer", emptyList())),
            instruction = "Plan.",
        ),
        plan = OrchestrationPlan(listOf(OrchestrationPlanStep("a", "A", "Do a", "engineer"))),
        reviewerName = "Adversarial Reviewer",
        instructions = "Be cynical.",
        pinnedProviderId = pinned,
        plannerSourceId = plannerSource,
    )

    @Test
    fun theFirstLinkedLlmReviewsAndItsVerdictIsParsed() = runTest {
        val first = FakeApi { "VERDICT: REJECT\nNo tests." }
        val second = FakeApi { "VERDICT: APPROVE" }

        val verdict = TextGenerationPlanReviewer(listOf(entry("gemini", first), entry("openai", second))).review(request())

        assertEquals(PlanVerdict.Reject("No tests.", answeredBy = "gemini"), verdict)
        assertTrue(second.prompts.isEmpty())
        assertTrue("Be cynical." in first.prompts.single() && "Objective: Ship it" in first.prompts.single())
    }

    @Test
    fun aReviewerLeftToTheAppMovesOnWhenAnLlmFailsOrGivesNoVerdict() = runTest {
        val down = FakeApi { error("HTTP 503") }
        val rambling = FakeApi { "It looks plausible to me." }
        val good = FakeApi { "VERDICT: APPROVE\nFine." }

        val verdict = TextGenerationPlanReviewer(listOf(entry("gemini", down), entry("openai", rambling), entry("anthropic", good))).review(request())

        assertEquals(PlanVerdict.Approve("Fine.", answeredBy = "anthropic"), verdict)
        assertEquals(1, down.prompts.size)
        assertEquals(1, rambling.prompts.size)
    }

    @Test
    fun aPinnedReviewerNeverSwitchesProviders() = runTest {
        val pinned = FakeApi { error("HTTP 503") }
        val other = FakeApi { "VERDICT: APPROVE" }
        val reviewer = TextGenerationPlanReviewer(listOf(entry("gemini", pinned), entry("openai", other)))

        val failure = assertFailsWith<IllegalStateException> { reviewer.review(request(pinned = "gemini")) }

        assertEquals("HTTP 503", failure.message)
        assertTrue(other.prompts.isEmpty(), "The keeper's choice of provider is not overridden")
    }

    @Test
    fun aPinnedReviewerWhoseProviderIsNotLinkedSaysSo() = runTest {
        val reviewer = TextGenerationPlanReviewer(listOf(entry("openai", FakeApi { "VERDICT: APPROVE" })))

        val failure = assertFailsWith<IllegalStateException> { reviewer.review(request(pinned = "gemini")) }

        assertTrue("pinned to gemini" in failure.message.orEmpty())
    }

    @Test
    fun anUnpinnedReviewerAsksAnotherSourceThanThePlannersFirst() = runTest {
        val planners = FakeApi { "VERDICT: REJECT\nSame Source." }
        val other = FakeApi { "VERDICT: APPROVE\nSUGGEST | AddCheck | Add a test | Catches a regression | a" }
        val reviewer = TextGenerationPlanReviewer(listOf(entry("gemini", planners), entry("openai", other)))

        val verdict = reviewer.review(request(plannerSource = "gemini"))

        assertEquals("openai", verdict.answeredBy)
        assertTrue(planners.prompts.isEmpty())
        assertEquals(1, verdict.suggestions.size)
        assertTrue("SUGGEST | kind" in other.prompts.single())
    }

    @Test
    fun theSameSourceReviewsWhenItIsTheOnlyOne() = runTest {
        val only = FakeApi { "VERDICT: APPROVE" }

        val verdict = TextGenerationPlanReviewer(listOf(entry("gemini", only))).review(request(plannerSource = "gemini"))

        assertEquals("gemini", verdict.answeredBy)
    }

    @Test
    fun itSaysWhichSourcesItMayAskForThePriceCheck() {
        val uses = TextGenerationPlanReviewer(listOf(entry("gemini", FakeApi { "" }))).uses

        assertEquals(listOf("gemini"), uses.map { it.sourceId })
        assertTrue(uses.single().billsPerToken)
    }

    @Test
    fun noLinkedLlmMeansNoReviewer() {
        assertEquals(null, configuredPlanReviewer(emptyMap()))
    }
}
