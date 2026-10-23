package com.hereliesaz.aive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.hereliesaz.geministrator.AzphaltHeading
import com.hereliesaz.geministrator.AzphaltParagraph
import com.hereliesaz.geministrator.AzphaltPrimaryButton
import com.hereliesaz.geministrator.AzphaltScreen
import com.hereliesaz.geministrator.AzphaltSecondaryButton

@Composable
internal fun AndroidGeminiProviderSetup(
    installedBridgeSupported: Boolean,
    installedAppDetected: Boolean,
    accessibilityEnabled: Boolean,
    onUseInstalledGemini: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
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
    AzphaltScreen {
        AzphaltHeading(stringResource(R.string.gemini_setup_title))
        AzphaltParagraph(
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
            AzphaltPrimaryButton(
                if (accessibilityEnabled) stringResource(R.string.gemini_setup_use_installed) else stringResource(R.string.gemini_setup_enable_bridge),
            ) { disclosing = true }
            if (!accessibilityEnabled) {
                AzphaltSecondaryButton(
                    stringResource(R.string.gemini_setup_open_accessibility),
                    onOpenAccessibilitySettings,
                )
            }
        }
        AzphaltParagraph(stringResource(R.string.gemini_setup_api_key_hint))
        AzphaltSecondaryButton(stringResource(R.string.gemini_setup_use_api_key), onUseApiKey)
        AzphaltSecondaryButton(stringResource(R.string.common_cancel), onCancel)
    }
}
