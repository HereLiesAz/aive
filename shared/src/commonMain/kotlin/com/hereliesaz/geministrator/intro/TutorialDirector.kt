package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.queen.QueenLevel
import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.queen.TutorialSay
import com.hereliesaz.geministrator.tour.CheckInPolicy
import com.hereliesaz.geministrator.tour.CheckInRecord
import com.hereliesaz.geministrator.tour.CheckInReply
import com.hereliesaz.geministrator.tour.CheckInTransitions
import com.hereliesaz.geministrator.tour.Comfort
import com.hereliesaz.geministrator.tour.ComfortRecord
import com.hereliesaz.geministrator.tour.LessonContext
import com.hereliesaz.geministrator.tour.LessonPayload
import com.hereliesaz.geministrator.tour.LessonWriteOutcome
import com.hereliesaz.geministrator.tour.LessonWriter
import com.hereliesaz.geministrator.tour.StepOutcome
import com.hereliesaz.geministrator.tour.TargetRow
import com.hereliesaz.geministrator.tour.TeachingLimits
import com.hereliesaz.geministrator.tour.TourController
import com.hereliesaz.geministrator.tour.TourPhase
import com.hereliesaz.geministrator.tour.TourState
import com.hereliesaz.geministrator.tour.Violation
import com.hereliesaz.geministrator.tour.writeValidatedLesson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** The overlay layer as the director sees it. [TourControllerSurface] is the real one; tests use a fake. */
interface TutorialSurface {
    val state: TourState?
    fun start(runKey: String?, total: Int)
    fun addLesson(lesson: LessonPayload)
    fun setTotal(total: Int)
    fun openCheckIn(line: String)
    fun replace(state: TourState)
    fun onReply(sink: ((CheckInReply) -> Unit)?)
}

object TourControllerSurface : TutorialSurface {
    override val state: TourState? get() = TourController.state
    override fun start(runKey: String?, total: Int) = TourController.start(runKey, total)
    override fun addLesson(lesson: LessonPayload) = TourController.addLesson(lesson)
    override fun setTotal(total: Int) = TourController.setTotal(total)
    override fun openCheckIn(line: String) = TourController.openCheckIn(line)
    override fun replace(state: TourState) = TourController.replace(state)
    override fun onReply(sink: ((CheckInReply) -> Unit)?) {
        TourController.checkInSink = sink
    }
}

/** A creature's voice: the same model, with that creature's generic role text in front (see queen/DisputeWiring.kt). */
class TutorialVoice(val name: String, val model: QueenModel)

/** How a tutorial run ended. Nothing here is a lesson; the app turns it into a plain sentence with a link. */
sealed interface TutorialOutcome {
    /**
     * The keeper reached the end, or hid the layer. [written] lessons were generated, [dropped] steps could not be made valid.
     * [reached] is the judgement of [TutorialReach] from the keeper's check-in answers and what the app saw; it is never asked of them.
     */
    data class Ended(val written: Int, val dropped: Int, val finished: Boolean, val reached: Boolean = false) : TutorialOutcome

    /** No plan passed the rules in the allowed rounds. There is no canned fallback. */
    data class PlanFailed(val violations: List<Violation>) : TutorialOutcome

    /** A model could not be reached. [written] lessons exist and stay usable. */
    data class SourceFailed(val written: Int, val reason: String) : TutorialOutcome
}

/**
 * Runs one tutorial: the Planner writes the plan, the Reviewer (and the Antagonist when she is staffed) turn it back
 * until the rules and the readers are satisfied, then a writer makes each lesson, one ahead of the keeper, checked by
 * the [com.hereliesaz.geministrator.tour.LessonValidator] with bounded repair, and hands it to the overlay. Check-ins
 * and the comfort record steer the pace. Nothing is prewritten: every line the keeper reads came from a model this run,
 * and nothing is done for the keeper.
 */
class TutorialDirector(
    private val planner: QueenModel,
    private val reviewers: List<TutorialVoice>,
    private val writer: QueenModel,
    private val rows: List<TargetRow>,
    private val whereOf: (String) -> String,
    private val surface: TutorialSurface,
    private val facts: List<String> = emptyList(),
    private val loadComfort: () -> ComfortRecord = { ComfortRecord() },
    private val saveComfort: (ComfortRecord) -> Unit = {},
    private val nowMillis: () -> Long = { 0L },
    /** How much the queen says in Messages (her pacing); read each time she decides. */
    private val level: () -> QueenLevel = { QueenLevel.Quiet },
    /** Where her lines to the keeper go (Messages). Each is a string, never model text. */
    private val say: (TutorialSay) -> Unit = {},
    /**
     * The chapters that play at once, before the swarm has planned anything: fixed, so the tour starts the moment the keeper feeds
     * the goal, and the keeper plays them while the plan is written. The swarm's own chapters follow. Empty: the tour waits for the plan.
     */
    private val opening: List<LessonPayload> = emptyList(),
    /** The chapter that ends the tour, added once every chapter the swarm planned has been written. */
    private val closingChapters: List<LessonPayload> = emptyList(),
    private val tick: suspend () -> Unit = { delay(POLL_MILLIS) },
) {
    suspend fun run(goal: String, scope: TutorialScope, runKey: String?): TutorialOutcome {
        val head = opening.size
        if (head > 0) {
            surface.start(runKey, head)
            opening.forEach(surface::addLesson)
        }
        var comfort = loadComfort()
        val shaky = comfort.shakyIds()
        val table = TeachingPrompts.targetTable(rows, whereOf)
        val label = rows.associate { it.id to it.label }

        // 1. The plan: planner, then the rules, then the readers; bounded.
        var problems: List<Violation> = emptyList()
        var objections: List<String> = emptyList()
        var plan: List<TeachingStep>? = null
        var lastViolations: List<Violation> = emptyList()
        try {
            for (round in 0..TeachingLimits.LESSON_REPAIRS) {
                val steps = parseTeachingPlan(
                    planner.answer(
                        TeachingPrompts.plannerTask(goal, table, facts, shaky.map { label[it] ?: it }, comfort.comfortableIds().map { label[it] ?: it }, scope, problems, objections),
                    ),
                )
                val floor = TeachingPlanFloor.check(steps, rows, scope)
                if (floor.isNotEmpty()) {
                    problems = floor
                    objections = emptyList()
                    lastViolations = floor
                    continue
                }
                plan = steps
                problems = emptyList()
                val found = mutableListOf<String>()
                for (voice in reviewers) {
                    when (val verdict = parsePlanVerdict(voice.model.answer(TeachingPrompts.reviewerTask(goal, steps, rows)))) {
                        is PlanVerdict.Revise -> found += verdict.findings.map { "${voice.name}: $it" }
                        PlanVerdict.Approve, PlanVerdict.Unreadable -> Unit
                    }
                }
                if (found.isEmpty()) break
                // Turned back: another round if one is left, else the plan the rules passed stands (readers are advisory at the end).
                objections = found
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TutorialOutcome.SourceFailed(0, e.message.orEmpty().take(200))
        }
        val steps = plan ?: return TutorialOutcome.PlanFailed(lastViolations)

        // 2. The lessons, one ahead of the keeper. The queen follows the keeper the whole way: what they open, tap and answer, how long
        // they stay and which way they go are signals that cost no model call ([tutorialAttention]); a model is only used to write lessons,
        // through the paced, spend-capped model she was given, so there is no fixed count of calls.
        if (head == 0) surface.start(runKey, steps.size) else surface.setTotal(head + steps.size)
        val replies = Channel<CheckInReply>(Channel.UNLIMITED)
        surface.onReply { replies.trySend(it) }
        val lessons = mutableListOf<LessonPayload>().apply { addAll(opening) }
        var total = head + steps.size
        val tail = closingChapters.size
        var closingAdded = tail == 0
        var dropped = 0
        var record = CheckInRecord()
        var checkInAbout: Int? = null
        var sortOfCount = 0
        // Every feeling the keeper stated, for [TutorialReach]; and the closing question ("do you feel you know what you are doing now").
        val answers = mutableListOf<CheckInReply>()
        var closing = false
        var closingAnswered = false
        var closingSince = 0L
        // What she watches: where the keeper is, how long they have stayed, how often they went back, where she last asked or updated.
        var lastIndex = surface.state?.index ?: 0
        var stepSince = nowMillis()
        var backs = 0
        var askedStep: Int? = null
        var updatedAt = lastIndex
        try {
            while (true) {
                val st = surface.state ?: return TutorialOutcome.Ended(lessons.size, dropped, finished = false)
                if (st.phase == TourPhase.Dismissed) return TutorialOutcome.Ended(lessons.size, dropped, finished = false)

                // Answers to an open check-in: plain transitions, no model.
                var repliedNow = false
                var slowedNow = false
                while (true) {
                    val reply = replies.tryReceive().getOrNull() ?: break
                    val about = checkInAbout ?: continue
                    val lesson = lessons.getOrNull(about) ?: continue
                    val current = surface.state ?: break
                    repliedNow = true
                    answers += reply
                    if (closing && reply != CheckInReply.ShowMe) {
                        // The closing question: the keeper's answer is the evidence; nothing more is asked or decided for them.
                        comfort = comfort.mark(lesson.target, if (reply == CheckInReply.Yes) Comfort.Comfortable else Comfort.Shaky, nowMillis())
                        saveComfort(comfort)
                        surface.replace(current.copy(checkInLine = null))
                        closing = false
                        closingAnswered = true
                        checkInAbout = null
                        continue
                    }
                    if (closing) {
                        // "Show me on the real thing" at the end: back to the last step, and the closing question comes again afterwards.
                        closing = false
                        closingAnswered = false
                    }
                    val result = CheckInTransitions.handle(current, record, reply, lesson, about, sortOfCount)
                    surface.replace(result.state)
                    record = result.record
                    sortOfCount = if (reply == CheckInReply.SortOf) sortOfCount + 1 else 0
                    if (reply == CheckInReply.Lost) slowedNow = true
                    result.comfort?.let {
                        comfort = comfort.mark(lesson.target, it, nowMillis())
                        saveComfort(comfort)
                    }
                    if (reply != CheckInReply.SortOf) checkInAbout = null
                }

                val now = surface.state ?: return TutorialOutcome.Ended(lessons.size, dropped, finished = false)
                // Where the keeper is: a step back is a signal; a reply moving them (Show me) is a request, not a signal.
                if (now.index < lastIndex && !repliedNow) backs += 1
                if (now.index != lastIndex || now.phase != TourPhase.Showing) {
                    stepSince = nowMillis()
                    lastIndex = now.index
                }

                if (now.finished && closingAdded && lessons.size + dropped >= head + steps.size + tail) {
                    val last = lessons.lastOrNull()
                    if (last != null && !closingAnswered) {
                        if (!closing) {
                            // The last lesson's own check-in line asks whether the keeper feels they know what they are doing now.
                            closing = true
                            closingSince = nowMillis()
                            checkInAbout = lessons.lastIndex
                            sortOfCount = 0
                            surface.openCheckIn(last.checkIn)
                        } else if (nowMillis() - closingSince >= TeachingLimits.CHECKIN_QUIET_TIMEOUT_MILLIS) {
                            // Unanswered, it ends quietly; what she knows is then what the app saw and the earlier answers.
                            surface.replace(now.copy(checkInLine = null))
                            closing = false
                            closingAnswered = true
                            checkInAbout = null
                        }
                        if (!closingAnswered) {
                            tick()
                            continue
                        }
                    }
                    val reached = TutorialReach.reached((0 until now.stepTotal).map { now.outcomes[it] }, answers)
                    say(TutorialSay.Finished(reached))
                    return TutorialOutcome.Ended(lessons.size, dropped, finished = true, reached = reached)
                }
                // Every step was dropped: nothing to show, and nothing is invented in its place.
                if (total == 0 && closingAdded && lessons.size + dropped >= head + steps.size + tail) return TutorialOutcome.Ended(lessons.size, dropped, finished = false)

                // The queen's attention: say nothing, update, ask, or slow down, from signals alone (pure; no model call).
                val shownIndex = now.index - 1
                var boundaryDue = false
                if (now.checkInLine == null && checkInAbout == null && shownIndex in lessons.indices) {
                    val row = rows.firstOrNull { it.id == lessons[shownIndex].target }
                    val harder = CheckInPolicy.isHarder(row?.needs?.size ?: 0, now.outcomes[shownIndex - 1])
                    val shown = now.outcomes[shownIndex] != StepOutcome.Skipped
                    boundaryDue = CheckInPolicy.shouldAsk(record, shownIndex + 1, total, shown, harder)
                }
                val signals = AttentionSignals(
                    stepsPassed = now.index.coerceAtMost(now.stepTotal),
                    checkInOpen = now.checkInLine != null || checkInAbout != null,
                    unhandledAnswer = if (slowedNow) CheckInReply.Lost else null,
                    missedCheckpoint = now.current != null && now.outcomes[now.index] == StepOutcome.Missed,
                    backNavigations = backs,
                    dwellMillis = if (now.phase == TourPhase.Showing && now.current != null) nowMillis() - stepSince else 0L,
                    askedForThisStep = askedStep == now.index,
                    boundaryDue = boundaryDue,
                    stepsSinceUpdate = now.index - updatedAt,
                    level = level(),
                )
                when (tutorialAttention(signals)) {
                    TutorialAttention.SlowDown -> say(TutorialSay.Slower)
                    TutorialAttention.Ask -> {
                        // A lost-looking keeper is asked about the step they are on; otherwise about the one they just left.
                        val about = if (keeperMayBeLost(signals) && now.current != null) now.index else shownIndex
                        if (about in lessons.indices) {
                            record = record.asked(about + 1)
                            checkInAbout = about
                            sortOfCount = 0
                            backs = 0
                            askedStep = now.index
                            surface.openCheckIn(lessons[about].checkIn)
                        }
                    }
                    TutorialAttention.Update -> {
                        updatedAt = now.index
                        say(TutorialSay.Progress(now.index.coerceAtMost(now.stepTotal), now.stepTotal))
                    }
                    TutorialAttention.Nothing -> Unit
                }

                // The next lesson, only while the keeper is within one of it.
                val next = lessons.size + dropped - head
                // Every planned chapter is written: the closing one follows, once.
                if (!closingAdded && next >= steps.size) {
                    closingAdded = true
                    total += tail
                    surface.setTotal(total)
                    closingChapters.forEach { chapter -> lessons += chapter; surface.addLesson(chapter) }
                    continue
                }
                if (next < steps.size && now.index >= lessons.size - 1) {
                    val step = steps[next]
                    val row = rows.first { it.id == step.target }
                    val ctx = LessonContext(
                        plannedTarget = step.target,
                        targets = rows,
                        uiWords = emptySet(),
                        introduced = steps.take(next).map { it.target }.toSet(),
                        firstLesson = next == 0,
                    )
                    val smaller = record.smallerRemaining > 0
                    val previous = lessons.lastOrNull()?.sentence
                    val outcome = writeValidatedLesson(
                        ctx,
                        LessonWriter { violations ->
                            writer.answer(
                                TeachingPrompts.lessonTask(
                                    step, row, whereOf(row.id), steps.take(next).mapNotNull { label[it.target] }, goal,
                                    first = next == 0, smaller = smaller, shakyBefore = step.target in shaky,
                                    previousSentence = previous, problems = violations, last = next == steps.size - 1,
                                ),
                            )
                        },
                    )
                    when (outcome) {
                        is LessonWriteOutcome.Accepted -> {
                            lessons += outcome.payload
                            record = record.lessonWritten()
                            surface.addLesson(outcome.payload)
                        }
                        is LessonWriteOutcome.Exhausted -> {
                            dropped += 1
                            total -= 1
                            surface.setTotal(total)
                        }
                    }
                    continue
                }
                tick()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            surface.setTotal(lessons.size)
            return TutorialOutcome.SourceFailed(lessons.size, e.message.orEmpty().take(200))
        } finally {
            surface.onReply(null)
        }
    }

    companion object {
        const val POLL_MILLIS = 400L
    }
}
