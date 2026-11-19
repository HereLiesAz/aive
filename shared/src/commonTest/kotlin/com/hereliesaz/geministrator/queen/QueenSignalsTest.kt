package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.TaskRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueenSignalsTest {
    private val definition = qDefinition(qTask("a"), qTask("b"))

    @Test
    fun twoFailedAttemptsInOneGroupRaiseRepeatedFailure() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 3), qTaskRun("b", TaskRunStatus.Ready))
        assertEquals(listOf(QueenSignal.RepeatedFailure("a", 2)), queenSignals(definition, run, listOf(engineerRole)))
    }

    @Test
    fun providerFailoverRaisesSourceSignal() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 2, failedProviders = listOf("claude")))
        assertEquals(listOf(QueenSignal.FailedOver("a")), queenSignals(definition, run, listOf(engineerRole)))
    }

    @Test
    fun roleAtEightyPercentOfBudgetRaisesBudgetBurn() {
        val role = engineerRole.copy(tokenBudget = 1000)
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, tokens = 850), qTaskRun("b", TaskRunStatus.Ready))
        val signals = queenSignals(definition, run, listOf(role))
        assertEquals(1, signals.size)
        assertEquals(85, (signals.single() as QueenSignal.BudgetBurn).percent)
    }

    @Test
    fun heldAttemptsDoNotCountAsFailures() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 3, held = 2))
        assertTrue(queenSignals(definition, run, listOf(engineerRole)).isEmpty())
    }

    @Test
    fun healthyRunHasNoSignals() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running))
        assertTrue(queenSignals(definition, run, listOf(engineerRole.copy(tokenBudget = 1000))).isEmpty())
    }

    @Test
    fun identicalStateGivesIdenticalSignals() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 4))
        assertEquals(queenSignals(definition, run, emptyList()), queenSignals(definition, run, emptyList()))
    }

    @Test
    fun aStepAlreadyWaitingOnTheKeeperRaisesNothing() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Escalated, attempt = 3))
        assertTrue(queenSignals(definition, run, emptyList()).isEmpty())
    }

    @Test
    fun watchSaysINoticedAtMostOnceMostSeriousFirst() {
        val run = qRun(
            qTaskRun("a", TaskRunStatus.Running, attempt = 2, failedProviders = listOf("x")),
            qTaskRun("b", TaskRunStatus.Running, attempt = 4),
        )
        val note = watchRelay(definition, run, queenSignals(definition, run, emptyList()))!!
        assertEquals(QueenVoice.WatchRepeatedFailure, note.voice)
        assertEquals(QueenRelay.Watch, note.relay)
        assertEquals("2", note.args[0])
    }

    @Test
    fun runCostNearItsAllowanceWithManyStepsLeftRaisesCostPace() {
        val three = qDefinition(qTask("a"), qTask("b"), qTask("c"))
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed, cost = 0.8), qTaskRun("b", TaskRunStatus.Running), qTaskRun("c", TaskRunStatus.Blocked))
        val caps = com.hereliesaz.geministrator.workflow.SpendCaps(runCostCapUsd = 1.0)
        val pace = queenSignals(three, run, emptyList(), caps = caps).filterIsInstance<QueenSignal.CostPace>().single()
        assertEquals(80, pace.percent)
        assertEquals(com.hereliesaz.geministrator.workflow.formatUsd(1.0), pace.capUsd)
        // No allowance: no signal. Allowance reached: the stop speaks, not the pace.
        assertTrue(queenSignals(three, run, emptyList()).none { it is QueenSignal.CostPace })
        assertTrue(queenSignals(three, run, emptyList(), caps = com.hereliesaz.geministrator.workflow.SpendCaps(runCostCapUsd = 0.5)).none { it is QueenSignal.CostPace })
        // The offer is the same whether or not the caller raised the signal.
        val fromSignal = queenCandidates(three, run, queenSignals(three, run, emptyList(), caps = caps), caps)
        val fromCaps = queenCandidates(three, run, queenSignals(three, run, emptyList()), caps)
        assertEquals(fromSignal, fromCaps)
        assertEquals(listOf(QueenAction.RaiseAllowance), fromSignal.map { it.action })
    }

    @Test
    fun costPaceAndNannyPendingAreNeverSaidAsAnINoticedLine() {
        val signals = listOf(QueenSignal.CostPace("", 80, "\$1.00"), QueenSignal.NannyPending("n"))
        assertEquals(null, watchRelay(definition, qRun(qTaskRun("a", TaskRunStatus.Running)), signals))
    }
}
