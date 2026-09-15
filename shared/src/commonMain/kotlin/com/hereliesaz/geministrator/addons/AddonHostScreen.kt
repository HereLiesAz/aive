package com.hereliesaz.geministrator.addons

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddonHostScreen(
    installations: List<AddonInstallation>,
    onRemove: (String) -> Unit,
    onEnableDisable: (String, Boolean) -> Unit,
    onAddAgentsToCompany: ((String) -> Unit)? = null,
    packageSourceStatus: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ADD-ONS")
        Text("Verified Azphalt workflow packages installed in Haive.")

        if (installations.isEmpty()) {
            Text("No workflow add-ons are installed.")
        } else {
            installations.sortedBy(AddonInstallation::id).forEach { installation ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${installation.id} · ${installation.version}")
                    Text(if (installation.enabled) "Enabled" else "Disabled")
                    Button(onClick = {
                        onEnableDisable(installation.id, !installation.enabled)
                    }) {
                        Text(if (installation.enabled) "Disable" else "Enable")
                    }
                    Button(onClick = { onRemove(installation.id) }) {
                        Text("Remove")
                    }
                    if (onAddAgentsToCompany != null) {
                        Button(onClick = { onAddAgentsToCompany(installation.id) }) {
                            Text("Add agents to company")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            packageSourceStatus
                ?: "No verified package source is connected. Haive will not install unverified manifest text as a package.",
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
