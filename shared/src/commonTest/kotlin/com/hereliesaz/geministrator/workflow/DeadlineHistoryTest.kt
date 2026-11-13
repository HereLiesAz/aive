package com.hereliesaz.geministrator.workflow

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeadlineHistoryTest {
    private val minute = 60_000L

    @Test
    fun recordedSamplesComeBackOldestFirstAndPerKind() {
        val history = DeadlineHistory(MapSettings())
        history.record(WorkKind.HostedModel, 1 * minute)
        history.record(WorkKind.HostedModel, 2 * minute)
        history.record(WorkKind.CliAgent, 9 * minute)
        assertEquals(listOf(1 * minute, 2 * minute), history.samples(WorkKind.HostedModel))
        assertEquals(listOf(9 * minute), history.samples(WorkKind.CliAgent))
        assertTrue(history.samples(WorkKind.Repository).isEmpty())
    }

    @Test
    fun onlyTheNewestSamplesAreKept() {
        val history = DeadlineHistory(MapSettings())
        for (i in 1..(DeadlineHistory.CAP + 10)) history.record(WorkKind.HostedModel, i * 1_000L)
        val kept = history.samples(WorkKind.HostedModel)
        assertEquals(DeadlineHistory.CAP, kept.size)
        assertEquals(11_000L, kept.first())
        assertEquals((DeadlineHistory.CAP + 10) * 1_000L, kept.last())
    }

    @Test
    fun nonsenseFiguresAreIgnoredAndStoredJunkIsSkipped() {
        val settings = MapSettings()
        val history = DeadlineHistory(settings)
        history.record(WorkKind.HostedModel, 0)
        history.record(WorkKind.HostedModel, -5)
        history.record(WorkKind.HostedModel, DeadlineHistory.MAX_SAMPLE_MILLIS + 1)
        assertTrue(history.samples(WorkKind.HostedModel).isEmpty())
        settings.putString(DeadlineHistory.key(WorkKind.HostedModel), "abc,,60000,-1,0, 120000 ,99999999999999")
        assertEquals(listOf(60_000L, 120_000L), history.samples(WorkKind.HostedModel))
    }

    @Test
    fun aHistoryThatKeepsNothingKeepsNothing() {
        DeadlineHistory.None.record(WorkKind.HostedModel, minute)
        assertTrue(DeadlineHistory.None.samples(WorkKind.HostedModel).isEmpty())
    }

    @Test
    fun enoughHistoryMovesTheQueensAutoFigureWithinBounds() {
        val none = DeadlinePolicy.resolveAuto(WorkKind.HostedModel, StepSize(), SourceTrait())
        assertEquals(Basis.Baseline, none.basis)
        assertEquals(2 * minute, none.millis)
        val slow = List(20) { 10 * minute }
        val learned = DeadlinePolicy.resolveAuto(WorkKind.HostedModel, StepSize(), SourceTrait(), slow)
        assertEquals(Basis.History, learned.basis)
        // A tenth of the evidence is not enough to move it.
        val few = DeadlinePolicy.resolveAuto(WorkKind.HostedModel, StepSize(), SourceTrait(), listOf(10 * minute))
        assertEquals(Basis.Baseline, few.basis)
        // Triple the baseline at most, so history never changes the order of magnitude.
        assertEquals(6 * minute, learned.millis)
    }
}
