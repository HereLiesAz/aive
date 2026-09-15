package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.inference.CompoundInferenceStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

class CompoundInferenceRequestTest {
    @Test
    fun agentRequestCarriesSingleInferenceGenealogyByDefault() {
        val parentTaskRunId = TaskRunId("parent-task-run")
        val artifactId = ArtifactId("research-artifact")
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("child-task-run"),
            objective = "Synthesize the research",
            roleInstructions = "Use evidence from dependency artifacts.",
            acceptanceCriteria = listOf(AcceptanceCriterion("Produces a synthesis")),
            contextArtifacts = listOf(
                ArtifactRef(
                    id = artifactId,
                    kind = ArtifactKind.Research,
                    taskRunId = parentTaskRunId,
                    label = "Research",
                    textContent = "Evidence",
                    createdAtEpochMillis = 1L,
                ),
            ),
            orchestrationContext = AgentOrchestrationContext(
                workflowRunId = WorkflowRunId("workflow-run"),
            ),
        )

        assertEquals(CompoundInferenceStrategy.Single, request.compoundInference.strategy)
        assertEquals(1, request.compoundInference.candidateBudget)
        assertEquals(0, request.compoundInference.aggregatorDepth)
        assertEquals(
            "workflow:workflow-run:task-run:child-task-run",
            request.compoundInference.genealogy.invocationId,
        )
        assertEquals(setOf(parentTaskRunId), request.compoundInference.genealogy.upstreamTaskRunIds)
        assertEquals(setOf(artifactId), request.compoundInference.genealogy.upstreamArtifactIds)
    }
}
