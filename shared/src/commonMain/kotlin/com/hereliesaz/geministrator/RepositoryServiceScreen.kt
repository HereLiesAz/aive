package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_connect
import com.hereliesaz.geministrator.resources.common_connected
import com.hereliesaz.geministrator.resources.repositories_connect_the_repository_hosts_aive_may
import com.hereliesaz.geministrator.resources.repositories_credential_configured
import com.hereliesaz.geministrator.resources.repositories_desktop_repository_service
import com.hereliesaz.geministrator.resources.repositories_disconnect
import com.hereliesaz.geministrator.resources.repositories_get_access_token
import com.hereliesaz.geministrator.resources.repositories_local_git
import com.hereliesaz.geministrator.resources.repositories_local_working_trees_are_linked_per
import com.hereliesaz.geministrator.resources.repositories_no_credential_configured
import com.hereliesaz.geministrator.resources.repositories_no_token
import com.hereliesaz.geministrator.resources.repositories_not_configured
import com.hereliesaz.geministrator.resources.repositories_reconfigure
import com.hereliesaz.geministrator.resources.repositories_repository_service
import com.hereliesaz.geministrator.resources.repositories_repository_services
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RepositoryServiceScreen(
    connectedServiceIds: Set<String>,
    onConfigureService: (String) -> Unit,
    onDisconnectService: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.repositories_repository_services), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            stringResource(Res.string.repositories_connect_the_repository_hosts_aive_may),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        RepositoryServiceCatalog.entries.forEach { entry ->
            val connected = entry.id in connectedServiceIds
            AzphaltRecord(
                seed = "repository-service-${entry.id}",
                eyebrow = stringResource(Res.string.repositories_repository_service),
                title = entry.displayName,
                body = buildString {
                    append(entry.description)
                    append("\n")
                    append(if (connected) stringResource(Res.string.repositories_credential_configured) else stringResource(Res.string.repositories_no_credential_configured))
                },
                endCap = if (connected) stringResource(Res.string.common_connected) else stringResource(Res.string.repositories_not_configured),
                selected = connected,
                well = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AzphaltPill(
                                label = if (connected) stringResource(Res.string.repositories_reconfigure) else stringResource(Res.string.common_connect),
                                seed = "repository-service-configure-${entry.id}",
                                onClick = { onConfigureService(entry.id) },
                            )
                            if (connected) {
                                AzphaltPill(
                                    label = stringResource(Res.string.repositories_disconnect),
                                    seed = "repository-service-disconnect-${entry.id}",
                                    onClick = { onDisconnectService(entry.id) },
                                )
                            }
                        }
                        AzphaltPill(
                            label = stringResource(Res.string.repositories_get_access_token),
                            seed = "repository-service-token-${entry.id}",
                            onClick = { uriHandler.openUri(entry.credentialUrl) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }

        AzphaltRecord(
            seed = "repository-service-local-git",
            eyebrow = stringResource(Res.string.repositories_desktop_repository_service),
            title = stringResource(Res.string.repositories_local_git),
            body = stringResource(Res.string.repositories_local_working_trees_are_linked_per),
            endCap = stringResource(Res.string.repositories_no_token),
        )
    }
}
