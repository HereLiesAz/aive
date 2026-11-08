package com.hereliesaz.geministrator.memory.procedural

/**
 * When a habit forms and when a controlled pass counts. These are the keeper's decisions
 * (docs/architecture/HABITS_AS_MEMORY.md, section 9): a habit is offered at three uses, at low
 * strength, and an alternative to a habit consolidates after the same number of knowing passes,
 * because that alternative is just a new habit.
 */
data class HabitSettings(
    val offerAtUses: Int = 3,
    val controlledPassesToConsolidate: Int = 3,
) {
    init {
        require(offerAtUses >= 1 && controlledPassesToConsolidate >= 1)
    }
}

enum class HabitStrength {
    /** Seen, but not often enough to be offered. */
    Forming,

    /** Used often enough to be offered; the lowest strength that is. */
    Low,
}

/** One value a slot has taken, with how often and when. */
data class HabitSlotValue(
    val value: String,
    val count: Int,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
)

/**
 * A slot of a habit: a configured value that varies across its uses. [values] is the slot's
 * register: every value it has taken, none ranked as correct, most used first.
 */
data class HabitSlot(
    val name: String,
    val type: ProcedureParameterType,
    val values: List<HabitSlotValue>,
)

/**
 * The flags written about a habit, counted by who wrote them, with the newest quoted. These are
 * counts and quotes, not a score: nothing divides them or ranks a habit by them.
 */
data class HabitFlags(
    val used: Int = 0,
    val handled: Int = 0,
    val keeper: Int = 0,
    val newest: List<ProcedureTraceFlag> = emptyList(),
) {
    val total: Int get() = used + handled + keeper

    companion object {
        const val NEWEST = 3
    }
}

/**
 * What repeated runs of one named operation add up to. The frame is the executor and the
 * operation; the slots are the fillers that varied. Nothing here judges: the counts are facts, and
 * strength says only how often the habit has been used.
 */
data class FormedHabit(
    val executorKind: String,
    val operation: String,
    /** Finished executions of the frame: completed plus failed. */
    val uses: Int,
    val completedUses: Int,
    val failedUses: Int,
    /** Uses a different reviewing task approved, and uses one turned back. */
    val verifiedUses: Int,
    val rejectedUses: Int,
    val slots: List<HabitSlot>,
    val firstUsedAtEpochMillis: Long,
    val lastUsedAtEpochMillis: Long,
    val strength: HabitStrength,
    /** Of the newest [recentUses] uses (at most [HabitFormation.RECENT]), how many failed. */
    val recentUses: Int = 0,
    val recentFailedUses: Int = 0,
    val flags: HabitFlags = HabitFlags(),
) {
    val offered: Boolean get() = strength != HabitStrength.Forming

    /** Uses no reviewing task has approved or turned back. */
    val unreviewedUses: Int get() = uses - verifiedUses - rejectedUses
}

object HabitFormation {
    const val MAX_HABITS = 64
    const val MAX_VALUES_PER_SLOT = 8

    /** How many of the newest uses make up a habit's recent outcomes. */
    const val RECENT = 3

    private data class UseKey(val workflowId: String, val taskRunId: String, val attempt: Int)

    private data class Use(
        val operation: String,
        val executorKind: String,
        val failed: Boolean,
        val verification: ProcedureTraceVerification,
        val slots: List<ProcedureTraceSlot>,
        val atEpochMillis: Long,
    )

    /**
     * The habits [traces] add up to. A use is one execution (a task run attempt) that finished,
     * however many receipts it left. An execution that only reported Running is not a use yet.
     * Receipts with no named operation cannot form a habit: there is no frame to repeat.
     */
    fun form(traces: List<ProcedureTrace>, settings: HabitSettings = HabitSettings()): List<FormedHabit> {
        val named = traces.filter { it.operation != null }
        val uses = named
            .filter { it.phase != ProcedureTracePhase.Flag }
            .groupBy { UseKey(it.workflowId, it.taskRunId, it.attempt) }
            .values
            .mapNotNull(::useOf)
        val flagsByOperation = named
            .filter { it.phase == ProcedureTracePhase.Flag }
            .distinctBy { it.receiptKey }
            .groupBy { it.operation }

        // The frame is the named operation: a placement choice (a Distributed executor) is not a
        // different thing being done.
        return uses.groupBy { it.operation }
            .map { (operation, group) ->
                habitOf(
                    executorKind = group.minBy { it.atEpochMillis }.executorKind,
                    operation = operation,
                    uses = group,
                    settings = settings,
                    flags = flagsOf(flagsByOperation[operation].orEmpty()),
                )
            }
            .sortedWith(compareByDescending<FormedHabit> { it.uses }.thenBy { it.operation })
            .take(MAX_HABITS)
    }

    private fun useOf(receipts: List<ProcedureTrace>): Use? {
        val execution = receipts.filter { it.phase != ProcedureTracePhase.Verification }
        val failed = execution.any {
            it.outcome == ProcedureTraceOutcome.Failed || it.outcome == ProcedureTraceOutcome.Threw
        }
        val completed = execution.any { it.outcome == ProcedureTraceOutcome.Completed }
        if (!failed && !completed) return null

        val verifications = receipts.filter { it.phase == ProcedureTracePhase.Verification }.map { it.verification }
        // One rejection outweighs any approval, as in ProcedureVerifications.
        val verification = when {
            ProcedureTraceVerification.Rejected in verifications -> ProcedureTraceVerification.Rejected
            ProcedureTraceVerification.Verified in verifications -> ProcedureTraceVerification.Verified
            else -> ProcedureTraceVerification.Unknown
        }
        val named = receipts.first()
        return Use(
            operation = checkNotNull(named.operation),
            executorKind = named.executorKind,
            failed = failed,
            verification = verification,
            slots = receipts.firstOrNull { it.slots.isNotEmpty() }?.slots.orEmpty(),
            atEpochMillis = receipts.maxOf { it.recordedAtEpochMillis },
        )
    }

    private fun flagsOf(receipts: List<ProcedureTrace>): HabitFlags {
        val flags = receipts.mapNotNull { trace -> trace.flag?.let { trace.recordedAtEpochMillis to it } }
        return HabitFlags(
            used = flags.count { it.second.source == ProcedureFlagSource.Used },
            handled = flags.count { it.second.source == ProcedureFlagSource.Handled },
            keeper = flags.count { it.second.source == ProcedureFlagSource.Keeper },
            newest = flags.sortedByDescending { it.first }.take(HabitFlags.NEWEST).map { it.second },
        )
    }

    private fun habitOf(
        executorKind: String,
        operation: String,
        uses: List<Use>,
        settings: HabitSettings,
        flags: HabitFlags,
    ): FormedHabit {
        val failedUses = uses.count { it.failed }
        val recent = uses.sortedByDescending { it.atEpochMillis }.take(RECENT)
        return FormedHabit(
            executorKind = executorKind,
            operation = operation,
            uses = uses.size,
            completedUses = uses.size - failedUses,
            failedUses = failedUses,
            verifiedUses = uses.count { it.verification == ProcedureTraceVerification.Verified },
            rejectedUses = uses.count { it.verification == ProcedureTraceVerification.Rejected },
            slots = slotsOf(uses),
            firstUsedAtEpochMillis = uses.minOf { it.atEpochMillis },
            lastUsedAtEpochMillis = uses.maxOf { it.atEpochMillis },
            strength = if (uses.size >= settings.offerAtUses) HabitStrength.Low else HabitStrength.Forming,
            recentUses = recent.size,
            recentFailedUses = recent.count { it.failed },
            flags = flags,
        )
    }

    private fun slotsOf(uses: List<Use>): List<HabitSlot> =
        uses.flatMap { use -> use.slots.map { slot -> slot to use.atEpochMillis } }
            .groupBy { (slot, _) -> slot.name to slot.type }
            .map { (key, seen) ->
                HabitSlot(
                    name = key.first,
                    type = key.second,
                    values = seen.groupBy { (slot, _) -> slot.value }
                        .map { (value, hits) ->
                            HabitSlotValue(
                                value = value,
                                count = hits.size,
                                firstSeenAtEpochMillis = hits.minOf { it.second },
                                lastSeenAtEpochMillis = hits.maxOf { it.second },
                            )
                        }
                        .sortedWith(compareByDescending<HabitSlotValue> { it.count }.thenByDescending { it.lastSeenAtEpochMillis })
                        .take(MAX_VALUES_PER_SLOT),
                )
            }
            .sortedBy { it.name }
}
