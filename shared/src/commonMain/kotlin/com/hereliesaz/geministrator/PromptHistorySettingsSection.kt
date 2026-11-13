package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.prompts.PromptHistory
import com.hereliesaz.geministrator.prompts.PromptHistoryStore
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.prompt_remember_clear
import com.hereliesaz.geministrator.resources.prompt_remember_cleared
import com.hereliesaz.geministrator.resources.prompt_remember_intro
import com.hereliesaz.geministrator.resources.prompt_remember_off
import com.hereliesaz.geministrator.resources.prompt_remember_on
import com.hereliesaz.geministrator.resources.prompt_remember_title
import org.jetbrains.compose.resources.stringResource

/**
 * Keeper's room › About and privacy: the one switch for remembered and suggested text, and the way to clear it.
 * On until turned off; turning it off deletes what was remembered.
 */
@Composable
internal fun PromptHistorySettingsSection(store: PromptHistoryStore = PromptHistory.store) {
    var cleared by remember { mutableStateOf(false) }
    ProviderSectionLabel(stringResource(Res.string.prompt_remember_title))
    Text(stringResource(Res.string.prompt_remember_intro), style = AzphaltType.body, color = azphaltOnSurface())
    val on = store.enabled()
    AzphaltPill(
        stringResource(if (on) Res.string.prompt_remember_on else Res.string.prompt_remember_off),
        "prompt-history-toggle",
        selected = on,
        onClick = {
            cleared = false
            store.setEnabled(!on)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    AzphaltPill(
        stringResource(Res.string.prompt_remember_clear),
        "prompt-history-clear",
        onClick = {
            store.clearAll()
            cleared = true
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (cleared) {
        Text(
            stringResource(Res.string.prompt_remember_cleared),
            style = AzphaltType.body,
            color = azphaltOnSurface(),
            modifier = Modifier.azphaltLiveRegion(),
        )
    }
}
