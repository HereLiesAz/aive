package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/** A dependent in one of these has not started on its inputs yet: it is still waiting on them. */
private val WAITING = setOf(TaskRunStatus.Created, TaskRunStatus.Blocked, TaskRunStatus.Ready)

/**
 * The creatures waiting on [taskId]'s output, named by role (else by task), in workflow order and
 * without repeats: the tasks that depend on it and have not started yet. A dependent with no task
 * run yet counts as waiting. Empty when nothing waits on it.
 */
internal fun creaturesWaitingOn(
    taskId: TaskDefinitionId,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
): List<String> {
    val rolesById = roles.associateBy(RoleDefinition::id)
    return definition.tasks
        .filter { taskId in it.dependsOn }
        .filter { dependent -> run.taskRuns[dependent.id]?.status.let { it == null || it in WAITING } }
        .map { dependent ->
            val roleId = run.taskRuns[dependent.id]?.assignedRoleId ?: dependent.roleId
            roleId?.let { rolesById[it]?.name } ?: dependent.name
        }
        .distinct()
}
