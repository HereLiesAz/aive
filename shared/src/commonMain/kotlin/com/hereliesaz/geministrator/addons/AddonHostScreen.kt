package com.hereliesaz.geministrator.addons

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddonHostScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Installed Add-ons")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { /* Explicit "Add agents to my company" action */ }) {
            Text("Add agents to my company")
        }
    }
}

@Composable
fun AddonScreenRenderer(screen: AddonScreen, modifier: Modifier = Modifier) {
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
                            is AddonScreenItem.Button -> Button(onClick = { /* Symbolic action dispatch */ }) { Text(item.label) }
                            is AddonScreenItem.TextInput -> Text("Input: \${item.label}")
                            is AddonScreenItem.Select -> Text("Select: \${item.label}")
                            is AddonScreenItem.Toggle -> Text("Toggle: \${item.label}")
                        }
                    }
                }
            }
        }
    }
}
