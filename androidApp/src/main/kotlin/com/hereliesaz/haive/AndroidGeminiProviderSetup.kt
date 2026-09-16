package com.hereliesaz.haive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AndroidGeminiProviderSetup(
    installedAppDetected: Boolean,
    accessibilityEnabled: Boolean,
    onUseInstalledGemini: () -> Unit,
    onUseApiKey: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect Gemini")
        Text(
            when {
                !installedAppDetected -> "The Gemini app was not detected. You can still connect Gemini with an API key."
                accessibilityEnabled -> "The installed Gemini app is ready. Haive can open it in a bounded window and use it as the Gemini transport."
                else -> "The Gemini app is installed. Enable The Haive · Gemini bridge in Android Accessibility settings to use the signed-in app without an API key."
            },
        )
        if (installedAppDetected) {
            Button(
                onClick = onUseInstalledGemini,
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
