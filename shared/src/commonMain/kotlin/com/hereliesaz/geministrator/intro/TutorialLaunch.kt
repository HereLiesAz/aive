package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.DurableUiState
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.queen.voiceModel
import com.hereliesaz.geministrator.tour.ComfortStore
import com.hereliesaz.geministrator.tour.TargetRow
import com.hereliesaz.geministrator.tour.TourController
import com.hereliesaz.geministrator.tour.TourTargetId
import com.hereliesaz.geministrator.tour.TourTargetRegistry
import com.hereliesaz.geministrator.tour.tourTargetRows

/**
 * Where a first-run "teach me" goal meets the engine: it builds the generic creatures' voices from the keeper's roles (the
 * queen's model, pinned or automatic, speaking as each role), the registry rows that exist on this layout, and runs the
 * [TutorialDirector]. The roles carry no tutorial wording; the tutorial arrives as task input ([TeachingPrompts]).
 */
internal object TutorialLaunch {
    /** A generic writing role: it makes the piece it is asked for, in the form and limits it is given. */
    private const val WRITER_NAME = "Writer"
    private const val WRITER_TEXT = "Write the one piece you are asked for, exactly in the form requested, within the stated limits, using only what you are given."

    private fun voice(base: QueenModel, roles: Collection<RoleDefinition>, default: RoleDefinition): TutorialVoice {
        val role = roles.firstOrNull { it.id == default.id && it.enabled } ?: default
        return TutorialVoice(role.name, voiceModel(base, role.name, role.instructions))
    }

    /** The Reviewer and, when the keeper has not switched her off, the Antagonist. */
    fun reviewers(base: QueenModel, roles: Collection<RoleDefinition>): List<TutorialVoice> = buildList {
        add(voice(base, roles, BuiltInRoles.Reviewer))
        // The Antagonist reads the plan too, unless the keeper has switched her off in the Roster.
        val mine = roles.firstOrNull { it.id == BuiltInRoles.Antagonist.id }
        if (mine == null || mine.enabled) add(voice(base, roles, BuiltInRoles.Antagonist))
    }

    fun planner(base: QueenModel, roles: Collection<RoleDefinition>): QueenModel = voice(base, roles, BuiltInRoles.Planner).model

    fun writer(base: QueenModel): QueenModel = voiceModel(base, WRITER_NAME, WRITER_TEXT)

    /** The registry rows with a label, without the rail's entries when no rail is on screen (a phone layout). */
    fun rows(labels: Map<String, String>): List<TargetRow> {
        val wide = TourTargetRegistry.boundsOf(TourTargetId.RAIL_HIVE) != null
        // The hive itself is taught by the opening chapters; it is not a control the planner can point a tap at.
        return tourTargetRows { labels[it] }.filter { it.id != TourTargetId.HIVE_CREATURES.name && (wide || !it.id.startsWith("RAIL_")) }
    }

    /** Closed, non-identifying facts handed to the planner. Never a name, a goal, a path or a key. */
    fun facts(rows: List<TargetRow>, opened: Boolean = false): List<String> = listOfNotNull(
        if (rows.any { it.id.startsWith("RAIL_") }) "The window is wide: the left rail is on screen." else "The window is a phone layout: there is no left rail.",
        if (opened) "The first chapters already showed the keeper the hive's creatures hatching, work changing hands, a creature's Work tab and the queen's Messages: go on from there and do not repeat them." else null,
    )

    /** Only the fixed opening, for a keeper with no Source yet: it needs no model, so the tour still starts at once. */
    suspend fun playOpening(): Boolean {
        val chapters = tutorialOpening()
        TourController.stop()
        TourController.start(runKey = null, total = chapters.size)
        chapters.forEach(TourController::addLesson)
        return true
    }

    suspend fun run(
        goal: String,
        base: QueenModel,
        roles: Collection<RoleDefinition>,
        labels: Map<String, String>,
        nowMillis: () -> Long,
        /** How much the queen says in Messages, read each time she decides. */
        level: () -> com.hereliesaz.geministrator.queen.QueenLevel = { com.hereliesaz.geministrator.queen.QueenLevel.Quiet },
        /** Where her lines to the keeper go (Messages). */
        say: (com.hereliesaz.geministrator.queen.TutorialSay) -> Unit = {},
        /** The chapters that play before the plan exists ([tutorialOpening]); empty keeps the tour waiting for the plan. */
        opening: List<com.hereliesaz.geministrator.tour.LessonPayload> = emptyList(),
    ): TutorialOutcome? {
        val scope = TutorialRouter.classify(goal) ?: return null
        val rows = rows(labels)
        val store = ComfortStore(DurableUiState.store, TourTargetId.registeredIds)
        val places = TourTargetId.entries.associate { it.name to it.place }
        val director = TutorialDirector(
            planner = planner(base, roles),
            reviewers = reviewers(base, roles),
            writer = writer(base),
            rows = rows,
            whereOf = { places[it].orEmpty() },
            surface = TourControllerSurface,
            facts = facts(rows, opened = opening.isNotEmpty()),
            opening = opening,
            loadComfort = { store.load() },
            saveComfort = { store.save(it) },
            nowMillis = nowMillis,
            level = level,
            say = say,
        )
        // Whatever tour was showing gives way to this run's.
        TourController.stop()
        return director.run(goal, scope, runKey = "tutorial-${nowMillis()}")
    }
}
