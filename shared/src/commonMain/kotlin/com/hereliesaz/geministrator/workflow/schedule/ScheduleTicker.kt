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
    /**
     * The device's offset from UTC right now (its daylight saving included), or null where it is not known. A schedule's hour is
     * the device's own, so each look reads its due time against this offset: a daylight-saving change or a journey moves the
     * schedule with the clock even while Schedules is not open.
     */
    private val deviceUtcOffsetMinutes: () -> Int? = { null },
    /** Where web calls and new mail are taken (docs/Scheduled-Workflows.md); null where there are none. */
    private val triggers: TriggerIntake? = null,
    /** Looks at the mailbox of each email schedule at its own interval and reports new mail to [triggers]. */
    private val mail: MailPoller? = null,
    /** Starts the schedule's hive for one [TriggerEvent]; the event's text is outside content. */
    private val startTriggered: suspend (WorkflowSchedule, TriggerEvent) -> Unit = { schedule, _ -> start(schedule) },
) {
    private var lastTriggerHold: String? = null
    private var lastHold: String? = null
    private var saidUnreadable = false
    private var recoveredInterrupted = false

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
        if (!recoveredInterrupted) {
            recoveredInterrupted = true
            recoverInterruptedStarts(at)
        }
        val device = deviceUtcOffsetMinutes()
        mail?.let { poller ->
            try {
                poller.poll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("Mail could not be checked: ${failure.message ?: failure::class.simpleName}")
            }
        }
        triggers?.let { intake -> tickTriggers(intake, at)?.let { return it } }
        val schedules = store.all().map { readAt(it, device) }
        val decision = decideSchedules(schedules, at, runActive = runActive())
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
                return startDue(decision, at, device)
            }
            is ScheduleDecision.Refused -> Unit
        }
        return decision
    }

    /**
     * A slot is claimed (`lastNote` = [STARTING_NOTE]) before anything launches, so a process that died between the claim and the
     * launch left a slot that was never started and that no later look would retry. At the first look of a new process, such a slot
     * inside [INTERRUPTED_START_GRACE_MILLIS] is started once now (when no run is active); an older one is marked missed with a note.
     */
    private suspend fun recoverInterruptedStarts(at: Long) {
        for (schedule in store.all()) {
            // A triggered start's event is gone with the process: it is never replayed, only marked missed.
            val action = interruptedStartAction(schedule, at, runActive = runActive())
                .let { if (it == InterruptedStartAction.Resume && schedule.trigger.kind != ScheduleTrigger.Kind.Time) InterruptedStartAction.Missed else it }
            when (action) {
                InterruptedStartAction.None -> Unit
                InterruptedStartAction.Missed -> {
                    store.update(schedule.id) { if (it.lastNote == STARTING_NOTE) it.copy(lastNote = MISSED_NOTE) else null }
                    log("Schedule '${schedule.name}' was interrupted before it started. $MISSED_NOTE")
                }
                InterruptedStartAction.Resume -> {
                    val outcome = try {
                        start(schedule)
                        "Started after the app reopened."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        "Could not start: ${failure.message ?: failure::class.simpleName}"
                    }
                    store.update(schedule.id) { if (it.lastNote == STARTING_NOTE) it.copy(lastNote = outcome) else null }
                    log("Schedule '${schedule.name}': $outcome")
                }
            }
        }
    }

    /** A schedule's due time read at the device's own offset; an every-N-hours cadence has no hour of day, so it is left as stored. */
    private fun readAt(schedule: WorkflowSchedule, device: Int?): WorkflowSchedule =
        if (device == null || schedule.cadence.kind == ScheduleCadence.Kind.EveryHours) schedule
        else schedule.copy(utcOffsetMinutes = device.coerceIn(-14 * 60, 14 * 60), daylightSaving = DaylightSaving.None)

    private class Vetted(val schedule: WorkflowSchedule, val refused: ScheduleDecision.Refused?)

    /** A schedule is bound to a hive's id, not its content: check the hive is still allowed and still the one that was scheduled. */
    private suspend fun vetBeforeStart(original: WorkflowSchedule): Vetted {
        var schedule = original
        vet(schedule)?.let { vetting ->
            val refusal = vetting.refusal
                ?: ScheduleVetting.CHANGED_NOTE.takeIf { schedule.definitionFingerprint != null && schedule.definitionFingerprint != vetting.fingerprint }
            if (refusal != null) {
                store.update(schedule.id) { it.copy(enabled = false, runNowRequested = false, lastNote = refusal) }
                log("Schedule '${schedule.name}' was not started and was turned off. $refusal")
                return Vetted(schedule, ScheduleDecision.Refused(schedule, refusal))
            }
            // A schedule saved before fingerprints existed takes the hive as it is on first sight.
            if (schedule.definitionFingerprint == null) {
                store.update(schedule.id) { it.copy(definitionFingerprint = vetting.fingerprint) }?.let { schedule = it }
            }
        }
        return Vetted(schedule, null)
    }

    /**
     * Starts the oldest taken web call or new-mail event, if any, under every guard a timed start has: the run-active hold and the
     * hive vetting. The event leaves the queue before its start, so it starts at most once.
     */
    private suspend fun tickTriggers(intake: TriggerIntake, at: Long): ScheduleDecision? {
        while (true) {
            val event = intake.next(at)
            if (event == null) {
                lastTriggerHold = null
                return null
            }
            val schedule = store.all().firstOrNull { it.id == event.scheduleId }
            if (schedule == null || !schedule.enabled || schedule.trigger.kind != event.kind) {
                intake.done(event)
                log("A triggered start for '${schedule?.name ?: event.scheduleId}' was dropped: the schedule is gone, off, or waits for something else now.")
                continue
            }
            if (runActive()) {
                val note = "Triggered, but a run has not finished; it starts when that run does."
                if (lastTriggerHold != event.eventId) {
                    log("Schedule '${schedule.name}' was triggered by ${event.cause}. $note")
                    lastTriggerHold = event.eventId
                }
                store.update(schedule.id) { if (it.lastNote != note) it.copy(lastNote = note) else null }
                return ScheduleDecision.Hold(schedule, HoldReason.RunActive)
            }
            val vetted = vetBeforeStart(schedule)
            if (vetted.refused != null) {
                intake.done(event)
                return vetted.refused
            }
            val current = vetted.schedule
            intake.done(event)
            val claimed = store.update(current.id) { it.copy(lastStartedAtEpochMillis = at, lastNote = STARTING_NOTE) }
            if (claimed == null) continue
            val outcome = try {
                startTriggered(current, event)
                "Started by ${event.cause}."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Could not start: ${failure.message ?: failure::class.simpleName}"
            }
            store.update(current.id) { it.copy(lastNote = outcome) }
            log("Schedule '${current.name}': $outcome")
            return ScheduleDecision.Start(current)
        }
    }

    private suspend fun startDue(decision: ScheduleDecision.Start, at: Long, device: Int?): ScheduleDecision {
        val vetted = vetBeforeStart(decision.schedule)
        vetted.refused?.let { return it }
        val schedule = vetted.schedule
        // Claim this slot in one step, before anything is launched: if the schedule was changed, turned off, or already started
        // (by a second look, or another window sharing this storage) since it was read, it is not started again. A failed start
        // still counts as the slot's attempt, so it is not retried every look.
        val claimed = store.update(schedule.id) { current ->
            if (current.lastStartedAtEpochMillis != schedule.lastStartedAtEpochMillis || !ScheduleClock.isDue(readAt(current, device), at)) {
                null
            } else {
                current.copy(lastStartedAtEpochMillis = at, lastNote = STARTING_NOTE, runNowRequested = false)
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

internal const val STARTING_NOTE = "Starting."
internal const val MISSED_NOTE = "Missed: the app closed while this was starting, and too much time has passed to start it now."

/** How long after a claimed slot a new process still starts it. */
internal const val INTERRUPTED_START_GRACE_MILLIS: Long = 30L * 60_000L

internal enum class InterruptedStartAction { None, Resume, Missed }

/** What to do at launch with a schedule whose claimed slot never reached "Started". Pure. */
internal fun interruptedStartAction(
    schedule: WorkflowSchedule,
    now: Long,
    runActive: Boolean,
    graceMillis: Long = INTERRUPTED_START_GRACE_MILLIS,
): InterruptedStartAction {
    if (schedule.lastNote != STARTING_NOTE || !schedule.enabled) return InterruptedStartAction.None
    val claimedAt = schedule.lastStartedAtEpochMillis ?: return InterruptedStartAction.Missed
    return if (!runActive && now - claimedAt in 0..graceMillis) InterruptedStartAction.Resume else InterruptedStartAction.Missed
}
