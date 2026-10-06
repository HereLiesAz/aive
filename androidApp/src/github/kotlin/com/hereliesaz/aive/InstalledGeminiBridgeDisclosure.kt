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
import androidx.compose.ui.res.stringResource
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
        Text(stringResource(R.string.gemini_bridge_disclosure_title))
        Text(stringResource(R.string.gemini_bridge_disclosure_body))
        Text(stringResource(R.string.gemini_bridge_disclosure_settings))
        Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.gemini_bridge_disclosure_agree))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.gemini_bridge_disclosure_back))
        }
    }
}
