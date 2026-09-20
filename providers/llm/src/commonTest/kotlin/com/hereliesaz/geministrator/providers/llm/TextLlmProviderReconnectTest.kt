package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextLlmProviderReconnectTest {
    @Test
    fun reconstructedApprovedSessionResumesSameProviderRunWithoutRedispatch() = runBlocking {
        var calls = 0
        val api = object : TextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult {
                calls += 1
                return TextGenerationResult(
                    text = if (calls == 1) "Recovered plan" else "Recovered execution",
                    inputTokens = 3,
                    outputTokens = 2,
                )
            }
        }
        val provider = TextLlmProvider(
            id = AgentProviderId("resume-provider"),
            displayName = "Resume provider",
            api = api,
        )
        val runId = ProviderRunId("resume-provider/task-1/42")
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("task-1"),
            objective = "Complete the resumed task",
            roleInstructions = "Produce the requested work product.",
            acceptanceCriteria = emptyList(),
            requirePlanApproval = true,
        )

        assertEquals(
            ProviderActionResult.Accepted,
            provider.reconnect(runId, request, planApproved = true),
        )

        val events = provider.observe(runId).toList()

        assertEquals(runId, events.first().runId)
        assertIs<AgentEvent.PlanGenerated>(events[0])
        assertIs<AgentEvent.PlanApproved>(events[1])
        assertTrue(events.any { it is AgentEvent.ArtifactProduced })
        assertTrue(events.any { it is AgentEvent.UsageReported })
        assertIs<AgentEvent.Completed>(events.last())
        assertEquals(2, calls)
    }
}
