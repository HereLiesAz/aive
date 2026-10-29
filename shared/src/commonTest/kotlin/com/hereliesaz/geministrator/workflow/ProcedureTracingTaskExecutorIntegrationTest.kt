package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
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
import com.hereliesaz.geministrator.inference.InMemoryInferenceDataRegistry
import com.hereliesaz.geministrator.memory.MemoryRuntimeBridge
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceRedactor
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcedureTracingTaskExecutorIntegrationTest {
    private fun context(): TaskExecutorContext {
        val taskId = TaskDefinitionId("task")
        val taskRunId = TaskRunId("task-run")
        val executor = TaskExecutor.TestRunner("SECRET_PASSWORD=sensitive123 ./gradlew test")
        val task = TaskDefinition(taskId, "Test", "Run tests", roleId = null, executor = executor)
        val workflow = WorkflowDefinition(WorkflowDefinitionId("workflow"), "Workflow", tasks = listOf(task))
        val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val taskRun = TaskRun(taskRunId, taskId, TaskRunStatus.Running, assignedRoleId = null, executor = executor)
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = workflow.id,
            objective = "Test",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        return TaskExecutorContext(project, workflow, run, task, taskRun, executor, 42L)
    }

    @Test
    fun redactorNeverCapturesCommandsResultOutputOrArtifactContent() {
        val ctx = context()
        val secret = "sensitive123"
        val result = TaskExecutorExecution(
            TaskRunStatus.Completed,
            externalRunId = "secret-remote-run-id",
            artifacts = listOf(ArtifactRef(
                ArtifactId("artifact"), ArtifactKind.CommandOutput, ctx.taskRun.id,
                "TOKEN=secret-label", textContent = "TOKEN=$secret", createdAtEpochMillis = 42L,
            )),
            progressMessage = "Token $secret",
        )
        val trace = ProcedureTraceRedactor.capture(ctx, ProcedureTracePhase.Reconcile, result)
        val encoded = Json.encodeToString(ProcedureTrace.serializer(), trace)
        assertFalse(secret in encoded)
        assertFalse("SECRET_PASSWORD" in encoded)
        assertFalse("secret-remote-run-id" in encoded)
        assertFalse("secret-label" in encoded)
        assertTrue("CommandOutput" in encoded)
        assertEquals(ProcedureTraceOutcome.Completed, trace.outcome)
        assertTrue("Verification: unknown" in trace.contextualSummary())
    }

    @Test
    fun registryRecordsDispatchAndTerminalReconcileWithoutDoubleExecution() = runBlocking {
        val ctx = context()
        var dispatches = 0
        var reconciles = 0
        val emitted = mutableListOf<ProcedureTrace>()
        val integration = object : TaskExecutorIntegration {
            override fun supports(executor: TaskExecutor) = executor is TaskExecutor.TestRunner
            override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
                dispatches++
                return TaskExecutorExecution(TaskRunStatus.Running)
            }
            override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
                reconciles++
                return TaskExecutorExecution(if (reconciles == 1) TaskRunStatus.Running else TaskRunStatus.Completed)
            }
        }
        val previous = MemoryRuntimeBridge.procedureTraceSink
        try {
            MemoryRuntimeBridge.procedureTraceSink = ProcedureTraceSink { emitted.add(it) }
            val registry = TaskExecutorIntegrationRegistry(listOf(integration))
                .withInferenceDataRegistry(InMemoryInferenceDataRegistry())
            val routed = requireNotNull(registry.integrationFor(ctx.executor, ctx.project))
            assertEquals(TaskRunStatus.Running, routed.dispatch(ctx).status)
            assertEquals(TaskRunStatus.Running, routed.reconcile(ctx).status)
            assertEquals(TaskRunStatus.Completed, routed.reconcile(ctx).status)
            assertEquals(1, dispatches)
            assertEquals(2, reconciles)
            assertEquals(listOf(ProcedureTracePhase.Dispatch, ProcedureTracePhase.Reconcile), emitted.map { it.phase })
            assertEquals(listOf(ProcedureTraceOutcome.Running, ProcedureTraceOutcome.Completed), emitted.map { it.outcome })
        } finally {
            MemoryRuntimeBridge.procedureTraceSink = previous
        }
    }

    @Test
    fun observerExceptionCannotAlterResultAndExecutorExceptionStillPropagates() = runBlocking {
        val ctx = context()
        val delegate = object : TaskExecutorIntegration {
            override fun supports(executor: TaskExecutor) = true
            override suspend fun dispatch(context: TaskExecutorContext) = TaskExecutorExecution(TaskRunStatus.Completed)
            override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
                error("executor failed")
        }
        val events = mutableListOf<ProcedureTrace>()
        val wrapped = ProcedureTracingTaskExecutorIntegration(delegate) {
            ProcedureTraceSink {
                events += it
                error("memory unavailable")
            }
        }
        assertEquals(TaskRunStatus.Completed, wrapped.dispatch(ctx).status)
        assertFailsWith<IllegalStateException> { wrapped.reconcile(ctx) }
        assertEquals(listOf(ProcedureTraceOutcome.Completed, ProcedureTraceOutcome.Threw), events.map { it.outcome })
        assertTrue(events.none { "executor failed" in it.contextualSummary() })
    }
}
