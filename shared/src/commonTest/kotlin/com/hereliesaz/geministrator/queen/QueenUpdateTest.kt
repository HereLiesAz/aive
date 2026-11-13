package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.inboxItemKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueenUpdateTest {
    private val definition = qDefinition(qTask("a"), qTask("b"), qTask("c"))
    private val roles = listOf(engineerRole)

    @Test
    fun statusNamesDoneOfTotal() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running), qTaskRun("c", TaskRunStatus.Created))
        val status = queenUpdate(definition, run, roles, QueenLevel.Helpful).last()
        assertEquals(QueenVoice.StatusWorkingClear, status.voice)
        assertEquals(listOf("1", "3", "2", "Task b"), status.args)
    }

    @Test
    fun quietSaysOnlyFinish() {
        val running = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running))
        assertTrue(queenUpdate(definition, running, roles, QueenLevel.Quiet).isEmpty())
        val done = qRun(qTaskRun("a", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        assertEquals(listOf(QueenVoice.FinishedAll), queenUpdate(definition, done, roles, QueenLevel.Quiet).map { it.voice })
    }

    @Test
    fun finishedRunSaysCostOnlyWhenKnown() {
        fun finish(cost: Double?, unknown: Boolean) = queenUpdate(
            definition,
            qRun(qTaskRun("a", TaskRunStatus.Completed, cost = cost, costUnknown = unknown, tokens = 5), status = WorkflowRunStatus.Completed),
            roles,
            QueenLevel.Quiet,
        ).single()
        assertEquals(QueenVoice.FinishedAllCost, finish(0.42, false).voice)
        assertEquals(listOf("\$0.42"), finish(0.42, false).args)
        assertEquals(QueenVoice.FinishedAll, finish(0.42, true).voice)
        assertEquals(QueenVoice.FinishedAll, finish(null, false).voice)
    }

    @Test
    fun aFailedRunNamesTheStepThatFailed() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Failed, attempt = 2), status = WorkflowRunStatus.Failed)
        val note = queenUpdate(definition, run, roles, QueenLevel.Quiet).single()
        assertEquals(QueenVoice.FinishedFailed, note.voice)
        assertEquals(listOf("2", "Task b", "2"), note.args)
    }

    @Test
    fun blockedDigestMatchesBadgeCount() {
        val run = qRun(qTaskRun("a", TaskRunStatus.AwaitingApproval), qTaskRun("b", TaskRunStatus.Escalated), qTaskRun("c", TaskRunStatus.Running))
        val digest = queenUpdate(definition, run, roles, QueenLevel.Helpful).single { it.voice == QueenVoice.WaitsMany }
        assertEquals(inboxItemKeys(run).size.toString(), digest.args[0])
        assertEquals(QueenLink.Message(inboxItemKeys(run, "a").single()), digest.link)
        assertNull(waitsDigest(definition, qRun(qTaskRun("a", TaskRunStatus.Running)), ))
    }

    @Test
    fun updatesNeverCountToBadge() {
        val run = qRun(qTaskRun("a", TaskRunStatus.AwaitingApproval), qTaskRun("b", TaskRunStatus.Escalated), qTaskRun("c", TaskRunStatus.Running))
        val notes = queenUpdate(definition, run, roles, QueenLevel.Helpful)
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.none { it.countsToBadge })
        // The badge's own keys are unchanged by what she says.
        assertEquals(2, inboxItemKeys(run).size)
    }

    @Test
    fun anEscalationGetsHerSentenceOnItsOwnRow() {
        val run = qRun(qTaskRun("b", TaskRunStatus.Escalated, attempt = 2))
        val note = queenUpdate(definition, run, roles, QueenLevel.Quiet).single()
        assertEquals(QueenVoice.WaitEscalation, note.voice)
        assertEquals(inboxItemKeys(run, "b").single(), note.attachedTo)
    }

    @Test
    fun aSpendStopGetsHerSentenceAndLinksToTheLimits() {
        val stop = com.hereliesaz.geministrator.domain.WorkflowSpendPause(
            kind = com.hereliesaz.geministrator.domain.SpendCapKind.RunCost, reason = "cap", capUsd = 1.0, spentUsd = 1.02,
            taskSnapshots = emptyList(), pausedAtEpochMillis = 4,
        )
        val run = qRun(qTaskRun("a", TaskRunStatus.Blocked)).copy(spendPause = stop)
        val note = queenUpdate(definition, run, roles, QueenLevel.Quiet).single()
        assertEquals(QueenVoice.AllowanceStopRun, note.voice)
        assertEquals(listOf("\$1.00", "\$1.02"), note.args)
        assertEquals(QueenLink.Limits, note.link)
        assertEquals(inboxItemKeys(run).single(), note.attachedTo)
    }

    @Test
    fun withoutANannyTheQueenSaysINoticed() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 3))
        val note = queenUpdate(definition, run, roles, QueenLevel.Quiet).single()
        assertEquals(QueenRelay.Watch, note.relay)
        assertFalse(note.attachedTo != null)
    }

    @Test
    fun identicalStateGivesIdenticalNotesAndIds() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 3))
        assertEquals(queenUpdate(definition, run, roles, QueenLevel.Helpful), queenUpdate(definition, run, roles, QueenLevel.Helpful))
    }

    @Test
    fun levelParsesToQuietWhenUnknown() {
        assertEquals(QueenLevel.Quiet, QueenLevel.parse(null))
        assertEquals(QueenLevel.Quiet, QueenLevel.parse("Loud"))
        assertEquals(QueenLevel.Helpful, QueenLevel.parse("Helpful"))
    }
}
