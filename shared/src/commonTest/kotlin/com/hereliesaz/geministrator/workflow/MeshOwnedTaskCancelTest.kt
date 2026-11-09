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
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Work a compute mesh holds for a task is stopped when the run is, like every other remote run. */
class MeshOwnedTaskCancelTest {
    private val remote = TaskDefinitionId("remote")
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("mesh"),
        name = "Mesh",
        tasks = listOf(
            TaskDefinition(
                id = remote,
                name = "Remote",
                objective = "Do it on a paired device",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            ),
        ),
    )
    private val run = WorkflowRun(
        id = WorkflowRunId("run-mesh"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            remote to TaskRun(
                id = TaskRunId("tr-remote"),
                taskDefinitionId = remote,
                status = TaskRunStatus.Running,
                assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                externalRunId = "mesh:group-1",
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun coordinator(persistence: InMemoryWorkflowPersistence, mesh: FakeMesh): WorkflowRuntimeCoordinator {
        val gateway = IdleGateway()
        return WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(gateway, BuiltInRoles.all),
            sessionGateway = gateway,
            remoteComputeCoordinator = mesh,
        )
    }

    @Test
    fun cancellingARunAsksTheMeshToStopWhatItLeasedForTheTask() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val mesh = FakeMesh()
        persistence.runs.put(run)

        coordinator(persistence, mesh).cancel(project, definition, WorkflowRuntimeState(run), 20L)

        // Before: tasks the mesh owns were skipped, so a cancelled run left its leased work running on the paired device.
        assertEquals(listOf("mesh:group-1"), mesh.cancelled)
    }

    @Test
    fun aMeshThatCannotCancelIsRecordedLikeAnyOtherRemoteRunThatWillNotStop() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val mesh = FakeMesh(refusal = "the relay is unreachable")
        persistence.runs.put(run)

        coordinator(persistence, mesh).cancel(project, definition, WorkflowRuntimeState(run), 20L)

        val failure = persistence.events.forRun(run.id).filterIsInstance<ExternalRunCancelFailed>().single()
        assertEquals("mesh:group-1", failure.externalRunId)
        assertTrue("relay is unreachable" in failure.reason)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) = ArtifactId("artifact")
}

private class FakeMesh(private val refusal: String? = null) : RemoteComputeCoordinator {
    val cancelled = mutableListOf<String>()

    override fun owns(taskRun: TaskRun) = taskRun.externalRunId?.startsWith("mesh:") == true
    override suspend fun dispatch(context: RemoteComputeContext): RemoteComputeDispatch = RemoteComputeDispatch.Local
    override suspend fun reconcile(context: RemoteComputeContext): RemoteComputeUpdate? = null
    override suspend fun cancel(taskRun: TaskRun): String? {
        cancelled += taskRun.externalRunId.orEmpty()
        return refusal
    }
}

private class IdleGateway : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("unused")
    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
}
