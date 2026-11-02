package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.mcp.CodeExecutionToolSource
import com.hereliesaz.geministrator.mcp.McpCommandLine
import com.hereliesaz.geministrator.mcp.McpConsentDecision
import com.hereliesaz.geministrator.mcp.McpHost
import com.hereliesaz.geministrator.mcp.PromptingMcpConsentGate
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_remove
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.credential_hide
import com.hereliesaz.geministrator.resources.credential_show
import com.hereliesaz.geministrator.resources.mcp_add
import com.hereliesaz.geministrator.resources.mcp_add_server
import com.hereliesaz.geministrator.resources.mcp_add_server_hint
import com.hereliesaz.geministrator.resources.mcp_add_server_hint_remote
import com.hereliesaz.geministrator.resources.mcp_always_allow
import com.hereliesaz.geministrator.resources.mcp_ask_each_run
import com.hereliesaz.geministrator.resources.mcp_by_address
import com.hereliesaz.geministrator.resources.mcp_by_command
import com.hereliesaz.geministrator.resources.mcp_check
import com.hereliesaz.geministrator.resources.mcp_check_failed
import com.hereliesaz.geministrator.resources.mcp_checking
import com.hereliesaz.geministrator.resources.mcp_consent_allow_always
import com.hereliesaz.geministrator.resources.mcp_consent_allow_run
import com.hereliesaz.geministrator.resources.mcp_consent_body
import com.hereliesaz.geministrator.resources.mcp_consent_deny
import com.hereliesaz.geministrator.resources.mcp_consent_title
import com.hereliesaz.geministrator.resources.mcp_remove_body
import com.hereliesaz.geministrator.resources.mcp_remove_title
import com.hereliesaz.geministrator.resources.mcp_script_sandbox
import com.hereliesaz.geministrator.resources.mcp_script_sandbox_detail
import com.hereliesaz.geministrator.resources.mcp_script_sandbox_off
import com.hereliesaz.geministrator.resources.mcp_script_sandbox_on
import com.hereliesaz.geministrator.resources.mcp_script_sandbox_turn_off
import com.hereliesaz.geministrator.resources.mcp_script_sandbox_turn_on
import com.hereliesaz.geministrator.resources.mcp_server_command
import com.hereliesaz.geministrator.resources.mcp_server_command_note
import com.hereliesaz.geministrator.resources.mcp_server_name
import com.hereliesaz.geministrator.resources.mcp_server_token
import com.hereliesaz.geministrator.resources.mcp_server_url
import com.hereliesaz.geministrator.resources.mcp_servers
import com.hereliesaz.geministrator.resources.mcp_servers_lead
import com.hereliesaz.geministrator.resources.mcp_status_allowed
import com.hereliesaz.geministrator.resources.mcp_status_asks
import com.hereliesaz.geministrator.resources.mcp_tool_count
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The MCP host, where the platform provides one (docs/architecture/MCP_HOST.md). */
internal val LocalMcpHost = compositionLocalOf<McpHost?> { null }

/** The script sandbox over the MCP host, where the platform has one (desktop). */
internal val LocalCodeExecution = compositionLocalOf<CodeExecutionToolSource?> { null }

/**
 * Connections › MCP servers: one row per server, its consent setting, and adding one by address
 * (every platform) or by command (desktop). A token typed here goes straight to the platform
 * credential store; nothing secret is kept in general settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun McpServerConnections(host: McpHost, compact: Boolean) {
    val scope = rememberCoroutineScope()
    val servers by host.servers.collectAsState()
    var adding by rememberDurableBooleanState("keeper.connections.adding-mcp")
    var byCommand by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var removeId by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val checkingText = stringResource(Res.string.mcp_checking)

    ProviderSectionLabel(stringResource(Res.string.mcp_servers))
    Text(stringResource(Res.string.mcp_servers_lead), style = AzphaltType.body, color = azphaltOnSurface())

    servers.forEach { server ->
        ConnectionRow(
            name = server.name,
            detail = listOfNotNull(server.location, checks[server.id]).joinToString("\n"),
            status = stringResource(if (server.alwaysAllowed) Res.string.mcp_status_allowed else Res.string.mcp_status_asks),
            connected = true,
            compact = compact,
        ) {
            AzphaltPill(
                label = stringResource(Res.string.mcp_check),
                seed = "mcp-check-${server.id}",
                onClick = {
                    checks = checks + (server.id to checkingText)
                    scope.launch {
                        val result = host.check(server.id)
                        val line = result.fold(
                            onSuccess = { tools -> getString(Res.string.mcp_tool_count, tools.size) },
                            onFailure = { failure -> getString(Res.string.mcp_check_failed, failure.message ?: failure::class.simpleName.orEmpty()) },
                        )
                        checks = checks + (server.id to line)
                    }
                },
            )
            AzphaltPill(
                label = stringResource(if (server.alwaysAllowed) Res.string.mcp_ask_each_run else Res.string.mcp_always_allow),
                seed = "mcp-allow-${server.id}",
                onClick = { scope.launch { host.setAlwaysAllowed(server.id, !server.alwaysAllowed) } },
            )
            AzphaltPill(
                label = stringResource(Res.string.common_remove),
                seed = "mcp-remove-${server.id}",
                onClick = { removeId = server.id },
            )
        }
    }

    LocalCodeExecution.current?.takeIf(CodeExecutionToolSource::available)?.let { CodeExecutionRow(it, compact) }

    AddConnectionRow(
        label = stringResource(Res.string.mcp_add_server),
        hint = stringResource(if (host.stdioSupported) Res.string.mcp_add_server_hint else Res.string.mcp_add_server_hint_remote),
        open = adding,
        onToggle = { adding = !adding },
    )
    if (adding) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (host.stdioSupported) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzphaltPill(stringResource(Res.string.mcp_by_address), "mcp-mode-address", selected = !byCommand, onClick = { byCommand = false })
                    AzphaltPill(stringResource(Res.string.mcp_by_command), "mcp-mode-command", selected = byCommand, onClick = { byCommand = true })
                }
            }
            AzphaltTextField(value = name, onValueChange = { name = it; addError = null }, label = stringResource(Res.string.mcp_server_name))
            if (byCommand && host.stdioSupported) {
                AzphaltTextField(value = command, onValueChange = { command = it; addError = null }, label = stringResource(Res.string.mcp_server_command))
                Text(stringResource(Res.string.mcp_server_command_note), style = AzphaltType.body, color = azphaltMutedOnSurface())
            } else {
                AzphaltTextField(value = url, onValueChange = { url = it; addError = null }, label = stringResource(Res.string.mcp_server_url))
                AzphaltTextField(
                    value = token,
                    onValueChange = { token = it; addError = null },
                    label = stringResource(Res.string.mcp_server_token),
                    secret = true,
                    showLabel = stringResource(Res.string.credential_show),
                    hideLabel = stringResource(Res.string.credential_hide),
                    errorMessage = addError,
                )
            }
            if (byCommand) addError?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
            AzphaltPill(
                label = stringResource(Res.string.mcp_add),
                seed = "mcp-add",
                tone = PillTone.Primary,
                onClick = {
                    scope.launch {
                        try {
                            if (byCommand && host.stdioSupported) {
                                host.addStdioServer(name, McpCommandLine.split(command))
                            } else {
                                host.addHttpServer(name, url, token)
                            }
                            name = ""
                            url = ""
                            token = ""
                            command = ""
                            adding = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            addError = failure.message ?: failure::class.simpleName
                        }
                    }
                },
            )
        }
    }

    removeId?.let { id ->
        AzphaltAlertDialog(
            title = stringResource(Res.string.mcp_remove_title, servers.firstOrNull { it.id == id }?.name ?: id),
            text = stringResource(Res.string.mcp_remove_body),
            confirmLabel = stringResource(Res.string.common_remove),
            onConfirm = {
                removeId = null
                scope.launch { host.remove(id) }
            },
            onDismissRequest = { removeId = null },
            dismissLabel = stringResource(Res.string.confirm_cancel),
        )
    }
}

/**
 * The keeper's opt-in for code execution with MCP (docs/architecture/MCP_HOST.md, "Code
 * execution"): off until turned on here.
 */
@Composable
private fun CodeExecutionRow(codeExecution: CodeExecutionToolSource, compact: Boolean) {
    val enabled by codeExecution.settings.enabled.collectAsState()
    ConnectionRow(
        name = stringResource(Res.string.mcp_script_sandbox),
        detail = stringResource(Res.string.mcp_script_sandbox_detail),
        status = stringResource(if (enabled) Res.string.mcp_script_sandbox_on else Res.string.mcp_script_sandbox_off),
        connected = enabled,
        compact = compact,
    ) {
        AzphaltPill(
            label = stringResource(if (enabled) Res.string.mcp_script_sandbox_turn_off else Res.string.mcp_script_sandbox_turn_on),
            seed = "mcp-script-sandbox",
            onClick = { codeExecution.settings.setEnabled(!enabled) },
        )
    }
}

/**
 * The approval gate for a server's tools (docs/architecture/MCP_HOST.md, Authority): what is
 * being called, where, with what, and three answers. Shown over everything while a run waits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun McpConsentDialog(gate: PromptingMcpConsentGate) {
    val request by gate.pending.collectAsState()
    val current = request ?: return
    MaterialTheme(colorScheme = GeministratorColors) {
        AlertDialog(
            onDismissRequest = { gate.answer(McpConsentDecision.Deny) },
            containerColor = Azphalt.Ink,
            titleContentColor = Azphalt.Ink.onOpposite(),
            textContentColor = Azphalt.Ink.onOpposite(),
            shape = RoundedCornerShape(AzphaltShapes.dialogCorner),
            title = { Text(stringResource(Res.string.mcp_consent_title, current.serverName), fontWeight = FontWeight.Black) },
            text = {
                Text(
                    stringResource(
                        Res.string.mcp_consent_body,
                        current.toolName,
                        current.serverName,
                        current.serverLocation,
                        current.argumentsPreview,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { gate.answer(McpConsentDecision.AllowForRun) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Azphalt.Yellow,
                        contentColor = Azphalt.Yellow.onOpposite(),
                    ),
                ) { Text(stringResource(Res.string.mcp_consent_allow_run)) }
            },
            dismissButton = {
                FlowRow {
                    TextButton(
                        onClick = { gate.answer(McpConsentDecision.Deny) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Azphalt.Ink.onOpposite()),
                    ) { Text(stringResource(Res.string.mcp_consent_deny)) }
                    TextButton(
                        onClick = { gate.answer(McpConsentDecision.AllowAlways) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Azphalt.Ink.onOpposite()),
                    ) { Text(stringResource(Res.string.mcp_consent_allow_always)) }
                }
            },
        )
    }
}
