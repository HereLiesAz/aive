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
) {
    private var lastHold: String? = null

    /** One look. Returns what it decided, for the caller and for tests. */
    suspend fun tick(): ScheduleDecision {
        val schedules = store.all()
        val decision = decideSchedules(schedules, now(), runActive = runActive())
        when (decision) {
            ScheduleDecision.Idle -> lastHold = null
            is ScheduleDecision.Hold -> {
                val note = "Due, but a run has not finished; it starts when that run does."
                if (lastHold != decision.schedule.id) {
                    log("Schedule '${decision.schedule.name}' is due. $note")
                    lastHold = decision.schedule.id
                }
                if (decision.schedule.lastNote != note) store.put(decision.schedule.copy(lastNote = note))
            }
            is ScheduleDecision.Start -> {
                lastHold = null
                val schedule = decision.schedule
                val at = now()
                val outcome = try {
                    start(schedule)
                    "Started."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    "Could not start: ${failure.message ?: failure::class.simpleName}"
                }
                // A failed start still counts as this slot's attempt, so it is not retried every look.
                store.put(schedule.copy(lastStartedAtEpochMillis = at, lastNote = outcome))
                log("Schedule '${schedule.name}': $outcome")
            }
        }
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
