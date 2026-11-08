package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.memory.procedural.HabitDecisions
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceDeliberation
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink

/**
 * A model's written decision about a habit (see [HabitDecisions]) becomes a Deliberation receipt,
 * through the same opt-in sink as the rest, authored by the model's role.
 *
 * One rule guards it: a task that had untrusted content in front of it records nothing. A flag may
 * carry a taint mark because a flag only informs; a decision can end a habit's version or put an
 * override in front of every later use, and text read from outside must not be able to do that.
 * It reads only the run and the definition, runs nothing, and never affects the run. A decision is
 * recorded at most once per task run and operation.
 */
class HabitDecisionObserver(
    private val sink: () -> ProcedureTraceSink,
) {
    private val reported = mutableSetOf<String>()

    suspend fun observe(project: Project, definition: WorkflowDefinition, run: WorkflowRun) {
        for (task in definition.tasks) {
            val taskRun = run.taskRuns[task.id] ?: continue
            if (taskRun.status != TaskRunStatus.Completed) continue
            if ((taskRun.executor ?: task.executor) !is TaskExecutor.RoleAgent) continue
            val text = taskRun.artifacts.mapNotNull { it.textContent }.joinToString("\n").take(MAX_WINDOW)
            if (text.isBlank()) continue
            val decisions = HabitDecisions.parse(text)
            if (decisions.isEmpty()) continue
            if (definition.contextInputs(run, task).any { it.isUntrustedContext() }) continue
            val author = (taskRun.assignedRoleId ?: task.roleId)?.value ?: "agent"
            for (decision in decisions) {
                val key = "${run.id.value}:${taskRun.id.value}:${taskRun.attempt}:${decision.operation}"
                if (!reported.add(key)) continue
                if (reported.size > MAX_REMEMBERED) reported.clear()
                val deliberation = try {
                    ProcedureTraceDeliberation(
                        outcome = decision.outcome,
                        author = author,
                        text = decision.text,
                        alternative = decision.alternative,
                        alternativeOperation = decision.alternativeOperation,
                    )
                } catch (_: IllegalArgumentException) {
                    continue
                }
                sink().record(
                    ProcedureTrace(
                        workflowId = run.id.value,
                        projectId = project.id.value,
                        taskRunId = taskRun.id.value,
                        taskDefinitionId = task.id.value,
                        attempt = taskRun.attempt,
                        phase = ProcedureTracePhase.Deliberation,
                        executorKind = "Deliberation",
                        outcome = ProcedureTraceOutcome.Running,
                        recordedAtEpochMillis = run.updatedAtEpochMillis,
                        operation = decision.operation,
                        deliberation = deliberation,
                    ),
                )
            }
        }
    }

    private companion object {
        const val MAX_WINDOW = 4000
        const val MAX_REMEMBERED = 4096
    }
}
