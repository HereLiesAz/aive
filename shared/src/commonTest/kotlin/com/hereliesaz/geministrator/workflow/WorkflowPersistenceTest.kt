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
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowPersistenceTest {
    @Test
    fun resumeReconnectsPersistedActiveProviderRunWithoutCreatingAnotherSession() = runBlocking {
        val taskId = TaskDefinitionId("task")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition"),
            name = "Definition",
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Task",
                    objective = "Do the work",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
            ),
        )
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Objective",
            nowEpochMillis = 0L,
            taskRunIdFactory = { TaskRunId("task-run") },
        ).copy(
            taskRuns = mapOf(
                taskId to WorkflowRunFactory.create(
                    definition = definition,
                    workflowRunId = WorkflowRunId("scratch"),
                    projectId = project.id,
                    objective = "Objective",
                    nowEpochMillis = 0L,
                    taskRunIdFactory = { TaskRunId("task-run") },
                ).taskRuns.getValue(taskId).copy(
                    status = TaskRunStatus.Running,
                    assignedProviderId = AgentProviderId("remote-agent"),
                    providerRunId = ProviderRunId("sessions/123"),
                ),
            ),
        )
        val persistence = InMemoryWorkflowPersistence()
        val gateway = ResumeRecordingGateway()
        val runtime = WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(gateway, BuiltInRoles.all),
            sessionGateway = gateway,
        )

        runtime.persist(project, definition, WorkflowRuntimeState(run))
        val resumed = runtime.resume(run.id)

        assertEquals(0, gateway.createCount)
        assertEquals(1, gateway.reconnected.size)
        assertEquals(ProviderRunId("sessions/123"), gateway.reconnected.single().first.providerRunId)
        assertEquals(ManagedSessionStatus.Running, gateway.reconnected.single().second)
        assertTrue(taskId in resumed.handles)
    }

    @Test
    fun repositoryEventSinkKeepsAppendOnlyRunHistory() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val sink = RepositoryWorkflowEventSink(persistence.events)
        val runId = WorkflowRunId("run")

        sink.append(TaskStarted(runId, TaskDefinitionId("a"), 1, 10L))
        sink.append(TaskStarted(runId, TaskDefinitionId("b"), 1, 20L))

        val events = persistence.events.forRun(runId)
        assertEquals(2, events.size)
        assertEquals(listOf(10L, 20L), events.map { it.occurredAtEpochMillis })
    }
}

private class ResumeRecordingGateway : ManagedSessionGateway {
    var createCount: Int = 0
    val reconnected = mutableListOf<Pair<ManagedSessionHandle, ManagedSessionStatus>>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = AgentProviderId("remote-agent")

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        createCount += 1
        error("resume must not create a new session")
    }

    override suspend fun reconnect(handle: ManagedSessionHandle, initialStatus: ManagedSessionStatus) {
        reconnected += handle to initialStatus
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running

    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
