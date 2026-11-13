package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TemplateRef
import com.hereliesaz.geministrator.queen.QueenLink
import com.hereliesaz.geministrator.queen.QueenVoice
import com.hereliesaz.geministrator.queen.qDefinition
import com.hereliesaz.geministrator.queen.qRun
import com.hereliesaz.geministrator.queen.qTask
import com.hereliesaz.geministrator.queen.qTaskRun
import com.hereliesaz.geministrator.queen.queenUpdate
import com.hereliesaz.geministrator.queen.QueenLevel
import com.hereliesaz.geministrator.queen.stepAllowanceNotes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The token allowance of a step made from a template: counted from reported tokens, never from the creature's words. */
class StepAllowanceTest {
    private fun task(allowance: Long?, startingPoint: Boolean = false) =
        qTask("s").copy(contract = TaskContract(TemplateRef("write-document", 1), tokenAllowance = allowance, startingPoint = startingPoint))

    @Test
    fun aStepIsOverItsAllowanceOnlyWhenItUsedAllOfIt() {
        assertFalse(stepAllowanceSpent(task(1_000), qTaskRun("s", TaskRunStatus.Running, tokens = 999)))
        assertTrue(stepAllowanceSpent(task(1_000), qTaskRun("s", TaskRunStatus.Running, tokens = 1_000)))
        assertTrue(stepAllowanceSpent(task(1_000), qTaskRun("s", TaskRunStatus.Running, tokens = 5_000)))
    }

    @Test
    fun noContractNoAllowanceANonPositiveOneOrAStartingPointHasNothingEnforced() {
        val used = qTaskRun("s", TaskRunStatus.Running, tokens = 1_000_000)
        assertFalse(stepAllowanceSpent(qTask("s"), used))
        assertFalse(stepAllowanceSpent(task(null), used))
        assertFalse(stepAllowanceSpent(task(0), used))
        assertFalse(stepAllowanceSpent(task(10, startingPoint = true), used))
        assertFalse(stepAllowanceSpent(null, used))
        assertNull(stepTokenAllowance(task(10, startingPoint = true)))
        assertEquals(10L, stepTokenAllowance(task(10)))
    }

    @Test
    fun aStepThatReportedNoTokensHasUsedNone() {
        assertEquals(0L, stepTokensUsed(qTaskRun("s", TaskRunStatus.Running)))
        assertFalse(stepAllowanceSpent(task(1), qTaskRun("s", TaskRunStatus.Running)))
    }

    @Test
    fun theReasonNamesTheCodeWhatWasUsedAndWhatWasAllowed() {
        val run = qTaskRun("s", TaskRunStatus.Failed, tokens = 1_200)
        val reason = stepAllowanceReason(task(1_000), run)
        assertEquals(STEP_ALLOWANCE_EXCEEDED, reason.code)
        assertTrue(reason.message.contains("1200") && reason.message.contains("1000"))
    }

    @Test
    fun theQueenReportsAStoppedStepInMessagesAtEveryLevelEvenWhenTheRunEnded() {
        val definition = qDefinition(task(1_000))
        val stopped = qTaskRun("s", TaskRunStatus.Failed, tokens = 1_200)
            .copy(blockingReason = BlockingReason(STEP_ALLOWANCE_EXCEEDED, "Stopped."))
        val running = qRun(stopped)
        val note = stepAllowanceNotes(definition, running).single()
        assertEquals(QueenVoice.StepAllowanceStopped, note.voice)
        assertEquals(listOf("1", "Task s", "1200", "1000"), note.args)
        assertTrue(note.link is QueenLink.Creature)
        val ended = qRun(stopped, status = com.hereliesaz.geministrator.domain.WorkflowRunStatus.Failed)
        assertTrue(queenUpdate(definition, ended, emptyList(), QueenLevel.Quiet).any { it.voice == QueenVoice.StepAllowanceStopped })
        assertTrue(queenUpdate(definition, running, emptyList(), QueenLevel.Quiet).any { it.voice == QueenVoice.StepAllowanceStopped })
    }

    @Test
    fun aStepStoppedForAnotherReasonSaysNothingAboutAnAllowance() {
        val definition = qDefinition(task(1_000))
        val failed = qTaskRun("s", TaskRunStatus.Failed, tokens = 1_200).copy(blockingReason = BlockingReason(OUTPUT_CONTRACT_VIOLATION, "x"))
        assertTrue(stepAllowanceNotes(definition, qRun(failed)).isEmpty())
    }
}
