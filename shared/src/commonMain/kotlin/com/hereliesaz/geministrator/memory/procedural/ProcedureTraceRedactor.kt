package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution

/**
 * Only allowlisted executor identity, status and artifact kinds are observed.
 * Command/script source, workflow input strings, result output, artifact content/URIs,
 * error messages and transport credentials are deliberately never accessed.
 * Success is a reported outcome, NOT independently verified success.
 */
object ProcedureTraceRedactor {
    fun capture(
        context: TaskExecutorContext,
        phase: ProcedureTracePhase,
        result: TaskExecutorExecution? = null,
        thrown: Boolean = false,
    ): ProcedureTrace {
        require(thrown != (result != null)) { "Exactly one result or failure marker is required" }
        val outcome = if (thrown) ProcedureTraceOutcome.Threw else when (result!!.status) {
            TaskRunStatus.Running -> ProcedureTraceOutcome.Running
            TaskRunStatus.Verifying -> ProcedureTraceOutcome.Verifying
            TaskRunStatus.Completed -> ProcedureTraceOutcome.Completed
            TaskRunStatus.Failed -> ProcedureTraceOutcome.Failed
            else -> error("Unsupported task-executor status")
        }
        val kind = when (val executor = context.executor) {
            is TaskExecutor.Script -> "Script-${executor.language.name}"
            is TaskExecutor.TestRunner -> "TestRunner"
            is TaskExecutor.GitHubAction -> "GitHubAction"
            is TaskExecutor.Deployment -> "Deployment"
            is TaskExecutor.RepositoryOperation -> "RepositoryOperation"
            is TaskExecutor.ExternalService -> "ExternalService"
            is TaskExecutor.NestedWorkflow -> "NestedWorkflow"
            is TaskExecutor.Distributed -> "Distributed"
            is TaskExecutor.RoleAgent -> "RoleAgent"
            is TaskExecutor.HumanApproval -> "HumanApproval"
        }
        return ProcedureTrace(
            workflowId = context.run.id.value,
            projectId = context.project.id.value,
            taskRunId = context.taskRun.id.value,
            taskDefinitionId = context.task.id.value,
            attempt = context.taskRun.attempt,
            phase = phase,
            executorKind = kind,
            outcome = outcome,
            artifactKinds = result?.artifacts.orEmpty().map { it.kind.name }.distinct().sorted().take(32),
            artifactCount = result?.artifacts?.size ?: 0,
            recordedAtEpochMillis = context.nowEpochMillis,
        )
    }
}
