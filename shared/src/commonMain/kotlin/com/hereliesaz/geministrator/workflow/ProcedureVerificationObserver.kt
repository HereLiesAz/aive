package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceRedactor
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceVerification

/**
 * Writes a verification receipt when a reviewing task approves or turns back a system executor's
 * completed work (see [ProcedureVerifications]). It reads only the run and the definition, runs
 * nothing, and writes through the same opt-in sink as every other receipt, so it records nothing
 * while procedural capture is off. Each outcome is reported once per process; the bank also ignores
 * a receipt it already holds, so a restart repeats nothing.
 */
class ProcedureVerificationObserver(
    private val sink: () -> ProcedureTraceSink,
) {
    private val reported = mutableSetOf<String>()

    suspend fun observe(project: Project, definition: WorkflowDefinition, run: WorkflowRun) {
        for (task in definition.tasks) {
            val taskRun = run.taskRuns[task.id] ?: continue
            val executor = taskRun.executor ?: task.executor ?: continue
            if (!executor.isSystemExecutor() || taskRun.status != TaskRunStatus.Completed) continue
            val verification = ProcedureVerifications.of(definition, run, task.id)
            if (verification == ProcedureTraceVerification.Unknown) continue
            val key = "${run.id.value}:${taskRun.id.value}:${taskRun.attempt}:${verification.name}"
            if (!reported.add(key)) continue
            if (reported.size > MAX_REMEMBERED) reported.clear()
            val context = TaskExecutorContext(project, definition, run, task, taskRun, executor, run.updatedAtEpochMillis)
            sink().record(ProcedureTraceRedactor.captureVerification(context, verification))
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 4096
    }
}
