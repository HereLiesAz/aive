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
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_cancel
import com.hereliesaz.geministrator.resources.provider_credential_ai_provider
import com.hereliesaz.geministrator.resources.provider_credential_connect
import com.hereliesaz.geministrator.resources.provider_credential_credential
import com.hereliesaz.geministrator.resources.provider_credential_credential_required
import com.hereliesaz.geministrator.resources.provider_credential_get
import com.hereliesaz.geministrator.resources.provider_credential_key_optional
import com.hereliesaz.geministrator.resources.provider_credential_leave_blank_to_use_the_free
import com.hereliesaz.geministrator.resources.provider_credential_save
import org.jetbrains.compose.resources.stringResource

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
                Text(
                    stringResource(Res.string.provider_credential_connect),
                    style = AzphaltType.hero,
                    color = Azphalt.currentGround.onPage,
                )
                Text(
                    entry.displayName.uppercase(),
                    style = AzphaltType.section,
                    color = Azphalt.currentGround.onPage,
                )

                AzphaltRecord(
                    seed = "credential-provider-${entry.id}",
                    eyebrow = stringResource(Res.string.provider_credential_ai_provider),
                    title = entry.displayName,
                    body = entry.description,
                    endCap = if (entry.keyOptional) stringResource(Res.string.provider_credential_key_optional) else stringResource(Res.string.provider_credential_credential_required),
                    well = {
                        AzphaltPill(
                            label = stringResource(Res.string.provider_credential_get, entry.credentialLabel),
                            seed = "credential-link-${entry.id}",
                            onClick = { uriHandler.openUri(entry.apiKeyUrl) },
                        )
                    },
                )

                Text(
                    stringResource(Res.string.provider_credential_credential),
                    style = AzphaltType.eyebrow,
                    color = Azphalt.currentGround.onPage,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        errorMessage = null
                    },
                    label = { Text(entry.credentialLabel) },
                    placeholder = if (entry.keyOptional) {
                        { Text(stringResource(Res.string.provider_credential_leave_blank_to_use_the_free)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { message ->
                        { Text(message) }
                    },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AzphaltPill(
                        label = stringResource(Res.string.provider_credential_save),
                        seed = "credential-save-${entry.id}",
                        selected = apiKey.isNotBlank() || entry.keyOptional,
                        onClick = {
                            val clean = apiKey.trim()
                            if (clean.isEmpty() && entry.keyOptional) {
                                onSave(ProviderCatalog.ANONYMOUS_CREDENTIAL)
                            } else if (clean.isEmpty()) {
                                errorMessage = "${entry.credentialLabel} is required"
                            } else {
                                onSave(clean)
                            }
                        },
                    )
                    AzphaltPill(
                        label = stringResource(Res.string.common_cancel),
                        seed = "credential-cancel-${entry.id}",
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}
