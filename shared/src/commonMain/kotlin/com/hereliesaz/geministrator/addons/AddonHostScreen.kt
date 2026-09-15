package com.hereliesaz.geministrator.addons

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddonHostScreen(
    installations: List<AddonInstallation>,
    onInstall: (AzphaltPackageManifest, Set<HostPermission>) -> Unit,
    onRemove: (String) -> Unit,
    onEnableDisable: (String, Boolean) -> Unit,
    onAddAgentsToCompany: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("ADD-ONS STORE & MANAGER")
        Spacer(modifier = Modifier.height(16.dp))

        Text("Installed Packages:")
        installations.forEach { inst ->
            Row {
                Text(inst.id)
                Text(if (inst.enabled) " (Enabled)" else " (Disabled)")
                Button(onClick = { onEnableDisable(inst.id, !inst.enabled) }) { Text("Toggle") }
                Button(onClick = { onRemove(inst.id) }) { Text("Remove") }
                Button(onClick = { onAddAgentsToCompany(inst.id) }) { Text("Add agents to my company") }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        // Placeholder for browsing logic utilizing repository path
        Text("Browse Repository (Placeholder)")
    }
}

@Composable
fun AddonScreenRenderer(
    screen: AddonScreen,
    actionDispatcher: (String) -> Unit,
    bindingProvider: (String) -> String,
    onInputUpdate: (String, String) -> Unit,
    modifier: Modifier = Modifier
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
                            is AddonScreenItem.Button -> Button(onClick = { actionDispatcher(item.actionId) }) { Text(item.label) }
                            is AddonScreenItem.TextInput -> {
                                OutlinedTextField(
                                    value = bindingProvider(item.bindingId),
                                    onValueChange = { onInputUpdate(item.bindingId, it) },
                                    label = { Text(item.label) }
                                )
                            }
                            is AddonScreenItem.Select -> {
                                Text("Select (Dropdown): \${item.label}")
                            }
                            is AddonScreenItem.Toggle -> {
                                Row {
                                    Text(item.label)
                                    Checkbox(
                                        checked = bindingProvider(item.bindingId) == "true",
                                        onCheckedChange = { onInputUpdate(item.bindingId, it.toString()) }
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
