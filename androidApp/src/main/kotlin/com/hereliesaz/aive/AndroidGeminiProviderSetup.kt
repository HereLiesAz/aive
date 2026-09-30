package com.hereliesaz.aive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AndroidGeminiProviderSetup(
    installedBridgeSupported: Boolean,
    installedAppDetected: Boolean,
    accessibilityEnabled: Boolean,
    onUseInstalledGemini: () -> Unit,
    onUseApiKey: () -> Unit,
    onCancel: () -> Unit,
) {
    // The bridge reads another app's screen, so it is enabled only after an explicit disclosure.
    var disclosing by remember { mutableStateOf(false) }
    if (disclosing) {
        InstalledGeminiBridgeDisclosure(
            onAgree = {
                disclosing = false
                onUseInstalledGemini()
            },
            onBack = { disclosing = false },
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect Gemini")
        Text(
            when {
                !installedBridgeSupported ->
                    "Google Play builds connect to Gemini through the official API. The installed-app bridge is available in GitHub releases."
                !installedAppDetected ->
                    "The Gemini app was not detected. You can still connect Gemini with an API key."
                accessibilityEnabled ->
                    "The installed Gemini app is ready. Aive can open it in a bounded window and use it as the Gemini transport."
                else ->
                    "The Gemini app is installed. Enable The Aive · Gemini bridge in Android Accessibility settings to use the signed-in app without an API key."
            },
        )
        if (installedBridgeSupported && installedAppDetected) {
            Button(
                onClick = { disclosing = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (accessibilityEnabled) "Use installed Gemini" else "Enable installed Gemini bridge")
            }
        }
        Button(
            onClick = onUseApiKey,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use Gemini API key")
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}
