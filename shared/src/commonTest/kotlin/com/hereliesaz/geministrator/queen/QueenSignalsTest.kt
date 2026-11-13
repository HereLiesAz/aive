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
}
