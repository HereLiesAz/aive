package com.hereliesaz.geministrator.tour

/** Why the queen has a lesson step rebuilt. A closed set: a reason never carries text from the keeper or from the run. */
enum class RedoReason(val note: String) {
    /** The keeper said they were lost and the step that follows had stopped. */
    KeeperLost("The queen rebuilt a lesson step because the keeper was lost"),

    /** The last checkpoint was missed and the step that follows had stopped. */
    HiveStalled("The queen rebuilt a lesson step because the swarm had stalled on it"),
}

/**
 * One lesson not yet played, as the queen sees it. Either a lesson that has arrived and waits ([index] set, with its [target]), or
 * a step whose creature stopped before handing one in ([stalled], [stepId] set, no [index]). A lesson already shown never appears here.
 */
data class RemainingLesson(
    /** The task that writes this lesson, or null when it is not known. */
    val stepId: String? = null,
    /** Position in [TourState.lessons] of an arrived lesson, else null. */
    val index: Int? = null,
    val target: String? = null,
    /** The creature's step stopped (escalated): it can be rebuilt through the mending path. */
    val stalled: Boolean = false,
    /** A lesson about a tap or an open the keeper could pass over (never one that waits on the hive, never the last). */
    val skippable: Boolean = false,
    /** Already revised once: a lesson is revised at most once. */
    val revised: Boolean = false,
)

/** What the queen decides about the lessons still to come. */
sealed interface LessonRevisionDecision {
    /** Leave the plan as it is. */
    data object Keep : LessonRevisionDecision

    /** Slow down: the arrived lessons still to come open on their small steps. */
    data object Smaller : LessonRevisionDecision

    /** The keeper reported these targets comfortable: lessons about them are passed over. */
    data class SkipKnown(val targets: Set<String>) : LessonRevisionDecision

    /** Rebuild this stalled step through the mending path ([com.hereliesaz.geministrator.repairFailureEscalation]). */
    data class Redo(val stepId: String, val reason: RedoReason) : LessonRevisionDecision
}

/**
 * The queen revises lessons not yet played, from the keeper's qualitative replies and what the run did (the keeper's ruling: the tutorial
 * hive writes the lesson and is the lesson, and she may adjust throughout). Pure: no model, no clock. It never rewrites the whole plan,
 * never touches a lesson already played (those are not in `remaining`), revises a lesson at most once, and stops after [MAX_PER_RUN]
 * revisions. Nothing here grades the keeper: a reply is a feeling, and the only inputs are enums, ids and counts.
 */
object LessonRevision {
    /** The most revisions one run gets. */
    const val MAX_PER_RUN = 3

    /**
     * @param replies the keeper's check-in replies so far, oldest first
     * @param outcomes how each played step ended, by step index
     * @param remaining the lessons not yet played
     * @param comfortable targets the keeper reported comfortable (see [ComfortRecord.comfortableIds])
     * @param revisionsUsed how many revisions this run has had
     */
    fun plan(
        replies: List<CheckInReply>,
        outcomes: Map<Int, StepOutcome>,
        remaining: List<RemainingLesson>,
        comfortable: Set<String> = emptySet(),
        revisionsUsed: Int = 0,
    ): LessonRevisionDecision {
        if (revisionsUsed >= MAX_PER_RUN || remaining.isEmpty()) return LessonRevisionDecision.Keep
        val last = replies.lastOrNull()
        val latest = outcomes.maxByOrNull { it.key }?.value
        val struggling = last == CheckInReply.Lost || latest == StepOutcome.Missed

        // The first thing she changes: a step that stopped, while the keeper is struggling, is rebuilt now rather than waited on.
        if (struggling) {
            val stepId = remaining.firstOrNull { it.stalled && it.stepId != null && !it.revised }?.stepId
            if (stepId != null) {
                return LessonRevisionDecision.Redo(stepId, if (last == CheckInReply.Lost) RedoReason.KeeperLost else RedoReason.HiveStalled)
            }
        }

        val waiting = remaining.filter { it.index != null && !it.revised }
        val unsure = replies.takeLast(3).count { it == CheckInReply.Lost || it == CheckInReply.SortOf }
        if (waiting.isNotEmpty() && (last == CheckInReply.Lost || unsure >= 2)) return LessonRevisionDecision.Smaller

        if (last == CheckInReply.Yes) {
            val known = waiting.filter { it.skippable && it.target?.let { t -> t in comfortable } == true }.mapNotNull { it.target }.toSet()
            if (known.isNotEmpty()) return LessonRevisionDecision.SkipKnown(known)
        }
        return LessonRevisionDecision.Keep
    }

    /** The reducer action for [decision] over [remaining] (Smaller and SkipKnown only; Redo and Keep act elsewhere), or null for none. */
    fun actionFor(decision: LessonRevisionDecision, remaining: List<RemainingLesson>): TourAction? {
        val waiting = remaining.filter { it.index != null && !it.revised }
        return when (decision) {
            LessonRevisionDecision.Smaller -> TourAction.Revise(known = emptySet(), slower = waiting.mapNotNull { it.index }.toSet())
            is LessonRevisionDecision.SkipKnown ->
                TourAction.Revise(known = waiting.filter { it.skippable && it.target?.let { t -> t in decision.targets } == true }.mapNotNull { it.index }.toSet(), slower = emptySet())
            else -> null
        }
    }

    /**
     * The lessons of [state] not yet played, plus the stalled steps [stalledSteps] (task ids whose creature stopped). A lesson passed over
     * or already revised carries that. [revisedSteps] are stalled steps already rebuilt this way.
     */
    fun remainingOf(state: TourState, stalledSteps: Set<String>, revisedSteps: Set<String>): List<RemainingLesson> {
        val arrived = state.lessons.mapIndexedNotNull { i, lesson ->
            if (i <= state.index) return@mapIndexedNotNull null
            RemainingLesson(
                stepId = lesson.stepId,
                index = i,
                target = lesson.target,
                skippable = i < state.stepTotal - 1 &&
                    (lesson.expect.kind == ExpectKind.Tapped || lesson.expect.kind == ExpectKind.Opened),
                revised = i in state.revised || i in state.known,
            )
        }
        val stalled = stalledSteps.sorted().map { RemainingLesson(stepId = it, stalled = true, revised = it in revisedSteps) }
        return arrived + stalled
    }
}
