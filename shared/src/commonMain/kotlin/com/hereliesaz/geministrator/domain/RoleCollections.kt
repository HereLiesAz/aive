package com.hereliesaz.geministrator.domain

const val ROLE_COLLECTION_MARKER_ID: String = "__aive-role-collection__"

/** Whether [id] is the internal role-collection marker. */
fun isRoleCollectionMarkerId(id: String): Boolean = id == ROLE_COLLECTION_MARKER_ID

/**
 * Built-in roster exposed by the app, including governance roles defined outside [BuiltInRoles] and the 29 new starting
 * role templates ([RoleStartingTemplates]), so Breeds, the tray and Add creature list them with no screen of their own.
 */
val aiveBuiltInRoles: List<RoleDefinition>
    get() = BuiltInRoles.all + HallMonitorRole.definition + RoleStartingTemplates.roles

fun resolveRoleCollection(stored: List<RoleDefinition>): List<RoleDefinition> {
    val marker = stored.firstOrNull { isRoleCollectionMarkerId(it.id.value) }
    val persisted = stored.filterNot { isRoleCollectionMarkerId(it.id.value) }
    val overrides = persisted.associateBy(RoleDefinition::id)
    val builtInIds = aiveBuiltInRoles.mapTo(linkedSetOf(), RoleDefinition::id)

    val resolvedById = linkedMapOf<RoleDefinitionId, RoleDefinition>()
    aiveBuiltInRoles.forEach { builtIn ->
        val resolved = overrides[builtIn.id] ?: builtIn
        // The Antagonist is a required agent alongside the queen, so it is always part of the active roster: a keeper's
        // edits to it stand, but a stored copy marked disabled does not take it out.
        resolvedById[builtIn.id] = if (builtIn.id == BuiltInRoles.Antagonist.id) resolved.copy(enabled = true) else resolved
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
        .filterNot { isRoleCollectionMarkerId(it.id.value) }
        .distinctBy(RoleDefinition::id)
        .map { it.copy(enabled = true) }
    val activeIds = active.mapTo(mutableSetOf(), RoleDefinition::id)
    val historical = (existingStored + aiveBuiltInRoles)
        .filterNot { isRoleCollectionMarkerId(it.id.value) }
        .distinctBy(RoleDefinition::id)
        .filter { it.id !in activeIds }
        .map { it.copy(enabled = false) }

    return listOf(roleCollectionMarker(active.map(RoleDefinition::id))) + active + historical
}

fun defaultRoleCollectionEntries(existingStored: List<RoleDefinition>): List<RoleDefinition> {
    val defaults = aiveBuiltInRoles.map { it.copy(enabled = true, preferredProviderId = null) }
    val builtInIds = defaults.mapTo(mutableSetOf(), RoleDefinition::id)
    val historicalCustom = existingStored
        .filterNot { isRoleCollectionMarkerId(it.id.value) || it.id in builtInIds }
        .distinctBy(RoleDefinition::id)
        .map { it.copy(enabled = false) }
    return listOf(roleCollectionMarker(defaults.map(RoleDefinition::id))) + defaults + historicalCustom
}

fun activeRoles(roles: Collection<RoleDefinition>): List<RoleDefinition> =
    roles.filter { it.enabled && !isRoleCollectionMarkerId(it.id.value) }

private fun roleCollectionMarker(order: List<RoleDefinitionId>): RoleDefinition = RoleDefinition(
    id = RoleDefinitionId(ROLE_COLLECTION_MARKER_ID),
    name = "Aive role collection",
    description = "Internal role collection ordering record.",
    instructions = order.joinToString("\n") { it.value },
    enabled = false,
)


val STARTER_REQUIRED_AUTHORITIES: Set<RoleAuthority> = setOf(
    RoleAuthority.Implement,
    RoleAuthority.AuthorTests,
    RoleAuthority.Verify,
    RoleAuthority.ReviewCode,
    RoleAuthority.ApproveRelease,
)

fun requireStarterRoleCoverage(roles: Collection<RoleDefinition>) {
    val active = activeRoles(roles)
    val missing = STARTER_REQUIRED_AUTHORITIES.filter { authority ->
        active.none { authority in it.authorities }
    }
    require(missing.isEmpty()) {
        "The active swarm is missing required starter-workflow authority: " +
            missing.joinToString { it.name }
    }
}
