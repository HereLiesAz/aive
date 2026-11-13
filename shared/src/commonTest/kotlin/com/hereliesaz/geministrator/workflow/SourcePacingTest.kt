package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.queen.QueenCallContext
import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.queen.currentQueenCallContext
import com.hereliesaz.geministrator.queen.pacedPerCall
import com.hereliesaz.geministrator.queen.QueenLink
import com.hereliesaz.geministrator.queen.QueenVoice
import com.hereliesaz.geministrator.queen.pacingBudget
import com.hereliesaz.geministrator.queen.sourceMovedNote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keeping a Source's request limit for the queen's own calls: wait the turn, within a bounded total, else give up. */
class SourcePacingTest {
    private val twoAMinute = ProviderCatalog.KeylessRateLimit(requests = 2, windowMillis = 60_000L)

    @Test
    fun paceTurnGoesWaitsOrGivesUp() {
        assertEquals(PaceTurn.Go, paceTurn(0L, 0L))
        assertEquals(PaceTurn.Wait(30_000L), paceTurn(30_000L, 90_000L))
        assertEquals(PaceTurn.Wait(90_000L), paceTurn(90_000L, 90_000L))
        assertEquals(PaceTurn.TooLong, paceTurn(90_001L, 90_000L))
    }

    @Test
    fun waitMillisIsUntilTheOldestCountedRequestLeavesTheWindow() {
        var now = 0L
        val pacer = KeylessPacer(now = { now }, limitOf = { twoAMinute })
        assertEquals(0L, pacer.waitMillis("ovh"))
        pacer.record("ovh")
        now = 10_000L
        assertEquals(0L, pacer.waitMillis("ovh"))
        pacer.record("ovh")
        now = 20_000L
        assertEquals(40_000L, pacer.waitMillis("ovh"))
        now = 60_000L
        assertEquals(0L, pacer.waitMillis("ovh"))
    }

    @Test
    fun aSourceWithNoStatedLimitIsNeverWaitedFor() {
        val pacer = KeylessPacer(now = { 0L }, limitOf = { null })
        repeat(10) { pacer.record("keyed") }
        assertEquals(0L, pacer.waitMillis("keyed"))
    }

    @Test
    fun awaitWaitsItsTurnAndThenCountsTheRequest() = runTest {
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { twoAMinute })
        val pacing = KeylessPacing(pacer, Mutex())
        assertEquals(0L, pacing.await("ovh", 90_000L))
        assertEquals(0L, pacing.await("ovh", 90_000L))
        assertEquals(60_000L, pacing.await("ovh", 90_000L))
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(0L, pacing.await("ovh", 90_000L))
        assertTrue(pacer.waitMillis("ovh") > 0L)
    }

    @Test
    fun aTurnBeyondTheBoundedWaitIsRefusedAndNothingIsCounted() = runTest {
        val slow = ProviderCatalog.KeylessRateLimit(requests = 1, windowMillis = 10 * 60_000L)
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { slow })
        val pacing = KeylessPacing(pacer, Mutex())
        assertEquals(0L, pacing.await("slow", 90_000L))
        assertNull(pacing.await("slow", 90_000L))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun theCallContextSpendsADisputeTotalAcrossCalls() = runTest {
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { twoAMinute })
        val context = QueenCallContext(KeylessPacing(pacer, Mutex()), totalWaitMillis = 100_000L, callWaitMillis = 90_000L)
        assertTrue(context.awaitTurn("ovh"))
        assertTrue(context.awaitTurn("ovh"))
        assertEquals(90_000L, context.callBudgetMillis())
        assertTrue(context.awaitTurn("ovh"))
        assertEquals(40_000L, context.callBudgetMillis())
        // The next turn is 60 s off, more than the 40 s of the total left: she does not wait for it.
        assertTrue(context.awaitTurn("ovh"))
        assertFalse(context.awaitTurn("ovh"))
    }

    @Test
    fun theCallCapCountsAcrossSourcesOfOneCallAndRestartsWithTheNextCall() = runTest {
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { twoAMinute })
        val context = QueenCallContext(KeylessPacing(pacer, Mutex()), totalWaitMillis = 100_000L, callWaitMillis = 90_000L)
        context.beginCall()
        assertTrue(context.awaitTurn("ovh"))
        assertTrue(context.awaitTurn("ovh"))
        assertTrue(context.awaitTurn("ovh"))
        // 60 s of this call's 90 s are spent, so the next Source tried gets 30 s, not a fresh 90 s.
        assertEquals(30_000L, context.callBudgetMillis())
        context.beginCall()
        assertEquals(40_000L, context.callBudgetMillis())
    }

    @Test
    fun everyAnswerOfAPacedModelRunsInItsOwnFreshContext() = runTest {
        val seen = mutableListOf<QueenCallContext?>()
        val base = QueenModel { seen += currentQueenCallContext(); "ok" }
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { twoAMinute })
        val model = pacedPerCall(base, KeylessPacing(pacer, Mutex()))
        model.answer("a")
        model.answer("b")
        assertEquals(2, seen.size)
        assertTrue(seen.all { it != null && it.paces })
        assertTrue(seen[0] !== seen[1])
        assertNull(currentQueenCallContext())
    }

    @Test
    fun theBudgetIsTheCallLimitOrWhatIsLeftOfTheTotal() {
        assertEquals(90_000L, pacingBudget(600_000L, 0L, 90_000L))
        assertEquals(10_000L, pacingBudget(600_000L, 590_000L, 90_000L))
        assertEquals(0L, pacingBudget(600_000L, 700_000L, 90_000L))
    }

    @Test
    fun aMoveToAnotherSourceIsAStoryNoteForThatRun() {
        val note = sourceMovedNote("run-1", "Kilo", "LLM7", "HTTP 429")
        assertEquals(QueenVoice.DisputeMovedSource, note.voice)
        assertEquals(listOf("Kilo", "LLM7", "HTTP 429"), note.args)
        assertEquals(QueenLink.Story, note.link)
        assertEquals(note.id, sourceMovedNote("run-1", "Kilo", "LLM7", "other reason").id)
        assertTrue(note.id != sourceMovedNote("run-2", "Kilo", "LLM7", "HTTP 429").id)
    }
}
