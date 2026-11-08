package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId

/**
 * Turns a habit into a task someone may add to a workflow. This is invocation in its governed form:
 * it runs nothing and has no executor of its own. The result is an ordinary [TaskDefinition], so
 * the workflow engine, its executor integrations, its approval gates and its context-taint gate
 * decide whether and how it runs. The task carries a human-approval policy, but the engine reads that
 * policy only to let an agent proceed past its plan, so it does not hold a system step. What holds it is
 * the human-approval task that WorkflowCompositionService.addProposedTasks puts in front of every
 * step when the proposal is added to a workflow; a proposal run any other way is not gated by this.
 *
 * Only what a receipt can name is rebuilt. The workflow, environment, operation or service comes from
 * the habit's operation; the git ref and any inputs come from the caller and must be the same kind of
 * short typed value a receipt keeps. A habit's slot register is advice about what has been used; it
 * is never copied in, because a receipt cannot restore an input's exact name.
 */
object HabitInvocation {
    sealed interface Result {
        data class Proposed(val task: TaskDefinition) : Result
        data class Refused(val reason: String) : Result
    }

    fun propose(
        habit: FormedHabit,
        taskId: TaskDefinitionId,
        ref: String? = null,
        inputs: Map<String, String> = emptyMap(),
    ): Result {
        if (habit.executorKind == "Distributed") {
            return Result.Refused("Its placement on another node is not recorded, so it cannot be rebuilt faithfully.")
        }
        val executor = executorOf(habit.operation, ref, inputs)
            ?: return Result.Refused("No executor can be rebuilt from ${habit.operation}.")
        if (executor is TaskExecutor.GitHubAction) {
            val kept = ProcedureTraceRedactor.slotsOf(executor).map { it.name }.toSet()
            val offered = buildList {
                ref?.let { add("ref") }
                inputs.keys.forEach { add(it.lowercase().replace('-', '_')) }
            }
            if (!kept.containsAll(offered)) {
                return Result.Refused("Only short numbers, flags and plain words are accepted as a ref or an input.")
            }
        } else if (ref != null || inputs.isNotEmpty()) {
            return Result.Refused("A ref and inputs apply to a GitHub Action only.")
        }
        return Result.Proposed(
            TaskDefinition(
                id = taskId,
                name = "Habit: ${habit.operation}",
                objective = objectiveOf(habit),
                roleId = null,
                executor = executor,
                approvalPolicy = ApprovalPolicy.HumanApproval,
            ),
        )
    }

    /**
     * A sequence as ordinary tasks, each depending on the one before so they run in the order they
     * were seen. Each step is gated by an approval task only when added through addProposedTasks. Refused if any step cannot be rebuilt or
     * was placed on another node. No refs or inputs are carried: edit the tasks after adding them.
     */
    fun proposeSequence(
        sequence: FormedSequence,
        habits: List<FormedHabit>,
        idPrefix: String,
    ): SequenceResult {
        if (idPrefix.isBlank()) return SequenceResult.Refused("Tasks need an id prefix.")
        val tasks = mutableListOf<TaskDefinition>()
        for ((index, operation) in sequence.operations.withIndex()) {
            val habit = habits.firstOrNull { it.operation == operation }
                ?: return SequenceResult.Refused("No habit has formed for $operation.")
            when (val step = propose(habit, TaskDefinitionId("$idPrefix-${index + 1}"))) {
                is Result.Refused -> return SequenceResult.Refused(step.reason)
                is Result.Proposed -> tasks += step.task.copy(
                    name = "Habit step ${index + 1} of ${sequence.operations.size}: $operation",
                    dependsOn = tasks.lastOrNull()?.let { setOf(it.id) } ?: emptySet(),
                )
            }
        }
        return SequenceResult.Proposed(tasks)
    }

    sealed interface SequenceResult {
        data class Proposed(val tasks: List<TaskDefinition>) : SequenceResult
        data class Refused(val reason: String) : SequenceResult
    }

    private fun executorOf(operation: String, ref: String?, inputs: Map<String, String>): TaskExecutor? {
        val kind = operation.substringBefore(':')
        val rest = operation.substringAfter(':', "")
        if (rest.isBlank()) return null
        return when (kind) {
            "github-action" -> TaskExecutor.GitHubAction(workflow = rest, ref = ref, inputs = inputs)
            "deployment" -> TaskExecutor.Deployment(rest)
            "repository" -> TaskExecutor.RepositoryOperation(rest)
            "service" -> TaskExecutor.ExternalService(rest.substringBefore(':'), rest.substringAfter(':', "").ifBlank { null })
            "nested-workflow" -> TaskExecutor.NestedWorkflow(WorkflowDefinitionId(rest))
            else -> null
        }
    }

    private fun objectiveOf(habit: FormedHabit): String = buildString {
        append("Carry out ").append(habit.operation).append(" as it has been done before: ")
            .append(habit.completedUses).append(" of ").append(habit.uses).append(" earlier uses completed.")
        habit.activeOverride?.let { o ->
            append(" An override stands (").append(o.author).append("): ")
                .append(o.text.replace(Regex("\\s+"), " ")).append(" Instead: ")
                .append(o.alternative.replace(Regex("\\s+"), " "))
        }
    }
}
