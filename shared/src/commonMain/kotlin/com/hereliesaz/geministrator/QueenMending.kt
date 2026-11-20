package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.intro.TutorialRouter
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import kotlinx.coroutines.CancellationException

/** The most attempts a lesson gets before the queen stops mending it and leaves the decision to the keeper. */
internal const val MAX_MENDED_ATTEMPT = 6

/**
 * The queen mends a swarm that teaches the app while it runs (the keeper's ruling: she may rebuild, rewrite or redo what does not work,
 * with a different approach, and is not limited to suggesting). A lesson that could not be done waits as an escalation
 * ([com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory.teachingEscalation]); here she asks the planner to repair the
 * remainder (completed steps stay as they are) and the run goes on. This is limited to a swarm for a goal to be taught the app, whose
 * steps make text and change nothing outside the app; every other swarm keeps asking the keeper. Each failed attempt is mended once.
 * Collects until cancelled.
 */
internal suspend fun ApplicationRuntime.mendTeachingSwarms(planner: OrchestrationAgentRuntime) {
    val handled = mutableSetOf<String>()
    state.collect { current ->
        val live = current as? ApplicationRuntimeState.Live ?: return@collect
        val run = live.presentation.run
        if (!TutorialRouter.isTutorialGoal(run.objective)) return@collect
        for ((taskId, taskRun) in run.taskRuns) {
            if (taskRun.status != TaskRunStatus.Escalated || taskRun.attempt >= MAX_MENDED_ATTEMPT) continue
            if (!handled.add("${run.id.value}:${taskId.value}:${taskRun.attempt}")) continue
            try {
                repairFailureEscalation(taskId, planner, note = "The queen rebuilt the plan herself (a swarm that teaches the app)")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Left escalated: the keeper's own Retry and repair are still there.
            }
        }
    }
}
