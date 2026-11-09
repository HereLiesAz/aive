package com.hereliesaz.geministrator.workflow.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Looks at the keeper's schedules, over and over, while the app is open, and starts what is due
 * (docs/Scheduled-Workflows.md). It decides with [decideSchedules] and does three things only: starts a
 * hive as drawn through [start], records what it decided on the schedule itself (`lastNote`), and
 * says so in the app log through [log]. It cannot approve anything.
 *
 * It runs only while the app is running: a schedule whose time passed while the app was closed starts
 * once, at the next look, not once per missed slot.
 */
class ScheduleTicker(
    private val store: WorkflowScheduleStore,
    private val now: () -> Long,
    /** Whether a run has not finished: one run is active at a time, and one waiting on a person counts. */
    private val runActive: suspend () -> Boolean,
    /** Starts [WorkflowSchedule.workflowId] as drawn with the schedule's objective; throws when it cannot. */
    private val start: suspend (WorkflowSchedule) -> Unit,
    private val log: (String) -> Unit,
    /**
     * What the host says about the schedule's hive as it is now, or null when it cannot (the hive is gone: the start then fails
     * with its reason). A hive that may never be scheduled, or one that differs from the schedule's fingerprint, is not started.
     */
    private val vet: suspend (WorkflowSchedule) -> HiveVetting? = { null },
) {
    private var lastHold: String? = null
    private var saidUnreadable = false

    /**
     * One look. Returns what it decided, for the caller and for tests.
     *
     * It never writes back the schedule it read: that copy goes stale while a slow start is awaited, and would bring back a
     * schedule the keeper turned off, changed or removed meanwhile. Each write changes only its own fields on the stored copy.
     */
    suspend fun tick(): ScheduleDecision {
        if (store.unreadable()) {
            if (!saidUnreadable) log("The saved schedules could not be read, so none will start. Open Schedules to keep or discard them.")
            saidUnreadable = true
            return ScheduleDecision.Idle
        }
        saidUnreadable = false
        val at = now()
        val decision = decideSchedules(store.all(), at, runActive = runActive())
        when (decision) {
            ScheduleDecision.Idle -> lastHold = null
            is ScheduleDecision.Hold -> {
                val note = "Due, but a run has not finished; it starts when that run does."
                if (lastHold != decision.schedule.id) {
                    log("Schedule '${decision.schedule.name}' is due. $note")
                    lastHold = decision.schedule.id
                }
                store.update(decision.schedule.id) { if (it.lastNote != note) it.copy(lastNote = note) else null }
            }
            is ScheduleDecision.Start -> {
                lastHold = null
                return startDue(decision, at)
            }
            is ScheduleDecision.Refused -> Unit
        }
        return decision
    }

    private suspend fun startDue(decision: ScheduleDecision.Start, at: Long): ScheduleDecision {
        var schedule = decision.schedule
        // A schedule is bound to a hive's id, not its content: check the hive is still allowed and still the one that was scheduled.
        vet(schedule)?.let { vetting ->
            val refusal = vetting.refusal
                ?: ScheduleVetting.CHANGED_NOTE.takeIf { schedule.definitionFingerprint != null && schedule.definitionFingerprint != vetting.fingerprint }
            if (refusal != null) {
                store.update(schedule.id) { it.copy(enabled = false, runNowRequested = false, lastNote = refusal) }
                log("Schedule '${schedule.name}' was not started and was turned off. $refusal")
                return ScheduleDecision.Refused(schedule, refusal)
            }
            // A schedule saved before fingerprints existed takes the hive as it is on first sight.
            if (schedule.definitionFingerprint == null) {
                store.update(schedule.id) { it.copy(definitionFingerprint = vetting.fingerprint) }?.let { schedule = it }
            }
        }
        // Claim this slot in one step, before anything is launched: if the schedule was changed, turned off, or already started
        // (by a second look, or another window sharing this storage) since it was read, it is not started again. A failed start
        // still counts as the slot's attempt, so it is not retried every look.
        val claimed = store.update(schedule.id) { current ->
            if (current.lastStartedAtEpochMillis != schedule.lastStartedAtEpochMillis || !ScheduleClock.isDue(current, at)) {
                null
            } else {
                current.copy(lastStartedAtEpochMillis = at, lastNote = "Starting.", runNowRequested = false)
            }
        } ?: return ScheduleDecision.Idle
        val outcome = try {
            start(schedule)
            "Started."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            "Could not start: ${failure.message ?: failure::class.simpleName}"
        }
        store.update(claimed.id) { it.copy(lastNote = outcome) }
        log("Schedule '${schedule.name}': $outcome")
        return decision
    }

    /** Looks every [everyMillis] until cancelled. */
    suspend fun run(everyMillis: Long = 60_000L) {
        while (true) {
            try {
                tick()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("Schedules could not be read: ${failure.message ?: failure::class.simpleName}")
            }
            delay(everyMillis)
        }
    }
}
