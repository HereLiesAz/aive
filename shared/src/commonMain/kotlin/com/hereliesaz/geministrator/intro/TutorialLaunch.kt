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
        return tourTargetRows { labels[it] }.filter { wide || !it.id.startsWith("RAIL_") }
    }

    /** Closed, non-identifying facts handed to the planner. Never a name, a goal, a path or a key. */
    fun facts(rows: List<TargetRow>): List<String> = listOf(
        if (rows.any { it.id.startsWith("RAIL_") }) "The window is wide: the left rail is on screen." else "The window is a phone layout: there is no left rail.",
    )

    suspend fun run(
        goal: String,
        base: QueenModel,
        roles: Collection<RoleDefinition>,
        labels: Map<String, String>,
        nowMillis: () -> Long,
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
            facts = facts(rows),
            loadComfort = { store.load() },
            saveComfort = { store.save(it) },
            nowMillis = nowMillis,
        )
        // Whatever tour was showing gives way to this run's.
        TourController.stop()
        return director.run(goal, scope, runKey = "tutorial-${nowMillis()}")
    }
}
