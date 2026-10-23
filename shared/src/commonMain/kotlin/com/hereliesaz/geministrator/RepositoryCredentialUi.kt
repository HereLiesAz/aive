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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_cancel
import com.hereliesaz.geministrator.resources.credential_show
import com.hereliesaz.geministrator.resources.credential_hide
import com.hereliesaz.geministrator.resources.credential_field_required
import com.hereliesaz.geministrator.resources.repository_credential_connect
import com.hereliesaz.geministrator.resources.repository_credential_credential
import com.hereliesaz.geministrator.resources.repository_credential_credential_required
import com.hereliesaz.geministrator.resources.repository_credential_get
import com.hereliesaz.geministrator.resources.repository_credential_repository_service
import com.hereliesaz.geministrator.resources.repository_credential_save
import org.jetbrains.compose.resources.stringResource

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
    val requiredMessage = stringResource(Res.string.credential_field_required, entry.credentialLabel)

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
                Text(stringResource(Res.string.repository_credential_connect), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
                Text(entry.displayName.uppercase(), style = AzphaltType.section, color = Azphalt.currentGround.onPage)

                AzphaltRecord(
                    seed = "repository-credential-${entry.id}",
                    eyebrow = stringResource(Res.string.repository_credential_repository_service),
                    title = entry.displayName,
                    body = entry.description,
                    endCap = stringResource(Res.string.repository_credential_credential_required),
                    well = {
                        AzphaltPill(
                            label = stringResource(Res.string.repository_credential_get, entry.credentialLabel),
                            seed = "repository-credential-link-${entry.id}",
                            onClick = { uriHandler.openUri(entry.credentialUrl) },
                        )
                    },
                )

                Text(stringResource(Res.string.repository_credential_credential), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                AzphaltTextField(
                    value = credential,
                    onValueChange = {
                        credential = it
                        errorMessage = null
                    },
                    label = entry.credentialLabel,
                    secret = true,
                    showLabel = stringResource(Res.string.credential_show),
                    hideLabel = stringResource(Res.string.credential_hide),
                    errorMessage = errorMessage,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AzphaltPill(
                        label = stringResource(Res.string.repository_credential_save),
                        seed = "repository-credential-save-${entry.id}",
                        selected = credential.isNotBlank(),
                        onClick = {
                            val clean = credential.trim()
                            if (clean.isEmpty()) {
                                errorMessage = requiredMessage
                            } else {
                                onSave(clean)
                            }
                        },
                    )
                    AzphaltPill(
                        label = stringResource(Res.string.common_cancel),
                        seed = "repository-credential-cancel-${entry.id}",
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}
