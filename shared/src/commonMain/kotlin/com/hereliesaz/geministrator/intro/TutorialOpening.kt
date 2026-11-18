package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.tour_open_hatch_sentence
import com.hereliesaz.geministrator.resources.tour_open_hatch_explain
import com.hereliesaz.geministrator.resources.tour_open_hatch_missed
import com.hereliesaz.geministrator.resources.tour_open_hatch_checkin
import com.hereliesaz.geministrator.resources.tour_open_hatch_another
import com.hereliesaz.geministrator.resources.tour_open_hatch_small1
import com.hereliesaz.geministrator.resources.tour_open_hatch_small2
import com.hereliesaz.geministrator.resources.tour_open_handoff_sentence
import com.hereliesaz.geministrator.resources.tour_open_handoff_explain
import com.hereliesaz.geministrator.resources.tour_open_handoff_missed
import com.hereliesaz.geministrator.resources.tour_open_handoff_checkin
import com.hereliesaz.geministrator.resources.tour_open_handoff_another
import com.hereliesaz.geministrator.resources.tour_open_handoff_small1
import com.hereliesaz.geministrator.resources.tour_open_handoff_small2
import com.hereliesaz.geministrator.resources.tour_open_work_sentence
import com.hereliesaz.geministrator.resources.tour_open_work_explain
import com.hereliesaz.geministrator.resources.tour_open_work_missed
import com.hereliesaz.geministrator.resources.tour_open_work_checkin
import com.hereliesaz.geministrator.resources.tour_open_work_another
import com.hereliesaz.geministrator.resources.tour_open_work_small1
import com.hereliesaz.geministrator.resources.tour_open_work_small2
import com.hereliesaz.geministrator.resources.tour_open_queen_sentence
import com.hereliesaz.geministrator.resources.tour_open_queen_explain
import com.hereliesaz.geministrator.resources.tour_open_queen_missed
import com.hereliesaz.geministrator.resources.tour_open_queen_checkin
import com.hereliesaz.geministrator.resources.tour_open_queen_another
import com.hereliesaz.geministrator.resources.tour_open_queen_small1
import com.hereliesaz.geministrator.resources.tour_open_queen_small2
import com.hereliesaz.geministrator.tour.ExpectKind
import com.hereliesaz.geministrator.tour.LessonPayload
import com.hereliesaz.geministrator.tour.TourExpect
import com.hereliesaz.geministrator.tour.TourTargetId
import org.jetbrains.compose.resources.getString

/**
 * The tour's opening chapters: fixed in order and in what they point at, so the tour starts the instant the goal is fed, with
 * the words in the keeper's language. They are about the thing on screen, the hive the keeper just fed: it hatching, work changing
 * hands, a creature's Work, the queen's Messages. Each waits on what the swarm does ([ExpectKind.RunHatched] and the rest) or on
 * the keeper opening something, so the lesson is the swarm at work, not a card about it. The swarm's own chapters follow.
 */
internal suspend fun tutorialOpening(): List<LessonPayload> = listOf(
    LessonPayload(
        target = TourTargetId.HIVE_CREATURES.name,
        sentence = getString(Res.string.tour_open_hatch_sentence),
        expect = TourExpect(ExpectKind.RunHatched, null),
        explain = getString(Res.string.tour_open_hatch_explain),
        ifMissed = getString(Res.string.tour_open_hatch_missed),
        checkIn = getString(Res.string.tour_open_hatch_checkin),
        anotherWay = getString(Res.string.tour_open_hatch_another),
        smaller = listOf(getString(Res.string.tour_open_hatch_small1), getString(Res.string.tour_open_hatch_small2)),
    ),
    LessonPayload(
        target = TourTargetId.HIVE_CREATURES.name,
        sentence = getString(Res.string.tour_open_handoff_sentence),
        expect = TourExpect(ExpectKind.RunHandedOff, null),
        explain = getString(Res.string.tour_open_handoff_explain),
        ifMissed = getString(Res.string.tour_open_handoff_missed),
        checkIn = getString(Res.string.tour_open_handoff_checkin),
        anotherWay = getString(Res.string.tour_open_handoff_another),
        smaller = listOf(getString(Res.string.tour_open_handoff_small1), getString(Res.string.tour_open_handoff_small2)),
    ),
    LessonPayload(
        target = TourTargetId.CREATURE_TAB_WORK.name,
        sentence = getString(Res.string.tour_open_work_sentence),
        expect = TourExpect(ExpectKind.Opened, TourTargetId.CREATURE_TAB_WORK.name),
        explain = getString(Res.string.tour_open_work_explain),
        ifMissed = getString(Res.string.tour_open_work_missed),
        checkIn = getString(Res.string.tour_open_work_checkin),
        anotherWay = getString(Res.string.tour_open_work_another),
        smaller = listOf(getString(Res.string.tour_open_work_small1), getString(Res.string.tour_open_work_small2)),
    ),
    LessonPayload(
        target = TourTargetId.MESSAGES_BUTTON.name,
        sentence = getString(Res.string.tour_open_queen_sentence),
        expect = TourExpect(ExpectKind.Tapped, TourTargetId.MESSAGES_BUTTON.name),
        explain = getString(Res.string.tour_open_queen_explain),
        ifMissed = getString(Res.string.tour_open_queen_missed),
        checkIn = getString(Res.string.tour_open_queen_checkin),
        anotherWay = getString(Res.string.tour_open_queen_another),
        smaller = listOf(getString(Res.string.tour_open_queen_small1), getString(Res.string.tour_open_queen_small2)),
    ),
)
