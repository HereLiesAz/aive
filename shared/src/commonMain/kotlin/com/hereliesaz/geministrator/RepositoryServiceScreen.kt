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
        Text("REPOSITORY SERVICES", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            "Connect the repository hosts Haive may operate while workflows run. Project repository links stay separate from AI-provider credentials and role routing.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        RepositoryServiceCatalog.entries.forEach { entry ->
            val connected = entry.id in connectedServiceIds
            AzphaltRecord(
                seed = "repository-service-${entry.id}",
                eyebrow = "Repository service",
                title = entry.displayName,
                body = buildString {
                    append(entry.description)
                    append("\n")
                    append(if (connected) "Credential configured" else "No credential configured")
                },
                endCap = if (connected) "Connected" else "Not configured",
                selected = connected,
                well = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AzphaltPill(
                                label = if (connected) "Reconfigure" else "Connect",
                                seed = "repository-service-configure-${entry.id}",
                                onClick = { onConfigureService(entry.id) },
                            )
                            if (connected) {
                                AzphaltPill(
                                    label = "Disconnect",
                                    seed = "repository-service-disconnect-${entry.id}",
                                    onClick = { onDisconnectService(entry.id) },
                                )
                            }
                        }
                        AzphaltPill(
                            label = "Get access token",
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
            eyebrow = "Desktop repository service",
            title = "Local Git",
            body = "Local working trees are linked per project from the Overview screen. Desktop validates the selected folder as a Git repository and executes bounded Git operations directly against that working tree.",
            endCap = "No token",
        )
    }
}
