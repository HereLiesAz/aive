package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
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
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowRuntimeCancellationTest {
    @Test
    fun cancellingARunStopsEveryLiveProviderSession() = runBlocking {
        val first = TaskDefinitionId("first")
        val second = TaskDefinitionId("second")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("wf"),
            name = "Workflow",
            tasks = listOf(
                TaskDefinition(first, "First", "Do one thing", roleId = BuiltInRoles.ImplementationEngineer.id),
                TaskDefinition(second, "Second", "Do another", roleId = BuiltInRoles.ImplementationEngineer.id),
            ),
        )
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
        )
        val created = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Objective",
            nowEpochMillis = 0L,
            taskRunIdFactory = { TaskRunId("task-${it.value}") },
        )
        val running = created.copy(
            status = WorkflowRunStatus.Running,
            taskRuns = created.taskRuns.mapValues { (_, taskRun) -> taskRun.copy(status = TaskRunStatus.Running) },
        )
        val handles = running.taskRuns.mapValues { (taskId, taskRun) ->
            ManagedSessionHandle(taskRun.id, AgentProviderId("provider"), ProviderRunId("provider-${taskId.value}"))
        }
        val gateway = RecordingCancelGateway()
        val persistence = InMemoryWorkflowPersistence()
        val coordinator = WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(gateway, BuiltInRoles.all),
            sessionGateway = gateway,
        )

        val cancelled = coordinator.cancel(project, definition, WorkflowRuntimeState(running, handles), 10L)

        assertEquals(WorkflowRunStatus.Cancelled, cancelled.run.status)
        assertEquals(emptyMap(), cancelled.handles)
        assertEquals(handles.values.toSet(), gateway.cancelled.toSet())
        assertEquals(WorkflowRunStatus.Cancelled, persistence.runs.get(running.id)?.status)
    }
}

private class RecordingCancelGateway : ManagedSessionGateway {
    val cancelled = mutableListOf<ManagedSessionHandle>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("not used")
    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        cancelled += handle
        return ProviderActionResult.Accepted
    }
    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
}
