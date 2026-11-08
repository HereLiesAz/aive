package com.hereliesaz.geministrator.memory.procedural

/**
 * How a model writes a decision about a habit in its own output (docs/architecture/HABITS_AS_MEMORY.md,
 * section 4 and 5): one line per decision, in the same spirit as the `VERDICT:` line a gate states.
 *
 * ~~~text
 * HABIT-DECISION: github-action:ci.yml | stands | The same inputs failed because the runner image changed.
 * HABIT-DECISION: github-action:ci.yml | supersedes | It now needs the integration job first.
 * HABIT-DECISION: github-action:ci.yml | overrides | It deploys to the wrong stage. | Use the v2 job. | github-action:ci-v2.yml
 * ~~~
 *
 * The parser reads text and nothing else. It does not decide whether a decision is recorded; that is
 * [com.hereliesaz.geministrator.workflow.HabitDecisionObserver]'s rule (never after untrusted content),
 * and the receipt's own bounds. A line that does not parse is ignored, not guessed at.
 */
object HabitDecisions {
    data class Decision(
        val operation: String,
        val outcome: ProcedureDeliberationOutcome,
        val text: String,
        val alternative: String?,
        val alternativeOperation: String?,
    )

    /** The instruction a task's objective may carry so its agent states a decision in a form this reads. */
    const val INSTRUCTION: String =
        "To record a decision about a habit, write one line: `HABIT-DECISION: <operation> | stands|supersedes|overrides | <your reason>`; " +
            "for overrides add ` | <what to do instead>` and, if it is a named operation, ` | <that operation>`."

    private val line = Regex("""^\W*habit[-_ ]?decision\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    /** At most [MAX_PER_TEXT] decisions are read from one text. */
    const val MAX_PER_TEXT: Int = 3

    fun parse(text: String): List<Decision> =
        text.lineSequence()
            .mapNotNull { raw -> line.find(raw.trim())?.groupValues?.get(1)?.let(::decisionOf) }
            .take(MAX_PER_TEXT)
            .toList()

    private fun decisionOf(body: String): Decision? {
        val fields = body.split('|').map { it.trim() }
        if (fields.size < 3) return null
        val operation = fields[0].takeIf { it.matches(ProcedureTrace.OPERATION) } ?: return null
        val outcome = when (fields[1].lowercase()) {
            "stands", "stand" -> ProcedureDeliberationOutcome.Stands
            "supersedes", "supersede", "superseded" -> ProcedureDeliberationOutcome.Superseded
            "overrides", "override", "overridden" -> ProcedureDeliberationOutcome.Overrides
            else -> return null
        }
        val reason = fields[2].take(ProcedureTraceFlag.MAX_TEXT).takeIf { it.isNotBlank() } ?: return null
        if (outcome != ProcedureDeliberationOutcome.Overrides) {
            return if (fields.size == 3) Decision(operation, outcome, reason, null, null) else null
        }
        val instead = fields.getOrNull(3)?.take(ProcedureTraceFlag.MAX_TEXT)?.takeIf { it.isNotBlank() } ?: return null
        val named = fields.getOrNull(4)?.takeIf { it.isNotBlank() }
        if (fields.size > 5) return null
        if (named != null && !named.matches(ProcedureTrace.OPERATION)) return null
        return Decision(operation, outcome, reason, instead, named)
    }
}
