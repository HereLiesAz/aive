package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.MemoryEpisodeId

/**
 * Discovery output, not a runnable recipe. Traces contain executor class and outcome but NOT the
 * operation/arguments or independently verified postconditions. A pattern may only be proposed
 * for inspection and cannot become a ProcedureDefinition without explicit typed operation data.
 */
data class ProcedurePatternStep(
    val taskDefinitionId: String,
    val executorKind: String,
    val receipts: List<String>,
    val sourceEpisodeIds: Set<MemoryEpisodeId>,
)

data class ProcedurePattern(
    val workflowId: String,
    val kind: ProcedureKind,
    val steps: List<ProcedurePatternStep>,
    val reportedCompletions: Int,
    val conflictingOrFailedAttempts: Int,
    val observedOrderOnly: Boolean,
) {
    val eligibleForExecution: Boolean get() = false
    val sourceEpisodeIds: Set<MemoryEpisodeId> get() =
        steps.flatMapTo(linkedSetOf()) { it.sourceEpisodeIds }
}

/** Deterministic, read-only pattern recognition over Aive's own redacted executor receipts. */
object ProcedureCandidateExtractor {
    private const val MAX_PATTERNS = 32
    private const val MAX_HABIT_STEPS = 8

    fun discover(
        workflowId: String,
        traces: List<ProcedureTrace>,
        episodes: List<MemoryEpisode>,
    ): List<ProcedurePattern> {
        require(workflowId.isNotBlank())
        val episodeByReceipt = episodes
            .asSequence()
            .filter { it.workflowRunId == workflowId && it.purged == null }
            .groupBy { it.sourceSessionId }
        val relevant = traces.filter { it.workflowId == workflowId }
        // One attempt is one observation. Dispatch + reconciliation must never count twice.
        val attempts = relevant.groupBy { it.taskRunId to it.attempt }
        val valid = mutableListOf<Pair<ProcedureTrace, MemoryEpisode>>()
        val failures = mutableMapOf<Pair<String, String>, Int>()
        for ((_, recorded) in attempts) {
            val signature = recorded.map { it.taskDefinitionId to it.executorKind }.distinct()
            if (signature.size != 1) continue
            val key = signature.single()
            val failed = recorded.any {
                it.outcome == ProcedureTraceOutcome.Failed || it.outcome == ProcedureTraceOutcome.Threw
            }
            if (failed) {
                failures[key] = (failures[key] ?: 0) + 1
                continue
            }
            val completed = recorded.filter { it.outcome == ProcedureTraceOutcome.Completed }
            val observation = completed.firstOrNull { it.phase == ProcedureTracePhase.Reconcile }
                ?: completed.firstOrNull { it.phase == ProcedureTracePhase.Dispatch }
                ?: continue
            val source = episodeByReceipt["executor:${observation.taskRunId}:${observation.attempt}"]
                .orEmpty().singleOrNull {
                    it.projectId == observation.projectId &&
                        it.taskRunId == observation.taskRunId &&
                        it.taskDefinitionId == observation.taskDefinitionId &&
                        it.userPrompt == "Execution receipt ${observation.receiptKey}: ${observation.contextualSummary()}"
                } ?: continue
            valid += observation to source
        }
        val reflexes = valid.groupBy { it.first.taskDefinitionId to it.first.executorKind }
            .toList()
            .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            .take(MAX_PATTERNS)
            .map { (key, examples) ->
                ProcedurePattern(
                    workflowId = workflowId,
                    kind = ProcedureKind.Reflex,
                    steps = listOf(ProcedurePatternStep(
                        taskDefinitionId = key.first,
                        executorKind = key.second,
                        receipts = examples.map { it.first.receiptKey }.sorted(),
                        sourceEpisodeIds = examples.mapTo(linkedSetOf()) { it.second.id },
                    )),
                    reportedCompletions = examples.size,
                    conflictingOrFailedAttempts = failures[key] ?: 0,
                    observedOrderOnly = false,
                )
            }

        // The clock shows observed chronology, NOT a dependency DAG. A human/agent must
        // separately supply and validate any proposed habit's dependencies.
        val chronological = valid.sortedWith(compareBy({ it.first.recordedAtEpochMillis }, { it.first.receiptKey }))
        val habit = if (chronological.size in 2..MAX_HABIT_STEPS &&
            chronological.map { it.first.recordedAtEpochMillis }.distinct().size == chronological.size &&
            chronological.map { it.first.taskDefinitionId }.distinct().size == chronological.size
        ) {
            ProcedurePattern(
                workflowId = workflowId,
                kind = ProcedureKind.Habit,
                steps = chronological.map { (trace, episode) ->
                    ProcedurePatternStep(
                        taskDefinitionId = trace.taskDefinitionId,
                        executorKind = trace.executorKind,
                        receipts = listOf(trace.receiptKey),
                        sourceEpisodeIds = setOf(episode.id),
                    )
                },
                reportedCompletions = chronological.size,
                conflictingOrFailedAttempts = failures.values.sum(),
                observedOrderOnly = true,
            )
        } else null
        return reflexes + listOfNotNull(habit)
    }
}
