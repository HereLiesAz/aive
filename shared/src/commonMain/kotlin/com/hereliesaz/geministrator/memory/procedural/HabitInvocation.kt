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
 * decide whether and how it runs. The proposed task always asks for a person's approval first.
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
