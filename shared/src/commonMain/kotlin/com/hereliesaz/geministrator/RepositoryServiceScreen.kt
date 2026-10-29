package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.hereliesaz.geministrator.resources.keeper_local_git_note
import com.hereliesaz.geministrator.resources.keeper_repositories
import com.hereliesaz.geministrator.resources.repositories_credential_configured
import com.hereliesaz.geministrator.resources.repositories_disconnect
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
import com.hereliesaz.geministrator.resources.repositories_entire_off
import com.hereliesaz.geministrator.resources.repositories_entire_on
import com.hereliesaz.geministrator.resources.repositories_entire_record_runs
import com.hereliesaz.geministrator.resources.repositories_entire_reference_hint
import com.hereliesaz.geministrator.resources.repositories_entire_repository
import com.hereliesaz.geministrator.resources.repositories_entire_signed_in_as
import com.hereliesaz.geministrator.resources.repositories_get_access_token
import com.hereliesaz.geministrator.resources.repositories_reconfigure
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Repository hosts in the Keeper's room's Connections (docs/Keepers-Room.md): where the swarm's
 * work lands. One row per host; Entire when the platform provides it; Local Git needs no row of
 * its own, since a folder is linked from the launch bar.
 */
@Composable
internal fun RepositoryConnections(
    connectedServiceIds: Set<String>,
    onConfigureService: (String) -> Unit,
    onDisconnectService: (String) -> Unit,
    compact: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    var disconnectServiceId by remember { mutableStateOf<String?>(null) }
    ProviderSectionLabel(stringResource(Res.string.keeper_repositories))
    RepositoryServiceCatalog.entries.forEach { entry ->
        val connected = entry.id in connectedServiceIds
        ConnectionRow(
            name = entry.displayName,
            detail = if (connected) stringResource(Res.string.repositories_credential_configured) else entry.description,
            status = if (connected) stringResource(Res.string.common_connected) else null,
            connected = connected,
            compact = compact,
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
            } else {
                AzphaltPill(
                    label = stringResource(Res.string.repositories_get_access_token),
                    seed = "repository-service-token-${entry.id}",
                    onClick = { uriHandler.openUri(entry.credentialUrl) },
                )
            }
        }
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

    LocalEntire.current?.let { entire -> EntireConnection(entire, compact) }

    Text(
        stringResource(Res.string.keeper_local_git_note),
        style = AzphaltType.body,
        color = azphaltMutedOnSurface(),
    )
}

/**
 * Entire as a repository host (docs/Entire.md): whether its command is installed, who is signed
 * in, whether runs are recorded as checkpoints, and cloning an Entire repository.
 */
@Composable
private fun EntireConnection(entire: EntireIntegration, compact: Boolean) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<EntireStatus?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reference by remember { mutableStateOf("") }
    var cloning by remember { mutableStateOf(false) }
    var cloned by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entire) { status = runCatching { entire.status() }.getOrDefault(EntireStatus()) }
    val current = status
    val loggingInText = stringResource(Res.string.repositories_entire_logging_in)
    val cloningText = stringResource(Res.string.repositories_entire_cloning)

    ConnectionRow(
        name = stringResource(Res.string.repositories_entire),
        detail = buildString {
            append(
                when {
                    current == null -> stringResource(Res.string.repositories_entire_checking)
                    !current.installed -> stringResource(Res.string.repositories_entire_not_installed)
                    current.account != null -> stringResource(Res.string.repositories_entire_signed_in_as, current.account)
                    else -> stringResource(Res.string.repositories_entire_keeps_every_agent_session)
                },
            )
            cloned?.let { append("\n").append(stringResource(Res.string.repositories_entire_cloned_to, it)) }
            (busy ?: note)?.let { append("\n").append(it) }
        },
        status = if (current?.account != null) stringResource(Res.string.common_connected) else null,
        connected = current?.account != null,
        compact = compact,
    ) {
        when {
            current == null -> Unit
            !current.installed -> AzphaltPill(
                label = stringResource(Res.string.repositories_entire_install),
                seed = "repository-service-entire-install",
                onClick = { uriHandler.openUri(ENTIRE_INSTALL_URL) },
            )
            else -> {
                if (current.account == null) {
                    AzphaltPill(
                        label = stringResource(Res.string.repositories_entire_log_in),
                        seed = "repository-service-entire-login",
                        onClick = {
                            if (busy != null) return@AzphaltPill
                            busy = loggingInText
                            scope.launch {
                                note = entire.logIn()
                                status = runCatching { entire.status() }.getOrDefault(current)
                                busy = null
                            }
                        },
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
                )
                if (current.account != null) {
                    AzphaltPill(
                        label = stringResource(Res.string.repositories_entire_clone),
                        seed = "repository-service-entire-clone-open",
                        selected = cloning,
                        onClick = { cloning = !cloning },
                    )
                }
            }
        }
    }
    if (cloning && current?.account != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AzphaltTextField(
                value = reference,
                onValueChange = { reference = it },
                label = stringResource(Res.string.repositories_entire_repository),
                placeholder = stringResource(Res.string.repositories_entire_reference_hint),
                errorMessage = stringResource(Res.string.repositories_entire_reference_hint)
                    .takeIf { reference.isNotBlank() && !isEntireRepositoryReference(reference) },
                modifier = Modifier.weight(1f),
            )
            AzphaltPill(
                label = stringResource(Res.string.repositories_entire_clone),
                seed = "repository-service-entire-clone",
                tone = PillTone.Primary,
                onClick = {
                    if (busy != null || !isEntireRepositoryReference(reference)) return@AzphaltPill
                    busy = cloningText
                    note = null
                    scope.launch {
                        runCatching { entire.clone(reference) }
                            .onSuccess { path -> if (path != null) cloned = path }
                            .onFailure { note = it.message }
                        busy = null
                    }
                },
            )
        }
    }
}
