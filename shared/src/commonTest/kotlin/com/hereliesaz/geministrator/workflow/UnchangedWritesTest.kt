package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.ArtifactRepository
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowRunRepository
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A run that is only waiting is not rewritten: unchanged artifacts, projects and runs stay as they were stored. */
class UnchangedWritesTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val job = TaskDefinitionId("job")
    private val done = TaskDefinitionId("done")
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val executor = TaskExecutor.RoleAgent(engineer.id)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("idle"),
        name = "Idle",
        tasks = listOf(
            TaskDefinition(id = done, name = "done", objective = "Done", roleId = engineer.id, executor = executor),
            TaskDefinition(id = job, name = "job", objective = "Job", roleId = engineer.id, executor = executor, dependsOn = setOf(done)),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun artifact(index: Int) = ArtifactRef(
        id = ArtifactId("artifact-$index"),
        kind = ArtifactKind.Research,
        taskRunId = TaskRunId("tr-done"),
        label = "Result $index",
        textContent = "text $index",
        createdAtEpochMillis = 5L,
    )

    private val run = WorkflowRun(
        id = WorkflowRunId("run-idle"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "Wait",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            done to TaskRun(
                id = TaskRunId("tr-done"),
                taskDefinitionId = done,
                status = TaskRunStatus.Completed,
                assignedRoleId = engineer.id,
                executor = executor,
                artifacts = listOf(artifact(1), artifact(2)),
            ),
            job to TaskRun(
                id = TaskRunId("tr-job"),
                taskDefinitionId = job,
                status = TaskRunStatus.Running,
                assignedRoleId = engineer.id,
                assignedProviderId = AgentProviderId("provider"),
                providerRunId = ProviderRunId("running-1"),
                executor = executor,
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private val handle = ManagedSessionHandle(TaskRunId("tr-job"), AgentProviderId("provider"), ProviderRunId("running-1"))

    @Test
    fun anIdleRunsCyclesWriteNeitherItsRunNorItsArtifactsAgain() = runBlocking<Unit> {
        val persistence = CountingPersistence()
        val gateway = StillRunningGateway()
        val coordinator = WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(gateway, BuiltInRoles.all),
            sessionGateway = gateway,
        )
        var state = WorkflowRuntimeState(run, mapOf(job to handle))

        state = coordinator.cycle(project, definition, state, 10L, ::artifactId)
        val runWritesAfterFirst = persistence.runPuts
        val artifactWritesAfterFirst = persistence.artifactPuts
        repeat(5) { index -> state = coordinator.cycle(project, definition, state, 20L + index, ::artifactId) }

        assertEquals(2, artifactWritesAfterFirst, "each artifact is written once")
        // Before: every cycle wrote every artifact two or three times and the run as many.
        assertEquals(artifactWritesAfterFirst, persistence.artifactPuts, "and not again while it has not changed")
        assertEquals(runWritesAfterFirst, persistence.runPuts, "an idle run is not rewritten")
        assertEquals(1L, state.run.updatedAtEpochMillis, "and does not take the time of a pass that changed nothing")
        assertTrue(state.handles.isNotEmpty())
    }

    @Test
    fun anArtifactThatChangesIsWrittenAgain() = runBlocking<Unit> {
        val persistence = CountingPersistence()
        val gateway = StillRunningGateway()
        val coordinator = WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(gateway, BuiltInRoles.all),
            sessionGateway = gateway,
        )
        val first = coordinator.cycle(project, definition, WorkflowRuntimeState(run, mapOf(job to handle)), 10L, ::artifactId)
        val before = persistence.artifactPuts

        val edited = first.run.copy(
            taskRuns = first.run.taskRuns + (done to first.run.taskRuns.getValue(done).copy(artifacts = listOf(artifact(1), artifact(2).copy(textContent = "changed")))),
        )
        coordinator.persist(project, definition, WorkflowRuntimeState(edited, first.handles))

        assertEquals(before + 1, persistence.artifactPuts)
        assertEquals("changed", persistence.inner.artifacts.get(ArtifactId("artifact-2"))?.textContent)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) = ArtifactId("artifact")
}

private class CountingPersistence(
    val inner: InMemoryWorkflowPersistence = InMemoryWorkflowPersistence(),
) : WorkflowPersistence by inner {
    var runPuts = 0
    var artifactPuts = 0

    override val runs: WorkflowRunRepository = object : WorkflowRunRepository by inner.runs {
        override suspend fun put(run: WorkflowRun) {
            runPuts += 1
            inner.runs.put(run)
        }
    }

    override val artifacts: ArtifactRepository = object : ArtifactRepository by inner.artifacts {
        override suspend fun put(artifact: ArtifactRef) {
            artifactPuts += 1
            inner.artifacts.put(artifact)
        }
    }
}

private class StillRunningGateway : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("unused")
    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Running
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
}
