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
 * A contrast within one habit: the same operation with the same configured values has both
 * completed and been failed or turned back. It is a fact, not a ruling; only a deliberation answers it.
 */
data class HabitContrast(
    /** The configured values the two sides share, as `name=value; ...`, or `(no values)`. */
    val signature: String,
    val completed: Int,
    val turnedBack: Int,
)

/**
 * An override on a habit: a written decision that its offer shows first, with what to do instead.
 * It informs and redirects; it never hides or blocks the habit, and the habit can still be chosen.
 */
data class HabitOverride(
    val author: String,
    val text: String,
    val alternative: String,
    val alternativeOperation: String?,
    val atEpochMillis: Long,
    /** Uses of the habit since the override: it was chosen anyway, or came up again. */
    val usesSince: Int,
    /** Uses of the alternative operation since the override, when it names one. */
    val alternativeUses: Int?,
    /** The alternative has become a habit of its own, so this override is history. */
    val retired: Boolean,
)

/**
 * What repeated runs of one named operation add up to. The frame is the named operation; the slots
 * are the fillers that varied. Nothing here judges: the counts are facts, and strength says only how
 * often the habit has been used. Every count is of the current version; what was done before a
 * superseding deliberation is [earlierUses], history.
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
    /** 1, plus one for each deliberation that superseded the habit. */
    val version: Int = 1,
    /** Uses from before the latest superseding deliberation: history, not counted above. */
    val earlierUses: Int = 0,
    val openContrasts: List<HabitContrast> = emptyList(),
    /** The newest deliberation about this habit, of either outcome. */
    val lastDeliberation: ProcedureTraceDeliberation? = null,
    /** The newest override of the current version, if any, active or retired. */
    val habitOverride: HabitOverride? = null,
) {
    val offered: Boolean get() = strength != HabitStrength.Forming

    /** An override that still stands and should be shown first. */
    val activeOverride: HabitOverride? get() = habitOverride?.takeUnless { it.retired }

    /** Uses no reviewing task has approved or turned back. */
    val unreviewedUses: Int get() = uses - verifiedUses - rejectedUses
}

object HabitFormation {
    const val MAX_HABITS = 64
    const val MAX_VALUES_PER_SLOT = 8
    const val MAX_CONTRASTS = 4

    /** How many of the newest uses make up a habit's recent outcomes. */
    const val RECENT = 3

    private data class UseKey(val workflowId: String, val taskRunId: String, val attempt: Int)

    private data class Use(
        val workflowId: String,
        val operation: String,
        val executorKind: String,
        val failed: Boolean,
        val verification: ProcedureTraceVerification,
        val slots: List<ProcedureTraceSlot>,
        val atEpochMillis: Long,
    ) {
        /** Failed, or completed but turned back by a reviewer. */
        val turnedBack: Boolean get() = failed || verification == ProcedureTraceVerification.Rejected

        val signature: String
            get() = slots.sortedBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }
                .ifEmpty { "(no values)" }
    }

    private val notExecutions = setOf(ProcedureTracePhase.Flag, ProcedureTracePhase.Deliberation)

    /**
     * The habits [traces] add up to. A use is one execution (a task run attempt) that finished,
     * however many receipts it left. An execution that only reported Running is not a use yet.
     * Receipts with no named operation cannot form a habit: there is no frame to repeat. Flags and
     * deliberations are about a habit and are never uses.
     */
    fun form(traces: List<ProcedureTrace>, settings: HabitSettings = HabitSettings()): List<FormedHabit> {
        val named = traces.filter { it.operation != null }
        val uses = named
            .filter { it.phase !in notExecutions }
            .groupBy { UseKey(it.workflowId, it.taskRunId, it.attempt) }
            .values
            .mapNotNull(::useOf)
        val flagsByOperation = named
            .filter { it.phase == ProcedureTracePhase.Flag }
            .distinctBy { it.receiptKey }
            .groupBy { it.operation }
        val deliberationsByOperation = named
            .filter { it.phase == ProcedureTracePhase.Deliberation }
            .distinctBy { it.receiptKey }
            .groupBy { it.operation }

        // The frame is the named operation: a placement choice (a Distributed executor) is not a
        // different thing being done.
        val usesByOperation = uses.groupBy { it.operation }
        return usesByOperation
            .map { (operation, group) ->
                habitOf(
                    executorKind = group.minBy { it.atEpochMillis }.executorKind,
                    operation = operation,
                    allUses = group,
                    settings = settings,
                    flags = flagsOf(flagsByOperation[operation].orEmpty()),
                    deliberations = deliberationsByOperation[operation].orEmpty(),
                    usesOf = { usesByOperation[it].orEmpty() },
                )
            }
            .sortedWith(compareByDescending<FormedHabit> { it.uses }.thenBy { it.operation })
            .take(MAX_HABITS)
    }

    /** One finished execution of a named operation, for readers that look across a run (sequences). */
    internal data class Execution(
        val workflowId: String,
        val operation: String,
        val failed: Boolean,
        val atEpochMillis: Long,
    )

    internal fun executions(traces: List<ProcedureTrace>): List<Execution> =
        traces.filter { it.operation != null && it.phase !in notExecutions }
            .groupBy { UseKey(it.workflowId, it.taskRunId, it.attempt) }
            .values
            .mapNotNull(::useOf)
            .map { Execution(it.workflowId, it.operation, it.turnedBack, it.atEpochMillis) }

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
            workflowId = named.workflowId,
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
        allUses: List<Use>,
        settings: HabitSettings,
        flags: HabitFlags,
        deliberations: List<ProcedureTrace>,
        usesOf: (String) -> List<Use>,
    ): FormedHabit {
        val decided = deliberations.mapNotNull { trace -> trace.deliberation?.let { trace.recordedAtEpochMillis to it } }
            .sortedBy { it.first }
        // A superseding deliberation closes a version: what came before it is history, and the
        // habit starts again from what is done after it.
        val superseded = decided.filter { it.second.outcome == ProcedureDeliberationOutcome.Superseded }
        val boundary = superseded.maxOfOrNull { it.first }
        val uses = if (boundary == null) allUses else allUses.filter { it.atEpochMillis > boundary }
        val lastStands = decided.filter { it.second.outcome == ProcedureDeliberationOutcome.Stands }
            .maxOfOrNull { it.first }

        val failedUses = uses.count { it.failed }
        val recent = uses.sortedByDescending { it.atEpochMillis }.take(RECENT)
        val timed = uses.ifEmpty { allUses }
        return FormedHabit(
            executorKind = executorKind,
            operation = operation,
            uses = uses.size,
            completedUses = uses.size - failedUses,
            failedUses = failedUses,
            verifiedUses = uses.count { it.verification == ProcedureTraceVerification.Verified },
            rejectedUses = uses.count { it.verification == ProcedureTraceVerification.Rejected },
            slots = slotsOf(uses),
            firstUsedAtEpochMillis = timed.minOf { it.atEpochMillis },
            lastUsedAtEpochMillis = timed.maxOf { it.atEpochMillis },
            strength = if (uses.size >= settings.offerAtUses) HabitStrength.Low else HabitStrength.Forming,
            recentUses = recent.size,
            recentFailedUses = recent.count { it.failed },
            flags = flags,
            version = 1 + superseded.size,
            earlierUses = allUses.size - uses.size,
            openContrasts = contrastsOf(uses, lastStands),
            lastDeliberation = decided.lastOrNull()?.second,
            habitOverride = overrideOf(decided, boundary, allUses, usesOf, settings),
        )
    }

    /**
     * The newest override of the current version. Its alternative's uses since it was written are the
     * controlled passes: the alternative is just a new habit, so the same number of uses that offers any
     * habit retires the override.
     */
    private fun overrideOf(
        decided: List<Pair<Long, ProcedureTraceDeliberation>>,
        boundary: Long?,
        habitUses: List<Use>,
        usesOf: (String) -> List<Use>,
        settings: HabitSettings,
    ): HabitOverride? {
        val (at, decision) = decided
            .filter { it.second.outcome == ProcedureDeliberationOutcome.Overrides && (boundary == null || it.first > boundary) }
            .maxByOrNull { it.first } ?: return null
        val alternativeUses = decision.alternativeOperation?.let { op -> usesOf(op).count { it.atEpochMillis > at } }
        return HabitOverride(
            author = decision.author,
            text = decision.text,
            alternative = checkNotNull(decision.alternative),
            alternativeOperation = decision.alternativeOperation,
            atEpochMillis = at,
            usesSince = habitUses.count { it.atEpochMillis > at },
            alternativeUses = alternativeUses,
            retired = alternativeUses != null && alternativeUses >= settings.controlledPassesToConsolidate,
        )
    }

    /**
     * Where the same configured values have both completed and been turned back. A deliberation that
     * let the habit stand answers the contrast: it reopens only when something is turned back after it.
     */
    private fun contrastsOf(uses: List<Use>, lastStands: Long?): List<HabitContrast> =
        uses.groupBy { it.signature }
            .mapNotNull { (signature, group) ->
                val good = group.count { !it.turnedBack }
                val bad = group.count { it.turnedBack }
                val newBad = group.count { it.turnedBack && (lastStands == null || it.atEpochMillis > lastStands) }
                if (good > 0 && newBad > 0) HabitContrast(signature, completed = good, turnedBack = bad) else null
            }
            .sortedWith(compareByDescending<HabitContrast> { it.completed + it.turnedBack }.thenBy { it.signature })
            .take(MAX_CONTRASTS)

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
