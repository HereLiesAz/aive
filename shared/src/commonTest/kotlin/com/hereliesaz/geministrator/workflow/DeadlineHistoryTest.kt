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
    fun theMostSpecificListWithEnoughEvidenceAnswers() {
        val history = DeadlineHistory(MapSettings())
        // Five slow runs of one task on a slow Source, five quick runs of another task on a fast one.
        repeat(5) { history.record(WorkKind.HostedModel, 9 * minute, source = "keyless-slow", task = "survey") }
        repeat(5) { history.record(WorkKind.HostedModel, 1 * minute, source = "keyed-fast", task = "lesson") }
        assertEquals(List(5) { 9 * minute }, history.samples(WorkKind.HostedModel, "keyless-slow", "survey"))
        assertEquals(List(5) { 1 * minute }, history.samples(WorkKind.HostedModel, "keyed-fast", "lesson"))
        // A task never seen on a Source known to be slow is judged by that Source.
        assertEquals(List(5) { 9 * minute }, history.samples(WorkKind.HostedModel, "keyless-slow", "never-seen"))
        // A Source never seen, with a task that was, is judged by that task.
        assertEquals(List(5) { 9 * minute }, history.samples(WorkKind.HostedModel, "new-source", "survey"))
        // Neither known: the kind's own list, which holds both groups.
        assertEquals(10, history.samples(WorkKind.HostedModel, "new-source", "never-seen").size)
    }

    @Test
    fun aListWithTooFewSamplesDefersToABroaderOne() {
        val history = DeadlineHistory(MapSettings())
        repeat(DeadlineHistory.ENOUGH) { history.record(WorkKind.CliAgent, 4 * minute, source = "cli-a", task = "build") }
        history.record(WorkKind.CliAgent, 30 * minute, source = "cli-a", task = "rare")
        // The rare task has one sample on this Source; the Source as a whole has enough, so it answers.
        val answer = history.samples(WorkKind.CliAgent, "cli-a", "rare")
        assertEquals(DeadlineHistory.ENOUGH + 1, answer.size)
        assertTrue(30 * minute in answer)
    }

    @Test
    fun longIdsFoldToShortStableKeys() {
        val long = "a".repeat(300)
        val keys = DeadlineHistory.specificKeys(WorkKind.HostedModel, long, long)
        assertTrue(keys.all { it.length < 80 }, keys.toString())
        assertEquals(keys, DeadlineHistory.specificKeys(WorkKind.HostedModel, long, long))
        assertEquals(3, keys.size)
        assertEquals(emptyList(), DeadlineHistory.specificKeys(WorkKind.HostedModel, null, " "))
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
