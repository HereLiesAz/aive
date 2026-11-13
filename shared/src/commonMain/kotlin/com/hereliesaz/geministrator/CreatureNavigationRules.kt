package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition

/** What a link to a creature does. A link opens a creature and never closes it. */
internal enum class CreatureOpenAction {
    /** The creature's panel was set aside: bring it back. */
    ReopenPanel,

    /** Another creature (or none) is selected: select this one. */
    Select,

    /** It is already selected and showing: nothing to do. */
    KeepOpen,
}

internal fun creatureOpenAction(tappedTaskId: String, selectedTaskId: String?, setAsideTaskId: String?): CreatureOpenAction = when {
    reopensSetAsidePanel(tappedTaskId, setAsideTaskId) -> CreatureOpenAction.ReopenPanel
    tappedTaskId != selectedTaskId -> CreatureOpenAction.Select
    else -> CreatureOpenAction.KeepOpen
}

/** The hive Workflows opens on: the one named, else the one on screen (the resting hive names itself). Null when neither. */
internal fun hiveToOpenInWorkflows(namedDefinitionId: String?, liveDefinitionId: String?): String? =
    namedDefinitionId ?: liveDefinitionId

/** A pin to a provider that is not connected fails at once, so the row says so and links to Connections. */
internal fun pinnedProviderNeedsConnection(pinnedProviderId: String?, connectedProviderIds: Set<String>): Boolean =
    pinnedProviderId != null && pinnedProviderId !in connectedProviderIds

/** A task that requires a provider by id is pinned by the task; the role has none, and the row does not edit the task. */
internal fun providerPinnedByTask(pinnedProviderId: String?, role: RoleDefinition): Boolean =
    pinnedProviderId != null && role.preferredProviderId == null
