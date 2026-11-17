package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.anyEffectful
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.domain.releasedDependencies
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.events.WorkflowEventSink
import com.hereliesaz.geministrator.memory.procedural.HabitInvokeCommand
import com.hereliesaz.geministrator.memory.procedural.HabitReplayLedger
import com.hereliesaz.geministrator.memory.procedural.HabitReplayPlanner
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceVerification
import com.hereliesaz.geministrator.memory.procedural.ReplayContextItem
import com.hereliesaz.geministrator.memory.procedural.ReplayInputs
import com.hereliesaz.geministrator.memory.procedural.ReplayStatus
import com.hereliesaz.geministrator.memory.procedural.ReplaySourceRun
import com.hereliesaz.geministrator.policy.PolicyEffect
import com.hereliesaz.geministrator.providers.HabitReplayAgentProvider

/**
 * Teaches and polices habit replay from what a run did. It reads the run and the definition, runs nothing and
 * never affects the run:
 *
 * - an accepted, text-only, trusted agent task (a different reviewing task approved it) becomes a source run;
 * - a replayed task that a reviewer turned back, or that failed or was sent back, quarantines its habit at once;
 * - a habit step (a task a habit proposed) that failed or was turned back quarantines that operation;
 * - a `/habit <ref>` line an agent wrote is checked against the approved catalogue: an approved ref is counted
 *   (a matcher miss when replay had not fired) and named in the run's story, anything else is ignored and the
 *   story says so. A task that read untrusted content is never obeyed.
 *
 * Each outcome is read once per process; the ledger also remembers task runs it has read.
 */
class HabitReplayObserver(
    private val ledger: () -> HabitReplayLedger?,
    private val allowed: () -> Boolean,
    private val events: WorkflowEventSink,
) {
    private val replayed = mutableMapOf<String, String>()
    private val done = mutableSetOf<String>()

    suspend fun observe(project: Project, definition: WorkflowDefinition, run: WorkflowRun) {
        val store = ledger() ?: return
        if (!allowed()) return
        for (task in definition.tasks) {
            val taskRun = run.taskRuns[task.id] ?: continue
            val verification = ProcedureVerifications.of(definition, run, task.id)

            // A task a habit proposed: any failure or turn-back quarantines the operation.
            if (task.name.startsWith(STEP_PREFIX) || task.name.startsWith(STEP_N_PREFIX)) {
                val failed = taskRun.status == TaskRunStatus.Failed || verification == ProcedureTraceVerification.Rejected
                if (failed && once("op:${taskRun.id.value}:${taskRun.attempt}")) {
                    store.quarantineOperation(task.name.substringAfter(": ").trim())
                }
                continue
            }
            if ((taskRun.executor ?: task.executor) !is TaskExecutor.RoleAgent) continue
            val roleId = (taskRun.assignedRoleId ?: task.roleId)?.value ?: continue

            if (taskRun.assignedProviderId == HabitReplayAgentProvider.ID) {
                taskRun.artifacts.firstNotNullOfOrNull { it.metadata[HabitReplayAgentProvider.META_HABIT_ID] }
                    ?.let { replayed[taskRun.id.value] = it }
                val habitId = replayed[taskRun.id.value]
                val badStatus = taskRun.status in setOf(TaskRunStatus.Failed, TaskRunStatus.Retrying, TaskRunStatus.Escalated)
                if (habitId != null && (verification == ProcedureTraceVerification.Rejected || badStatus || taskRun.lastFailure != null) &&
                    once("q:${taskRun.id.value}")
                ) {
                    store.quarantine(habitId, "A replayed result was turned back or failed a check.")
                }
            }

            if (taskRun.status != TaskRunStatus.Completed) continue
            val untrusted = definition.taintInputs(run, task).any { it.isUntrustedContext() } || taskRun.artifacts.any { it.isUntrustedContext() }
            val text = taskRun.artifacts.mapNotNull { it.textContent }.joinToString("\n")

            for (ref in HabitInvokeCommand.refsIn(text)) {
                if (!once("i:${taskRun.id.value}:${taskRun.attempt}:$ref")) continue
                val state = store.state.value
                val textHabit = state.habits.firstOrNull { it.ref == ref }
                val approved = !untrusted && (
                    (textHabit != null && textHabit.status == ReplayStatus.Active && textHabit.approved) ||
                        (textHabit == null && operationApproved(store, ref))
                    )
                if (approved) {
                    if (taskRun.assignedProviderId != HabitReplayAgentProvider.ID) store.recordMiss(ref)
                }
                note(run, task.id.value, roleId, ref, approved, untrusted, run.updatedAtEpochMillis)
            }

            if (taskRun.assignedProviderId == HabitReplayAgentProvider.ID) continue
            if (verification != ProcedureTraceVerification.Verified || untrusted) continue
            if (!once("s:${taskRun.id.value}:${taskRun.attempt}")) continue
            if (store.hasSeen("${taskRun.id.value}:${taskRun.attempt}")) continue
            store.markSeen("${taskRun.id.value}:${taskRun.attempt}")
            source(project, definition, run, task, taskRun, roleId)?.let(store::recordSource)
        }
    }

    private fun operationApproved(store: HabitReplayLedger, ref: String): Boolean {
        val body = ref.removePrefix("proc:")
        val version = body.substringAfterLast('@').toIntOrNull() ?: return false
        return store.isOperationApproved(body.substringBeforeLast('@'), version)
    }

    private suspend fun note(run: WorkflowRun, taskId: String, roleId: String, ref: String, approved: Boolean, untrusted: Boolean, at: Long) {
        runCatching {
            events.append(
                PolicyDecisionRecorded(
                    workflowRunId = run.id,
                    taskDefinitionId = run.taskRuns.keys.firstOrNull { it.value == taskId },
                    effect = if (approved) PolicyEffect.Allow else PolicyEffect.Deny,
                    code = if (approved) "habit-invoked" else "habit-ref-ignored",
                    action = "InvokeHabit",
                    subjectType = "agent",
                    subjectId = roleId,
                    resourceType = "habit",
                    resourceId = ref,
                    reason = when {
                        approved -> "The agent chose approved habit $ref; it runs only through the normal checks and approvals."
                        untrusted -> "Ignored $ref: the task had read untrusted content."
                        else -> "Ignored $ref: it is not an approved habit."
                    },
                    occurredAtEpochMillis = at,
                ),
            )
        }
    }

    private fun source(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        task: com.hereliesaz.geministrator.domain.TaskDefinition,
        taskRun: com.hereliesaz.geministrator.domain.TaskRun,
        roleId: String,
    ): ReplaySourceRun? {
        val role = run.roleSnapshot.firstOrNull { it.id.value == roleId } ?: return null
        if (!role.capabilitiesRequired.all { it in HabitReplayPlanner.TEXT_SAFE }) return null
        if (task.contract?.effects?.anyEffectful() == true) return null
        val redaction = definition.payloadRedactionPolicy
        if (redaction.redactObjective) return null
        val artifact = taskRun.artifacts.singleOrNull()?.takeIf { !it.textContent.isNullOrBlank() } ?: return null
        val output = artifact.textContent!!.take(MAX_TEXT)
        val context = definition.releasedDependencies(task)
            .mapNotNull(run.taskRuns::get)
            .flatMap { it.artifacts }
            .filter { it.kind !in redaction.excludedArtifactKinds }
            .mapNotNull { a -> a.textContent?.let { ReplayContextItem(a.label, it.take(MAX_CONTEXT)) } }
        val inputs = ReplayInputs(task.objective, task.acceptanceCriteria.map { it.description }, context)
        val promptChars = inputs.objective.length + inputs.criteria.sumOf { it.length } + context.sumOf { it.text.length + it.label.length }
        return ReplaySourceRun(
            workflowRunId = run.id.value,
            taskRunId = taskRun.id.value,
            attempt = taskRun.attempt,
            roleId = roleId,
            inputs = inputs,
            artifactKind = artifact.kind.name,
            output = output,
            promptTokens = taskRun.usage?.inputTokens?.takeIf { it > 0 } ?: (promptChars / 4L),
            outputTokens = taskRun.usage?.outputTokens?.takeIf { it > 0 } ?: (output.length / 4L),
            atEpochMillis = run.updatedAtEpochMillis,
        )
    }

    private fun once(key: String): Boolean {
        if (!done.add(key)) return false
        if (done.size > MAX_REMEMBERED) done.clear()
        return true
    }

    companion object {
        const val STEP_PREFIX = "Habit: "
        const val STEP_N_PREFIX = "Habit step "
        private const val MAX_TEXT = 8000
        private const val MAX_CONTEXT = 2000
        private const val MAX_REMEMBERED = 4096
    }
}
