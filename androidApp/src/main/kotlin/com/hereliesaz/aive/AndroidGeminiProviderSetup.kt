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
import androidx.compose.ui.res.stringResource
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
        Text(stringResource(R.string.gemini_setup_title))
        Text(
            when {
                !installedBridgeSupported ->
                    stringResource(R.string.gemini_setup_bridge_unsupported)
                !installedAppDetected ->
                    stringResource(R.string.gemini_setup_app_not_detected)
                accessibilityEnabled ->
                    stringResource(R.string.gemini_setup_bridge_ready)
                else ->
                    stringResource(R.string.gemini_setup_enable_bridge_hint)
            },
        )
        if (installedBridgeSupported && installedAppDetected) {
            Button(
                onClick = { disclosing = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (accessibilityEnabled) stringResource(R.string.gemini_setup_use_installed) else stringResource(R.string.gemini_setup_enable_bridge))
            }
        }
        Button(
            onClick = onUseApiKey,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.gemini_setup_use_api_key))
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}
