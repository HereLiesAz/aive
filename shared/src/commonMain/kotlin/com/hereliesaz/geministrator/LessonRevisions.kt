package com.hereliesaz.geministrator

import androidx.compose.runtime.snapshotFlow
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.intro.TutorialRouter
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.tour.ComfortRecord
import com.hereliesaz.geministrator.tour.LessonRevision
import com.hereliesaz.geministrator.tour.LessonRevisionDecision
import com.hereliesaz.geministrator.tour.TourController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The queen revises lessons not yet played (the keeper's ruling: she may reconstruct, rewrite, redo or take another approach, and is not
 * limited to the first plan). After each reply of the keeper or step they finish, [LessonRevision.plan] decides from their feelings and the
 * run's events: keep, go smaller, pass over what they are comfortable with, or rebuild a stopped step through the same mending path as
 * [mendTeachingSwarms] ([repairFailureEscalation]). She never rewrites the whole plan, never touches a lesson already played, revises a
 * lesson once, and stops after [LessonRevision.MAX_PER_RUN] revisions for a run; everything the planner writes still passes the usual
 * checks. Only a swarm that teaches the app. Collects until cancelled.
 */
internal suspend fun ApplicationRuntime.reviseLessons(planner: OrchestrationAgentRuntime, comfort: () -> ComfortRecord) {
    var runKey: String? = null
    var used = 0
    val rebuilt = mutableSetOf<String>()
    snapshotFlow { TourController.state?.let { Triple(it.index, it.replies.size, it.lessons.size) } }
        .distinctUntilChanged()
        .collect {
            val tour = TourController.state ?: return@collect
            val live = state.value as? ApplicationRuntimeState.Live ?: return@collect
            val run = live.presentation.run
            if (!TutorialRouter.isTutorialGoal(run.objective)) return@collect
            if (runKey != run.id.value) {
                runKey = run.id.value
                used = 0
                rebuilt.clear()
            }
            val stalled = run.taskRuns.filterValues { it.status == TaskRunStatus.Escalated }.keys.mapTo(mutableSetOf()) { it.value }
            val remaining = LessonRevision.remainingOf(tour, stalled, rebuilt)
            val decision = LessonRevision.plan(tour.replies, tour.outcomes, remaining, comfort().comfortableIds().toSet(), used)
            when (decision) {
                LessonRevisionDecision.Keep -> Unit
                is LessonRevisionDecision.Redo -> {
                    used += 1
                    rebuilt += decision.stepId
                    try {
                        repairFailureEscalation(
                            com.hereliesaz.geministrator.domain.TaskDefinitionId(decision.stepId),
                            planner,
                            note = decision.reason.note,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Left as it was: the usual retry and the keeper's own repair are still there.
                    }
                }
                else -> LessonRevision.actionFor(decision, remaining)?.let {
                    used += 1
                    TourController.dispatch(it)
                }
            }
        }
}
