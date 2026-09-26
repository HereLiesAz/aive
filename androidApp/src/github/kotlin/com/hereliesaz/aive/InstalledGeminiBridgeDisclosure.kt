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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Prominent disclosure shown before the user is sent to Accessibility settings. */
@Composable
internal fun InstalledGeminiBridgeDisclosure(
    onAgree: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Before you enable the Gemini bridge")
        Text(
            "The bridge is an Android accessibility service. While The Aive hands a task to the " +
                "installed Gemini app, it types the task into Gemini and reads Gemini's reply from the " +
                "screen. It only watches the Gemini app, and only while a handoff is running; it does " +
                "not read other apps. The task text goes to Google through your signed-in Gemini app " +
                "under Google's terms.",
        )
        Text(
            "Android will open Accessibility settings, where you turn on \"The Aive · Gemini bridge\". " +
                "You can turn it off there, or disconnect Gemini in The Aive, at any time. This bridge " +
                "exists only in GitHub releases; Google Play builds use the Gemini API instead.",
        )
        Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
            Text("I understand, enable the bridge")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
