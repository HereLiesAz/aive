package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StarterWorkflowDispatchTest {
    @Test
    fun implementationDispatchCarriesRepositoryAndRequiresProviderPlanApproval() = runBlocking {
        val repository = RepositoryRef("HereLiesAz", "haive", "main")
        val project = Project(
            id = ProjectId("project"),
            name = "The Haive",
            repository = repository,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val definition = StarterWorkflowFactory.create(
            id = WorkflowDefinitionId("starter"),
            objective = "Ship the objective",
        ).copy(testDesignPolicy = TestDesignPolicy.None)
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Ship the objective",
            nowEpochMillis = 1L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        val gateway = CapturingStarterGateway()
        val engine = WorkflowEngine(
            sessionGateway = gateway,
            roles = BuiltInRoles.all,
        )

        val dispatched = engine.dispatchReadyTasks(
            project = project,
            definition = definition,
            run = run,
            nowEpochMillis = 2L,
        )

        val request = assertNotNull(gateway.lastRequest).taskRequest
        assertEquals(repository, request.repository)
        assertTrue(request.requirePlanApproval)
        assertEquals(project.id, request.orchestrationContext.projectId)
        assertEquals(run.id, request.orchestrationContext.workflowRunId)
        assertEquals(definition.id, request.orchestrationContext.workflowDefinitionId)
        assertEquals(TaskDefinitionId("implementation"), request.orchestrationContext.taskDefinitionId)
        assertNotNull(request.orchestrationContext.roleId)
        assertEquals(
            "workflow:${run.id.value}:task-run:${request.taskRunId.value}",
            request.compoundInference.genealogy.invocationId,
        )
        assertEquals(TaskRunStatus.Planning, dispatched.run.taskRuns.getValue(TaskDefinitionId("implementation")).status)
    }
}

private class CapturingStarterGateway : ManagedSessionGateway {
    private val providerId = AgentProviderId("starter-provider")
    var lastRequest: ManagedSessionRequest? = null

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        lastRequest = request
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = providerId,
            providerRunId = ProviderRunId("provider-run"),
        )
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Planning

    override suspend fun message(
        handle: ManagedSessionHandle,
        message: String,
    ): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
