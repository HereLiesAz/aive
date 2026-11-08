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

class HabitOverrideTest {
    private val old = "github-action:ci.yml"
    private val new = "github-action:ci-v2.yml"

    private fun use(run: String, at: Long, operation: String = old) = ProcedureTrace(
        workflowId = run, projectId = "project", taskRunId = "tr-$run", taskDefinitionId = "build", attempt = 1,
        phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
        outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = at, operation = operation,
    )

    private fun decision(
        at: Long,
        outcome: ProcedureDeliberationOutcome,
        alternative: String? = null,
        alternativeOperation: String? = null,
    ) = ProcedureTrace(
        workflowId = "w-d", projectId = "project", taskRunId = "deliberation", taskDefinitionId = "habit-deliberation",
        attempt = 0, phase = ProcedureTracePhase.Deliberation, executorKind = "Deliberation",
        outcome = ProcedureTraceOutcome.Running, recordedAtEpochMillis = at, operation = old,
        deliberation = ProcedureTraceDeliberation(outcome, "keeper", "It deploys to the wrong stage.", alternative, alternativeOperation),
    )

    private val oldUses = listOf(use("a", 1), use("b", 2), use("c", 3))
    private fun anOverride(alternativeOperation: String? = new) =
        decision(10, ProcedureDeliberationOutcome.Overrides, "Use the v2 job.", alternativeOperation)

    private fun habit(traces: List<ProcedureTrace>, operation: String = old) =
        HabitFormation.form(traces).first { it.operation == operation }

    @Test
    fun anOverrideIsRequiredToNameItsAlternativeAndOnlyAnOverrideMay() {
        assertFailsToBuild { ProcedureTraceDeliberation(ProcedureDeliberationOutcome.Overrides, "keeper", "No.") }
        assertFailsToBuild {
            ProcedureTraceDeliberation(ProcedureDeliberationOutcome.Overrides, "keeper", "No.", "Use v2.", "Not A Name")
        }
        assertFailsToBuild { ProcedureTraceDeliberation(ProcedureDeliberationOutcome.Stands, "keeper", "No.", "Use v2.") }
    }

    private fun assertFailsToBuild(block: () -> Unit) {
        val failed = try {
            block()
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun theAlternativesUsesAreTheControlledPassesAndThreeOfThemRetireTheOverride() {
        val start = habit(oldUses + anOverride())
        assertEquals(0, start.habitOverride?.usesSince)
        assertEquals(0, start.habitOverride?.alternativeUses)
        assertNotNull(start.activeOverride)
        // The habit is not hidden or blocked by its override.
        assertTrue(start.offered)

        val twoPasses = habit(oldUses + anOverride() + use("d", 11, new) + use("e", 12, new) + use("f", 13))
        assertEquals(2, twoPasses.habitOverride?.alternativeUses)
        assertEquals(1, twoPasses.habitOverride?.usesSince)
        assertNotNull(twoPasses.activeOverride)

        val threePasses = habit(oldUses + anOverride() + use("d", 11, new) + use("e", 12, new) + use("g", 13, new))
        assertEquals(3, threePasses.habitOverride?.alternativeUses)
        assertTrue(threePasses.habitOverride?.retired == true)
        assertNull(threePasses.activeOverride)
        // The alternative is now an ordinary habit of its own.
        assertTrue(habit(oldUses + anOverride() + use("d", 11, new) + use("e", 12, new) + use("g", 13, new), new).offered)
    }

    @Test
    fun passesBeforeTheOverrideDoNotCountAndWithoutANamedAlternativeNothingRetiresIt() {
        val before = habit(oldUses + use("x", 5, new) + use("y", 6, new) + use("z", 7, new) + anOverride())
        assertEquals(0, before.habitOverride?.alternativeUses)
        assertFalse(before.habitOverride?.retired == true)

        val unnamed = habit(oldUses + anOverride(alternativeOperation = null) + use("d", 11, new) + use("e", 12, new) + use("g", 13, new))
        assertNull(unnamed.habitOverride?.alternativeUses)
        assertFalse(unnamed.habitOverride?.retired == true)
    }

    @Test
    fun aSupersedingDecisionAfterAnOverrideEndsItWithTheVersion() {
        val h = habit(oldUses + anOverride() + decision(20, ProcedureDeliberationOutcome.Superseded))
        assertNull(h.habitOverride)
        assertEquals(2, h.version)
    }

    @Test
    fun anActiveOverrideIsShownWhereTheCueAppearsEvenBeforeTheHabitIsOffered() {
        val forming = habit(listOf(use("a", 1)) + anOverride())
        assertFalse(forming.offered)
        assertEquals(listOf(old), HabitOffers.select(listOf(forming), "run CI").map { it.habit.operation })
        assertTrue(HabitOffers.select(listOf(habit(listOf(use("a", 1)))), "run CI").isEmpty())
    }

    @Test
    fun theOfferStatesTheOverrideAndItsPassesAsDataAndNeverHidesTheHabit() {
        val h = habit(oldUses + anOverride() + use("d", 11, new) + use("e", 12))
        val text = assertNotNull(HabitOffers.render(listOf(HabitOffer(h, listOf("ci"))), 20L))
        assertTrue(old + ": used 4 times" in text)
        assertTrue("Override by keeper: \"It deploys to the wrong stage.\" Instead: \"Use the v2 job.\"." in text)
        assertTrue("Since it was written this habit has been used 1 time and $new 1 time (it becomes a habit of its own at 3 uses)." in text)

        val retired = habit(oldUses + anOverride() + use("d", 11, new) + use("e", 12, new) + use("g", 13, new))
        val gone = assertNotNull(HabitOffers.render(listOf(HabitOffer(retired, listOf("ci"))), 20L))
        assertTrue("Earlier override by keeper (retired: its alternative $new has been used 3 times and is a habit of its own)" in gone)
        assertFalse("Instead:" in gone)
    }

    @Test
    fun theControllerRecordsAnOverrideOnlyWithItsAlternative() = runBlocking {
        val lineage = MemoryLineage()
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("w1" to InMemoryMemoryStore(), "w2" to InMemoryMemoryStore(), "w3" to InMemoryMemoryStore())),
            procedureCatalog = ProcedureBankCatalog(lineage),
            lineage = lineage,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 100L },
        )
        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        listOf("w1", "w2", "w3").forEachIndexed { index, run -> controller.procedureTraceSink.record(use(run, 10L + index)) }
        val overrides = ProcedureDeliberationOutcome.Overrides
        assertFalse(controller.deliberateHabit("w3", old, overrides, "keeper", "Wrong stage."))
        assertFalse(controller.deliberateHabit("w3", old, ProcedureDeliberationOutcome.Stands, "keeper", "Fine.", alternative = "Use v2."))
        assertFalse(controller.deliberateHabit("w3", old, overrides, "keeper", "Wrong stage.", "Use v2.", "Not A Name"))
        assertTrue(controller.deliberateHabit("w3", old, overrides, "keeper", "Wrong stage.", "Use v2.", new))

        val habit = controller.formedHabits("w3").single { it.operation == old }
        assertEquals("Use v2.", habit.habitOverride?.alternative)
        assertEquals(new, habit.habitOverride?.alternativeOperation)
        assertTrue(habit.offered)
    }
}
