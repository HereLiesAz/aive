package com.hereliesaz.geministrator

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.settings_spend_cap_invalid
import com.hereliesaz.geministrator.resources.settings_spend_caps
import com.hereliesaz.geministrator.resources.settings_spend_caps_description
import com.hereliesaz.geministrator.resources.settings_spend_hourly_cap
import com.hereliesaz.geministrator.resources.settings_spend_run_cap
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.stringResource

/**
 * The Keeper's room, This device: the per-run cost cap and the hourly spend cap. Each saves as soon
 * as it holds a valid amount (blank is no cap); the running hive reads it at its next cycle.
 */
@Composable
internal fun SpendCapsSettings(store: SpendCapStore = remember { SpendCapStore(Settings()) }) {
    ProviderSectionLabel(stringResource(Res.string.settings_spend_caps))
    Text(
        stringResource(Res.string.settings_spend_caps_description),
        style = AzphaltType.body,
        color = azphaltOnSurface(),
    )
    SpendCapField(
        label = stringResource(Res.string.settings_spend_run_cap),
        initial = store.caps.runCostCapUsd,
        onCap = store::setRunCostCap,
    )
    SpendCapField(
        label = stringResource(Res.string.settings_spend_hourly_cap),
        initial = store.caps.hourlySpendCapUsd,
        onCap = store::setHourlySpendCap,
    )
}

@Composable
private fun SpendCapField(label: String, initial: Double?, onCap: (Double?) -> Unit) {
    var draft by remember { mutableStateOf(spendCapText(initial)) }
    val input = parseSpendCapInput(draft)
    AzphaltTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            when (val parsed = parseSpendCapInput(text)) {
                SpendCapInput.Off -> onCap(null)
                is SpendCapInput.Cap -> onCap(parsed.usd)
                SpendCapInput.Invalid -> Unit
            }
        },
        label = label,
        errorMessage = if (input == SpendCapInput.Invalid) stringResource(Res.string.settings_spend_cap_invalid) else null,
    )
}
