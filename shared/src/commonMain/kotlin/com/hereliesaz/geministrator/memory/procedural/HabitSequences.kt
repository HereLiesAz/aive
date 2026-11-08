package com.hereliesaz.geministrator.memory.procedural

/**
 * Several named operations done in the same order, one after another, in several runs. This is a
 * habit of more than one step. The order is the order they finished, as observed; a clock is not a
 * dependency graph, and nothing here claims one step caused the next.
 */
data class FormedSequence(
    val operations: List<String>,
    /** Distinct runs in which these ran consecutively, in this order. */
    val runs: Int,
    /** Of those runs, how many had every step complete without being failed or turned back. */
    val cleanRuns: Int,
    val lastSeenAtEpochMillis: Long,
    val offered: Boolean,
)

object HabitSequences {
    const val MIN_STEPS = 2
    const val MAX_STEPS = 6
    const val MAX_SEQUENCES = 16

    private data class Window(val operations: List<String>)

    private class Tally {
        val runs = mutableSetOf<String>()
        val cleanRuns = mutableSetOf<String>()
        var last = Long.MIN_VALUE
    }

    /**
     * The sequences in [traces] that repeat across at least [HabitSettings.offerAtUses] runs. Within a
     * run only consecutive finished executions count, and a stretch where two finished at the same
     * instant is skipped, because its order is not known. A sequence contained in a longer one seen
     * in exactly the same runs is left out; the longer one says more.
     */
    fun form(traces: List<ProcedureTrace>, settings: HabitSettings = HabitSettings()): List<FormedSequence> {
        val tallies = mutableMapOf<Window, Tally>()
        HabitFormation.executions(traces)
            .groupBy { it.workflowId }
            .forEach { (run, executions) ->
                val ordered = executions.sortedWith(compareBy({ it.atEpochMillis }, { it.operation }))
                for (start in ordered.indices) {
                    for (length in MIN_STEPS..MAX_STEPS) {
                        val end = start + length
                        if (end > ordered.size) break
                        val steps = ordered.subList(start, end)
                        if (steps.zipWithNext().any { (a, b) -> a.atEpochMillis == b.atEpochMillis }) break
                        val tally = tallies.getOrPut(Window(steps.map { it.operation })) { Tally() }
                        tally.runs += run
                        if (steps.none { it.failed }) tally.cleanRuns += run
                        tally.last = maxOf(tally.last, steps.last().atEpochMillis)
                    }
                }
            }

        val frequent = tallies.filterValues { it.runs.size >= settings.offerAtUses }
        return frequent
            .filter { (window, tally) ->
                frequent.none { (other, otherTally) ->
                    other.operations.size > window.operations.size &&
                        otherTally.runs == tally.runs &&
                        contains(other.operations, window.operations)
                }
            }
            .map { (window, tally) ->
                FormedSequence(
                    operations = window.operations,
                    runs = tally.runs.size,
                    cleanRuns = tally.cleanRuns.size,
                    lastSeenAtEpochMillis = tally.last,
                    offered = true,
                )
            }
            .sortedWith(
                compareByDescending<FormedSequence> { it.runs }
                    .thenByDescending { it.operations.size }
                    .thenBy { it.operations.joinToString(">") },
            )
            .take(MAX_SEQUENCES)
    }

    private fun contains(whole: List<String>, part: List<String>): Boolean =
        (0..whole.size - part.size).any { start -> whole.subList(start, start + part.size) == part }
}
