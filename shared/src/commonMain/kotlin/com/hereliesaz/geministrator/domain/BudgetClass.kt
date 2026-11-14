package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Budget classes (docs/Step-Templates.md). A role template names a class (small, medium or large), not a number. The
 * keeper gives each class real numbers in the existing spend settings; a role may override its class with a number of
 * its own (RoleDefinition.tokenBudget), and the number always wins. The class only supplies a budget to a role that
 * has none. Pure data and pure functions; no model call.
 */

@Serializable
enum class BudgetClass(val label: String) {
    Small("small"),
    Medium("medium"),
    Large("large"),
}

/** The real numbers behind the three classes: tokens one role may spend in one run. */
@Serializable
data class BudgetClassSettings(
    val small: Long = DEFAULT_SMALL,
    val medium: Long = DEFAULT_MEDIUM,
    val large: Long = DEFAULT_LARGE,
) {
    fun tokensFor(budgetClass: BudgetClass): Long = when (budgetClass) {
        BudgetClass.Small -> small
        BudgetClass.Medium -> medium
        BudgetClass.Large -> large
    }

    /** These settings with [budgetClass] set to [tokens]; a number that is not above zero (or too large) puts the default back. */
    fun with(budgetClass: BudgetClass, tokens: Long?): BudgetClassSettings {
        val value = tokens?.takeIf { it in 1..MAX_TOKENS } ?: defaultFor(budgetClass)
        return when (budgetClass) {
            BudgetClass.Small -> copy(small = value)
            BudgetClass.Medium -> copy(medium = value)
            BudgetClass.Large -> copy(large = value)
        }
    }

    companion object {
        const val DEFAULT_SMALL: Long = 200_000L
        const val DEFAULT_MEDIUM: Long = 600_000L
        const val DEFAULT_LARGE: Long = 2_000_000L

        /** The most a class may be set to: a typing slip must not make a budget meaningless. */
        const val MAX_TOKENS: Long = 1_000_000_000L

        val Defaults: BudgetClassSettings = BudgetClassSettings()

        fun defaultFor(budgetClass: BudgetClass): Long = Defaults.tokensFor(budgetClass)
    }
}

/** The persisted form of [BudgetClassSettings]: plain JSON that reads back tolerantly (a bad or missing number is the default). */
object BudgetClassCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(settings: BudgetClassSettings): String = json.encodeToString(BudgetClassSettings.serializer(), clean(settings))

    /** Never throws: unreadable text, or a number not above zero, gives the default for that class. */
    fun decode(text: String?): BudgetClassSettings {
        if (text.isNullOrBlank()) return BudgetClassSettings.Defaults
        val read = try {
            json.decodeFromString(BudgetClassSettings.serializer(), text)
        } catch (_: Exception) {
            return BudgetClassSettings.Defaults
        }
        return clean(read)
    }

    private fun clean(settings: BudgetClassSettings): BudgetClassSettings =
        BudgetClass.entries.fold(settings) { acc, c -> acc.with(c, settings.tokensFor(c)) }
}

/**
 * The numbers in use now. The keeper's settings publish here when they load and when they change, so a run reads the
 * current numbers at its next cycle. Tests and anything that never loaded settings see the defaults.
 */
object BudgetClassesInUse {
    var current: BudgetClassSettings = BudgetClassSettings.Defaults
}

/** The class [role] runs under: its own, else the class its starting template names; null for a role with neither. */
fun RoleDefinition.effectiveBudgetClass(): BudgetClass? = budgetClass ?: RoleStartingTemplates.classOf(id.value)

/** The tokens [role] may spend in one run: its own number, else its class's number, else null (no budget). */
fun RoleDefinition.effectiveTokenBudget(classes: BudgetClassSettings = BudgetClassesInUse.current): Long? =
    tokenBudget ?: effectiveBudgetClass()?.let(classes::tokensFor)

/**
 * The tools a step may use: [contractTools] (null means the step names none, so no filter), narrowed by the role's
 * [ceiling]. An empty ceiling is no ceiling. A ceiling with no contract tools becomes the filter; with contract tools it
 * is their intersection (a ceiling only ever narrows).
 */
fun effectiveAllowedTools(contractTools: List<String>?, ceiling: List<String>): List<String>? = when {
    ceiling.isEmpty() -> contractTools
    contractTools == null -> ceiling
    else -> contractTools.filter { it in ceiling }
}
