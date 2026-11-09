package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.domain.releasedDependencies
import com.hereliesaz.geministrator.memory.procedural.CautionClerk
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
 * The same reading is made of an agent that a finished executor task depends on directly (the use
 * window): the model that prepared or chose the run, whose cautions about it are recorded with source
 * `used`. Both windows record at most one flag per agent task run per habit per source.
 *
 * It reads only the run and the definition, runs nothing, and records at most one flag per agent
 * task run per habit. An agent that wrote no warning leaves nothing; silence is not a flag. The flag
 * records whether the agent had untrusted content in front of it, because a result can try to steer
 * its handler.
 */
class HabitFlagObserver(
    private val clerk: () -> CautionClerk? = { null },
    private val sink: () -> ProcedureTraceSink,
) {
    private val reported = mutableSetOf<String>()

    suspend fun observe(project: Project, definition: WorkflowDefinition, run: WorkflowRun) {
        // The results window: an agent that handled a finished executor's results.
        for (handler in definition.tasks) {
            val handlerRun = run.taskRuns[handler.id] ?: continue
            if (!isFinishedAgent(handler, handlerRun)) continue
            val window = windowOf(handlerRun) ?: continue
            for (sourceId in definition.releasedDependencies(handler)) {
                val (_, executor) = finishedSystemTask(definition, run, sourceId) ?: continue
                record(project, definition, run, handler, handlerRun, executor, window, ProcedureFlagSource.Handled)
            }
        }
        // The use window: an agent that prepared or chose a run, once that run has finished. Its
        // cautions about the operation are the user's, counted apart from the handlers'.
        for (task in definition.tasks) {
            val (_, executor) = finishedSystemTask(definition, run, task.id) ?: continue
            for (userId in definition.releasedDependencies(task)) {
                val user = definition.tasks.firstOrNull { it.id == userId } ?: continue
                val userRun = run.taskRuns[userId] ?: continue
                if (!isFinishedAgent(user, userRun)) continue
                val window = windowOf(userRun) ?: continue
                record(project, definition, run, user, userRun, executor, window, ProcedureFlagSource.Used)
            }
        }
    }

    private fun isFinishedAgent(task: TaskDefinition, taskRun: TaskRun): Boolean =
        taskRun.status == TaskRunStatus.Completed && (taskRun.executor ?: task.executor) is TaskExecutor.RoleAgent

    private fun windowOf(taskRun: TaskRun): String? =
        taskRun.artifacts.mapNotNull { it.textContent }.joinToString("\n").take(MAX_WINDOW).takeIf { it.isNotBlank() }

    private fun finishedSystemTask(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        id: TaskDefinitionId,
    ): Pair<TaskDefinition, TaskExecutor>? {
        val sourceRun = run.taskRuns[id] ?: return null
        if (sourceRun.status != TaskRunStatus.Completed) return null
        val sourceTask = definition.tasks.firstOrNull { it.id == id } ?: return null
        val executor = sourceRun.executor ?: sourceTask.executor ?: return null
        if (!executor.isSystemExecutor()) return null
        return sourceTask to executor
    }

    private suspend fun record(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        agent: TaskDefinition,
        agentRun: TaskRun,
        executor: TaskExecutor,
        window: String,
        source: ProcedureFlagSource,
    ) {
        val operation = ProcedureTraceRedactor.operationOf(executor) ?: return
        val cues = HabitOffers.cuesOf(operation)
        var cautions = CautionRecognizer.recognize(window, cues)
        // The recognizer is conservative on purpose. A clerk, when the app has one, may add to what it
        // kept by labelling the sentences it set aside; it never rewrites one.
        if (cautions.isEmpty()) {
            val labeller = clerk()
            if (labeller != null) {
                cautions = CautionRecognizer.candidates(window, cues).filter { sentence ->
                    try {
                        labeller.isCaution(sentence, operation)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }.take(CautionRecognizer.MAX_SENTENCES)
            }
        }
        if (cautions.isEmpty()) return
        val key = "${source.name}:${run.id.value}:${agentRun.id.value}:${agentRun.attempt}:$operation"
        if (!reported.add(key)) return
        if (reported.size > MAX_REMEMBERED) reported.clear()
        val tainted = definition.taintInputs(run, agent).any { it.isUntrustedContext() }
        sink().record(
            ProcedureTrace(
                workflowId = run.id.value,
                projectId = project.id.value,
                taskRunId = agentRun.id.value,
                taskDefinitionId = agent.id.value,
                attempt = agentRun.attempt,
                phase = ProcedureTracePhase.Flag,
                executorKind = ProcedureTraceRedactor.kindOf(executor),
                outcome = ProcedureTraceOutcome.Running,
                recordedAtEpochMillis = run.updatedAtEpochMillis,
                operation = operation,
                flag = ProcedureTraceFlag(
                    source = source,
                    author = (agentRun.assignedRoleId ?: agent.roleId)?.value ?: "agent",
                    text = cautions.joinToString(" ").take(ProcedureTraceFlag.MAX_TEXT),
                    tainted = tainted,
                ),
                environment = ProcedureTraceRedactor.environmentOf(project.repository),
            ),
        )
    }

    private companion object {
        const val MAX_WINDOW = 4000
        const val MAX_REMEMBERED = 4096
    }
}
