package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ConcurrencyPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowConcurrencyTest {
    @Test
    fun providerLimitCanBeLowerThanGlobalConcurrencyLimit() = runBlocking {
        val providerId = AgentProviderId("remote-agent")
        val firstId = TaskDefinitionId("first")
        val secondId = TaskDefinitionId("second")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow"),
            name = "Workflow",
            testDesignPolicy = com.hereliesaz.geministrator.domain.TestDesignPolicy.None,
            concurrencyPolicy = ConcurrencyPolicy(
                maxConcurrentTasks = 4,
                perProviderLimits = mapOf(providerId to 1),
            ),
            tasks = listOf(
                TaskDefinition(firstId, "First", "First task", BuiltInRoles.ImplementationEngineer.id),
                TaskDefinition(secondId, "Second", "Second task", BuiltInRoles.ImplementationEngineer.id),
            ),
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            objective = "Objective",
            nowEpochMillis = 0L,
            taskRunIdFactory = { TaskRunId("run-${it.value}") },
        )
        val gateway = SingleProviderConcurrencyGateway(providerId)
        val engine = WorkflowEngine(gateway, BuiltInRoles.all)

        val dispatched = engine.dispatchReadyTasks(
            project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L),
            definition = definition,
            run = run,
            nowEpochMillis = 10L,
        )

        assertEquals(1, gateway.created)
        assertEquals(1, dispatched.run.taskRuns.values.count { it.status == TaskRunStatus.Running })
        assertEquals(1, dispatched.run.taskRuns.values.count { it.status == TaskRunStatus.Ready })
    }
}

private class SingleProviderConcurrencyGateway(
    private val providerId: AgentProviderId,
) : ManagedSessionGateway {
    var created: Int = 0

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        created += 1
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = providerId,
            providerRunId = ProviderRunId("run-$created"),
        )
    }

    override suspend fun reconnect(handle: ManagedSessionHandle, initialStatus: ManagedSessionStatus) = Unit
    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running
    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
