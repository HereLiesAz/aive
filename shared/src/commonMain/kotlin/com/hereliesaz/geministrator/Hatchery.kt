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
 * child is made from its parents' configs, with bounded instruction-locus mutation at birth.
 */
internal fun hatchFromRun(
    run: WorkflowRun, roles: Collection<RoleDefinition>, echo: Map<RoleDefinitionId, Int> = emptyMap(),
): RoleDefinition? {
    if (run.status != WorkflowRunStatus.Completed) return null
    val rolesById = (run.roleSnapshot.ifEmpty { roles.toList() }).associateBy(RoleDefinition::id)
    // Contributors, most completed tasks first; ties by id so the same run always hatches the same child.
    val contributors = run.taskRuns.values
        .filter { it.status == TaskRunStatus.Completed && it.byAgent }
        .mapNotNull { it.assignedRoleId }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<RoleDefinitionId, Int>> { it.value }.thenBy { it.key.value })
        .mapNotNull { rolesById[it.key] }
        // Programmatic roles (a script or an action) only connect; they are never a parent.
        .filter { it.executionSource == com.hereliesaz.geministrator.domain.RoleExecutionSource.Agent }
    val a = contributors.firstOrNull() ?: return null
    val childId = RoleDefinitionId("hatch-${run.id.value}")
    val b = contributors.getOrNull(1)
        ?: return mutateHatchedInstructions(run, a.copy(
            id = childId,
            name = "${a.name} · clone",
            enabled = false,
            parents = listOf(a.id),
            hatchedFromRunId = run.id.value,
        ), echo)
    return mutateHatchedInstructions(run, a.copy(
        id = childId,
        name = "${a.name} × ${b.name}",
        instructions = listOf(a.instructions, b.instructions).filter(String::isNotBlank).joinToString("\n\n"),
        preferredProviderId = b.preferredProviderId ?: a.preferredProviderId,
        capabilitiesRequired = a.capabilitiesRequired + b.capabilitiesRequired,
        surfaces = (a.surfaces + b.surfaces).distinctBy { it.alias },
        enabled = false,
        parents = listOf(a.id, b.id),
        hatchedFromRunId = run.id.value,
    ), echo)
}

/**
 * First functional mutation locus: change how the child approaches work, never what it may do.
 * The documented 2% base rises to 8% between Echo 30 and 60, capped there until higher rates
 * have a defined policy. Unknown parent balances supply no multiplier. The run-ID draw makes
 * retrying a hatch reproducible. Only the new disabled configuration is changed.
 */
private fun mutateHatchedInstructions(
    run: WorkflowRun, child: RoleDefinition, echo: Map<RoleDefinitionId, Int>,
): RoleDefinition {
    val highest = child.parents.mapNotNull(echo::get).maxOrNull() ?: 0
    val chance = 200 + (highest.coerceIn(3_000, 6_000) - 3_000) * 600 / 3_000
    val draw = ("${run.id.value}:instruction-mutation-v1").hashCode().toUInt() % 10_000u
    if (draw >= chance.toUInt()) return child
    val variants = listOf(
        "Within the assigned scope, compare plausible explanations against the available evidence before choosing an approach.",
        "Within the assigned scope, prefer a small falsifiable check of a material assumption before committing to an approach.",
        "When otherwise comparable approaches are available within the assigned scope, favor reversible choices and identify material trade-offs.",
    ).filterNot { it in child.instructions }
    if (variants.isEmpty()) return child
    val choice = (("${run.id.value}:instruction-variant-v1").hashCode().toUInt() % variants.size.toUInt()).toInt()
    return child.copy(
        name = if (child.parents.size == 1) child.name.removeSuffix(" · clone") + " · variant" else child.name,
        instructions = listOf(child.instructions, variants[choice]).filter(String::isNotBlank).joinToString("\n\n"),
    )
}
