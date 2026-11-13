package com.hereliesaz.geministrator.tour

/** How a step ended. Nothing is graded: this only paces the next lessons and says what was observed. */
enum class StepOutcome { Met, Skipped, Next, Missed }

enum class TourPhase { Showing, Paused, Dismissed }

/** Everything the keeper can do to the layer, plus what the real screen reports. */
sealed interface TourAction {
    data class Observed(val event: TourEvent) : TourAction
    data class LessonArrived(val lesson: LessonPayload) : TourAction
    data class SetTotal(val total: Int) : TourAction
    data object Next : TourAction
    /** "I know this": the keeper skips the step. */
    data object KnowThis : TourAction
    data object Back : TourAction
    data object Pause : TourAction
    data object Resume : TourAction
    /** Escape, the Back gesture or "Stop the tour": hides the layer; the swarm goes on. */
    data object Escape : TourAction
    /** Shows or hides the longer explanation. */
    data object ToggleExplain : TourAction
    /** The checkpoint allowance passed without the event. */
    data object WaitElapsed : TourAction
}

/**
 * The layer's whole state. Pure data: every transition is [reduceTour]. [lessons] grows as the swarm writes them;
 * [total] is how many the plan has (so "step 5 of 14" is honest before step 14 exists).
 */
data class TourState(
    val lessons: List<LessonPayload> = emptyList(),
    val total: Int = 0,
    val index: Int = 0,
    val phase: TourPhase = TourPhase.Showing,
    val outcomes: Map<Int, StepOutcome> = emptyMap(),
    val explainOpen: Boolean = false,
    /** True once the "if missed" line has been shown for this step (shown once, then only "I know this" remains). */
    val missedShown: Boolean = false,
    /** Text the card shows in place of the sentence (a check-in's other way of saying it), or null. */
    val note: String? = null,
    /** Further small cards still to show, one per "Got it", after "Lost me, go slower". */
    val smallerQueue: List<String> = emptyList(),
    /** An open check-in's line; the card shows the four quick replies while it is set. */
    val checkInLine: String? = null,
) {
    val current: LessonPayload? get() = lessons.getOrNull(index)
    val stepTotal: Int get() = maxOf(total, lessons.size)
    val finished: Boolean get() = stepTotal > 0 && index >= stepTotal
    /** The 1-based number shown to the keeper. */
    val stepNumber: Int get() = (index + 1).coerceAtMost(maxOf(stepTotal, 1))
}

/**
 * The four quick replies of a check-in. A reply is an enum, never text. Nothing about a reply is graded: it only says
 * how the keeper feels, and it is handled with no model call (see [CheckInTransitions]).
 */
enum class CheckInReply { Yes, SortOf, Lost, ShowMe }

private fun TourState.advance(outcome: StepOutcome): TourState =
    copy(
        outcomes = outcomes + (index to outcome),
        index = index + 1,
        explainOpen = false,
        missedShown = false,
        note = null,
        smallerQueue = emptyList(),
        checkInLine = null,
    )

/** True when [event] is exactly what [expect] waits for. */
fun expectationMet(expect: TourExpect, event: TourEvent): Boolean = when (event) {
    is TourEvent.Tapped -> expect.kind == ExpectKind.Tapped && expect.target == event.id.name
    is TourEvent.Opened -> expect.kind == ExpectKind.Opened && expect.target == event.id.name
}

fun reduceTour(state: TourState, action: TourAction): TourState = when (action) {
    is TourAction.LessonArrived -> state.copy(lessons = state.lessons + action.lesson)
    is TourAction.SetTotal -> state.copy(total = action.total.coerceAtLeast(0))
    is TourAction.Observed -> {
        val step = state.current
        if (state.phase == TourPhase.Showing && step != null && expectationMet(step.expect, action.event)) {
            state.advance(StepOutcome.Met)
        } else state
    }
    TourAction.Next -> when {
        state.smallerQueue.isNotEmpty() -> state.copy(note = state.smallerQueue.first(), smallerQueue = state.smallerQueue.drop(1))
        state.current != null -> state.advance(StepOutcome.Next)
        else -> state
    }
    TourAction.KnowThis -> if (state.current != null) state.advance(StepOutcome.Skipped) else state
    TourAction.Back -> if (state.index > 0) {
        state.copy(index = state.index - 1, explainOpen = false, missedShown = false, note = null, smallerQueue = emptyList(), checkInLine = null)
    } else state
    TourAction.Pause -> if (state.phase == TourPhase.Showing) state.copy(phase = TourPhase.Paused) else state
    TourAction.Resume -> if (state.phase != TourPhase.Showing) state.copy(phase = TourPhase.Showing) else state
    TourAction.Escape -> state.copy(phase = TourPhase.Dismissed)
    TourAction.ToggleExplain -> state.copy(explainOpen = !state.explainOpen)
    TourAction.WaitElapsed -> {
        if (state.phase == TourPhase.Showing && state.current != null && !state.missedShown) {
            state.copy(missedShown = true, outcomes = state.outcomes + (state.index to StepOutcome.Missed))
        } else state
    }
}

/** Compact record of where the keeper was, so a restart resumes at the same step: `index;M,S,N,X` (outcomes by step). */
object TourProgressCodec {
    fun encode(state: TourState): String {
        val letters = (0 until state.index).joinToString("") { i ->
            when (state.outcomes[i]) {
                StepOutcome.Met -> "M"
                StepOutcome.Skipped -> "S"
                StepOutcome.Next -> "N"
                StepOutcome.Missed -> "X"
                null -> "N"
            }
        }
        return "${state.index};$letters"
    }

    /** Returns the saved index and outcomes, or null for anything that does not parse. */
    fun decode(text: String?): Pair<Int, Map<Int, StepOutcome>>? {
        if (text == null) return null
        val parts = text.split(';')
        if (parts.size != 2) return null
        val index = parts[0].toIntOrNull()?.takeIf { it in 0..MAX_STEPS } ?: return null
        if (parts[1].length != index) return null
        val outcomes = parts[1].mapIndexed { i, c ->
            i to when (c) {
                'M' -> StepOutcome.Met
                'S' -> StepOutcome.Skipped
                'N' -> StepOutcome.Next
                'X' -> StepOutcome.Missed
                else -> return null
            }
        }.toMap()
        return index to outcomes
    }

    private const val MAX_STEPS = 30
}
