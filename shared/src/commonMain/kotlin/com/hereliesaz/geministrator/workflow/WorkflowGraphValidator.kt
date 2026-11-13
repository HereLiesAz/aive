package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.policy.ApprovalEvidence
import com.hereliesaz.geministrator.template.StepInstantiation
import com.hereliesaz.geministrator.policy.InProcessPolicyDecisionPoint
import com.hereliesaz.geministrator.policy.PolicyAction
import com.hereliesaz.geministrator.policy.PolicyContext
import com.hereliesaz.geministrator.policy.PolicyRequest
import com.hereliesaz.geministrator.policy.PolicyResource
import com.hereliesaz.geministrator.policy.PolicySubject
import com.hereliesaz.geministrator.policy.isDenied

sealed interface WorkflowValidationError {
    data class DuplicateTaskId(val taskId: TaskDefinitionId) : WorkflowValidationError
    data class MissingDependency(
        val taskId: TaskDefinitionId,
        val missingDependencyId: TaskDefinitionId,
    ) : WorkflowValidationError
    data class MissingConditionTarget(
        val taskId: TaskDefinitionId,
        val missingTargetId: TaskDefinitionId,
    ) : WorkflowValidationError
    data class MissingExecutor(val taskId: TaskDefinitionId) : WorkflowValidationError
    data class MissingRepositoryMutationApproval(val taskId: TaskDefinitionId) : WorkflowValidationError
    data class MissingStepApproval(val taskId: TaskDefinitionId) : WorkflowValidationError
    data class MissingContractInput(val taskId: TaskDefinitionId, val port: String) : WorkflowValidationError
    data class UnboundContractInput(val taskId: TaskDefinitionId, val port: String) : WorkflowValidationError
    data class SelfDependency(val taskId: TaskDefinitionId) : WorkflowValidationError
    data class Cycle(val taskIds: Set<TaskDefinitionId>) : WorkflowValidationError
}

fun WorkflowValidationError.humanReadable(): String = when (this) {
    is WorkflowValidationError.DuplicateTaskId ->
        "Task '${taskId.value}' appears more than once. Each task must have a unique ID."
    is WorkflowValidationError.MissingDependency ->
        "Task '${taskId.value}' depends on '${missingDependencyId.value}', which doesn't exist in this workflow."
    is WorkflowValidationError.MissingConditionTarget ->
        "Task '${taskId.value}' has a condition referencing '${missingTargetId.value}', which doesn't exist in this workflow."
    is WorkflowValidationError.MissingExecutor ->
        "Task '${taskId.value}' has no executor or role assigned. Every task must specify who does the work."
    is WorkflowValidationError.MissingRepositoryMutationApproval ->
        "Repository mutation task '${taskId.value}' must depend on an unconditional human-approval task."
    is WorkflowValidationError.MissingStepApproval ->
        "Step '${taskId.value}' changes something outside its own output, so it needs your approval: turn its approval on, or put an approval step before it."
    is WorkflowValidationError.MissingContractInput ->
        "Step '${taskId.value}' needs its '$port' input before it can start."
    is WorkflowValidationError.UnboundContractInput ->
        "Step '${taskId.value}' takes its '$port' input from a step it does not wait for."
    is WorkflowValidationError.SelfDependency ->
        "Task '${taskId.value}' lists itself as a dependency. A task cannot depend on itself."
    is WorkflowValidationError.Cycle ->
        "Circular dependency detected among tasks: ${taskIds.joinToString(" → ") { it.value }}. These tasks can never all complete."
}

/** Repository operations that only read; every other operation mutates and needs human approval. */
val READ_ONLY_REPOSITORY_OPERATIONS: Set<String> = setOf("status", "fetch")

fun TaskExecutor.RepositoryOperation.isMutation(): Boolean = operation.trim() !in READ_ONLY_REPOSITORY_OPERATIONS

/** The executor that actually does the work: a [TaskExecutor.Distributed] placement is unwrapped. */
fun TaskExecutor?.withoutPlacement(): TaskExecutor? = (this as? TaskExecutor.Distributed)?.delegate ?: this

object WorkflowGraphValidator {
    fun validate(definition: WorkflowDefinition): List<WorkflowValidationError> {
        val errors = mutableListOf<WorkflowValidationError>()
        val grouped = definition.tasks.groupBy { it.id }
        grouped.filterValues { it.size > 1 }.keys.forEach {
            errors += WorkflowValidationError.DuplicateTaskId(it)
        }

        val knownIds = grouped.keys
        val tasksById = definition.tasks.associateBy { it.id }

        // A conditional approval can be skipped at runtime, so only an unconditional one gates.
        fun hasHumanApprovalAncestor(taskId: TaskDefinitionId): Boolean {
            val visited = mutableSetOf<TaskDefinitionId>()
            fun visit(id: TaskDefinitionId): Boolean {
                if (!visited.add(id)) return false
                val task = tasksById[id] ?: return false
                return task.dependsOn.any { dependencyId ->
                    val dependency = tasksById[dependencyId] ?: return@any false
                    (dependency.executor is TaskExecutor.HumanApproval && dependency.condition is TaskCondition.Always) ||
                        visit(dependencyId)
                }
            }
            return visit(taskId)
        }

        definition.tasks.forEach { task ->
            if (task.executor == null && task.roleId == null) {
                errors += WorkflowValidationError.MissingExecutor(task.id)
            }
            task.dependsOn.forEach { dependency ->
                when {
                    dependency == task.id -> errors += WorkflowValidationError.SelfDependency(task.id)
                    dependency !in knownIds -> errors += WorkflowValidationError.MissingDependency(task.id, dependency)
                }
            }
            val conditionTarget = when (val c = task.condition) {
                is TaskCondition.Always -> null
                is TaskCondition.OnAnyOutcome -> c.ofTask
                is TaskCondition.OnFailure -> c.ofTask
            }
            if (conditionTarget != null && conditionTarget !in knownIds) {
                errors += WorkflowValidationError.MissingConditionTarget(task.id, conditionTarget)
            }

            // A step's inputs are present, and each one taken from another step waits for that step.
            task.contract?.let { contract ->
                StepInstantiation.missingInputs(contract).forEach { errors += WorkflowValidationError.MissingContractInput(task.id, it) }
                contract.inputs.forEach { (port, value) ->
                    val from = StepInstantiation.boundTask(value)
                    if (from != null && from !in task.dependsOn) errors += WorkflowValidationError.UnboundContractInput(task.id, port)
                }
            }

            // A step that declares an effect is made only with an approval (invariant 4a).
            if (task.isEffectfulStep()) {
                val decision = decideStepEffect(
                    task.id,
                    task,
                    stepApprovalEvidence(task, hasApprovalAncestor = hasHumanApprovalAncestor(task.id), granted = false),
                )
                if (decision.isDenied || decision is com.hereliesaz.geministrator.policy.PolicyDecision.RequireApproval) {
                    errors += WorkflowValidationError.MissingStepApproval(task.id)
                }
            }

            // A repository operation placed on another device is still a repository operation.
            // Invariant 4 is decided by the policy decision point: statically, a declared approval
            // ancestor gates the mutation, and its absence denies it.
            val repositoryOperation = task.executor.withoutPlacement() as? TaskExecutor.RepositoryOperation
            if (repositoryOperation != null) {
                val decision = InProcessPolicyDecisionPoint.decide(
                    PolicyRequest(
                        subject = PolicySubject.System,
                        action = PolicyAction.RunRepositoryOperation(repositoryOperation.operation),
                        resource = PolicyResource.task(task.id),
                        context = PolicyContext(
                            taskDefinitionId = task.id,
                            approval = if (repositoryOperation.isMutation() && hasHumanApprovalAncestor(task.id)) {
                                ApprovalEvidence.Declared
                            } else {
                                ApprovalEvidence.None
                            },
                        ),
                    ),
                )
                if (decision.isDenied) {
                    errors += WorkflowValidationError.MissingRepositoryMutationApproval(task.id)
                }
            }
        }

        if (errors.any { it is WorkflowValidationError.DuplicateTaskId }) {
            return errors
        }

        val cycleNodes = detectCycleNodes(definition)
        if (cycleNodes.isNotEmpty()) {
            errors += WorkflowValidationError.Cycle(cycleNodes)
        }

        return errors
    }

    fun requireValid(definition: WorkflowDefinition) {
        val errors = validate(definition)
        require(errors.isEmpty()) {
            errors.joinToString(separator = "\n") { it.humanReadable() }
        }
    }

    private fun detectCycleNodes(definition: WorkflowDefinition): Set<TaskDefinitionId> {
        val dependencies = definition.tasks.associate { task ->
            val conditionTarget = when (val c = task.condition) {
                is TaskCondition.Always -> null
                is TaskCondition.OnAnyOutcome -> c.ofTask
                is TaskCondition.OnFailure -> c.ofTask
            }
            task.id to (task.dependsOn + listOfNotNull(conditionTarget)).distinct()
        }
        val visiting = mutableSetOf<TaskDefinitionId>()
        val visited = mutableSetOf<TaskDefinitionId>()
        val cycleNodes = linkedSetOf<TaskDefinitionId>()

        fun visit(taskId: TaskDefinitionId, path: MutableList<TaskDefinitionId>) {
            if (taskId in visited) return
            if (taskId in visiting) {
                val cycleStart = path.indexOf(taskId)
                if (cycleStart >= 0) {
                    cycleNodes += path.subList(cycleStart, path.size)
                }
                cycleNodes += taskId
                return
            }

            visiting += taskId
            path += taskId
            dependencies[taskId].orEmpty()
                .filter { it in dependencies }
                .forEach { visit(it, path) }
            path.removeAt(path.lastIndex)
            visiting -= taskId
            visited += taskId
        }

        dependencies.keys.forEach { visit(it, mutableListOf()) }
        return cycleNodes
    }
}
