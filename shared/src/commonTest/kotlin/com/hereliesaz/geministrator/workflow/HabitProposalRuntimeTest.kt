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
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.memory.procedural.HabitFormation
import com.hereliesaz.geministrator.memory.procedural.HabitInvocation
import com.hereliesaz.geministrator.memory.procedural.HabitSequences
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A habit sequence, added to a workflow, run by the real runtime: nothing it proposed is dispatched
 * until a person completes the approval task in front of that step, and the steps run in the order
 * they were observed. The proposal's own approval policy is not what holds them (the engine reads it
 * only for an agent's plan), so this is the behavior the proposal depends on.
 */
class HabitProposalRuntimeTest {
    private val ci = "github-action:ci.yml"
    private val deploy = "deployment:production"

    private fun step(run: String, at: Long, operation: String) = ProcedureTrace(
        workflowId = run, projectId = "project", taskRunId = "tr-$run-$operation", taskDefinitionId = operation, attempt = 1,
        phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
        outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = at, operation = operation,
    )

    @Test
    fun proposedStepsRunInOrderAndOnlyAfterTheirApprovalsAreCompleted(): Unit = runBlocking {
        val receipts = listOf("r1", "r2", "r3").flatMap { listOf(step(it, 10, ci), step(it, 11, deploy)) }
        val proposed = assertIs<HabitInvocation.SequenceResult.Proposed>(
            HabitInvocation.proposeSequence(HabitSequences.form(receipts).single(), HabitFormation.form(receipts), "habit"),
        ).tasks
        val lint = TaskDefinitionId("lint")
        val base = WorkflowDefinition(
            id = WorkflowDefinitionId("base"),
            name = "Base",
            tasks = listOf(
                TaskDefinition(
                    id = lint, name = "Lint", objective = "Lint", roleId = null,
                    executor = TaskExecutor.GitHubAction("lint.yml"),
                ),
            ),
        )
        val definition = WorkflowCompositionService(InMemoryWorkflowPersistence())
            .addProposedTasks(base, proposed, connectFrom = setOf(lint))

        val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Ship",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId("run-${it.value}") },
        )
        val gateway = IdleGateway()
        val integration = RecordingIntegration()
        val engine = WorkflowEngine(gateway, BuiltInRoles.all)
        val coordinator = WorkflowRuntimeCoordinator(
            persistence = InMemoryWorkflowPersistence(),
            engine = engine,
            sessionGateway = gateway,
            executorIntegrations = TaskExecutorIntegrationRegistry(listOf(integration)),
        )
        var state = WorkflowRuntimeState(run)
        var clock = 10L
        suspend fun cycle() {
            clock += 10
            state = coordinator.cycle(project, definition, state, clock) { taskRun, _, index -> ArtifactId("${taskRun.id.value}-$index") }
        }
        fun status(id: String) = state.run.taskRuns.getValue(TaskDefinitionId(id)).status

        repeat(4) { cycle() }
        assertEquals(TaskRunStatus.Completed, status("lint"))
        assertEquals(TaskRunStatus.AwaitingApproval, status("habit-1-approval"))
        assertEquals(listOf("lint.yml"), integration.dispatched, "nothing proposed runs before its approval")

        state = WorkflowRuntimeState(
            engine.completeHumanApprovalTask(definition, state.run, TaskDefinitionId("habit-1-approval"), clock + 5),
            state.handles,
        )
        repeat(4) { cycle() }
        assertEquals(TaskRunStatus.Completed, status("habit-1"))
        assertEquals(TaskRunStatus.AwaitingApproval, status("habit-2-approval"))
        assertEquals(listOf("lint.yml", "ci.yml"), integration.dispatched, "the second step still waits")

        state = WorkflowRuntimeState(
            engine.completeHumanApprovalTask(definition, state.run, TaskDefinitionId("habit-2-approval"), clock + 5),
            state.handles,
        )
        repeat(4) { cycle() }
        assertEquals(listOf("lint.yml", "ci.yml", "production"), integration.dispatched)
        assertEquals(WorkflowRunStatus.Completed, state.run.status)
    }

    @Test
    fun theControlWithoutApprovalTasksRunsAStepUnattendedSoThePolicyAloneDoesNotHoldIt(): Unit = runBlocking {
        val receipts = listOf("r1", "r2", "r3").flatMap { listOf(step(it, 10, ci), step(it, 11, deploy)) }
        val proposed = assertIs<HabitInvocation.SequenceResult.Proposed>(
            HabitInvocation.proposeSequence(HabitSequences.form(receipts).single(), HabitFormation.form(receipts), "habit"),
        ).tasks
        // The proposal as it was before addProposedTasks: approvalPolicy set, no approval task.
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("ungated"),
            name = "Ungated",
            tasks = proposed,
        )
        val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val gateway = IdleGateway()
        val integration = RecordingIntegration()
        val coordinator = WorkflowRuntimeCoordinator(
            persistence = InMemoryWorkflowPersistence(),
            engine = WorkflowEngine(gateway, BuiltInRoles.all),
            sessionGateway = gateway,
            executorIntegrations = TaskExecutorIntegrationRegistry(listOf(integration)),
        )
        var state = WorkflowRuntimeState(
            WorkflowRunFactory.create(
                definition = definition,
                workflowRunId = WorkflowRunId("ungated-run"),
                projectId = project.id,
                objective = "Ship",
                nowEpochMillis = 1L,
                taskRunIdFactory = { TaskRunId("ungated-${it.value}") },
            ),
        )
        var clock = 10L
        repeat(6) {
            clock += 10
            state = coordinator.cycle(project, definition, state, clock) { taskRun, _, index -> ArtifactId("${taskRun.id.value}-$index") }
        }
        assertEquals(listOf("ci.yml", "production"), integration.dispatched)
    }

    private class IdleGateway : ManagedSessionGateway {
        override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")

        override suspend fun createSession(request: ManagedSessionRequest) = ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = AgentProviderId("provider"),
            providerRunId = ProviderRunId("provider-run"),
        )

        override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Completed

        override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted

        override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted

        override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
    }

    private class RecordingIntegration : TaskExecutorIntegration {
        val dispatched = mutableListOf<String>()

        override fun supports(executor: TaskExecutor): Boolean =
            executor is TaskExecutor.GitHubAction || executor is TaskExecutor.Deployment

        override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
            dispatched += when (val executor = context.executor) {
                is TaskExecutor.GitHubAction -> executor.workflow
                is TaskExecutor.Deployment -> executor.environment
                else -> error("Unsupported executor")
            }
            return TaskExecutorExecution(TaskRunStatus.Running, externalRunId = "run-${dispatched.size}")
        }

        override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
            TaskExecutorExecution(TaskRunStatus.Completed, externalRunId = context.taskRun.externalRunId, progress = 1f)
    }
}
