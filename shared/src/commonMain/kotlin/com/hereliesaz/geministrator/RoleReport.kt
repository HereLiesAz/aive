package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId

/**
 * What the queen, the nanny and the antagonist can read about a role (a neuron creature): what it is made of and the
 * numbers it has earned. The queen cannot plan well, nor the nanny improve a creature, without seeing both. Each part is
 * left out when it is not known: an unreported number is never printed as zero. Echo is a plain 0 to 100 count of how
 * many times the creature has heard itself; nothing here explains it or acts on it.
 */
internal fun roleReport(
    role: RoleDefinition, echoHundredths: Int?, trophicHundredths: Int?, traits: OperationalTraits?, synapseCapacity: Int?,
): String = buildString {
    append(role.name).append(" [").append(role.id.value).append(']')
    role.description.takeIf { it.isNotBlank() }?.let { append(": ").append(it.trim()) }
    val parts = listOfNotNull(
        role.authorities.takeIf { it.isNotEmpty() }?.let { "authorities " + it.map { a -> a.name }.sorted().joinToString(", ") },
        role.capabilitiesRequired.takeIf { it.isNotEmpty() }?.let { "needs " + it.map { c -> c.name }.sorted().joinToString(", ") },
        role.toolCeiling.takeIf { it.isNotEmpty() }?.let { "tools limited to " + it.sorted().joinToString(", ") },
        role.preferredProviderId?.let { "prefers source ${it.value}" },
        role.parents.takeIf { it.isNotEmpty() }?.let { "born of ${it.size} parent${if (it.size == 1) "" else "s"}" },
        echoHundredths?.let { "Echo ${it / 100}" },
        trophicHundredths?.let { "trophic reserve ${it / 100} (${trophicStateOf(it).name.lowercase()})" },
        traits?.tier?.let { "tier ${it.name}" },
        traits?.tolerance?.let { "tolerance ${it.name}" },
        traits?.resilience?.let { "resilience ${it.name}" },
        traits?.efficiency?.let { "signal ${it.name}" },
        synapseCapacity?.let { "synapse capacity $it" },
    )
    if (parts.isNotEmpty()) append(" | ").append(parts.joinToString("; "))
}

/** One report per role, from the numbers the settled runs gave each. A role no run has reported on shows only what it is made of. */
internal fun roleReports(
    roles: Collection<RoleDefinition>, echo: Map<RoleDefinitionId, Int>, trophic: Map<RoleDefinitionId, Int>,
    traits: Map<RoleDefinitionId, OperationalTraits>,
): Map<RoleDefinitionId, String> {
    val capacity = synapseCapacities(roles)
    return roles.associate { it.id to roleReport(it, echo[it.id], trophic[it.id], traits[it.id], capacity[it.id]) }
}
