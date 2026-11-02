package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STATUS_WORKFLOW = "wf-status"

class MemoryStatusLineTest {
    @Test
    fun consolidateNowStateFollowsOffThenPausedThenWorking() {
        val idle = MemoryActivity()
        val working = MemoryActivity(working = MemoryConsolidationStage.Sectioning)
        val on = MemoryLayerSettings()
        assertEquals(ConsolidateNowState.Ready, consolidateNowState(on, idle))
        assertEquals(ConsolidateNowState.Working, consolidateNowState(on, working))
        assertEquals(ConsolidateNowState.Paused, consolidateNowState(on.copy(consolidationPaused = true), working))
        assertEquals(ConsolidateNowState.Off, consolidateNowState(on.copy(enabled = false, consolidationPaused = true), idle))
    }

    @Test
    fun onlyRealWorkCountsAsConsolidated() {
        val id = MemoryQueueId("q")
        assertFalse(MemoryConsolidationResult.Idle.consolidatedSomething)
        assertFalse(MemoryConsolidationResult.Failed(id, MemoryConsolidationStage.Sectioning, "down").consolidatedSomething)
        assertTrue(MemoryConsolidationResult.Applied(id, MemoryConsolidationStage.Sectioning, 1).consolidatedSomething)
        assertTrue(MemoryConsolidationResult.Completed(id).consolidatedSomething)
        assertTrue(MemoryConsolidationResult.PairSummaries(2).consolidatedSomething)
        assertTrue(MemoryConsolidationResult.Declined(id, "no").consolidatedSomething)
    }

    @Test
    fun elapsedRoundsDownToMinutesHoursDays() {
        val minute = 60_000L
        assertEquals(MemoryElapsed(MemoryElapsedUnit.JustNow, 0), memoryElapsed(0, 59_999))
        assertEquals(MemoryElapsed(MemoryElapsedUnit.JustNow, 0), memoryElapsed(10_000, 0))
        assertEquals(MemoryElapsed(MemoryElapsedUnit.Minutes, 1), memoryElapsed(0, minute))
        assertEquals(MemoryElapsed(MemoryElapsedUnit.Minutes, 59), memoryElapsed(0, 60 * minute - 1))
        assertEquals(MemoryElapsed(MemoryElapsedUnit.Hours, 2), memoryElapsed(0, 150 * minute))
        assertEquals(MemoryElapsed(MemoryElapsedUnit.Days, 3), memoryElapsed(0, 3 * 24 * 60 * minute + minute))
    }

    @Test
    fun waitingCountsPendingAndProcessingOnly() {
        fun entry(n: Int, status: MemoryQueueStatus) =
            MemoryQueueEntry(MemoryQueueId("q$n"), n.toLong(), MemoryEpisodeId("e$n"), status = status, createdAtEpochMillis = 0)
        val snapshot = MemorySnapshot(
            queue = listOf(
                entry(1, MemoryQueueStatus.Pending),
                entry(2, MemoryQueueStatus.Processing),
                entry(3, MemoryQueueStatus.Failed),
                entry(4, MemoryQueueStatus.Complete),
            ),
        )
        assertEquals(2, snapshot.waitingToConsolidate())
    }

    private fun controller(store: MemoryStore, now: () -> Long) = MemoryLayerController(
        banks = MemoryBanks.inMemory(mapOf(STATUS_WORKFLOW to store)),
        settingsStore = MemoryLayerSettingsStore(MapSettings()),
        engineProvider = object : MemoryEngineProvider {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        nowEpochMillis = now,
    )

    private suspend fun MemoryStore.bank(id: String, text: String) {
        MemoryConsolidationQueue(this).enqueueSession(
            MemorySessionEnvelope(id, workflowRunId = STATUS_WORKFLOW, userPrompt = text, parts = emptyList(), closedAtEpochMillis = 1L),
        )
    }

    @Test
    fun lastConsolidatedIsSetByWorkNotByAnIdleDrain() = runBlocking {
        var now = 1_000L
        val store = InMemoryMemoryStore()
        val controller = controller(store) { now }
        controller.drain()
        assertNull(controller.activity.value.lastConsolidatedAtEpochMillis)

        store.bank("a", "Replace the settings store with SQLDelight because commits re-encode everything.")
        now = 5_000L
        controller.drain()
        assertEquals(5_000L, controller.activity.value.lastConsolidatedAtEpochMillis)

        // A later drain with nothing due keeps the last real time.
        now = 9_000L
        controller.drain()
        assertEquals(5_000L, controller.activity.value.lastConsolidatedAtEpochMillis)
        assertEquals(9_000L, controller.activity.value.lastAtEpochMillis)
    }

    @Test
    fun consolidateNowRespectsPauseAndOff() = runBlocking {
        val store = InMemoryMemoryStore()
        store.bank("a", "Something worth remembering about the gradle build.")
        val controller = controller(store) { 1_000L }
        controller.updateSettings { it.copy(consolidationPaused = true) }
        assertEquals(ConsolidateNowState.Paused, controller.consolidateNow())
        assertTrue(store.read().queue.single().status != MemoryQueueStatus.Complete)

        controller.updateSettings { it.copy(consolidationPaused = false, enabled = false) }
        assertEquals(ConsolidateNowState.Off, controller.consolidateNow())
        assertTrue(store.read().queue.single().status != MemoryQueueStatus.Complete)
        assertNull(controller.activity.value.lastConsolidatedAtEpochMillis)
    }
}
