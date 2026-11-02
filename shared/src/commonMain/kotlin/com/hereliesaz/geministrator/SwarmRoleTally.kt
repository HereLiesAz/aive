package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition

/** The Swarm's role filter: every role, the ones that make things, or the ones that review them. */
internal enum class SwarmRoleFilter { All, Producers, Reviewers }

/** A reviewer is a role whose kind is in [REVIEWER_KINDS]; every other role is a producer. */
internal fun SwarmRoleFilter.admits(role: RoleDefinition): Boolean = when (this) {
    SwarmRoleFilter.All -> true
    SwarmRoleFilter.Producers -> !isReviewerRoleName(role.name)
    SwarmRoleFilter.Reviewers -> isReviewerRoleName(role.name)
}

/** The roles a task is given to: its responsible role, and the role its agent executor runs as. */
private fun TaskDefinition.roleIds(): Set<RoleDefinitionId> = setOfNotNull(
    roleId,
    (executor as? TaskExecutor.RoleAgent)?.roleId,
)

/**
 * How many hives (saved workflow definitions) give at least one task to each role. A role in no hive
 * is absent from the map; a hive counts once however many of its tasks the role holds.
 */
internal fun roleHiveCounts(definitions: Collection<WorkflowDefinition>): Map<RoleDefinitionId, Int> =
    definitions
        .distinctBy { it.id }
        .flatMap { definition -> definition.tasks.flatMapTo(mutableSetOf()) { it.roleIds() } }
        .groupingBy { it }
        .eachCount()
