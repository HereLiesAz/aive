package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.a2a.A2aAgentConnection
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskDefinition

/**
 * Which provider answers for a creature, as the hive shows it. A creature is Auto unless its role
 * names a provider (`preferredProviderId`) or its task requires one by id. A pinned creature never
 * switches providers silently; an Auto creature fails over to another connected provider.
 *
 * This is routing only. Approval gates (`ApprovalPolicy`, HumanApproval before mutating repository
 * operations) are decided on the task and never read from here.
 */
internal sealed interface CreatureProviderRouting {
    data object Auto : CreatureProviderRouting

    data class Pinned(val providerId: String) : CreatureProviderRouting
}

/** The one rule for "the keeper left the provider to the app"; the engine's failover uses it too. */
internal fun isProviderAuto(role: RoleDefinition, task: TaskDefinition): Boolean =
    role.preferredProviderId == null && task.providerConstraints !is ProviderConstraints.RequireProvider

/**
 * How [task] is routed when [role] runs it. Null for a creature that is not an agent (a script, a
 * test run, a person's approval): it has no provider to choose.
 */
internal fun creatureProviderRouting(role: RoleDefinition?, task: TaskDefinition): CreatureProviderRouting? {
    if (role == null || role.executionSource !is RoleExecutionSource.Agent) return null
    val required = (task.providerConstraints as? ProviderConstraints.RequireProvider)?.providerId?.value
    val pinned = role.preferredProviderId?.value ?: required
    return if (pinned == null) CreatureProviderRouting.Auto else CreatureProviderRouting.Pinned(pinned)
}

/**
 * [roles] with the provider of [roleId] set to [providerId], or left to the app when null. Only that
 * role's `preferredProviderId` changes; the saved list keeps its order and every other field.
 */
internal fun withRoleProvider(
    roles: List<RoleDefinition>,
    roleId: RoleDefinitionId,
    providerId: String?,
): List<RoleDefinition> = roles.map { role ->
    if (role.id == roleId) role.copy(preferredProviderId = providerId?.let(::AgentProviderId)) else role
}

/**
 * The name a pin is shown under: the catalog's name, a drifter's own name when the pin is an A2A
 * agent, else the id itself.
 */
internal fun pinnedProviderName(providerId: String, role: RoleDefinition?): String = when {
    ProviderCatalog.entry(providerId) != null -> providerDisplayName(providerId)
    providerId.startsWith(A2aAgentConnection.PROVIDER_ID_PREFIX) && role != null &&
        role.preferredProviderId?.value == providerId -> role.name
    else -> providerId
}

/** What a creature's badge says: its routing and, when pinned, the name the pin goes by. */
internal data class CreatureProviderBadge(val routing: CreatureProviderRouting, val pinnedName: String?)

/** The badges of the agent creatures in [tasks], by task id; a creature that is not an agent has none. */
internal fun creatureProviderBadges(
    tasks: List<TaskDefinition>,
    roleFor: (TaskDefinition) -> RoleDefinition?,
): Map<String, CreatureProviderBadge> = buildMap {
    tasks.forEach { task ->
        val role = roleFor(task)
        val routing = creatureProviderRouting(role, task) ?: return@forEach
        val name = (routing as? CreatureProviderRouting.Pinned)?.let { pinnedProviderName(it.providerId, role) }
        put(task.id.value, CreatureProviderBadge(routing, name))
    }
}

internal val LocalProviderBadges = androidx.compose.runtime.compositionLocalOf<Map<String, CreatureProviderBadge>> { emptyMap() }
