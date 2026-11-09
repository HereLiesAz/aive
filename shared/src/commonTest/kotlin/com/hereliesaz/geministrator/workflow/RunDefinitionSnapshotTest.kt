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
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A run follows the hive it started with (docs/Keepers-Room.md, Pause, change, resume). The saved copy
 * in the library and the run share one definition id, so a structural edit saved while the run is live
 * (a creature removed, one added, a policy changed) must never be what the run is resumed against.
 */
class RunDefinitionSnapshotTest {
    private val kept = TaskDefinitionId("kept")
    private val removed = TaskDefinitionId("removed")
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L)

    private fun task(id: TaskDefinitionId) = TaskDefinition(
        id = id,
        name = id.value,
        objective = "Do ${id.value}",
        roleId = BuiltInRoles.ImplementationEngineer.id,
    )

    private fun hive() = WorkflowDefinition(
        id = WorkflowDefinitionId("snapshot-hive"),
        name = "Hive",
        tasks = listOf(task(kept), task(removed)),
        testDesignPolicy = TestDesignPolicy.None,
    )

    /** A run of [hive] in which both creatures hold a live provider session. */
    private fun runWithSessions(definition: WorkflowDefinition): WorkflowRun {
        val created = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Ship",
            nowEpochMillis = 0L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        return created.copy(
            taskRuns = created.taskRuns.mapValues { (id, taskRun) ->
                taskRun.copy(
                    status = TaskRunStatus.Running,
                    assignedProviderId = AgentProviderId("remote-agent"),
                    providerRunId = ProviderRunId("sessions/${id.value}"),
                )
            },
        )
    }

    private fun coordinator(persistence: InMemoryWorkflowPersistence, gateway: SnapshotGateway) =
        WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(gateway, BuiltInRoles.all),
            sessionGateway = gateway,
        )

    @Test
    fun aCreatureRemovedFromTheSavedHiveStillResumesWithItsSessionAfterARestart() = runBlocking<Unit> {
        val definition = hive()
        val persistence = InMemoryWorkflowPersistence()
        val run = runWithSessions(definition)
        coordinator(persistence, SnapshotGateway()).persist(project, definition, WorkflowRuntimeState(run))

        // The keeper removes a creature that is running and saves; the library copy now lacks it.
        persistence.definitions.put(definition.copy(tasks = definition.tasks.filter { it.id == kept }))

        // The app restarts: a fresh coordinator resumes the run from what is persisted.
        val gateway = SnapshotGateway()
        val resumed = coordinator(persistence, gateway).resume(run.id)

        // Before: "Task removed was not found", so the live run could not be resumed at all.
        assertEquals(setOf(kept, removed), resumed.handles.keys)
        assertEquals(2, gateway.reconnected.size, "both live sessions are reconnected, none is started again")
        assertEquals(0, gateway.createCount)
    }

    @Test
    fun aRunSavedBeforeSnapshotsExistedResumesAgainstTheSavedHive() = runBlocking<Unit> {
        val definition = hive()
        val persistence = InMemoryWorkflowPersistence()
        val legacy = runWithSessions(definition).copy(definitionSnapshot = null)
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(legacy)

        val gateway = SnapshotGateway()
        val resumed = coordinator(persistence, gateway).resume(legacy.id)

        assertEquals(setOf(kept, removed), resumed.handles.keys)
        assertNull(persistence.runs.get(legacy.id)?.definitionSnapshot)
    }

    @Test
    fun theRunRecordsTheHiveItWasStartedWith() {
        val definition = hive()
        assertEquals(definition, runWithSessions(definition).definitionSnapshot)
        assertTrue(runWithSessions(definition).definitionSnapshot?.tasks?.size == 2)
    }
}

private class SnapshotGateway : ManagedSessionGateway {
    var createCount: Int = 0
    val reconnected = mutableListOf<ManagedSessionHandle>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = AgentProviderId("remote-agent")

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        createCount += 1
        error("resume must not create a new session")
    }

    override suspend fun reconnect(handle: ManagedSessionHandle, initialStatus: ManagedSessionStatus) {
        reconnected += handle
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running

    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
