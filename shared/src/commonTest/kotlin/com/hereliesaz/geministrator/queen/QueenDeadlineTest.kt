package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.TaskClock
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TimeLimit
import com.hereliesaz.geministrator.workflow.slowCreatureAllowedMinutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueenDeadlineTest {
    private val roles = listOf(engineerRole)
    private val clock = TaskClock(
        attempt = 1, startedAtEpochMillis = 0, autoMillis = 8 * 60_000L, autoHardMillis = 16 * 60_000L,
        lastSeenEpochMillis = 0, progressAtEpochMillis = 0, softAtEpochMillis = 8 * 60_000L,
    )
    private val definition = qDefinition(qTask("a"), qTask("b"))
    private fun slowRun() = qRun(
        qTaskRun("a", TaskRunStatus.Running).copy(clock = clock),
        qTaskRun("b", TaskRunStatus.Running),
    )

    @Test fun quietShowsNoSoftNote() {
        assertTrue(queenUpdate(definition, slowRun(), roles, QueenLevel.Quiet).none { it.voice == QueenVoice.WatchSlow })
    }

    @Test fun helpfulShowsSoftNoteLinkedToCreatureWork() {
        val note = queenUpdate(definition, slowRun(), roles, QueenLevel.Helpful).single { it.voice == QueenVoice.WatchSlow }
        assertEquals(QueenLink.Creature("a", CreatureTab.Work), note.link)
        assertEquals(listOf("1", "Task a", "8"), note.args)
        assertFalse(note.countsToBadge)
        assertEquals(QueenRelay.Watch, note.relay)
    }

    @Test fun keeperExtensionChangesTheNoteAndOffRemovesIt() {
        val task = qTask("a")
        val run = slowRun().taskRuns.getValue(task.id)
        assertEquals(8, slowCreatureAllowedMinutes(task, run))
        assertEquals(60, slowCreatureAllowedMinutes(task.copy(timeLimit = TimeLimit.Minutes(60)), run))
        assertNull(slowCreatureAllowedMinutes(task.copy(timeLimit = TimeLimit.Off), run))
    }

    @Test fun aCreatureWaitingOnTheKeeperIsNotSlow() {
        val waiting = qTaskRun("a", TaskRunStatus.AwaitingApproval).copy(clock = clock)
        assertNull(slowCreatureAllowedMinutes(qTask("a"), waiting))
    }

    @Test fun staleClockOfAnotherAttemptIsIgnored() {
        val retry = qTaskRun("a", TaskRunStatus.Running, attempt = 2).copy(clock = clock)
        assertNull(slowCreatureAllowedMinutes(qTask("a"), retry))
    }

    @Test fun helpfulSaysWhenAStepWasStoppedForTakingTooLongAndQuietDoesNot() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 2).copy(timedOutAttempts = 1), qTaskRun("b", TaskRunStatus.Running))
        val note = queenUpdate(definition, run, roles, QueenLevel.Helpful).single { it.voice == QueenVoice.WatchTimedOut }
        assertEquals(listOf("1", "Task a", "1"), note.args)
        assertTrue(queenUpdate(definition, run, roles, QueenLevel.Quiet).none { it.voice == QueenVoice.WatchTimedOut })
    }

    @Test fun anEscalatedStepGetsNoSecondNote() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Escalated).copy(timedOutAttempts = 2), qTaskRun("b", TaskRunStatus.Running))
        assertTrue(queenUpdate(definition, run, roles, QueenLevel.Helpful).none { it.voice == QueenVoice.WatchTimedOut })
    }
}
