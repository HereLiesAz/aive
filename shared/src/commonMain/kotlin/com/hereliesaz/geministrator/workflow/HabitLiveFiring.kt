package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.events.WorkflowEventSink
import com.hereliesaz.geministrator.memory.MemoryRuntimeBridge
import com.hereliesaz.geministrator.memory.procedural.HabitInvocation
import com.hereliesaz.geministrator.policy.PolicyEffect

/**
 * Acts on a habit an agent chose with a `/habit <ref>` line that [HabitReplayObserver] accepted (docs/architecture/
 * PROCEDURAL_MEMORY.md, section 11). It adds the habit's step to the live run the way a programmatic firing does:
 * the proposal is the ordinary [HabitInvocation] task, added through [WorkflowCompositionService.addProposedTasks]
 * behind its own human-approval task, and the run takes it the way [ApplicationRuntime.extendRun] takes added steps
 * (a fresh task run for each new step, every step already in the run keeping its own state).
 *
 * When it starts: the first added approval task depends on the task that chose the habit, so it is released once
 * that task is complete (it always is: the choice is read from the finished answer). The habit's step then waits
 * for a person's approval like any proposed step. Nothing the choosing task did is changed, restarted or cancelled.
 *
 * Once per task and habit: the added tasks have ids made from the choosing task and the habit, so a repeat, also
 * after a restart (the run keeps its definition), finds them already there and is ignored. Each outcome is one
 * line in the run's story.
 */
class HabitLiveFiring(
    private val events: WorkflowEventSink,
    private val composition: WorkflowCompositionService,
    private val propose: suspend (ref: String, idPrefix: String) -> HabitInvocation.Result = { ref, idPrefix ->
        MemoryRuntimeBridge.habitRefProposer?.invoke(ref, idPrefix) ?: HabitInvocation.Result.Refused("Habit replay is off.")
    },
) {
    /**
     * Adds [choice]'s step to [run] and hands the new definition and run to [commit], which saves and publishes them;
     * the story says "added" only after [commit] returned. Returns whether the run took the step.
     */
    suspend fun fire(
        choice: HabitChoice,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        nowEpochMillis: Long,
        commit: suspend (WorkflowDefinition, WorkflowRun) -> Unit,
    ): Boolean {
        suspend fun say(effect: PolicyEffect, code: String, reason: String): Boolean {
            runCatching {
                events.append(
                    PolicyDecisionRecorded(
                        workflowRunId = run.id, taskDefinitionId = choice.taskId, effect = effect, code = code,
                        action = "InvokeHabit", subjectType = "agent", subjectId = choice.roleId,
                        resourceType = "habit", resourceId = choice.ref, reason = reason, occurredAtEpochMillis = nowEpochMillis,
                    ),
                )
            }
            return false
        }
        if (choice.runId != run.id) {
            return say(PolicyEffect.Deny, "habit-ignored", "Habit ${choice.ref} was ignored: the run that chose it is not the one on screen.")
        }
        val task = definition.tasks.firstOrNull { it.id == choice.taskId }
        if (task == null || run.taskRuns[choice.taskId]?.status != TaskRunStatus.Completed) {
            return say(PolicyEffect.Deny, "habit-ignored", "Habit ${choice.ref} was ignored: the task that chose it is not finished.")
        }
        if (run.status.isTerminal()) {
            return say(
                PolicyEffect.Deny, "habit-not-eligible",
                "Habit ${choice.ref} was not added: the run had already finished, so there was nothing left to add a step to.",
            )
        }
        val prefix = "habit-${safe(choice.taskId.value)}-${safe(choice.ref.removePrefix("proc:"))}"
        if (definition.tasks.any { it.id.value == "$prefix-1" }) {
            return say(PolicyEffect.Deny, "habit-ignored", "Habit ${choice.ref} was ignored: it was already added for ${task.name}, and it is added once.")
        }
        val proposed = when (val result = propose(choice.ref, prefix)) {
            is HabitInvocation.Result.Refused ->
                return say(PolicyEffect.Deny, "habit-ignored", "Habit ${choice.ref} was ignored: ${result.reason}")
            is HabitInvocation.Result.Proposed -> result.task
        }
        val extended = try {
            composition.addProposedTasks(definition, listOf(proposed), connectFrom = setOf(choice.taskId))
        } catch (refusal: IllegalArgumentException) {
            return say(PolicyEffect.Deny, "habit-not-eligible", "Habit ${choice.ref} was not added: ${refusal.message ?: "the run could not take it."}")
        }
        val fresh = WorkflowRunFactory.create(
            definition = extended,
            workflowRunId = run.id,
            projectId = run.projectId,
            objective = run.objective,
            nowEpochMillis = nowEpochMillis,
            taskRunIdFactory = { id: TaskDefinitionId -> TaskRunId("${run.id.value}:${id.value}:habit") },
            roles = run.roleSnapshot,
            parentWorkflowRunIds = run.parentWorkflowRunIds,
            objectiveProvenance = run.objectiveProvenance,
        )
        // Every task already in the run keeps its own state; only the added ones take a new one.
        val taskRuns = fresh.taskRuns.mapValues { (id, added) -> run.taskRuns[id] ?: added }
        val followed = WorkflowRunFactory.refreshReadiness(
            definition = extended,
            run = run.copy(taskRuns = taskRuns, definitionSnapshot = extended, updatedAtEpochMillis = nowEpochMillis),
            nowEpochMillis = nowEpochMillis,
        )
        commit(extended, followed)
        say(
            PolicyEffect.Allow, "habit-fired",
            "Habit ${choice.ref} was added after ${task.name} finished: \"${proposed.name}\" waits for your approval, and nothing runs until you give it.",
        )
        return true
    }

    private fun safe(text: String): String = text.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '-' }.joinToString("")
}
