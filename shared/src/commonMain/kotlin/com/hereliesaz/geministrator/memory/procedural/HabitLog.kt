package com.hereliesaz.geministrator.memory.procedural

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One line of the ledger a habit is computed from: a receipt, in plain words, newest first. Nothing
 * here is a summary or a score; it is what was recorded, so the keeper can see why a habit reads as
 * it does and correct it (docs/architecture/HABITS_AS_MEMORY.md).
 */
data class HabitLogEntry(
    val atEpochMillis: Long,
    val operation: String,
    val kind: Kind,
    val text: String,
    val workflowId: String,
) {
    enum class Kind { Started, Finished, Reviewed, Flag, Decision }

    /** One line for a screen or a copied log. */
    @OptIn(ExperimentalTime::class)
    fun line(): String =
        Instant.fromEpochMilliseconds(atEpochMillis).toString().take(16).replace('T', ' ') +
            " UTC  " + kind.name.lowercase().padEnd(8) + " " + operation + "  " + text
}

object HabitLog {
    const val DEFAULT_LIMIT = 100

    /**
     * The receipts for [operation] (all named operations when null), newest first, at most [limit].
     * A receipt kept twice appears once.
     */
    fun entries(
        traces: List<ProcedureTrace>,
        operation: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<HabitLogEntry> =
        traces.asSequence()
            .filter { it.operation != null && (operation == null || it.operation == operation) }
            .distinctBy { it.receiptKey }
            .map(::entryOf)
            .sortedWith(compareByDescending<HabitLogEntry> { it.atEpochMillis }.thenBy { it.text })
            .take(limit)
            .toList()

    fun render(entries: List<HabitLogEntry>): String = entries.joinToString("\n") { it.line() }

    private fun entryOf(trace: ProcedureTrace): HabitLogEntry {
        val where = trace.environment?.let { " in $it" }.orEmpty()
        val values = trace.slots.takeIf { it.isNotEmpty() }
            ?.joinToString("; ", prefix = " (", postfix = ")") { "${it.name}=${it.value}" }.orEmpty()
        val (kind, text) = when {
            trace.flag != null -> HabitLogEntry.Kind.Flag to
                "${trace.flag.source.name.lowercase()} flag by ${trace.flag.author}: \"${quoted(trace.flag.text)}\"" +
                if (trace.flag.tainted) " (written with untrusted content in view)" else ""
            trace.deliberation != null -> HabitLogEntry.Kind.Decision to
                "${trace.deliberation.outcome.name.lowercase()} by ${trace.deliberation.author}: " +
                "\"${quoted(trace.deliberation.text)}\"" +
                (trace.deliberation.alternative?.let { " Instead: \"${quoted(it)}\"" }.orEmpty()) +
                (trace.deliberation.alternativeOperation?.let { " ($it)" }.orEmpty())
            trace.phase == ProcedureTracePhase.Verification -> HabitLogEntry.Kind.Reviewed to
                "a reviewer ${if (trace.verification == ProcedureTraceVerification.Rejected) "turned it back" else "approved it"}$where"
            trace.phase == ProcedureTracePhase.Dispatch -> HabitLogEntry.Kind.Started to
                "started$where$values, run ${trace.workflowId}"
            else -> HabitLogEntry.Kind.Finished to
                "${trace.outcome.name.lowercase()}$where$values, run ${trace.workflowId}"
        }
        return HabitLogEntry(trace.recordedAtEpochMillis, checkNotNull(trace.operation), kind, text, trace.workflowId)
    }

    private fun quoted(text: String): String = text.replace('"', '\'').replace(Regex("\\s+"), " ").trim()
}
