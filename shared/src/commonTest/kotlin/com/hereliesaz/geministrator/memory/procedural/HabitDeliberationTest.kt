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

class HabitDeliberationTest {
    private val ci = "github-action:ci.yml"

    private fun use(
        run: String,
        at: Long,
        failed: Boolean = false,
        ref: String? = "main",
        verification: ProcedureTraceVerification = ProcedureTraceVerification.Unknown,
    ): List<ProcedureTrace> {
        fun trace(phase: ProcedureTracePhase, outcome: ProcedureTraceOutcome, v: ProcedureTraceVerification) = ProcedureTrace(
            workflowId = run, projectId = "project", taskRunId = "tr-$run", taskDefinitionId = "build", attempt = 1,
            phase = phase, executorKind = "GitHubAction", outcome = outcome, recordedAtEpochMillis = at, operation = ci,
            slots = ref?.let { listOf(ProcedureTraceSlot("ref", ProcedureParameterType.Identifier, it)) }.orEmpty(),
            verification = v,
        )
        val executed = trace(
            ProcedureTracePhase.Reconcile,
            if (failed) ProcedureTraceOutcome.Failed else ProcedureTraceOutcome.Completed,
            ProcedureTraceVerification.Unknown,
        )
        return if (verification == ProcedureTraceVerification.Unknown) {
            listOf(executed)
        } else {
            listOf(executed, trace(ProcedureTracePhase.Verification, ProcedureTraceOutcome.Completed, verification))
        }
    }

    private fun decision(at: Long, outcome: ProcedureDeliberationOutcome, text: String = "Considered.", author: String = "keeper") =
        ProcedureTrace(
            workflowId = "w-d", projectId = "project", taskRunId = "deliberation", taskDefinitionId = "habit-deliberation",
            attempt = 0, phase = ProcedureTracePhase.Deliberation, executorKind = "Deliberation",
            outcome = ProcedureTraceOutcome.Running, recordedAtEpochMillis = at, operation = ci,
            deliberation = ProcedureTraceDeliberation(outcome, author, text),
        )

    @Test
    fun theSameValuesBothCompletedAndTurnedBackIsAContrastButDifferentValuesAreOnlyVariation() {
        val contrast = HabitFormation.form(use("a", 1) + use("b", 2) + use("c", 3, failed = true)).single()
        assertEquals(listOf(HabitContrast("ref=main", completed = 2, turnedBack = 1)), contrast.openContrasts)

        val variation = HabitFormation.form(use("a", 1, ref = "main") + use("b", 2, ref = "dev", failed = true)).single()
        assertTrue(variation.openContrasts.isEmpty())

        val rejected = HabitFormation.form(
            use("a", 1) + use("b", 2, verification = ProcedureTraceVerification.Rejected),
        ).single()
        assertEquals(1, rejected.openContrasts.size)
    }

    @Test
    fun aDecisionThatTheHabitStandsAnswersAContrastAndOnlyNewEvidenceReopensIt() {
        val before = use("a", 1) + use("b", 2) + use("c", 3, failed = true)
        val answered = HabitFormation.form(before + decision(4, ProcedureDeliberationOutcome.Stands)).single()
        assertTrue(answered.openContrasts.isEmpty())
        assertEquals(3, answered.uses)
        assertEquals(1, answered.version)

        val reopened = HabitFormation.form(
            before + decision(4, ProcedureDeliberationOutcome.Stands) + use("d", 5, failed = true),
        ).single()
        assertEquals(listOf(HabitContrast("ref=main", completed = 2, turnedBack = 2)), reopened.openContrasts)
    }

    @Test
    fun aSupersedingDecisionClosesTheVersionAndTheNewOneStartsFromWhatIsDoneAfter() {
        val old = use("a", 1) + use("b", 2) + use("c", 3, failed = true)
        val superseded = HabitFormation.form(old + decision(10, ProcedureDeliberationOutcome.Superseded, "Use the new job.")).single()
        assertEquals(2, superseded.version)
        assertEquals(3, superseded.earlierUses)
        assertEquals(0, superseded.uses)
        assertFalse(superseded.offered)
        assertTrue(superseded.openContrasts.isEmpty())
        assertEquals("Use the new job.", superseded.lastDeliberation?.text)

        // The alternative is just a new habit: offered again after three uses.
        val again = HabitFormation.form(old + decision(10, ProcedureDeliberationOutcome.Superseded) + use("d", 11) + use("e", 12) + use("f", 13)).single()
        assertEquals(3, again.uses)
        assertTrue(again.offered)
        assertEquals(3, again.earlierUses)
        assertEquals(2, again.version)
    }

    @Test
    fun aDecisionIsNeverAUseAndTheOfferStatesContrastsAndDecisionsAsData() {
        val habit = HabitFormation.form(use("a", 1) + use("b", 2) + use("c", 3, failed = true) + decision(0, ProcedureDeliberationOutcome.Stands, "It \"needs\" a\nclean tree.", "qa-engineer")).single()
        assertEquals(3, habit.uses)

        val text = assertNotNull(HabitOffers.render(listOf(HabitOffer(habit, listOf("ci"))), 5L))
        // The decision predates the failure, so the contrast is open and the decision is shown.
        assertTrue("Open contrast: with ref=main, it has completed 2 times and been failed or turned back 1 time." in text)
        assertTrue("Latest decision (stands, qa-engineer): \"It 'needs' a clean tree.\"" in text)
        assertFalse("No decision about it has been written." in text)

        val undecided = HabitFormation.form(use("a", 1) + use("b", 2) + use("c", 3, failed = true)).single()
        val bare = assertNotNull(HabitOffers.render(listOf(HabitOffer(undecided, listOf("ci"))), 5L))
        assertTrue("No decision about it has been written." in bare)
    }

    @Test
    fun theControllerRecordsADecisionAndTheHabitShowsIt() = runBlocking {
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
        val stands = ProcedureDeliberationOutcome.Stands
        assertFalse(controller.deliberateHabit("w3", ci, stands, "keeper", "Leave it."))

        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        listOf("w1", "w2", "w3").forEachIndexed { index, run ->
            controller.procedureTraceSink.record(use(run, 10L + index).single())
        }
        assertTrue(controller.deliberateHabit("w3", ci, stands, "keeper", "Leave it."))
        assertFalse(controller.deliberateHabit("w3", "not an operation", stands, "keeper", "x"))
        assertFalse(controller.deliberateHabit("w3", ci, stands, " ", "x"))
        assertFalse(controller.deliberateHabit("w3", ci, stands, "keeper", "  "))

        val habit = controller.formedHabits("w3").single()
        assertEquals(3, habit.uses)
        assertEquals("Leave it.", habit.lastDeliberation?.text)
        assertNull(habit.flags.newest.firstOrNull())
    }
}
