package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.workflow.SpendCaps
import com.russhwolf.settings.Settings

/**
 * The keeper's spend caps, kept on this device (Keeper's room, This device). Read fresh each time,
 * so a cap changed in the Keeper's room applies to the run already under way at its next cycle.
 */
internal class SpendCapStore(private val settings: Settings) {
    val caps: SpendCaps
        get() = SpendCaps(
            runCostCapUsd = read(RUN_COST_CAP_KEY),
            hourlySpendCapUsd = read(HOURLY_SPEND_CAP_KEY),
        )

    /** Sets (or, with null, clears) the per-run cost cap. */
    fun setRunCostCap(usd: Double?) = write(RUN_COST_CAP_KEY, usd)

    /** Sets (or, with null, clears) the hourly spend cap across runs. */
    fun setHourlySpendCap(usd: Double?) = write(HOURLY_SPEND_CAP_KEY, usd)

    private fun read(key: String): Double? =
        settings.getStringOrNull(key)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    private fun write(key: String, usd: Double?) {
        if (usd == null || !usd.isFinite() || usd <= 0.0) {
            settings.remove(key)
        } else {
            settings.putString(key, usd.toString())
        }
    }

    companion object {
        const val RUN_COST_CAP_KEY: String = "aive.spend.run-cost-cap-usd.v1"
        const val HOURLY_SPEND_CAP_KEY: String = "aive.spend.hourly-cap-usd.v1"
    }
}

/** What a keeper typed for a cap: blank is no cap; otherwise a positive dollar amount ("5", "$2.50"). */
internal sealed interface SpendCapInput {
    data object Off : SpendCapInput
    data class Cap(val usd: Double) : SpendCapInput
    data object Invalid : SpendCapInput
}

internal fun parseSpendCapInput(text: String): SpendCapInput {
    val trimmed = text.trim().removePrefix("\$").trim()
    if (trimmed.isEmpty()) return SpendCapInput.Off
    val value = trimmed.toDoubleOrNull() ?: return SpendCapInput.Invalid
    return if (value.isFinite() && value > 0.0) SpendCapInput.Cap(value) else SpendCapInput.Invalid
}

/** A stored cap as the keeper would type it back: no trailing ".0", blank when off. */
internal fun spendCapText(usd: Double?): String {
    if (usd == null) return ""
    val text = usd.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}
