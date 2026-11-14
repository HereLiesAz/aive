package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BudgetClass
import com.hereliesaz.geministrator.domain.BudgetClassCodec
import com.hereliesaz.geministrator.domain.BudgetClassSettings
import com.hereliesaz.geministrator.domain.BudgetClassesInUse
import com.russhwolf.settings.Settings

/**
 * The keeper's real numbers for the budget classes (small, medium, large), kept on this device beside the spend caps
 * (Keeper's room, This device). Creating one publishes what is stored, and every change publishes again, so a run reads
 * the new number at its next cycle ([BudgetClassesInUse]). A role's own token budget, when it has one, always wins.
 */
internal class BudgetClassStore(private val settings: Settings) {
    init {
        BudgetClassesInUse.current = classes
    }

    val classes: BudgetClassSettings
        get() = BudgetClassCodec.decode(settings.getStringOrNull(KEY))

    /** Sets [budgetClass] to [tokens]; null (or a number not above zero) puts its default back. */
    fun set(budgetClass: BudgetClass, tokens: Long?) {
        val next = classes.with(budgetClass, tokens)
        if (next == BudgetClassSettings.Defaults) settings.remove(KEY) else settings.putString(KEY, BudgetClassCodec.encode(next))
        BudgetClassesInUse.current = next
    }

    companion object {
        const val KEY: String = "aive.budget-classes.v1"
    }
}

/** What a keeper typed for a class: blank is the default; otherwise a whole number of tokens above zero ("600000", "600,000"). */
internal sealed interface BudgetClassInput {
    data object Default : BudgetClassInput
    data class Tokens(val tokens: Long) : BudgetClassInput
    data object Invalid : BudgetClassInput
}

internal fun parseBudgetClassInput(text: String): BudgetClassInput {
    val trimmed = text.trim().replace(",", "").replace("_", "").replace(" ", "")
    if (trimmed.isEmpty()) return BudgetClassInput.Default
    val value = trimmed.toLongOrNull() ?: return BudgetClassInput.Invalid
    return if (value in 1..BudgetClassSettings.MAX_TOKENS) BudgetClassInput.Tokens(value) else BudgetClassInput.Invalid
}
