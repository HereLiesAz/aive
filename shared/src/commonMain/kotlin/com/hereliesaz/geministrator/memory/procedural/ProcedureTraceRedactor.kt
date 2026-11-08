package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution

/**
 * Only allowlisted executor identity, status and artifact kinds are observed, plus the executor's
 * configured target by name and a few typed values it was configured with (a number, a flag, a
 * short token). Command/script source, free-text input, result output, artifact content/URIs,
 * error messages and transport credentials are deliberately never accessed or kept.
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
            operation = operationOf(context.executor),
            slots = slotsOf(context.executor),
        )
    }

    /**
     * The receipt written after an independent reviewer approved or turned back an execution that
     * completed. It repeats the execution's operation and slots so it can be read alone, and it
     * never replaces the original receipt.
     */
    fun captureVerification(
        context: TaskExecutorContext,
        verification: ProcedureTraceVerification,
    ): ProcedureTrace {
        require(verification != ProcedureTraceVerification.Unknown) { "Unknown is not a verification" }
        return capture(
            context,
            ProcedureTracePhase.Verification,
            result = TaskExecutorExecution(TaskRunStatus.Completed),
        ).copy(verification = verification)
    }

    /** The executor wrapped by a placement choice; placement is not what the task does. */
    private fun semantic(executor: TaskExecutor): TaskExecutor =
        if (executor is TaskExecutor.Distributed) executor.delegate else executor

    /**
     * The configured target by name, only when it is a plain name. A script or a test command has
     * no name: its text is never read, so it has no operation until a host names it.
     */
    fun operationOf(executor: TaskExecutor): String? {
        val candidate = when (val inner = semantic(executor)) {
            is TaskExecutor.GitHubAction -> "github-action:${inner.workflow}"
            is TaskExecutor.Deployment -> "deployment:${inner.environment}"
            is TaskExecutor.RepositoryOperation -> "repository:${inner.operation}"
            is TaskExecutor.ExternalService ->
                "service:${inner.service}" + (inner.operation?.let { ":$it" } ?: "")
            is TaskExecutor.NestedWorkflow -> "nested-workflow:${inner.workflowDefinitionId.value}"
            else -> return null
        }
        return candidate.takeIf { it.matches(ProcedureTrace.OPERATION) && ControlRedaction.scrub(it) == it }
    }

    private val sensitiveName = Regex("(?i)(token|secret|password|passwd|credential|auth|bearer|key|private|cert)")

    /**
     * The configured values that are safe to keep: a git ref and the workflow inputs of a GitHub
     * Action, except its context blob. A value is kept only as an integer, a flag, or one short
     * token that does not look like a key, under a name that does not suggest something sensitive.
     * Anything else is dropped whole, never trimmed.
     */
    fun slotsOf(executor: TaskExecutor): List<ProcedureTraceSlot> {
        val action = semantic(executor) as? TaskExecutor.GitHubAction ?: return emptyList()
        val raw = buildList {
            action.ref?.let { add("ref" to it) }
            action.inputs.forEach { (name, value) -> if (name != action.contextInput) add(name to value) }
        }
        return raw.mapNotNull { (rawName, value) -> slotOf(rawName, value) }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .take(ProcedureTraceSlot.MAX_SLOTS)
    }

    private fun slotOf(rawName: String, value: String): ProcedureTraceSlot? {
        val name = rawName.lowercase().replace('-', '_')
        if (!name.matches(ProcedureTraceSlot.NAME) || sensitiveName.containsMatchIn(name)) return null
        if (ControlRedaction.scrub(value) != value) return null
        val type = when {
            value.matches(ProcedureTraceSlot.INTEGER) -> ProcedureParameterType.Integer
            value == "true" || value == "false" -> ProcedureParameterType.Boolean
            value.matches(ProcedureTraceSlot.IDENTIFIER) && !ProcedureTraceSlot.looksLikeKey(value) ->
                ProcedureParameterType.Identifier
            else -> return null
        }
        return ProcedureTraceSlot(name, type, value)
    }
}
