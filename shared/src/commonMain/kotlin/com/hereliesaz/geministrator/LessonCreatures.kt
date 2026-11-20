package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.intro.TutorialRouter
import com.hereliesaz.geministrator.tour.LessonArtifactBridge
import com.hereliesaz.geministrator.tour.TargetRow
import com.hereliesaz.geministrator.tour.TourController
import com.hereliesaz.geministrator.workflow.isTerminal

/**
 * The swarm's own lesson creatures hand in the overlays (tour/LessonArtifactBridge.kt). In a run for a goal to be taught the app, a
 * completed creature whose output ends with a lesson payload that passes the lesson rules is played by the tour layer, voiced by that
 * creature (its name is on the card). A payload that fails the rules never gets here: its task was turned back with the rules it broke
 * as the reason, and the usual retry and the queen's mending ([mendTeachingSwarms]) redo it. Nothing is played for the keeper, and a
 * tour that is not running waits (the fixed opening starts it at once). Collects until cancelled.
 */
internal suspend fun ApplicationRuntime.playLessonCreatures(rowsOf: () -> List<TargetRow>) {
    val played = mutableSetOf<String>()
    state.collect { current ->
        val live = current as? ApplicationRuntimeState.Live ?: return@collect
        val run = live.presentation.run
        if (!TutorialRouter.isTutorialGoal(run.objective)) return@collect
        LessonArtifactBridge.writing = !run.status.isTerminal()
        if (TourController.state == null) return@collect
        val lessons = LessonArtifactBridge.newLessons(run, rowsOf(), played)
        if (lessons.isEmpty()) return@collect
        lessons.forEach(TourController::addLesson)
        LessonArtifactBridge.supplied = true
    }
}
