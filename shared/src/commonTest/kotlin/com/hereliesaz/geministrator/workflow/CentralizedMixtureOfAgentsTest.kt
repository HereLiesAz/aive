package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.VerificationPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.inference.INFERENCE_INVOCATION_ID_METADATA_KEY
import com.hereliesaz.geministrator.inference.InferenceGenealogy
import com.hereliesaz.geministrator.inference.InferenceGenealogyGovernanceRuntime
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CentralizedMixtureOfAgentsTest {
    @Test
    fun expanderMaterializesGovernedProposerAggregatorVerifierDag() {
        val taskId = TaskDefinitionId("implement")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("wf"),
            name = "MoA workflow",
            testDesignPolicy = TestDesignPolicy.None,
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Implement",
                    objective = "Implement the requested change",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    verificationPolicy = VerificationPolicy.Required(BuiltInRoles.QaEngineer.id),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                    compoundInferencePolicy = CompoundInferencePolicy.CentralizedMixtureOfAgents(
                        proposerRoleIds = listOf(BuiltInRoles.Researcher.id, BuiltInRoles.Architect.id),
                        aggregatorRoleId = BuiltInRoles.ImplementationEngineer.id,
                    ),
                ),
            ),
        )

        val expanded = CentralizedMixtureOfAgentsExpander.expand(definition, BuiltInRoles.all)
        val byId = expanded.tasks.associateBy(TaskDefinition::id)
        val proposerOne = TaskDefinitionId("implement--moa-proposer-1")
        val proposerTwo = TaskDefinitionId("implement--moa-proposer-2")
        val governance = TaskDefinitionId("implement--moa-governance")
        val verifier = TaskDefinitionId("implement--moa-verifier")

        assertEquals(5, expanded.tasks.size)
        assertEquals(setOf(proposerOne, proposerTwo), byId.getValue(governance).dependsOn)
        assertIs<TaskExecutor.ExternalService>(byId.getValue(governance).executor)
        assertEquals(
            setOf(proposerOne, proposerTwo, governance),
            byId.getValue(taskId).dependsOn,
        )
        assertEquals(
            setOf(proposerOne, proposerTwo, governance, taskId),
            byId.getValue(verifier).dependsOn,
        )
        assertEquals(CompoundInferencePolicy.Single, byId.getValue(taskId).compoundInferencePolicy)
        assertEquals(CompoundInferencePolicy.Single, byId.getValue(verifier).compoundInferencePolicy)
    }

    @Test
    fun genealogyGateCompletesForStructurallyIndependentCandidateInvocations() = runBlocking {
        val governance = InferenceGenealogyGovernanceRuntime()
        governance.registerInvocation(
            InferenceGenealogy(
                invocationId = "candidate-a",
                upstreamArtifactIds = setOf(ArtifactId("evidence-a")),
            ),
        )
        governance.registerInvocation(
            InferenceGenealogy(
                invocationId = "candidate-b",
                upstreamArtifactIds = setOf(ArtifactId("evidence-b")),
            ),
        )

        val execution = GenealogyGovernanceExecutorIntegration(governance).dispatch(
            governanceContext("candidate-a", "candidate-b"),
        )

        assertEquals(TaskRunStatus.Completed, execution.status)
        assertEquals(1, execution.artifacts.size)
        assertEquals(ArtifactKind.Verification, execution.artifacts.single().kind)
        assertEquals("true", execution.artifacts.single().metadata["gatePassed"])
        assertEquals("true", execution.artifacts.single().metadata["structurallyIndependent"])
    }

    @Test
    fun genealogyGateFlagsFalseConsensusFromSharedEvidenceButAllowsSynthesis() = runBlocking {
        val governance = InferenceGenealogyGovernanceRuntime()
        val sharedEvidence = ArtifactId("same-source")
        governance.registerInvocation(
            InferenceGenealogy(
                invocationId = "candidate-a",
                upstreamArtifactIds = setOf(sharedEvidence),
            ),
        )
        governance.registerInvocation(
            InferenceGenealogy(
                invocationId = "candidate-b",
                upstreamArtifactIds = setOf(sharedEvidence),
            ),
        )

        val execution = GenealogyGovernanceExecutorIntegration(governance).dispatch(
            governanceContext("candidate-a", "candidate-b"),
        )

        assertEquals(TaskRunStatus.Completed, execution.status)
        assertEquals("true", execution.artifacts.single().metadata["gatePassed"])
        assertEquals("false", execution.artifacts.single().metadata["structurallyIndependent"])
        assertTrue(execution.artifacts.single().textContent.orEmpty().contains("CommonAncestry"))
        assertTrue(execution.artifacts.single().textContent.orEmpty().contains("UnsupportedConsensus"))
    }

    @Test
    fun downstreamRequestInheritsProducingInvocationFromDurableArtifact() {
        val artifact = candidateArtifact(
            suffix = "a",
            invocationId = "candidate-a",
            taskRunId = TaskRunId("candidate-run-1"),
        )

        val request = AgentTaskRequest(
            taskRunId = TaskRunId("aggregator-run"),
            objective = "Aggregate the candidates",
            roleInstructions = "Preserve disagreement and evidence.",
            acceptanceCriteria = emptyList(),
            contextArtifacts = listOf(artifact),
        )

        assertEquals(
            setOf("candidate-a"),
            request.compoundInference.genealogy.upstreamInvocationIds,
        )
        assertEquals(
            setOf(artifact.id),
            request.compoundInference.genealogy.upstreamArtifactIds,
        )
    }

    private fun governanceContext(
        firstInvocationId: String,
        secondInvocationId: String,
    ): TaskExecutorContext {
        val firstId = TaskDefinitionId("candidate-1")
        val secondId = TaskDefinitionId("candidate-2")
        val gateId = TaskDefinitionId("gate")
        val firstRun = TaskRun(
            id = TaskRunId("candidate-run-1"),
            taskDefinitionId = firstId,
            status = TaskRunStatus.Completed,
            assignedRoleId = BuiltInRoles.Researcher.id,
            artifacts = listOf(candidateArtifact("a", firstInvocationId, TaskRunId("candidate-run-1"))),
            executor = TaskExecutor.RoleAgent(BuiltInRoles.Researcher.id),
        )
        val secondRun = TaskRun(
            id = TaskRunId("candidate-run-2"),
            taskDefinitionId = secondId,
            status = TaskRunStatus.Completed,
            assignedRoleId = BuiltInRoles.Architect.id,
            artifacts = listOf(candidateArtifact("b", secondInvocationId, TaskRunId("candidate-run-2"))),
            executor = TaskExecutor.RoleAgent(BuiltInRoles.Architect.id),
        )
        val gateTask = TaskDefinition(
            id = gateId,
            name = "Genealogy gate",
            objective = "Check ancestry",
            roleId = null,
            dependsOn = setOf(firstId, secondId),
            environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            executor = TaskExecutor.ExternalService(GENEALOGY_GOVERNANCE_SERVICE),
        )
        val gateRun = TaskRun(
            id = TaskRunId("gate-run"),
            taskDefinitionId = gateId,
            status = TaskRunStatus.Ready,
            assignedRoleId = null,
            executor = gateTask.executor,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("wf"),
            name = "Governance",
            testDesignPolicy = TestDesignPolicy.None,
            tasks = listOf(
                TaskDefinition(
                    id = firstId,
                    name = "Candidate 1",
                    objective = "Candidate 1",
                    roleId = BuiltInRoles.Researcher.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.Researcher.id),
                ),
                TaskDefinition(
                    id = secondId,
                    name = "Candidate 2",
                    objective = "Candidate 2",
                    roleId = BuiltInRoles.Architect.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.Architect.id),
                ),
                gateTask,
            ),
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Govern",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(firstId to firstRun, secondId to secondRun, gateId to gateRun),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )
        return TaskExecutorContext(
            project = Project(
                id = run.projectId,
                name = "Project",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 2L,
            ),
            definition = definition,
            run = run,
            task = gateTask,
            taskRun = gateRun,
            executor = requireNotNull(gateTask.executor),
            nowEpochMillis = 3L,
        )
    }

    private fun candidateArtifact(
        suffix: String,
        invocationId: String,
        taskRunId: TaskRunId,
    ): ArtifactRef = ArtifactRef(
        id = ArtifactId("candidate-$suffix"),
        kind = ArtifactKind.TaskPlan,
        taskRunId = taskRunId,
        label = "Candidate $suffix",
        textContent = "candidate",
        metadata = mapOf(INFERENCE_INVOCATION_ID_METADATA_KEY to invocationId),
        createdAtEpochMillis = 1L,
    )
}
