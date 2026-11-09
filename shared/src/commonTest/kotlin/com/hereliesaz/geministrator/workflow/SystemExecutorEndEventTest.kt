package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A workflow that ends on a system step (a deployment, a test run) leaves the same end event as one that ends on an agent. */
class SystemExecutorEndEventTest {
    private val deploy = TaskDefinitionId("deploy")
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("system-only"),
        name = "System only",
        tasks = listOf(
            TaskDefinition(
                id = deploy,
                name = "Deploy",
                objective = "Deploy the build",
                roleId = null,
                executor = TaskExecutor.Deployment("production"),
            ),
        ),
    )

    private suspend fun runToTheEnd(
        finalStatus: TaskRunStatus,
        definition: WorkflowDefinition = this.definition,
        start: com.hereliesaz.geministrator.domain.WorkflowRun? = null,
    ): Pair<WorkflowRuntimeState, InMemoryWorkflowPersistence> {
        val persistence = InMemoryWorkflowPersistence()
        val gateway = NoSessionGateway()
        val coordinator = WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(gateway, BuiltInRoles.all, eventSink = RepositoryWorkflowEventSink(persistence.events)),
            sessionGateway = gateway,
            executorIntegrations = TaskExecutorIntegrationRegistry(listOf(EndingIntegration(finalStatus))),
        )
        val run = start ?: WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run-system"),
            projectId = project.id,
            objective = "Ship",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId("run-system-${it.value}") },
        )
        var state = WorkflowRuntimeState(run)
        repeat(3) { index -> state = coordinator.cycle(project, definition, state, 10L * (index + 1), ::artifactId) }
        return state to persistence
    }

    @Test
    fun aWorkflowThatEndsOnASystemStepRecordsItsCompletion() = runBlocking<Unit> {
        val (state, persistence) = runToTheEnd(TaskRunStatus.Completed)

        assertEquals(WorkflowRunStatus.Completed, state.run.status)
        // Before: the status was set after the system step with nothing written to the run's log.
        assertEquals(1, persistence.events.forRun(state.run.id).filterIsInstance<WorkflowCompleted>().size)
    }

    @Test
    fun aRunWhoseEveryTaskEndedCancelledRecordsItsCancellation() = runBlocking<Unit> {
        // Every task ended Cancelled (a step whose condition was not met, an approval nobody granted).
        val start = com.hereliesaz.geministrator.domain.WorkflowRun(
            id = WorkflowRunId("run-system"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                deploy to TaskRun(
                    id = TaskRunId("run-system-deploy"),
                    taskDefinitionId = deploy,
                    status = TaskRunStatus.Cancelled,
                    assignedRoleId = null,
                    executor = TaskExecutor.Deployment("production"),
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val (state, persistence) = runToTheEnd(TaskRunStatus.Completed, definition, start)

        assertEquals(WorkflowRunStatus.Cancelled, state.run.status)
        assertEquals(1, persistence.events.forRun(state.run.id).filterIsInstance<WorkflowCancelled>().size)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) = ArtifactId("artifact")
}

private class NoSessionGateway : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("no agent task")
    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
}

private class EndingIntegration(private val finalStatus: TaskRunStatus) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.Deployment

    override suspend fun dispatch(context: TaskExecutorContext) =
        TaskExecutorExecution(TaskRunStatus.Running, externalRunId = "deploy-run")

    override suspend fun reconcile(context: TaskExecutorContext) =
        TaskExecutorExecution(status = finalStatus, externalRunId = context.taskRun.externalRunId, progress = 1f)
}
