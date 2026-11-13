package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.VerificationPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.InMemoryWorkflowEventSink
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E1 (a compiled output check turns an attempt back) and E7 (an optional creature never ends the run). */
class WorkflowEngineOutputCheckTest {
    private val project = Project(
        id = ProjectId("project"),
        name = "Project",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
    )
    private val mustSayOk = TaskOutputValidatorRegistry.Empty.with("must-say-ok") { artifacts ->
        if (artifacts.any { it.textContent?.contains("OK") == true }) emptyList() else listOf("The lesson must say OK.")
    }

    private fun definition(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("check"),
        name = "Check",
        tasks = tasks.toList(),
    )

    private fun task(id: String, policy: VerificationPolicy = VerificationPolicy.None, optional: Boolean = false) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        verificationPolicy = policy,
        optional = optional,
        retryPolicy = RetryPolicy(maxAttempts = 1),
    )

    private fun newRun(definition: WorkflowDefinition): WorkflowRun = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        objective = "Check",
        nowEpochMillis = 0L,
        taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
    )

    private val ids: (com.hereliesaz.geministrator.domain.TaskRun, ProviderArtifact, Int) -> ArtifactId =
        { taskRun, _, index -> ArtifactId("${taskRun.id.value}-$index") }

    @Test
    fun aDeterministicCheckThatFailsTurnsTheAttemptBackWithItsReason() = runBlocking {
        val def = definition(task("write", VerificationPolicy.Deterministic("must-say-ok")))
        val gateway = ScriptedGateway(done = setOf("Do write"), text = "nothing useful")
        val events = InMemoryWorkflowEventSink()
        val engine = WorkflowEngine(gateway, BuiltInRoles.all, events, outputValidators = mustSayOk)
        val started = engine.dispatchReadyTasks(project, def, newRun(def), nowEpochMillis = 10L)

        val reconciled = engine.reconcile(def, started.run, started.handles, 20L, ids)

        val taskRun = reconciled.taskRuns.getValue(TaskDefinitionId("write"))
        assertEquals(TaskRunStatus.Failed, taskRun.status)
        assertEquals(TASK_OUTPUT_REJECTED, taskRun.blockingReason?.code)
        assertTrue(events.snapshot().any { it is VerificationFailed && it.reason.contains("must say OK") })

        val retried = engine.handleFailure(
            definition = def,
            run = reconciled.copy(status = WorkflowRunStatus.Running),
            taskDefinitionId = TaskDefinitionId("write"),
            retryReason = RetryReason.VerificationFailed,
            reason = taskRun.blockingReason!!.message,
            nowEpochMillis = 30L,
        )
        // maxAttempts = 1 in this fixture: no retry, so no failure text is kept; the policy decided, not the check.
        assertNull(retried.taskRuns.getValue(TaskDefinitionId("write")).lastFailure)
    }

    @Test
    fun aRetryKeepsTheReasonForTheNextPrompt() = runBlocking {
        val two = task("write", VerificationPolicy.Deterministic("must-say-ok")).copy(retryPolicy = RetryPolicy(maxAttempts = 3))
        val def = definition(two)
        val gateway = ScriptedGateway(done = setOf("Do write"), text = "nothing useful")
        val engine = WorkflowEngine(gateway, BuiltInRoles.all, InMemoryWorkflowEventSink(), outputValidators = mustSayOk)
        val started = engine.dispatchReadyTasks(project, def, newRun(def), nowEpochMillis = 10L)
        val reconciled = engine.reconcile(def, started.run, started.handles, 20L, ids)
        val message = reconciled.taskRuns.getValue(TaskDefinitionId("write")).blockingReason!!.message

        val retried = engine.handleFailure(def, reconciled.copy(status = WorkflowRunStatus.Running), TaskDefinitionId("write"), RetryReason.VerificationFailed, message, 30L)

        val taskRun = retried.taskRuns.getValue(TaskDefinitionId("write"))
        assertEquals(TaskRunStatus.Retrying, taskRun.status)
        assertEquals(message, taskRun.lastFailure)
    }

    @Test
    fun aDeterministicCheckThatPassesCompletesTheStep() = runBlocking {
        val def = definition(task("write", VerificationPolicy.Deterministic("must-say-ok")))
        val gateway = ScriptedGateway(done = setOf("Do write"), text = "all OK here")
        val engine = WorkflowEngine(gateway, BuiltInRoles.all, InMemoryWorkflowEventSink(), outputValidators = mustSayOk)
        val started = engine.dispatchReadyTasks(project, def, newRun(def), nowEpochMillis = 10L)

        val reconciled = engine.reconcile(def, started.run, started.handles, 20L, ids)

        assertEquals(TaskRunStatus.Completed, reconciled.taskRuns.getValue(TaskDefinitionId("write")).status)
        assertEquals(WorkflowRunStatus.Completed, reconciled.status)
    }

    @Test
    fun anUnknownCheckIdIsAViolationNotAPass() = runBlocking {
        val def = definition(task("write", VerificationPolicy.Deterministic("not-registered")))
        val gateway = ScriptedGateway(done = setOf("Do write"), text = "all OK here")
        val engine = WorkflowEngine(gateway, BuiltInRoles.all, InMemoryWorkflowEventSink())
        val started = engine.dispatchReadyTasks(project, def, newRun(def), nowEpochMillis = 10L)

        val reconciled = engine.reconcile(def, started.run, started.handles, 20L, ids)

        assertEquals(TaskRunStatus.Failed, reconciled.taskRuns.getValue(TaskDefinitionId("write")).status)
    }

    @Test
    fun anOptionalCreatureThatFailsForGoodIsSkippedAndTheRunCompletes() = runBlocking {
        val def = definition(task("main"), task("extra", optional = true))
        val gateway = ScriptedGateway(done = setOf("Do main"), text = "x")
        val engine = WorkflowEngine(gateway, BuiltInRoles.all, InMemoryWorkflowEventSink())
        val started = engine.dispatchReadyTasks(project, def, newRun(def), nowEpochMillis = 10L)
        val extraId = TaskDefinitionId("extra")
        val failedRun = started.run.copy(
            taskRuns = started.run.taskRuns + (extraId to started.run.taskRuns.getValue(extraId).copy(status = TaskRunStatus.Failed)),
        )

        val skipped = engine.handleFailure(def, failedRun, extraId, RetryReason.ProviderFailure, "no reply", 20L)

        assertEquals(WorkflowRunStatus.Running, skipped.status)
        assertEquals(OPTIONAL_STEP_SKIPPED, skipped.taskRuns.getValue(extraId).blockingReason?.code)
        val finished = engine.reconcile(def, skipped, started.handles - extraId, 30L, ids)
        assertEquals(WorkflowRunStatus.Completed, finished.status)
    }

    @Test
    fun aRequiredCreatureThatFailsStillEndsTheRun() = runBlocking {
        val def = definition(task("main"))
        val engine = WorkflowEngine(ScriptedGateway(done = emptySet(), text = "x"), BuiltInRoles.all, InMemoryWorkflowEventSink())
        val started = engine.dispatchReadyTasks(project, def, newRun(def), nowEpochMillis = 10L)
        val id = TaskDefinitionId("main")
        val failedRun = started.run.copy(
            taskRuns = started.run.taskRuns + (id to started.run.taskRuns.getValue(id).copy(status = TaskRunStatus.Failed)),
        )

        val ended = engine.handleFailure(def, failedRun, id, RetryReason.ProviderFailure, "no reply", 20L)

        assertEquals(WorkflowRunStatus.Failed, ended.status)
        assertNotNull(ended.taskRuns[id])
    }
}

/** Starts every session; a session whose objective is in [done] reports Completed with one artifact of [text]. */
private class ScriptedGateway(private val done: Set<String>, private val text: String) : ManagedSessionGateway {
    private val providerId = AgentProviderId("scripted")
    private val objectives = mutableMapOf<TaskRunId, String>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        objectives[request.taskRequest.taskRunId] = request.taskRequest.objective
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = providerId,
            providerRunId = ProviderRunId("provider-run-${request.taskRequest.taskRunId.value}"),
        )
    }

    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus =
        if (objectives[handle.taskRunId] in done) ManagedSessionStatus.Completed else ManagedSessionStatus.Running

    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> =
        if (objectives[handle.taskRunId] in done) {
            listOf(ProviderArtifact(kind = ArtifactKind.Specification, label = "out", textContent = text))
        } else {
            emptyList()
        }
}
