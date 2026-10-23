package com.hereliesaz.aive

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hereliesaz.geministrator.AzphaltHeading
import com.hereliesaz.geministrator.AzphaltParagraph
import com.hereliesaz.geministrator.AzphaltPrimaryButton
import com.hereliesaz.geministrator.AzphaltScreen
import com.hereliesaz.geministrator.AzphaltSecondaryButton

/** Prominent disclosure shown before the user is sent to Accessibility settings. */
@Composable
internal fun InstalledGeminiBridgeDisclosure(
    onAgree: () -> Unit,
    onBack: () -> Unit,
) {
    AzphaltScreen {
        AzphaltHeading(stringResource(R.string.gemini_bridge_disclosure_title))
        AzphaltParagraph(stringResource(R.string.gemini_bridge_disclosure_body))
        AzphaltParagraph(stringResource(R.string.gemini_bridge_disclosure_settings))
        AzphaltPrimaryButton(stringResource(R.string.gemini_bridge_disclosure_agree), onAgree)
        AzphaltSecondaryButton(stringResource(R.string.gemini_bridge_disclosure_back), onBack)
    }
}
