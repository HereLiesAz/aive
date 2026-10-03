package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextLlmProviderFailureTest {
    private val refusing = object : TextGenerationApi {
        override suspend fun generate(prompt: String): TextGenerationResult =
            error("HTTP 429 Too Many Requests")
    }

    private fun request(requirePlanApproval: Boolean) = AgentTaskRequest(
        taskRunId = TaskRunId("task-429"),
        objective = "Plan the work",
        roleInstructions = "Produce the requested work product.",
        acceptanceCriteria = emptyList(),
        requirePlanApproval = requirePlanApproval,
    )

    @Test
    fun providerRefusalDuringPlanningFailsTheRunInsteadOfThrowing() = runTest {
        val provider = TextLlmProvider(AgentProviderId("limited"), "Limited", refusing)
        val runId = provider.start(request(requirePlanApproval = true)).providerRunId

        val events = provider.observe(runId).toList()

        val failed = assertIs<AgentEvent.Failed>(events.single())
        assertEquals("Limited: HTTP 429 Too Many Requests", failed.reason)
    }

    @Test
    fun providerRefusalWithoutApprovalFailsTheRunAndForgetsTheSession() = runTest {
        val provider = TextLlmProvider(AgentProviderId("limited"), "Limited", refusing)
        val runId = provider.start(request(requirePlanApproval = false)).providerRunId

        assertIs<AgentEvent.Failed>(provider.observe(runId).toList().single())
        assertTrue(runCatching { provider.observe(runId).toList() }.isFailure)
    }

    @Test
    fun draftPlanAsksForAPlanOnlyAndRaisesTheProvidersRefusal() = runTest {
        val prompts = mutableListOf<String>()
        val planner = TextLlmProvider(
            AgentProviderId("planner"),
            "Planner",
            object : TextGenerationApi {
                override suspend fun generate(prompt: String): TextGenerationResult {
                    prompts += prompt
                    return TextGenerationResult("1. Read\n2. Write")
                }
            },
        )
        assertEquals("1. Read\n2. Write", planner.draftPlan(request(requirePlanApproval = true)))
        assertTrue(prompts.single().contains("PLAN ONLY"))

        val refused = runCatching {
            TextLlmProvider(AgentProviderId("limited"), "Limited", refusing).draftPlan(request(requirePlanApproval = true))
        }.exceptionOrNull()
        assertEquals("Limited: HTTP 429 Too Many Requests", refused?.message)
    }
}
