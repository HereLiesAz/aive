package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Artifact kind precedence: declared artifacts, then the role, then objective keywords. */
class TextLlmProviderArtifactKindTest {
    private suspend fun kindFor(request: AgentTaskRequest): ArtifactKind {
        val provider = TextLlmProvider(
            id = AgentProviderId("kind-provider"),
            displayName = "Kind provider",
            api = object : TextGenerationApi {
                override suspend fun generate(prompt: String) = TextGenerationResult("Output", 1, 1)
            },
        )
        val runId = provider.start(request).providerRunId
        return provider.observe(runId).toList()
            .filterIsInstance<AgentEvent.ArtifactProduced>()
            .single()
            .artifact.kind
    }

    private fun request(
        objective: String,
        roleId: String? = null,
        instructions: String = "Produce the requested work product.",
        required: Set<ArtifactKind> = emptySet(),
    ) = AgentTaskRequest(
        taskRunId = TaskRunId("task-1"),
        objective = objective,
        roleInstructions = instructions,
        acceptanceCriteria = emptyList(),
        requiredArtifacts = required,
        orchestrationContext = AgentOrchestrationContext(roleId = roleId?.let(::RoleDefinitionId)),
    )

    @Test
    fun declaredArtifactWinsOverRoleAndKeywords() = runBlocking<Unit> {
        assertEquals(
            ArtifactKind.TaskPlan,
            kindFor(request("Analyse the failure and release it", roleId = "qa-engineer", required = setOf(ArtifactKind.TaskPlan))),
        )
    }

    @Test
    fun roleWinsOverKeywordsInInstructions() = runBlocking<Unit> {
        // Instructions that mention failure recovery must not turn an orchestrator's plan into a failure analysis.
        assertEquals(
            ArtifactKind.TaskPlan,
            kindFor(
                request(
                    objective = "Coordinate the work",
                    roleId = "orchestrator",
                    instructions = "On failure, route to the recovery engineer and QA before release.",
                ),
            ),
        )
    }

    @Test
    fun keywordsReadOnlyTheObjectiveAsWholeWords() = runBlocking<Unit> {
        assertEquals(
            ArtifactKind.Research,
            kindFor(request("Summarise prerelease notes", instructions = "Mention any failure or QA concern.")),
        )
        assertEquals(ArtifactKind.Verification, kindFor(request("Verify the build")))
    }
}
