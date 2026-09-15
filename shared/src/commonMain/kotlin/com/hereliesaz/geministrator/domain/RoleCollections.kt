package com.hereliesaz.geministrator.domain

const val ROLE_COLLECTION_MARKER_ID: String = "__haive-role-collection__"

fun resolveRoleCollection(stored: List<RoleDefinition>): List<RoleDefinition> {
    val marker = stored.firstOrNull { it.id.value == ROLE_COLLECTION_MARKER_ID }
    val persisted = stored.filterNot { it.id.value == ROLE_COLLECTION_MARKER_ID }
    val overrides = persisted.associateBy(RoleDefinition::id)
    val builtInIds = BuiltInRoles.all.mapTo(linkedSetOf(), RoleDefinition::id)

    val resolvedById = linkedMapOf<RoleDefinitionId, RoleDefinition>()
    BuiltInRoles.all.forEach { builtIn ->
        resolvedById[builtIn.id] = overrides[builtIn.id] ?: builtIn
    }
    persisted.filter { it.id !in builtInIds }.forEach { custom ->
        resolvedById[custom.id] = custom
    }

    val orderedIds = marker
        ?.instructions
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.map(::RoleDefinitionId)
        ?.toList()
        .orEmpty()

    val active = buildList {
        val seen = mutableSetOf<RoleDefinitionId>()
        orderedIds.forEach { id ->
            resolvedById[id]?.takeIf(RoleDefinition::enabled)?.let { role ->
                if (seen.add(role.id)) add(role)
            }
        }
        resolvedById.values.forEach { role ->
            if (role.enabled && seen.add(role.id)) add(role)
        }
    }
    val inactive = resolvedById.values.filterNot(RoleDefinition::enabled)
    return active + inactive
}

fun roleCollectionEntries(
    activeRoles: List<RoleDefinition>,
    existingStored: List<RoleDefinition>,
): List<RoleDefinition> {
    val active = activeRoles
        .filterNot { it.id.value == ROLE_COLLECTION_MARKER_ID }
        .distinctBy(RoleDefinition::id)
        .map { it.copy(enabled = true) }
    val activeIds = active.mapTo(mutableSetOf(), RoleDefinition::id)
    val historical = (BuiltInRoles.all + existingStored)
        .filterNot { it.id.value == ROLE_COLLECTION_MARKER_ID }
        .distinctBy(RoleDefinition::id)
        .filter { it.id !in activeIds }
        .map { it.copy(enabled = false) }

    return listOf(roleCollectionMarker(active.map(RoleDefinition::id))) + active + historical
}

fun defaultRoleCollectionEntries(existingStored: List<RoleDefinition>): List<RoleDefinition> {
    val defaults = BuiltInRoles.all.map { it.copy(enabled = true, preferredProviderId = null) }
    val builtInIds = defaults.mapTo(mutableSetOf(), RoleDefinition::id)
    val historicalCustom = existingStored
        .filterNot { it.id.value == ROLE_COLLECTION_MARKER_ID || it.id in builtInIds }
        .distinctBy(RoleDefinition::id)
        .map { it.copy(enabled = false) }
    return listOf(roleCollectionMarker(defaults.map(RoleDefinition::id))) + defaults + historicalCustom
}

fun activeRoles(roles: Collection<RoleDefinition>): List<RoleDefinition> =
    roles.filter { it.enabled && it.id.value != ROLE_COLLECTION_MARKER_ID }

private fun roleCollectionMarker(order: List<RoleDefinitionId>): RoleDefinition = RoleDefinition(
    id = RoleDefinitionId(ROLE_COLLECTION_MARKER_ID),
    name = "Haive role collection",
    description = "Internal role collection ordering record.",
    instructions = order.joinToString("\n") { it.value },
    enabled = false,
)
