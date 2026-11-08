package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class GateVerdictTest {
    private fun artifact(kind: ArtifactKind, text: String?, metadata: Map<String, String> = emptyMap()) = ArtifactRef(
        id = ArtifactId("a"),
        kind = kind,
        taskRunId = TaskRunId("t"),
        label = "x",
        textContent = text ?: "body",
        metadata = metadata,
        createdAtEpochMillis = 1L,
    )

    @Test
    fun verdictIsReadFromMetadataOrAFirstLineAndOnlyOnReviewingArtifacts() {
        assertEquals(ArtifactVerdict.Reject, ArtifactVerdicts.of(artifact(ArtifactKind.Verification, "VERDICT: reject\nbecause")))
        assertEquals(ArtifactVerdict.Approve, ArtifactVerdicts.of(artifact(ArtifactKind.Review, "\n\n**Verdict:** Approved")))
        assertEquals(ArtifactVerdict.Revise, ArtifactVerdicts.of(artifact(ArtifactKind.Verification, null, mapOf(ARTIFACT_VERDICT_METADATA to "revise"))))
        assertEquals(ArtifactVerdict.Reject, ArtifactVerdicts.of(artifact(ArtifactKind.Verification, "VERDICT: approve", mapOf(ARTIFACT_VERDICT_METADATA to "fail"))))
        assertNull(ArtifactVerdicts.of(artifact(ArtifactKind.Verification, "Looks fine to me. VERDICT: reject")))
        assertNull(ArtifactVerdicts.of(artifact(ArtifactKind.TaskPlan, "VERDICT: reject")))
        assertNull(ArtifactVerdicts.of(artifact(ArtifactKind.Verification, "VERDICT: maybe")))
        assertEquals(true, ArtifactVerdict.Revise.blocks)
        assertEquals(false, ArtifactVerdict.Approve.blocks)
    }

    private val gate = TaskDefinitionId("gate")
    private val next = TaskDefinitionId("next")
    private val project = Project(
        id = ProjectId("project"),
        name = "The Aive",
        repository = RepositoryRef("HereLiesAz", "aive", "main"),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("gated"),
        name = "Gated",
        testDesignPolicy = TestDesignPolicy.None,
        tasks = listOf(
            TaskDefinition(
                id = gate,
                name = "Gate",
                objective = "Review the plan",
                roleId = BuiltInRoles.AdversarialReviewer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.AdversarialReviewer.id),
                requiredArtifacts = setOf(ArtifactKind.Verification),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
            TaskDefinition(
                id = next,
                name = "Next",
                objective = "Build on the approved plan",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                dependsOn = setOf(gate),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
        ),
    )

    private fun reconcileWith(text: String?): WorkflowRun = runBlocking {
        val artifacts = if (text == null) {
            emptyList()
        } else {
            listOf(ProviderArtifact(kind = ArtifactKind.Verification, label = "review", textContent = text))
        }
        val gateway = VerdictGateway(artifacts)
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Ship",
            nowEpochMillis = 1L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        val dispatched = engine.dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L)
        engine.reconcile(definition, dispatched.run, dispatched.handles, 3L) { _, _, index -> ArtifactId("a$index") }
    }

    @Test
    fun aRejectingGateFailsAndWhatDependsOnItStaysBlocked() {
        val run = reconcileWith("VERDICT: reject\nThe level widens the work.")
        val gateRun = run.taskRuns.getValue(gate)
        assertEquals(TaskRunStatus.Failed, gateRun.status)
        assertEquals(GATE_VERDICT_BLOCKED, gateRun.blockingReason?.code)
        assertNotNull(run.taskRuns.getValue(next))
        assertEquals(TaskRunStatus.Blocked, run.taskRuns.getValue(next).status)
    }

    @Test
    fun anApprovingGateOrOneThatStatesNoVerdictCompletesAsBefore() {
        for (text in listOf("VERDICT: approve\nSound.", "Sound, no verdict stated.", null)) {
            val run = reconcileWith(text)
            assertEquals(TaskRunStatus.Completed, run.taskRuns.getValue(gate).status, "text=$text")
            assertNull(run.taskRuns.getValue(gate).blockingReason, "text=$text")
        }
    }
}

private class VerdictGateway(private val artifacts: List<ProviderArtifact>) : ManagedSessionGateway {
    private val providerId = AgentProviderId("verdict-provider")

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle =
        ManagedSessionHandle(request.taskRequest.taskRunId, providerId, ProviderRunId("verdict-run"))

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Completed

    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = artifacts
}
