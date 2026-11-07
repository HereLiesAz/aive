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

    private fun request(pinned: String? = null) = PlanReviewRequest(
        packet = OrchestrationPacket(
            objective = "Ship it",
            availableAgents = listOf(OrchestrationRole("engineer", "Engineer", emptyList())),
            instruction = "Plan.",
        ),
        plan = OrchestrationPlan(listOf(OrchestrationPlanStep("a", "A", "Do a", "engineer"))),
        reviewerName = "Adversarial Reviewer",
        instructions = "Be cynical.",
        pinnedProviderId = pinned,
    )

    @Test
    fun theFirstLinkedLlmReviewsAndItsVerdictIsParsed() = runTest {
        val first = FakeApi { "VERDICT: REJECT\nNo tests." }
        val second = FakeApi { "VERDICT: APPROVE" }

        val verdict = TextGenerationPlanReviewer(listOf(entry("gemini", first), entry("openai", second))).review(request())

        assertEquals(PlanVerdict.Reject("No tests."), verdict)
        assertTrue(second.prompts.isEmpty())
        assertTrue("Be cynical." in first.prompts.single() && "Objective: Ship it" in first.prompts.single())
    }

    @Test
    fun aReviewerLeftToTheAppMovesOnWhenAnLlmFailsOrGivesNoVerdict() = runTest {
        val down = FakeApi { error("HTTP 503") }
        val rambling = FakeApi { "It looks plausible to me." }
        val good = FakeApi { "VERDICT: APPROVE\nFine." }

        val verdict = TextGenerationPlanReviewer(listOf(entry("gemini", down), entry("openai", rambling), entry("anthropic", good))).review(request())

        assertEquals(PlanVerdict.Approve("Fine."), verdict)
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
    fun noLinkedLlmMeansNoReviewer() {
        assertEquals(null, configuredPlanReviewer(emptyMap()))
    }
}
