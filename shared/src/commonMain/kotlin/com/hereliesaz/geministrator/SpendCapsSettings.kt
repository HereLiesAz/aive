package com.hereliesaz.geministrator

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.domain.BudgetClass
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.settings_budget_class_invalid
import com.hereliesaz.geministrator.resources.settings_budget_class_large
import com.hereliesaz.geministrator.resources.settings_budget_class_medium
import com.hereliesaz.geministrator.resources.settings_budget_class_small
import com.hereliesaz.geministrator.resources.settings_budget_classes_description
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
internal fun SpendCapsSettings(
    store: SpendCapStore = remember { SpendCapStore(Settings()) },
    budgets: BudgetClassStore = remember { BudgetClassStore(Settings()) },
) {
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
    // A role names a class, not a number; the numbers are the keeper's. A role's own token budget (its Mind) still wins.
    Text(
        stringResource(Res.string.settings_budget_classes_description),
        style = AzphaltType.body,
        color = azphaltOnSurface(),
    )
    BudgetClassField(
        label = stringResource(Res.string.settings_budget_class_small),
        initial = budgets.classes.small,
        onTokens = { budgets.set(BudgetClass.Small, it) },
    )
    BudgetClassField(
        label = stringResource(Res.string.settings_budget_class_medium),
        initial = budgets.classes.medium,
        onTokens = { budgets.set(BudgetClass.Medium, it) },
    )
    BudgetClassField(
        label = stringResource(Res.string.settings_budget_class_large),
        initial = budgets.classes.large,
        onTokens = { budgets.set(BudgetClass.Large, it) },
    )
}

@Composable
private fun BudgetClassField(label: String, initial: Long, onTokens: (Long?) -> Unit) {
    var draft by remember { mutableStateOf(initial.toString()) }
    val input = parseBudgetClassInput(draft)
    AzphaltTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            when (val parsed = parseBudgetClassInput(text)) {
                BudgetClassInput.Default -> onTokens(null)
                is BudgetClassInput.Tokens -> onTokens(parsed.tokens)
                BudgetClassInput.Invalid -> Unit
            }
        },
        label = label,
        errorMessage = if (input == BudgetClassInput.Invalid) stringResource(Res.string.settings_budget_class_invalid) else null,
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
