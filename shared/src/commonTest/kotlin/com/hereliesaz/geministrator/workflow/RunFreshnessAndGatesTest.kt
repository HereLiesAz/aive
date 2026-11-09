package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CONTEXT_PROVENANCE_METADATA_KEY
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.TaskCondition
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
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rules that keep a decision from being lost or a gate from being skipped: one freshness rule between the
 * live and the stored copy of a run, a person's own approval step decided as one gate, and a held task that
 * a failure handled in the same cycle cannot release past the taint gate.
 */
class RunFreshnessAndGatesTest {
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val engineer = BuiltInRoles.ImplementationEngineer

    // ---- One freshness rule ----

    private fun approvalHive(): Pair<WorkflowDefinition, TaskDefinitionId> {
        val gate = TaskDefinitionId("gate")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("freshness"),
            name = "Freshness",
            tasks = listOf(
                TaskDefinition(
                    id = gate,
                    name = "Gate",
                    objective = "Approve",
                    roleId = null,
                    executor = TaskExecutor.HumanApproval("Approve"),
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
        return definition to gate
    }

    private fun awaiting(definition: WorkflowDefinition, gate: TaskDefinitionId, updatedAt: Long) = WorkflowRun(
        id = WorkflowRunId("run-freshness"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.AwaitingHuman,
        taskRuns = mapOf(
            gate to TaskRun(
                id = TaskRunId("task-run-gate"),
                taskDefinitionId = gate,
                status = TaskRunStatus.AwaitingApproval,
                assignedRoleId = null,
                executor = TaskExecutor.HumanApproval("Approve"),
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt,
    )

    @Test
    fun aCancelBuildsOnADecisionSomeoneSavedInTheSameMillisecond() = runBlocking<Unit> {
        val (definition, gate) = approvalHive()
        val persistence = InMemoryWorkflowPersistence()
        val live = awaiting(definition, gate, updatedAt = 10L)
        // Someone else decided the gate in the very millisecond the live copy was made.
        val decided = live.copy(
            taskRuns = live.taskRuns.mapValues { (_, taskRun) -> taskRun.copy(status = TaskRunStatus.Completed) },
        )
        persistence.runs.put(decided)
        val gateway = FreshnessGateway()
        val coordinator = WorkflowRuntimeCoordinator(persistence, WorkflowEngine(gateway, BuiltInRoles.all), gateway)

        coordinator.cancel(project, definition, WorkflowRuntimeState(live), 10L)

        // Before: the rewind, cancel, liftSpendStop and the rest used a strict comparison, so this was overwritten by the live copy.
        assertEquals(
            TaskRunStatus.Completed,
            persistence.runs.get(live.id)?.taskRuns?.getValue(gate)?.status,
            "what was decided stays decided",
        )
    }

    @Test
    fun oneRuleDecidesWhichCopyOfARunIsNewer() {
        val (definition, gate) = approvalHive()
        val live = awaiting(definition, gate, updatedAt = 10L)
        val sameMillisecond = live.copy(objective = "Changed by someone else")
        val older = live.copy(objective = "Older", updatedAtEpochMillis = 9L)
        val newer = live.copy(objective = "Newer", updatedAtEpochMillis = 11L)

        assertEquals(sameMillisecond, newestOf(live, sameMillisecond), "a different stored copy in the same millisecond is someone else's")
        assertEquals(newer, newestOf(live, newer))
        assertEquals(live, newestOf(live, older), "a live copy that is strictly newer has changes not stored yet")
        assertEquals(live, newestOf(live, live))
        assertEquals(live, newestOf(live, null))
    }

    @Test
    fun aDecisionIsNeverStampedBeforeTheRunItDecides() {
        val (definition, gate) = approvalHive()
        val run = awaiting(definition, gate, updatedAt = 1_000L)

        assertEquals(2_000L, decisionTime(2_000L, run))
        // The clock stepped back: the decision still sorts after what it decides instead of looking older and being dropped.
        assertEquals(1_001L, decisionTime(500L, run))
        assertEquals(1_001L, decisionTime(1_000L, run))
    }

    // ---- A person's own approval step has one gate ----

    @Test
    fun approvingThePlanGateOfAPersonsApprovalStepCompletesItAndNeverStartsIt() = runBlocking<Unit> {
        val (definition, gate) = approvalHive()
        val persistence = InMemoryWorkflowPersistence()
        val gateway = FreshnessGateway()
        val coordinator = WorkflowRuntimeCoordinator(persistence, WorkflowEngine(gateway, BuiltInRoles.all), gateway)
        val start = awaiting(definition, gate, updatedAt = 10L)

        // The cycle opens the plan gate for a task that is waiting for approval.
        val first = coordinator.cycle(project, definition, WorkflowRuntimeState(start), 20L, ::artifactId)
        val gateId = ApprovalGateId("plan:${start.id.value}:${gate.value}:1")
        assertEquals(ApprovalGateStatus.Pending, persistence.approvalGates.get(gateId)?.status)

        // The gate is approved on its own (an inbox of gates, the control surface), with no session behind it.
        WorkflowApprovalService(
            gateRepository = persistence.approvalGates,
            gateCoordinator = ApprovalGateCoordinator(persistence.approvalGates, RepositoryWorkflowEventSink(persistence.events)),
            sessionGateway = gateway,
        ).approvePlan(gateId, handle = null, decidedByRoleId = null, note = "ok", nowEpochMillis = 25L)
        val second = coordinator.cycle(project, definition, first, 30L, ::artifactId)

        // Before: AwaitingApproval -> Running, with no session and nothing to ever finish it.
        assertEquals(TaskRunStatus.Completed, second.run.taskRuns.getValue(gate).status)
        assertEquals(WorkflowRunStatus.Completed, second.run.status)
    }

    // ---- The taint gate between a failure and a dispatch ----

    @Test
    fun aTaskReleasedByAFailureHandledInTheSameCycleStillPassesTheTaintGate() = runBlocking<Unit> {
        val source = TaskDefinitionId("source")
        val flaky = TaskDefinitionId("flaky")
        val cleanup = TaskDefinitionId("cleanup")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("taint-window"),
            name = "Taint window",
            tasks = listOf(
                TaskDefinition(
                    id = source,
                    name = "Source",
                    objective = "Gather",
                    roleId = engineer.id,
                    executor = TaskExecutor.RoleAgent(engineer.id),
                ),
                TaskDefinition(
                    id = flaky,
                    name = "Flaky",
                    objective = "Run CI",
                    roleId = null,
                    executor = TaskExecutor.GitHubAction("ci.yml"),
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                    escalationPolicy = EscalationPolicy.RequireHumanDecision,
                ),
                // Runs whatever happens to the flaky step, and can change the repository.
                TaskDefinition(
                    id = cleanup,
                    name = "Cleanup",
                    objective = "Clean up",
                    roleId = engineer.id,
                    executor = TaskExecutor.RoleAgent(engineer.id),
                    dependsOn = setOf(source, flaky),
                    condition = TaskCondition.OnAnyOutcome(flaky),
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val external = ArtifactRef(
            id = ArtifactId("artifact-web"),
            kind = ArtifactKind.Research,
            taskRunId = TaskRunId("task-run-source"),
            label = "web-findings",
            textContent = "text from a web page",
            metadata = mapOf(CONTEXT_PROVENANCE_METADATA_KEY to ContextProvenance.ExternalContent.name),
            createdAtEpochMillis = 5L,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run-taint-window"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                source to TaskRun(
                    id = TaskRunId("task-run-source"),
                    taskDefinitionId = source,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = engineer.id,
                    artifacts = listOf(external),
                    executor = TaskExecutor.RoleAgent(engineer.id),
                ),
                flaky to TaskRun(
                    id = TaskRunId("task-run-flaky"),
                    taskDefinitionId = flaky,
                    status = TaskRunStatus.Ready,
                    assignedRoleId = null,
                    executor = TaskExecutor.GitHubAction("ci.yml"),
                ),
                cleanup to TaskRun(
                    id = TaskRunId("task-run-cleanup"),
                    taskDefinitionId = cleanup,
                    status = TaskRunStatus.Blocked,
                    assignedRoleId = engineer.id,
                    executor = TaskExecutor.RoleAgent(engineer.id),
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val persistence = InMemoryWorkflowPersistence()
        val gateway = FreshnessGateway()
        val coordinator = WorkflowRuntimeCoordinator(
            persistence,
            WorkflowEngine(gateway, BuiltInRoles.all),
            gateway,
            executorIntegrations = TaskExecutorIntegrationRegistry(listOf(AlwaysFailingCi)),
        )

        val result = coordinator.cycle(project, definition, WorkflowRuntimeState(run), 20L, ::artifactId)

        // The flaky step failed for good in this cycle and escalated, which released the cleanup task.
        assertEquals(TaskRunStatus.Escalated, result.run.taskRuns.getValue(flaky).status)
        // Before: it was dispatched in the same cycle, acting on web content with no human gate.
        assertEquals(0, gateway.created, "nothing that can change the repository starts on external content unasked")
        val held = result.run.taskRuns.getValue(cleanup)
        assertEquals(TaskRunStatus.AwaitingApproval, held.status)
        assertTrue(held.isHeldAtContextTaintGate())
        assertNotNull(persistence.approvalGates.get(contextTaintGateId(run.id, cleanup, 1)))
    }

    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) = ArtifactId("artifact")
}

/** A CI step whose dispatch always fails, and never retries. */
private object AlwaysFailingCi : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.GitHubAction

    override val retryDispatchFailures: Boolean = false

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution =
        throw IllegalStateException("CI is down")

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
        throw IllegalStateException("CI is down")
}

private class FreshnessGateway : ManagedSessionGateway {
    var created = 0

    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        created += 1
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = AgentProviderId("provider"),
            providerRunId = ProviderRunId("created-$created"),
        )
    }

    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Running

    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
}
