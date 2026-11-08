package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.InMemoryMemoryStore
import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryLineage
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitOffersTest {
    private val day = 86_400_000L

    private fun habit(
        operation: String,
        uses: Int = 5,
        failed: Int = 1,
        verified: Int = 3,
        rejected: Int = 0,
        recentFailed: Int = 1,
        lastUsed: Long = 10 * day,
        slots: List<HabitSlot> = emptyList(),
    ) = FormedHabit(
        executorKind = "GitHubAction",
        operation = operation,
        uses = uses,
        completedUses = uses - failed,
        failedUses = failed,
        verifiedUses = verified,
        rejectedUses = rejected,
        slots = slots,
        firstUsedAtEpochMillis = 0L,
        lastUsedAtEpochMillis = lastUsed,
        strength = if (uses >= 3) HabitStrength.Low else HabitStrength.Forming,
        recentUses = minOf(uses, 3),
        recentFailedUses = recentFailed,
    )

    @Test
    fun aHabitIsOfferedOnlyWhenTheTaskNamesItsOperationAndItHasBeenUsedThreeTimes() {
        val ci = habit("github-action:ci.yml")
        val release = habit("github-action:release.yml")
        val forming = habit("deployment:staging", uses = 2)
        val offers = HabitOffers.select(listOf(ci, release, forming), "Run CI on the main branch and staging")
        assertEquals(listOf("github-action:ci.yml"), offers.map { it.habit.operation })
        assertEquals(listOf("ci"), offers.single().matchedCues)
        // Generic words and slot values never match on their own.
        assertTrue(HabitOffers.select(listOf(ci), "Use the github action workflow on main").isEmpty())
    }

    @Test
    fun theBestMatchesComeFirstAndOnlyAFewAreOffered() {
        val habits = listOf(
            habit("repository:merge", uses = 9),
            habit("repository:rebase", uses = 4),
            habit("repository:squash", uses = 6),
            habit("repository:merge-queue", uses = 3),
        )
        val offers = HabitOffers.select(habits, "merge the queue then rebase and squash")
        assertEquals(HabitOffers.MAX_OFFERS, offers.size)
        // merge-queue matches two words, so it leads; the rest follow by use.
        assertEquals("repository:merge-queue", offers.first().habit.operation)
        assertEquals(listOf("repository:merge-queue", "repository:merge", "repository:squash"), offers.map { it.habit.operation })
    }

    @Test
    fun theRenderedOfferStatesCountsAndFactsAndNeverTellsTheReaderWhatToDo() {
        val offer = HabitOffer(
            habit(
                "github-action:ci.yml",
                slots = listOf(
                    HabitSlot(
                        "ref",
                        ProcedureParameterType.Identifier,
                        listOf(HabitSlotValue("main", 4, 0L, 10 * day), HabitSlotValue("dev", 1, 0L, 5 * day)),
                    ),
                ),
            ),
            listOf("ci"),
        )
        val text = assertNotNull(HabitOffers.render(listOf(offer), 12 * day))
        assertTrue("used 5 times (4 completed, 1 failed)" in text)
        assertTrue("3 approved, 0 turned back, 2 not reviewed" in text)
        assertTrue("Newest 3 uses: 1 failed" in text)
        assertTrue("Last used 2 days ago" in text)
        assertTrue("Strength: low" in text)
        assertTrue("ref has been main (4), dev (1)" in text)
        assertTrue("nothing here runs" in text)
        // No judging or instructing words.
        listOf("should", "must", "recommend", "best", "avoid", "good", "bad", "wrong", "correct").forEach {
            assertFalse(Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(text), it)
        }
        assertNull(HabitOffers.render(emptyList(), 0L))
    }

    @Test
    fun theOfferIsBoundedByWholeEntries() {
        val many = (1..3).map {
            HabitOffer(
                habit("github-action:job$it.yml", slots = listOf(
                    HabitSlot("note", ProcedureParameterType.Identifier, (1..8).map { v -> HabitSlotValue("v$it$v", 1, 0L, 0L) }),
                )),
                listOf("job$it"),
            )
        }
        val text = assertNotNull(HabitOffers.render(many, 0L))
        assertTrue(text.length <= HabitOffers.MAX_CHARS)
        assertTrue(text.contains("job1"))
    }

    @Test
    fun theControllerOffersHabitsOnlyWhileCaptureIsOn() = runBlocking {
        val lineage = MemoryLineage()
        val procedures = ProcedureBankCatalog(lineage)
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(
                mapOf("w1" to InMemoryMemoryStore(), "w2" to InMemoryMemoryStore(), "w3" to InMemoryMemoryStore()),
            ),
            procedureCatalog = procedures,
            lineage = lineage,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 20 * day },
        )
        assertNull(controller.habitOfferText("w3", "run ci"))

        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        listOf("w1", "w2", "w3").forEachIndexed { index, run ->
            controller.procedureTraceSink.record(
                ProcedureTrace(
                    workflowId = run,
                    projectId = "project",
                    taskRunId = "tr-$run",
                    taskDefinitionId = "build",
                    attempt = 1,
                    phase = ProcedureTracePhase.Reconcile,
                    executorKind = "GitHubAction",
                    outcome = ProcedureTraceOutcome.Completed,
                    recordedAtEpochMillis = 10 * day + index,
                    operation = "github-action:ci.yml",
                ),
            )
        }
        val text = assertNotNull(controller.habitOfferText("w3", "please run CI again"))
        assertTrue("github-action:ci.yml: used 3 times" in text)
        assertNull(controller.habitOfferText("w3", "write the release notes"))
    }

    @Test
    fun flagsAreCountedBySourceAndQuotedAsDataWithTheirAuthorAndTaint() {
        val flagged = habit("github-action:ci.yml").copy(
            flags = HabitFlags(
                used = 0,
                handled = 2,
                keeper = 1,
                newest = listOf(
                    ProcedureTraceFlag(ProcedureFlagSource.Handled, "qa-engineer", "The build assumed a \"clean\" tree.\nTwice.", tainted = true),
                    ProcedureTraceFlag(ProcedureFlagSource.Keeper, "keeper", "It deploys to the wrong stage."),
                ),
            ),
        )
        val text = assertNotNull(HabitOffers.render(listOf(HabitOffer(flagged, listOf("ci"))), 12 * day))
        assertTrue("Flags: 0 by models that used it, 2 by models that handled its results, 1 by the keeper." in text)
        assertTrue("\"The build assumed a 'clean' tree. Twice.\" (handled, qa-engineer, written with untrusted content in view)" in text)
        assertTrue("\"It deploys to the wrong stage.\" (keeper, keeper)" in text)
        val none = assertNotNull(HabitOffers.render(listOf(HabitOffer(habit("github-action:ci.yml"), listOf("ci"))), 12 * day))
        assertTrue("Flags: none yet." in none)
    }

    @Test
    fun theKeeperCanFlagAHabitAtAnyTimeAndItShowsAmongItsFlags() = runBlocking {
        val lineage = MemoryLineage()
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("w1" to InMemoryMemoryStore(), "w2" to InMemoryMemoryStore(), "w3" to InMemoryMemoryStore())),
            procedureCatalog = ProcedureBankCatalog(lineage),
            lineage = lineage,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 20 * day },
        )
        val operation = "github-action:ci.yml"
        assertFalse(controller.flagHabit("w3", operation, "It needs a clean tree."))

        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        listOf("w1", "w2", "w3").forEachIndexed { index, run ->
            controller.procedureTraceSink.record(
                ProcedureTrace(
                    workflowId = run, projectId = "project", taskRunId = "tr-$run", taskDefinitionId = "build", attempt = 1,
                    phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
                    outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = 10 * day + index, operation = operation,
                ),
            )
        }
        assertTrue(controller.flagHabit("w3", operation, "It needs a clean tree."))
        assertTrue(controller.flagHabit("w1", operation, "It also ignores the cache."))
        assertFalse(controller.flagHabit("w3", "not an operation", "x"))
        assertFalse(controller.flagHabit("w3", operation, "   "))

        val habit = controller.formedHabits("w3").single()
        assertEquals(3, habit.uses)
        assertEquals(2, habit.flags.keeper)
        val text = assertNotNull(controller.habitOfferText("w3", "please run CI again"))
        assertTrue("It needs a clean tree." in text)
        assertTrue("2 by the keeper" in text)
    }
}
