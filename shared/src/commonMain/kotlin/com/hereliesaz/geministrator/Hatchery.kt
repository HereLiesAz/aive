package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Swarm OS step 6 (docs/Swarm-OS.md, docs/Node-Creature-Genome.md "Birth"): a run that clears
 * hatches one new config from the agents that did its work. With one contributor it is a clone;
 * with more, it recombines the two that did the most. The child is born unadopted (disabled), with
 * its parents and the run recorded; the person adopts it from the lineage. Nothing is invented: the
 * child is made only of its parents' configs.
 */
internal fun hatchFromRun(run: WorkflowRun, roles: Collection<RoleDefinition>): RoleDefinition? {
    if (run.status != WorkflowRunStatus.Completed) return null
    val rolesById = roles.associateBy(RoleDefinition::id)
    // Contributors, most completed tasks first; ties by id so the same run always hatches the same child.
    val contributors = run.taskRuns.values
        .filter { it.status == TaskRunStatus.Completed }
        .mapNotNull { it.assignedRoleId }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<RoleDefinitionId, Int>> { it.value }.thenBy { it.key.value })
        .mapNotNull { rolesById[it.key] }
    val a = contributors.firstOrNull() ?: return null
    val childId = RoleDefinitionId("hatch-${run.id.value}")
    val b = contributors.getOrNull(1)
        ?: return a.copy(
            id = childId,
            name = "${a.name} · clone",
            enabled = false,
            parents = listOf(a.id),
            hatchedFromRunId = run.id.value,
        )
    return a.copy(
        id = childId,
        name = "${a.name} × ${b.name}",
        instructions = listOf(a.instructions, b.instructions).filter(String::isNotBlank).joinToString("\n\n"),
        preferredProviderId = b.preferredProviderId ?: a.preferredProviderId,
        capabilitiesRequired = a.capabilitiesRequired + b.capabilitiesRequired,
        surfaces = (a.surfaces + b.surfaces).distinctBy { it.alias },
        enabled = false,
        parents = listOf(a.id, b.id),
        hatchedFromRunId = run.id.value,
    )
}
