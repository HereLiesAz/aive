package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun RepositoryCredentialSetup(
    serviceId: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val entry = RepositoryServiceCatalog.entry(serviceId)
        ?: error("Unknown repository service $serviceId")
    val uriHandler = LocalUriHandler.current
    var credential by remember(serviceId) { mutableStateOf("") }
    var errorMessage by remember(serviceId) { mutableStateOf<String?>(null) }

    MaterialTheme(colorScheme = GeministratorColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Azphalt.currentGround.page,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("CONNECT", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
                Text(entry.displayName.uppercase(), style = AzphaltType.section, color = Azphalt.currentGround.onPage)

                AzphaltRecord(
                    seed = "repository-credential-${entry.id}",
                    eyebrow = "Repository service",
                    title = entry.displayName,
                    body = entry.description,
                    endCap = "Credential required",
                    well = {
                        AzphaltPill(
                            label = "Get ${entry.credentialLabel}",
                            seed = "repository-credential-link-${entry.id}",
                            onClick = { uriHandler.openUri(entry.credentialUrl) },
                        )
                    },
                )

                Text("CREDENTIAL", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                OutlinedTextField(
                    value = credential,
                    onValueChange = {
                        credential = it
                        errorMessage = null
                    },
                    label = { Text(entry.credentialLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { message -> { Text(message) } },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AzphaltPill(
                        label = "Save",
                        seed = "repository-credential-save-${entry.id}",
                        selected = credential.isNotBlank(),
                        onClick = {
                            val clean = credential.trim()
                            if (clean.isEmpty()) {
                                errorMessage = "${entry.credentialLabel} is required"
                            } else {
                                onSave(clean)
                            }
                        },
                    )
                    AzphaltPill(
                        label = "Cancel",
                        seed = "repository-credential-cancel-${entry.id}",
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}
