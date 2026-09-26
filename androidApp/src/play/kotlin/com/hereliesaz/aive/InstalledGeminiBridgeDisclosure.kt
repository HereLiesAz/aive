package com.hereliesaz.aive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Play builds have no installed-Gemini bridge, so there is nothing to disclose. */
@Composable
internal fun InstalledGeminiBridgeDisclosure(
    onAgree: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onBack() }
}
