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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_connect
import com.hereliesaz.geministrator.resources.common_connected
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.confirm_disconnect_action
import com.hereliesaz.geministrator.resources.confirm_disconnect_repository_body
import com.hereliesaz.geministrator.resources.confirm_disconnect_title
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.repositories_entire
import com.hereliesaz.geministrator.resources.repositories_entire_checking
import com.hereliesaz.geministrator.resources.repositories_entire_clone
import com.hereliesaz.geministrator.resources.repositories_entire_cloned_to
import com.hereliesaz.geministrator.resources.repositories_entire_cloning
import com.hereliesaz.geministrator.resources.repositories_entire_install
import com.hereliesaz.geministrator.resources.repositories_entire_keeps_every_agent_session
import com.hereliesaz.geministrator.resources.repositories_entire_log_in
import com.hereliesaz.geministrator.resources.repositories_entire_logging_in
import com.hereliesaz.geministrator.resources.repositories_entire_not_installed
import com.hereliesaz.geministrator.resources.repositories_entire_not_installed_cap
import com.hereliesaz.geministrator.resources.repositories_entire_not_signed_in
import com.hereliesaz.geministrator.resources.repositories_entire_off
import com.hereliesaz.geministrator.resources.repositories_entire_on
import com.hereliesaz.geministrator.resources.repositories_entire_record_runs
import com.hereliesaz.geministrator.resources.repositories_entire_reference_hint
import com.hereliesaz.geministrator.resources.repositories_entire_repository
import com.hereliesaz.geministrator.resources.repositories_entire_signed_in_as

@Composable
internal fun RepositoryServiceScreen(
    connectedServiceIds: Set<String>,
    onConfigureService: (String) -> Unit,
    onDisconnectService: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var disconnectServiceId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.repositories_repository_services), style = AzphaltType.hero, color = azphaltOnSurface())
        Text(
            stringResource(Res.string.repositories_connect_the_repository_hosts_aive_may),
            style = AzphaltType.body,
            color = azphaltOnSurface(),
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
                                    onClick = { disconnectServiceId = entry.id },
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

        disconnectServiceId?.let { id ->
            AzphaltAlertDialog(
                title = stringResource(
                    Res.string.confirm_disconnect_title,
                    RepositoryServiceCatalog.entries.firstOrNull { it.id == id }?.displayName ?: id,
                ),
                text = stringResource(Res.string.confirm_disconnect_repository_body),
                confirmLabel = stringResource(Res.string.confirm_disconnect_action),
                onConfirm = {
                    disconnectServiceId = null
                    onDisconnectService(id)
                },
                onDismissRequest = { disconnectServiceId = null },
                dismissLabel = stringResource(Res.string.confirm_cancel),
            )
        }

        LocalEntire.current?.let { entire -> EntireRecord(entire) }

        AzphaltRecord(
            seed = "repository-service-local-git",
            eyebrow = stringResource(Res.string.repositories_desktop_repository_service),
            title = stringResource(Res.string.repositories_local_git),
            body = stringResource(Res.string.repositories_local_working_trees_are_linked_per),
            endCap = stringResource(Res.string.repositories_no_token),
        )
    }
}

/**
 * Entire as a repository host (docs/Entire.md): whether its command is installed, who is signed
 * in, whether runs are recorded as checkpoints, and cloning an Entire repository.
 */
@Composable
private fun EntireRecord(entire: EntireIntegration) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<EntireStatus?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reference by remember { mutableStateOf("") }
    LaunchedEffect(entire) { status = runCatching { entire.status() }.getOrDefault(EntireStatus()) }
    val current = status
    val loggingIn = stringResource(Res.string.repositories_entire_logging_in)
    val cloning = stringResource(Res.string.repositories_entire_cloning)
    var cloned by remember { mutableStateOf<String?>(null) }

    AzphaltRecord(
        seed = "repository-service-entire",
        eyebrow = stringResource(Res.string.repositories_repository_service),
        title = stringResource(Res.string.repositories_entire),
        body = buildString {
            append(stringResource(Res.string.repositories_entire_keeps_every_agent_session))
            append("\n")
            append(
                when {
                    current == null -> stringResource(Res.string.repositories_entire_checking)
                    !current.installed -> stringResource(Res.string.repositories_entire_not_installed)
                    current.account != null -> stringResource(Res.string.repositories_entire_signed_in_as, current.account)
                    else -> stringResource(Res.string.repositories_entire_not_signed_in)
                },
            )
            cloned?.let { append("\n").append(stringResource(Res.string.repositories_entire_cloned_to, it)) }
            (busy ?: note)?.let { append("\n").append(it) }
        },
        endCap = when {
            current == null -> null
            !current.installed -> stringResource(Res.string.repositories_entire_not_installed_cap)
            current.account != null -> stringResource(Res.string.common_connected)
            else -> stringResource(Res.string.repositories_not_configured)
        },
        selected = current?.account != null,
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null && !current.installed) {
                    AzphaltPill(
                        label = stringResource(Res.string.repositories_entire_install),
                        seed = "repository-service-entire-install",
                        onClick = { uriHandler.openUri(ENTIRE_INSTALL_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (current?.installed == true) {
                    if (current.account == null) {
                        AzphaltPill(
                            label = stringResource(Res.string.repositories_entire_log_in),
                            seed = "repository-service-entire-login",
                            onClick = {
                                if (busy != null) return@AzphaltPill
                                busy = loggingIn
                                scope.launch {
                                    note = entire.logIn()
                                    status = runCatching { entire.status() }.getOrDefault(current)
                                    busy = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AzphaltPill(
                        label = stringResource(Res.string.repositories_entire_record_runs),
                        seed = "repository-service-entire-record",
                        selected = current.recordRuns,
                        stateDescription = stringResource(if (current.recordRuns) Res.string.repositories_entire_on else Res.string.repositories_entire_off),
                        role = androidx.compose.ui.semantics.Role.Switch,
                        onClick = {
                            entire.setRecordRuns(!current.recordRuns)
                            status = current.copy(recordRuns = !current.recordRuns)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (current.account != null) {
                        AzphaltTextField(
                            value = reference,
                            onValueChange = { reference = it },
                            label = stringResource(Res.string.repositories_entire_repository),
                            placeholder = stringResource(Res.string.repositories_entire_reference_hint),
                            errorMessage = stringResource(Res.string.repositories_entire_reference_hint)
                                .takeIf { reference.isNotBlank() && !isEntireRepositoryReference(reference) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AzphaltPill(
                            label = stringResource(Res.string.repositories_entire_clone),
                            seed = "repository-service-entire-clone",
                            onClick = {
                                if (busy != null || !isEntireRepositoryReference(reference)) return@AzphaltPill
                                busy = cloning
                                note = null
                                scope.launch {
                                    runCatching { entire.clone(reference) }
                                        .onSuccess { path -> if (path != null) cloned = path }
                                        .onFailure { note = it.message }
                                    busy = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    )
}
