package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.creature_provider_auto_note
import com.hereliesaz.geministrator.resources.creature_provider_badge_auto
import com.hereliesaz.geministrator.resources.creature_provider_badge_pinned
import com.hereliesaz.geministrator.resources.creature_provider_label
import com.hereliesaz.geministrator.resources.creature_provider_pinned_note
import com.hereliesaz.geministrator.resources.creature_provider_pinned_unconnected
import com.hereliesaz.geministrator.resources.launch_error_check_connections
import com.hereliesaz.geministrator.resources.creature_provider_role_scope
import org.jetbrains.compose.resources.stringResource

/**
 * A creature's provider (the panel's Work tab): Auto, or one connected provider. It reads and writes
 * the creature's role `preferredProviderId` through [onSaveRoleCollection], the path the role editors
 * save by, so nothing about approval or the task changes. [onSaveRoleCollection] null shows the
 * routing without changing it.
 */
@Composable
internal fun CreatureProviderRow(
    role: RoleDefinition,
    task: TaskDefinition,
    roles: List<RoleDefinition>,
    connectedProviderIds: Set<String>,
    onSaveRoleCollection: ((List<RoleDefinition>) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Opens Connections: a pinned provider that is not connected links there. Null leaves the warning without it. */
    onOpenConnections: (() -> Unit)? = null,
) {
    val routing = creatureProviderRouting(role, task) ?: return
    val pinnedId = (routing as? CreatureProviderRouting.Pinned)?.providerId
    // A task that requires a provider by id is pinned by the task, which this row does not edit.
    val pinnedByTask = providerPinnedByTask(pinnedId, role)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.creature_provider_label), style = AzphaltType.eyebrow, color = azphaltMutedOnSurface())
        if (!pinnedByTask && onSaveRoleCollection != null) {
            ProviderChoiceRow(
                selectedProviderId = pinnedId,
                connectedProviderIds = connectedProviderIds,
                seedPrefix = "creature-provider-${task.id.value}",
                pinnedLabel = pinnedId?.let { pinnedProviderName(it, role) },
                onSelected = { selected -> onSaveRoleCollection(withRoleProvider(roles, role.id, selected)) },
            )
        }
        Text(
            text = if (pinnedId == null) {
                stringResource(Res.string.creature_provider_auto_note)
            } else {
                stringResource(Res.string.creature_provider_pinned_note, pinnedProviderName(pinnedId, role))
            },
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
        // A pin to a provider that is not linked fails at once; say so where it can be fixed.
        if (pinnedId != null && pinnedProviderNeedsConnection(pinnedId, connectedProviderIds)) {
            Text(
                stringResource(Res.string.creature_provider_pinned_unconnected, pinnedProviderName(pinnedId, role)),
                style = AzphaltType.body,
                color = azphaltOnSurface(),
            )
            onOpenConnections?.let { open ->
                AzphaltPill(
                    label = stringResource(Res.string.launch_error_check_connections),
                    seed = "creature-provider-connect-${task.id.value}",
                    onClick = open,
                )
            }
        }
        Text(stringResource(Res.string.creature_provider_role_scope, role.name), style = AzphaltType.body, color = azphaltMutedOnSurface())
    }
}

/** The badge a creature wears in the terrarium: "Pinned: Claude" or "Auto". Null for a non-agent. */
@Composable
internal fun creatureProviderBadgeText(routing: CreatureProviderRouting, pinnedName: String?): String = when (routing) {
    CreatureProviderRouting.Auto -> stringResource(Res.string.creature_provider_badge_auto)
    is CreatureProviderRouting.Pinned ->
        stringResource(Res.string.creature_provider_badge_pinned, pinnedName ?: providerDisplayName(routing.providerId))
}
