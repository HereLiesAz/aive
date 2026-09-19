package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.DistributedComputeRequirements
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.effectiveExecutor

sealed interface ComputeDelegationTarget {
    data object Local : ComputeDelegationTarget
    data object AnyRemote : ComputeDelegationTarget

    data class Node(val nodeId: String) : ComputeDelegationTarget {
        init {
            require(nodeId.isNotBlank()) { "Delegation node ID must not be blank" }
        }
    }
}

fun TaskDefinition.isComputeDelegatable(): Boolean =
    baseDelegatedExecutor() !is TaskExecutor.HumanApproval

fun TaskDefinition.computeDelegationTarget(): ComputeDelegationTarget {
    val distributed = executor as? TaskExecutor.Distributed ?: return ComputeDelegationTarget.Local
    val requiredNodes = distributed.requirements.requiredNodeIds
    return if (requiredNodes.size == 1) {
        ComputeDelegationTarget.Node(requiredNodes.single())
    } else {
        ComputeDelegationTarget.AnyRemote
    }
}

fun TaskDefinition.withComputeDelegation(target: ComputeDelegationTarget): TaskDefinition {
    val existing = executor as? TaskExecutor.Distributed
    val delegate = baseDelegatedExecutor()
    if (delegate is TaskExecutor.HumanApproval) return this

    return when (target) {
        ComputeDelegationTarget.Local -> {
            if (existing == null) {
                this
            } else {
                val restoredExecutor = if (
                    delegate is TaskExecutor.RoleAgent &&
                    roleId == delegate.roleId
                ) {
                    null
                } else {
                    delegate
                }
                copy(executor = restoredExecutor)
            }
        }

        ComputeDelegationTarget.AnyRemote -> {
            val requirements = (existing?.requirements ?: DistributedComputeRequirements()).copy(
                requiredNodeIds = emptySet(),
                preferredNodeIds = emptySet(),
            )
            copy(
                executor = TaskExecutor.Distributed(
                    delegate = delegate,
                    requirements = requirements,
                    label = "Any available device",
                ),
            )
        }

        is ComputeDelegationTarget.Node -> {
            val requirements = (existing?.requirements ?: DistributedComputeRequirements()).copy(
                requiredNodeIds = setOf(target.nodeId),
                preferredNodeIds = setOf(target.nodeId),
            )
            copy(
                executor = TaskExecutor.Distributed(
                    delegate = delegate,
                    requirements = requirements,
                    label = "Assigned device",
                ),
            )
        }
    }
}

fun WorkflowDefinition.withWorkflowComputeDelegation(
    target: ComputeDelegationTarget,
): WorkflowDefinition = copy(
    tasks = tasks.map { task ->
        if (task.isComputeDelegatable()) task.withComputeDelegation(target) else task
    },
)

fun WorkflowDefinition.withRoleComputeDelegation(
    roleId: RoleDefinitionId,
    target: ComputeDelegationTarget,
): WorkflowDefinition = copy(
    tasks = tasks.map { task ->
        if (task.isAssignedToRole(roleId) && task.isComputeDelegatable()) {
            task.withComputeDelegation(target)
        } else {
            task
        }
    },
)

fun WorkflowDefinition.withTaskComputeDelegation(
    taskId: TaskDefinitionId,
    target: ComputeDelegationTarget,
): WorkflowDefinition = copy(
    tasks = tasks.map { task ->
        if (task.id == taskId && task.isComputeDelegatable()) {
            task.withComputeDelegation(target)
        } else {
            task
        }
    },
)

fun TaskDefinition.isAssignedToRole(roleId: RoleDefinitionId): Boolean {
    if (this.roleId == roleId) return true
    return baseDelegatedExecutor() == TaskExecutor.RoleAgent(roleId)
}

private fun TaskDefinition.baseDelegatedExecutor(): TaskExecutor {
    val effective = effectiveExecutor()
    return if (effective is TaskExecutor.Distributed) effective.delegate else effective
}
