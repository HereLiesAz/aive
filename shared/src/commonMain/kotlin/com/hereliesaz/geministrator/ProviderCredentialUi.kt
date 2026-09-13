package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
fun ProviderCredentialSetup(
    providerId: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val entry = ProviderCatalog.entry(providerId)
        ?: error("Unknown provider $providerId")
    val uriHandler = LocalUriHandler.current
    var apiKey by remember(providerId) { mutableStateOf("") }
    var errorMessage by remember(providerId) { mutableStateOf<String?>(null) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("CONNECT ${entry.displayName.uppercase()}", style = MaterialTheme.typography.headlineMedium)
                Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { uriHandler.openUri(entry.apiKeyUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("GET ${entry.credentialLabel.uppercase()}")
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        errorMessage = null
                    },
                    label = { Text(entry.credentialLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { message -> { Text(message) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val clean = apiKey.trim()
                            if (clean.isEmpty()) {
                                errorMessage = "${entry.credentialLabel} is required"
                            } else {
                                onSave(clean)
                            }
                        },
                        enabled = apiKey.isNotBlank(),
                    ) {
                        Text("SAVE")
                    }
                    OutlinedButton(onClick = onCancel) {
                        Text("CANCEL")
                    }
                }
            }
        }
    }
}

@Composable
fun InitialProviderSetup(
    configuredProviderIds: Set<String>,
    onConfigure: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("CONNECT AI PROVIDERS", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Connect one or more providers. You can assign different company roles to different providers later, so a single service does not have to carry the whole orchestration.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ProviderCatalog.entries.forEach { entry ->
                    val connected = entry.id in configuredProviderIds
                    Text(
                        "${entry.displayName} · ${if (connected) "Connected" else "Not configured"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(entry.description, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfigure(entry.id) }) {
                            Text(if (connected) "RECONFIGURE" else "CONNECT")
                        }
                        OutlinedButton(onClick = { uriHandler.openUri(entry.apiKeyUrl) }) {
                            Text("GET API KEY")
                        }
                    }
                }
                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (configuredProviderIds.isEmpty()) "CONTINUE WITHOUT A PROVIDER" else "CONTINUE")
                }
            }
        }
    }
}
