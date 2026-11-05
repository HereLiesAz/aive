package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.orchestration.FailoverOrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.orchestration.PlannerCandidate
import com.hereliesaz.geministrator.workflow.AllProvidersFailedException
import com.hereliesaz.geministrator.workflow.FailoverNotes
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan output budget, retry-once and failover on a reply cut off at the output limit. */
class PlanTruncationTest {
    private val packet = OrchestrationPacket(
        objective = "Audit all aspects of the repo",
        availableAgents = listOf(OrchestrationRole(id = "engineer", name = "Engineer", authorities = listOf("Implement"))),
        instruction = "Plan the work.",
    )

    /** Truncates its first [truncations] calls, then answers; records every budget it was asked for. */
    private class FakeApi(
        private val truncations: Int,
        override val outputTokenCap: Int? = 32_000,
    ) : TextGenerationApi {
        val budgets = mutableListOf<Int?>()
        val prompts = mutableListOf<String>()
        private var calls = 0

        override suspend fun generate(prompt: String): TextGenerationResult = respond(prompt, null)

        override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
            respond(prompt, maxOutputTokens)

        private fun respond(prompt: String, budget: Int?): TextGenerationResult {
            budgets += budget
            prompts += prompt
            if (++calls <= truncations) {
                throw OutputTruncatedException("Compatible chat response was truncated because the provider reached its output-token limit")
            }
            return TextGenerationResult(text = GOOD)
        }

        companion object {
            const val GOOD = """{"steps":[{"id":"audit","name":"Audit","objective":"Audit it","roleId":"engineer"}]}"""
        }
    }

    @Test
    fun theFirstAskIsGenerousAndNeverAboveTheCap() = runTest {
        val api = FakeApi(truncations = 0)
        TextGenerationOrchestrationAgentRuntime(api).plan(packet)
        assertEquals(listOf<Int?>(PlanOutputBudget.FIRST), api.budgets)
        assertTrue(api.prompts.single().contains("Be compact"))

        val small = FakeApi(truncations = 1, outputTokenCap = 8_192)
        TextGenerationOrchestrationAgentRuntime(small).plan(packet)
        assertTrue(small.budgets.all { it != null && it <= 8_192 }, small.budgets.toString())
    }

    @Test
    fun budgetNeverExceedsAnApisCap() {
        listOf(1_000, 8_192, 16_000, 32_000, 200_000).forEach { cap ->
            val api = FakeApi(0, outputTokenCap = cap)
            assertTrue(PlanOutputBudget.first(api)!! <= cap)
            assertTrue(PlanOutputBudget.retry(api)!! <= cap)
        }
        assertNull(PlanOutputBudget.first(FakeApi(0, outputTokenCap = null)))
        // Every real API's cap is at or below what it documents, and the retry is never smaller than the first ask.
        val key = LlmApiKeyProvider { "k" }
        listOf(AnthropicMessagesApi(key), GeminiGenerateContentApi(key), OpenAiResponsesApi(key), XaiResponsesApi(key)).forEach {
            assertTrue(it.outputTokenCap!! in 1..PlanOutputBudget.NATIVE_CAP)
            assertTrue(PlanOutputBudget.retry(it)!! >= PlanOutputBudget.first(it)!!)
        }
        assertEquals(PlanOutputBudget.ANTHROPIC_CAP, AnthropicMessagesApi(key).outputTokenCap)
    }

    @Test
    fun aCutOffPlanIsAskedForOnceMoreWithABiggerBudgetAndACompactInstruction() = runTest {
        val api = FakeApi(truncations = 1)
        val plan = TextGenerationOrchestrationAgentRuntime(api).plan(packet)
        assertEquals(listOf("audit"), plan.steps.map { it.id })
        assertEquals(listOf<Int?>(PlanOutputBudget.FIRST, PlanOutputBudget.RETRY), api.budgets)
        assertTrue(api.prompts[1].contains("cut off") && !api.prompts[0].contains("cut off"))
    }

    @Test
    fun aSecondCutOffIsRaisedForFailoverAndNotRetriedAgain() = runTest {
        val api = FakeApi(truncations = 5)
        assertFailsWith<OutputTruncatedException> { TextGenerationOrchestrationAgentRuntime(api).plan(packet) }
        assertEquals(2, api.budgets.size)
    }

    @Test
    fun anApiWithoutABudgetIsAskedPlainlyAndStillRetriesOnce() = runTest {
        val api = FakeApi(truncations = 1, outputTokenCap = null)
        TextGenerationOrchestrationAgentRuntime(api).plan(packet)
        assertEquals(listOf<Int?>(null, null), api.budgets)
    }

    private fun failover(vararg apis: Pair<String, FakeApi>) = FailoverOrchestrationAgentRuntime(
        apis.map { (name, api) -> PlannerCandidate(name.lowercase(), name, TextGenerationOrchestrationAgentRuntime(api)) },
    )

    @Test
    fun aTruncatesTwiceAndBAnswersSoThePlanIsBsWithAFailoverNote() = runTest {
        val a = FakeApi(truncations = 2)
        val b = FakeApi(truncations = 0)
        val notes = mutableListOf<String>()
        val plan = withContext(FailoverNotes { notes += it }) { failover("Gemini" to a, "OpenAI" to b).plan(packet) }
        assertEquals(listOf("audit"), plan.steps.map { it.id })
        assertEquals(2, a.budgets.size)
        assertEquals(1, b.budgets.size)
        assertEquals(listOf("Gemini ran out of room for its answer; continued with OpenAI."), notes)
    }

    @Test
    fun everyProviderTruncatingStopsWithThePlainMessageNamingThem() = runTest {
        val a = FakeApi(truncations = 9)
        val b = FakeApi(truncations = 9)
        val failure = assertFailsWith<AllProvidersFailedException> { failover("Gemini" to a, "OpenAI" to b).plan(packet) }
        assertEquals(
            "The plan was too long for the provider's output limit (tried Gemini, OpenAI). " +
                "Try a shorter description, or a provider with a larger limit.",
            failure.message,
        )
        assertEquals(2, a.budgets.size)
        assertEquals(2, b.budgets.size)
    }
}
