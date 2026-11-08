package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.domain.releasedDependencies
import com.hereliesaz.geministrator.memory.procedural.CautionRecognizer
import com.hereliesaz.geministrator.memory.procedural.HabitOffers
import com.hereliesaz.geministrator.memory.procedural.ProcedureFlagSource
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceFlag
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceRedactor
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink

/**
 * The results window of docs/architecture/HABITS_AS_MEMORY.md, 5.3: when an agent task that depends
 * directly on a system executor's finished work has finished itself, what it wrote is read by the
 * [CautionRecognizer]. If the agent warned about the work, its own sentences are recorded as a flag
 * on the habit that work belongs to, beside the executor's receipts, through the same opt-in sink.
 *
 * It reads only the run and the definition, runs nothing, and records at most one flag per agent
 * task run per habit. An agent that wrote no warning leaves nothing; silence is not a flag. The flag
 * records whether the agent had untrusted content in front of it, because a result can try to steer
 * its handler.
 */
class HabitFlagObserver(
    private val sink: () -> ProcedureTraceSink,
) {
    private val reported = mutableSetOf<String>()

    suspend fun observe(project: Project, definition: WorkflowDefinition, run: WorkflowRun) {
        for (handler in definition.tasks) {
            val handlerRun = run.taskRuns[handler.id] ?: continue
            if (handlerRun.status != TaskRunStatus.Completed) continue
            if ((handlerRun.executor ?: handler.executor) !is TaskExecutor.RoleAgent) continue
            val window = handlerRun.artifacts.mapNotNull { it.textContent }.joinToString("\n").take(MAX_WINDOW)
            if (window.isBlank()) continue
            val tainted = definition.contextInputs(run, handler).any { it.isUntrustedContext() }
            for (sourceId in definition.releasedDependencies(handler)) {
                val sourceRun = run.taskRuns[sourceId] ?: continue
                if (sourceRun.status != TaskRunStatus.Completed) continue
                val sourceTask = definition.tasks.firstOrNull { it.id == sourceId } ?: continue
                val executor = sourceRun.executor ?: sourceTask.executor ?: continue
                if (!executor.isSystemExecutor()) continue
                val operation = ProcedureTraceRedactor.operationOf(executor) ?: continue
                val cautions = CautionRecognizer.recognize(window, HabitOffers.cuesOf(operation))
                if (cautions.isEmpty()) continue
                val key = "${run.id.value}:${handlerRun.id.value}:${handlerRun.attempt}:$operation"
                if (!reported.add(key)) continue
                if (reported.size > MAX_REMEMBERED) reported.clear()
                sink().record(
                    ProcedureTrace(
                        workflowId = run.id.value,
                        projectId = project.id.value,
                        taskRunId = handlerRun.id.value,
                        taskDefinitionId = handler.id.value,
                        attempt = handlerRun.attempt,
                        phase = ProcedureTracePhase.Flag,
                        executorKind = ProcedureTraceRedactor.kindOf(executor),
                        outcome = ProcedureTraceOutcome.Running,
                        recordedAtEpochMillis = run.updatedAtEpochMillis,
                        operation = operation,
                        flag = ProcedureTraceFlag(
                            source = ProcedureFlagSource.Handled,
                            author = (handlerRun.assignedRoleId ?: handler.roleId)?.value ?: "agent",
                            text = cautions.joinToString(" ").take(ProcedureTraceFlag.MAX_TEXT),
                            tainted = tainted,
                        ),
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
