package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.swarm_auto
import org.jetbrains.compose.resources.stringResource

/**
 * The provider choice a role or a creature takes: Auto, or one connected provider. Shared by the
 * role editors and the hive's creature panel so the three never drift apart.
 *
 * [pinnedLabel] names a selection the catalog does not list (an A2A drifter): it still shows as
 * selected, so a creature pinned to it can be seen and set back to Auto.
 */
@Composable
internal fun ProviderChoiceRow(
    selectedProviderId: String?,
    connectedProviderIds: Set<String>,
    onSelected: (String?) -> Unit,
    seedPrefix: String = "provider-choice",
    pinnedLabel: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = stringResource(Res.string.swarm_auto),
            seed = "$seedPrefix-auto-${selectedProviderId.orEmpty()}",
            selected = selectedProviderId == null,
            onClick = { onSelected(null) },
        )
        val listed = ProviderCatalog.entries.filter { it.id in connectedProviderIds }
        listed.forEach { entry ->
            AzphaltPill(
                label = entry.displayName,
                seed = "$seedPrefix-${entry.id}-${selectedProviderId.orEmpty()}",
                selected = selectedProviderId == entry.id,
                onClick = { onSelected(entry.id) },
            )
        }
        if (selectedProviderId != null && listed.none { it.id == selectedProviderId }) {
            AzphaltPill(
                label = pinnedLabel ?: providerDisplayName(selectedProviderId),
                seed = "$seedPrefix-$selectedProviderId-$selectedProviderId",
                selected = true,
                onClick = { onSelected(selectedProviderId) },
            )
        }
    }
}
