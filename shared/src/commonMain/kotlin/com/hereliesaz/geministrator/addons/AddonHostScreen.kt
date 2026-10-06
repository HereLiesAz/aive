package com.hereliesaz.geministrator.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.addons_add_agents_to_swarm
import com.hereliesaz.geministrator.resources.addons_add_on_could_not_be_removed
import com.hereliesaz.geministrator.resources.addons_add_on_state_could_not_be
import com.hereliesaz.geministrator.resources.addons_add_on_storage_could_not_be
import com.hereliesaz.geministrator.resources.addons_add_ons
import com.hereliesaz.geministrator.resources.addons_azphalt_workflow_packages_installed_in_haive
import com.hereliesaz.geministrator.resources.addons_disable
import com.hereliesaz.geministrator.resources.addons_disabled
import com.hereliesaz.geministrator.resources.addons_enable
import com.hereliesaz.geministrator.resources.addons_enabled
import com.hereliesaz.geministrator.resources.addons_no_verified_package_source_is_connected
import com.hereliesaz.geministrator.resources.addons_no_workflow_add_ons_are_installed
import com.hereliesaz.geministrator.resources.addons_storage_error
import com.hereliesaz.geministrator.resources.common_remove
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddonHostScreen(
    installations: List<AddonInstallation>,
    onInstall: (AzphaltPackageManifest, Set<HostPermission>) -> Unit,
    onRemove: (String) -> Unit,
    onEnableDisable: (String, Boolean) -> Unit,
    onAddAgentsToCompany: (String) -> Unit,
    modifier: Modifier = Modifier,
    enableCompanyContribution: Boolean = false,
    packageSourceStatus: String? = null,
) {
    val persistence = remember { SettingsAddonPersistence.createDefault() }
    val scope = rememberCoroutineScope()
    var storedInstallations by remember { mutableStateOf(emptyList<AddonInstallation>()) }
    var storageFailure by remember { mutableStateOf<String?>(null) }

    fun reloadStoredInstallations() {
        scope.launch {
            runCatching { persistence.getInstallations() }
                .onSuccess {
                    storedInstallations = it
                    storageFailure = null
                }
                .onFailure { failure ->
                    storageFailure = failure.message ?: getString(Res.string.addons_add_on_storage_could_not_be)
                }
        }
    }

    val visibleInstallations = if (installations.isNotEmpty()) installations else storedInstallations

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.addons_add_ons))
        Text(stringResource(Res.string.addons_azphalt_workflow_packages_installed_in_haive))

        storageFailure?.let { Text(stringResource(Res.string.addons_storage_error, it)) }

        if (visibleInstallations.isEmpty()) {
            Text(stringResource(Res.string.addons_no_workflow_add_ons_are_installed))
        } else {
            visibleInstallations.sortedBy(AddonInstallation::id).forEach { installation ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${installation.id} · ${installation.version}")
                    Text(if (installation.enabled) stringResource(Res.string.addons_enabled) else stringResource(Res.string.addons_disabled))
                    Button(onClick = {
                        val enabled = !installation.enabled
                        scope.launch {
                            runCatching {
                                persistence.saveInstallation(installation.copy(enabled = enabled))
                            }.onSuccess {
                                reloadStoredInstallations()
                                onEnableDisable(installation.id, enabled)
                            }.onFailure { failure ->
                                storageFailure = failure.message ?: getString(Res.string.addons_add_on_state_could_not_be)
                            }
                        }
                    }) {
                        Text(if (installation.enabled) stringResource(Res.string.addons_disable) else stringResource(Res.string.addons_enable))
                    }
                    Button(onClick = {
                        scope.launch {
                            runCatching { persistence.removeInstallation(installation.id) }
                                .onSuccess {
                                    reloadStoredInstallations()
                                    onRemove(installation.id)
                                }
                                .onFailure { failure ->
                                    storageFailure = failure.message ?: getString(Res.string.addons_add_on_could_not_be_removed)
                                }
                        }
                    }) {
                        Text(stringResource(Res.string.common_remove))
                    }
                    if (enableCompanyContribution) {
                        Button(onClick = { onAddAgentsToCompany(installation.id) }) {
                            Text(stringResource(Res.string.addons_add_agents_to_swarm))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            packageSourceStatus
                ?: stringResource(Res.string.addons_no_verified_package_source_is_connected),
        )
    }
}

@Composable
fun AddonScreenRenderer(
    screen: AddonScreen,
    actionDispatcher: (String) -> Unit,
    bindingProvider: (String) -> String,
    onInputUpdate: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(screen.title)
        for (section in screen.sections) {
            when (section) {
                is AddonScreenSection.Group -> {
                    Text(section.title)
                    for (item in section.items) {
                        when (item) {
                            is AddonScreenItem.Text -> Text(item.content)
                            is AddonScreenItem.Record -> Row { Text(item.key); Text(": "); Text(item.value) }
                            is AddonScreenItem.Status -> Row { Text(item.label); Text(": "); Text(item.status) }
                            is AddonScreenItem.Button -> Button(
                                onClick = { actionDispatcher(item.actionId) },
                            ) {
                                Text(item.label)
                            }
                            is AddonScreenItem.TextInput -> {
                                OutlinedTextField(
                                    value = bindingProvider(item.bindingId),
                                    onValueChange = { onInputUpdate(item.bindingId, it) },
                                    label = { Text(item.label) },
                                )
                            }
                            is AddonScreenItem.Select -> {
                                var expanded by remember(item.bindingId) { mutableStateOf(false) }
                                val selected = bindingProvider(item.bindingId)
                                Column {
                                    Button(
                                        onClick = { expanded = true },
                                        enabled = item.options.isNotEmpty(),
                                    ) {
                                        Text(
                                            if (selected.isBlank()) item.label
                                            else "${item.label}: $selected",
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                    ) {
                                        item.options.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option) },
                                                onClick = {
                                                    onInputUpdate(item.bindingId, option)
                                                    expanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            is AddonScreenItem.Toggle -> {
                                Row {
                                    Text(item.label)
                                    Checkbox(
                                        checked = bindingProvider(item.bindingId) == "true",
                                        onCheckedChange = {
                                            onInputUpdate(item.bindingId, it.toString())
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
