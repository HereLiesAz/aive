package com.hereliesaz.geministrator.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.DurableUiState

/**
 * The one holder of the overlay layer's state. With no tour running [state] is null and the layer draws nothing.
 *
 * INTEGRATION POINT (the queen's planner, teacher and follow creatures are built elsewhere): they call [start] once with
 * the plan's step count, [addLesson] for each lesson that passed the validator, [openCheckIn] when a check-in slot asks,
 * and set [checkInSink] to receive the keeper's quick reply. Nothing here calls a model, reads the screen's content or
 * acts for the keeper.
 */
object TourController {
    var state: TourState? by mutableStateOf(null)
        private set

    /** Receives the keeper's quick reply to an open check-in (the follow creature's answer path). */
    var checkInSink: ((CheckInReply) -> Unit)? = null

    private var runKey: String? = null

    val active: Boolean get() = state?.let { it.phase != TourPhase.Dismissed && !it.finished } == true

    /** Begins (or resumes) a tour for [runKey] (null: nothing is saved) with [total] planned steps. A saved position is restored. */
    fun start(runKey: String?, total: Int) {
        this.runKey = runKey
        val saved = runKey?.let { TourProgressCodec.decode(safe { DurableUiState.store.getString(storageKey(it)) }) }
        state = TourState(
            total = total.coerceIn(0, TeachingLimits.MAX_STEPS),
            index = saved?.first ?: 0,
            outcomes = saved?.second ?: emptyMap(),
        )
        TourTargetRegistry.observer = { event -> dispatch(TourAction.Observed(event)) }
        syncTracking()
    }

    fun addLesson(lesson: LessonPayload) = dispatch(TourAction.LessonArrived(lesson))

    fun openCheckIn(line: String) {
        state = state?.copy(checkInLine = line)
    }

    fun dispatch(action: TourAction) {
        val before = state ?: return
        val after = reduceTour(before, action)
        state = after
        syncTracking()
        val key = runKey
        if (key != null && after.index != before.index) {
            safe { DurableUiState.store.putString(storageKey(key), TourProgressCodec.encode(after)) }
        }
    }

    /** The keeper's quick reply to the open check-in; the transition itself is [CheckInTransitions]. */
    fun reply(reply: CheckInReply) {
        checkInSink?.invoke(reply)
    }

    /** Escape or Back: hides the layer for this run. The swarm is not stopped. */
    fun escape() = dispatch(TourAction.Escape)

    /** Ends the tour and forgets nothing on the device but the live state. */
    fun stop() {
        state = null
        runKey = null
        checkInSink = null
        TourTargetRegistry.observer = null
        syncTracking()
    }

    /** Taps and opens are reported to the layer only while it is showing. */
    private fun syncTracking() {
        TourTargetRegistry.tracking = state?.let { it.phase == TourPhase.Showing && !it.finished } == true
    }

    private fun storageKey(runKey: String) = "tour.progress.v1.$runKey"

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}
