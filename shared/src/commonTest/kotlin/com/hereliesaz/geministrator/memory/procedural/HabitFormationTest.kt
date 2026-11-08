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
import kotlin.test.assertTrue

class HabitFormationTest {
    private val ci = "github-action:ci.yml"

    private fun receipt(
        run: String,
        at: Long,
        operation: String? = ci,
        phase: ProcedureTracePhase = ProcedureTracePhase.Reconcile,
        outcome: ProcedureTraceOutcome = ProcedureTraceOutcome.Completed,
        slots: List<ProcedureTraceSlot> = emptyList(),
        verification: ProcedureTraceVerification = ProcedureTraceVerification.Unknown,
        attempt: Int = 1,
    ) = ProcedureTrace(
        workflowId = run,
        projectId = "project",
        taskRunId = "tr-$run",
        taskDefinitionId = "build",
        attempt = attempt,
        phase = phase,
        executorKind = "GitHubAction",
        outcome = outcome,
        recordedAtEpochMillis = at,
        operation = operation,
        slots = slots,
        verification = verification,
    )

    private fun slot(name: String, value: String) =
        ProcedureTraceSlot(name, ProcedureParameterType.Identifier, value)

    @Test
    fun aHabitIsOfferedAtThreeUsesAtLowStrengthAndNotBefore() {
        val two = HabitFormation.form(listOf(receipt("a", 1), receipt("b", 2)))
        assertEquals(1, two.size)
        assertEquals(HabitStrength.Forming, two.single().strength)
        assertFalse(two.single().offered)

        val three = HabitFormation.form(listOf(receipt("a", 1), receipt("b", 2), receipt("c", 3))).single()
        assertEquals(3, three.uses)
        assertEquals(HabitStrength.Low, three.strength)
        assertTrue(three.offered)
        assertEquals(1L, three.firstUsedAtEpochMillis)
        assertEquals(3L, three.lastUsedAtEpochMillis)
        assertEquals(3, HabitSettings().controlledPassesToConsolidate)
    }

    @Test
    fun oneExecutionIsOneUseHoweverManyReceiptsItLeft() {
        val habit = HabitFormation.form(
            listOf(
                receipt("a", 1, phase = ProcedureTracePhase.Dispatch, outcome = ProcedureTraceOutcome.Running),
                receipt("a", 2, phase = ProcedureTracePhase.Reconcile),
                receipt("a", 3, phase = ProcedureTracePhase.Dispatch),
                receipt("b", 4, phase = ProcedureTracePhase.Dispatch, outcome = ProcedureTraceOutcome.Running),
            ),
        ).single()
        // "b" only reported Running: not a finished use.
        assertEquals(1, habit.uses)
    }

    @Test
    fun failuresAreUsesAndCountedApart() {
        val habit = HabitFormation.form(
            listOf(
                receipt("a", 1),
                receipt("b", 2, outcome = ProcedureTraceOutcome.Failed),
                receipt("c", 3, outcome = ProcedureTraceOutcome.Threw),
            ),
        ).single()
        assertEquals(3, habit.uses)
        assertEquals(1, habit.completedUses)
        assertEquals(2, habit.failedUses)
        assertTrue(habit.offered)
    }

    @Test
    fun recentOutcomesLookAtTheNewestThreeUsesOnly() {
        val habit = HabitFormation.form(
            listOf(
                receipt("a", 1, outcome = ProcedureTraceOutcome.Failed),
                receipt("b", 2),
                receipt("c", 3, outcome = ProcedureTraceOutcome.Failed),
                receipt("d", 4),
                receipt("e", 5),
            ),
        ).single()
        assertEquals(HabitFormation.RECENT, habit.recentUses)
        // The newest three are c (failed), d, e.
        assertEquals(1, habit.recentFailedUses)
        assertEquals(2, habit.failedUses)
        assertEquals(5 - 0 - 0, habit.unreviewedUses)
    }

    @Test
    fun verificationReceiptsCountApartAndARejectionOutweighsAnApproval() {
        val habit = HabitFormation.form(
            listOf(
                receipt("a", 1),
                receipt("a", 2, phase = ProcedureTracePhase.Verification, verification = ProcedureTraceVerification.Verified),
                receipt("b", 3),
                receipt("b", 4, phase = ProcedureTracePhase.Verification, verification = ProcedureTraceVerification.Rejected),
                receipt("c", 5),
                receipt("c", 6, phase = ProcedureTracePhase.Verification, verification = ProcedureTraceVerification.Verified),
                receipt("c", 7, phase = ProcedureTracePhase.Verification, verification = ProcedureTraceVerification.Rejected),
                receipt("d", 8),
            ),
        ).single()
        assertEquals(4, habit.uses)
        assertEquals(1, habit.verifiedUses)
        assertEquals(2, habit.rejectedUses)
    }

    @Test
    fun slotsKeepARegisterOfEveryValueTheyTookWithoutRankingAnyAsCorrect() {
        val habit = HabitFormation.form(
            listOf(
                receipt("a", 1, slots = listOf(slot("ref", "main"))),
                receipt("b", 2, slots = listOf(slot("ref", "main"))),
                receipt("c", 3, slots = listOf(slot("ref", "dev"))),
            ),
        ).single()
        val ref = habit.slots.single()
        assertEquals("ref", ref.name)
        assertEquals(listOf("main", "dev"), ref.values.map { it.value })
        assertEquals(listOf(2, 1), ref.values.map { it.count })
        assertEquals(3L, ref.values.last().lastSeenAtEpochMillis)
    }

    @Test
    fun receiptsWithNoNamedOperationFormNothingAndFramesStayApart() {
        assertTrue(HabitFormation.form(listOf(receipt("a", 1, operation = null))).isEmpty())
        val habits = HabitFormation.form(
            listOf(receipt("a", 1), receipt("b", 2), receipt("c", 3), receipt("d", 4, operation = "deployment:production")),
        )
        assertEquals(listOf(ci, "deployment:production"), habits.map { it.operation })
        assertEquals(listOf(3, 1), habits.map { it.uses })
    }

    @Test
    fun theControllerPoolsTheRunsOfAProjectReadOnlyAndFormsNothingWhileCaptureIsOff() = runBlocking {
        val lineage = MemoryLineage()
        val procedures = ProcedureBankCatalog(lineage)
        val banks = MemoryBanks.inMemory(
            mapOf("w1" to InMemoryMemoryStore(), "w2" to InMemoryMemoryStore(), "w3" to InMemoryMemoryStore()),
        )
        val controller = MemoryLayerController(
            banks = banks,
            procedureCatalog = procedures,
            lineage = lineage,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1000L },
        )
        assertTrue(controller.formedHabits("w3").isEmpty())

        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        listOf("w1", "w2", "w3").forEachIndexed { index, run ->
            controller.procedureTraceSink.record(receipt(run, 10L + index, slots = listOf(slot("ref", "main"))))
        }
        val habit = controller.formedHabits("w3").single()
        assertEquals(3, habit.uses)
        assertTrue(habit.offered)
        assertEquals(3, controller.formedHabits("w1").single().uses)
    }
}
