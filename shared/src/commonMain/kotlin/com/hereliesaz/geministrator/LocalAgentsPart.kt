@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.providers.cli.CliAgentHost
import com.hereliesaz.geministrator.providers.cli.CliAgentStatus
import com.hereliesaz.geministrator.providers.cli.LocalCliAgentHost
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.local_agents_found
import com.hereliesaz.geministrator.resources.local_agents_keys_off
import com.hereliesaz.geministrator.resources.local_agents_keys_on
import com.hereliesaz.geministrator.resources.local_agents_never_tested
import com.hereliesaz.geministrator.resources.local_agents_not_installed
import com.hereliesaz.geministrator.resources.local_agents_note
import com.hereliesaz.geministrator.resources.local_agents_project_off
import com.hereliesaz.geministrator.resources.local_agents_project_on
import com.hereliesaz.geministrator.resources.local_agents_off
import com.hereliesaz.geministrator.resources.local_agents_on
import com.hereliesaz.geministrator.resources.local_agents_install
import com.hereliesaz.geministrator.resources.local_agents_installing
import com.hereliesaz.geministrator.resources.local_agents_test
import com.hereliesaz.geministrator.resources.local_agents_workspace_note
import com.hereliesaz.geministrator.resources.local_agents_workspace_read_only
import com.hereliesaz.geministrator.resources.local_agents_workspace_writes
import com.hereliesaz.geministrator.resources.local_agents_test_ok
import com.hereliesaz.geministrator.resources.local_agents_test_failed
import com.hereliesaz.geministrator.resources.local_agents_testing
import com.hereliesaz.geministrator.resources.local_agents_login_note
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Local agents (docs/Local-CLI-Agents.md): the vendors' own command-line tools, run headless on this computer under
 * the keeper's own login, so no API key is needed for them. Each is **off** until turned on here; **Test**
 * runs the installed one read-only on a tiny prompt and says what happened, so a flag that is wrong for its
 * version shows up here instead of as a failed task. Only shown where the app can run a program.
 */
@Composable
internal fun LocalAgentsPart() {
    val host = LocalCliAgentHost.current ?: return
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<CliAgentStatus>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var testing by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        while (true) {
            statuses = host.statuses()
            if (statuses.none { it.busy }) break
            delay(2_000)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.local_agents_note), style = AzphaltType.body, color = azphaltOnSurface())
        val readOnly by WorkspaceAgentMode.readOnly.collectAsState()
        AzphaltPill(
            label = stringResource(if (readOnly) Res.string.local_agents_workspace_read_only else Res.string.local_agents_workspace_writes),
            seed = "workspace-agent-read-only",
            selected = readOnly,
            onClick = { WorkspaceAgentMode.setReadOnly(!readOnly) },
        )
        Text(stringResource(Res.string.local_agents_workspace_note), style = AzphaltType.body, color = azphaltOnSurface())
        host.removeRuntimeLabel?.let { label ->
            AzphaltPill(
                label = label,
                seed = "local-agents-remove-runtime",
                onClick = { scope.launch { host.removeRuntime(); reload += 1 } },
            )
        }
        statuses.forEach { status ->
            CliCard(
                status = status,
                testing = testing == status.spec.id,
                installing = installing == status.spec.id,
                host = host,
                onInstall = {
                    scope.launch {
                        installing = status.spec.id
                        host.install(status.spec.id)
                        installing = null
                        reload += 1
                    }
                },
                onToggle = { host.setEnabled(status.spec.id, !status.enabled); reload += 1 },
                onKeys = { host.setPassKeys(status.spec.id, !status.passKeys); reload += 1 },
                onProjectConfig = { host.setAllowProjectConfig(status.spec.id, !status.allowProjectConfig); reload += 1 },
                onTest = {
                    scope.launch {
                        testing = status.spec.id
                        host.test(status.spec.id)
                        testing = null
                        reload += 1
                    }
                },
            )
        }
    }
}

@Composable
private fun CliCard(
    status: CliAgentStatus, testing: Boolean, installing: Boolean, onInstall: () -> Unit, host: CliAgentHost, onToggle: () -> Unit, onKeys: () -> Unit, onProjectConfig: () -> Unit, onTest: () -> Unit,
) {
    val found = status.path != null
    val where = if (found) stringResource(Res.string.local_agents_found, status.path.orEmpty(), status.version ?: "") else stringResource(Res.string.local_agents_not_installed)
    val test = status.lastTest
    val tested = when {
        testing -> stringResource(Res.string.local_agents_testing)
        test == null -> stringResource(Res.string.local_agents_never_tested)
        test.ok -> stringResource(Res.string.local_agents_test_ok, test.summary)
        else -> stringResource(Res.string.local_agents_test_failed, test.summary)
    }
    AzphaltRecord(
        seed = "local-agent-${status.spec.id}",
        eyebrow = where,
        title = status.spec.displayName,
        body = listOfNotNull(status.note, status.spec.sandboxNote,stringResource(Res.string.local_agents_login_note), tested).joinToString("\n"),
        well = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!found && status.canInstall) {
                    AzphaltPill(
                        label = stringResource(if (installing) Res.string.local_agents_installing else Res.string.local_agents_install),
                        seed = "local-agent-install-${status.spec.id}", onClick = { if (!installing) onInstall() },
                    )
                }
                if (found) {
                    AzphaltPill(
                        label = stringResource(if (status.enabled) Res.string.local_agents_on else Res.string.local_agents_off),
                        seed = "local-agent-toggle-${status.spec.id}", selected = status.enabled, onClick = onToggle,
                    )
                    AzphaltPill(label = stringResource(Res.string.local_agents_test), seed = "local-agent-test-${status.spec.id}", onClick = { if (!testing) onTest() })
                    if (status.spec.projectConfig.isNotEmpty()) {
                        AzphaltPill(
                            label = stringResource(if (status.allowProjectConfig) Res.string.local_agents_project_on else Res.string.local_agents_project_off),
                            seed = "local-agent-project-${status.spec.id}", selected = status.allowProjectConfig, onClick = onProjectConfig,
                        )
                    }
                    if (status.spec.optionalKeyEnvironment.isNotEmpty()) {
                        AzphaltPill(
                            label = stringResource(if (status.passKeys) Res.string.local_agents_keys_on else Res.string.local_agents_keys_off),
                            seed = "local-agent-keys-${status.spec.id}", selected = status.passKeys, onClick = onKeys,
                        )
                    }
                }
            }
        },
    )
}
