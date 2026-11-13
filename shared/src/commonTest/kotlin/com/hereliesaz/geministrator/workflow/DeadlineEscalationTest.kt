package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
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
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The coordinator with a scripted gateway and a fake clock: a creature that never finishes. */
class DeadlineEscalationTest {
    private val id = TaskDefinitionId("slow")
    private val minute = 60_000L
    private val handle = ManagedSessionHandle(TaskRunId("tr"), AgentProviderId("provider"), ProviderRunId("run-1"))

    private class HangingGateway : ManagedSessionGateway {
        val cancelled = mutableListOf<ManagedSessionHandle>()
        override suspend fun resolveProvider(selection: com.hereliesaz.geministrator.workflow.ProviderSelectionRequest) = AgentProviderId("provider")
        override suspend fun createSession(request: ManagedSessionRequest) = ManagedSessionHandle(
            request.taskRequest.taskRunId, AgentProviderId("provider"), ProviderRunId("run-new"),
        )
        override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Running
        override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
        override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
        override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
        override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
            cancelled += handle
            return ProviderActionResult.Accepted
        }
    }

    private val definition = WorkflowDefinition(
        WorkflowDefinitionId("d"), "d",
        tasks = listOf(
            TaskDefinition(
                id = id, name = "Slow", objective = "never ends", roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            ),
        ),
    )

    private fun run(timedOut: Int = 0) = WorkflowRun(
        id = WorkflowRunId("r"), projectId = ProjectId("p"), workflowDefinitionId = definition.id, objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            id to TaskRun(
                TaskRunId("tr"), id, TaskRunStatus.Running, assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                assignedProviderId = handle.providerId, providerRunId = handle.providerRunId,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id), timedOutAttempts = timedOut,
            ),
        ),
        createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
    )

    private class Rig(val gateway: HangingGateway, val coordinator: WorkflowRuntimeCoordinator)

    private fun rig(enforce: Boolean): Rig {
        val persistence = InMemoryWorkflowPersistence()
        val gateway = HangingGateway()
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)
        return Rig(
            gateway,
            WorkflowRuntimeCoordinator(
                persistence, engine, gateway,
                deadlineAllowance = { _, _ -> Allowance(8 * minute, 16 * minute, Basis.Baseline) },
                enforceDeadlines = enforce,
            ),
        )
    }

    /** One cycle a minute from the start up to [untilMinute], as the runtime's loop would. */
    private fun drive(rig: Rig, start: WorkflowRun, untilMinute: Int): WorkflowRuntimeState = runBlocking {
        var state = WorkflowRuntimeState(start, mapOf(id to handle))
        for (m in 0..untilMinute) {
            state = rig.coordinator.cycle(
                project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
                definition = definition,
                state = state,
                nowEpochMillis = m * minute,
                artifactIdFactory = { _, _, i -> ArtifactId("a-$i") },
            )
        }
        state
    }

    @Test fun softOnlyWhenNotEnforced() {
        val state = drive(rig(enforce = false), run(), 30)
        val t = state.run.taskRuns.getValue(id)
        assertEquals(TaskRunStatus.Running, t.status)
        assertNotNull(t.clock?.softAtEpochMillis)
        assertEquals(0, t.timedOutAttempts)
    }

    @Test fun hardStopCancelsSessionAndRetriesWithinPolicy() {
        val rig = rig(enforce = true)
        val state = drive(rig, run(), 17)
        val t = state.run.taskRuns.getValue(id)
        assertTrue(handle in rig.gateway.cancelled, "the provider session was cancelled")
        assertEquals(1, t.timedOutAttempts)
        assertTrue(t.status == TaskRunStatus.Retrying || t.status == TaskRunStatus.Running, "started again: ${t.status}")
        assertTrue(t.attempt >= 2, "a new attempt")
    }

    @Test fun secondExpiryEscalatesWhateverTheEscalationPolicy() {
        val state = drive(rig(enforce = true), run(timedOut = 1), 17)
        val t = state.run.taskRuns.getValue(id)
        assertEquals(2, t.timedOutAttempts)
        assertEquals(TaskRunStatus.Escalated, t.status)
        assertEquals(WorkflowRunStatus.AwaitingHuman, state.run.status)
    }

    @Test fun waitingOnTheKeeperDoesNotExpireTheCreature() {
        val rig = rig(enforce = true)
        val asking = run().let { r ->
            r.copy(taskRuns = r.taskRuns.mapValues { it.value.copy(keeperQuestion = "Which one?") })
        }
        val state = drive(rig, asking, 60)
        assertEquals(TaskRunStatus.Running, state.run.taskRuns.getValue(id).status)
        assertTrue(rig.gateway.cancelled.isEmpty())
    }
}
