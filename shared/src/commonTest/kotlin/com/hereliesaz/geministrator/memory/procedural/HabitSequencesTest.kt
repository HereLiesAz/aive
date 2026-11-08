package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinitionId
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HabitSequencesTest {
    private val a = "github-action:ci.yml"
    private val b = "deployment:production"
    private val c = "repository:merge"

    private fun step(run: String, at: Long, operation: String, failed: Boolean = false) = ProcedureTrace(
        workflowId = run, projectId = "project", taskRunId = "tr-$run-$operation", taskDefinitionId = operation,
        attempt = 1, phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
        outcome = if (failed) ProcedureTraceOutcome.Failed else ProcedureTraceOutcome.Completed,
        recordedAtEpochMillis = at, operation = operation,
    )

    private fun run(name: String, vararg operations: String, start: Long = 10, failed: Set<String> = emptySet()) =
        operations.mapIndexed { index, op -> step(name, start + index, op, failed = op in failed) }

    @Test
    fun anOrderDoneInThreeRunsIsASequenceAndTwoRunsAreNot() {
        val three = HabitSequences.form(run("r1", a, b) + run("r2", a, b) + run("r3", a, b))
        assertEquals(listOf(a, b), three.single().operations)
        assertEquals(3, three.single().runs)
        assertTrue(three.single().offered)
        assertTrue(HabitSequences.form(run("r1", a, b) + run("r2", a, b)).isEmpty())
        // The same two steps in the other order are another order, seen only once.
        assertTrue(HabitSequences.form(run("r1", a, b) + run("r2", a, b) + run("r3", b, a)).isEmpty())
    }

    @Test
    fun aShorterSequenceInsideALongerOneSeenInTheSameRunsIsLeftOut() {
        val sequences = HabitSequences.form(run("r1", a, b, c) + run("r2", a, b, c) + run("r3", a, b, c))
        assertEquals(listOf(listOf(a, b, c)), sequences.map { it.operations })

        // Seen in more runs than the longer one, the shorter one says something the longer does not.
        val more = HabitSequences.form(run("r1", a, b, c) + run("r2", a, b, c) + run("r3", a, b, c) + run("r4", a, b))
        assertEquals(listOf(listOf(a, b), listOf(a, b, c)), more.map { it.operations })
        assertEquals(listOf(4, 3), more.map { it.runs })
    }

    @Test
    fun stepsMustFollowEachOtherAndAStretchWithTiedClocksIsSkipped() {
        val separated = HabitSequences.form(
            run("r1", a, "service:slack", b) + run("r2", a, "service:slack", b) + run("r3", a, "service:slack", b),
        )
        assertTrue(separated.none { it.operations == listOf(a, b) })
        assertTrue(separated.any { it.operations == listOf(a, "service:slack", b) })

        fun tied(name: String) = listOf(step(name, 10, a), step(name, 10, b))
        assertTrue(HabitSequences.form(tied("r1") + tied("r2") + tied("r3")).isEmpty())
    }

    @Test
    fun cleanRunsCountOnlyRunsWhereNoStepFailedOrWasTurnedBack() {
        val sequence = HabitSequences.form(
            run("r1", a, b) + run("r2", a, b, failed = setOf(b)) + run("r3", a, b),
        ).single()
        assertEquals(3, sequence.runs)
        assertEquals(2, sequence.cleanRuns)
    }

    @Test
    fun theOfferNamesTheSequenceAsObservedNotAsADependency() = runBlocking {
        val day = 86_400_000L
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
        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        listOf("w1", "w2", "w3").forEach { workflow ->
            run(workflow, a, b, start = 10 * day).forEach { controller.procedureTraceSink.record(it) }
        }
        val text = assertNotNull(controller.habitOfferText("w3", "please run CI again"))
        assertTrue("(the order is observed, not a dependency)" in text)
        assertTrue("- $a, then $b: in 3 runs, 3 of them with every step clean. Last seen 9 days ago." in text)
        // A task that names neither step is offered neither.
        assertEquals(null, controller.habitOfferText("w3", "write the release notes"))
        Unit
    }

    @Test
    fun aSequenceBecomesChainedTasksThatEachAskForApproval() {
        val habits = HabitFormation.form(run("r1", a, b) + run("r2", a, b) + run("r3", a, b))
        val sequence = HabitSequences.form(run("r1", a, b) + run("r2", a, b) + run("r3", a, b)).single()
        val result = assertIs<HabitInvocation.SequenceResult.Proposed>(HabitInvocation.proposeSequence(sequence, habits, "seq"))
        assertEquals(listOf("seq-1", "seq-2"), result.tasks.map { it.id.value })
        assertEquals(emptySet(), result.tasks[0].dependsOn)
        assertEquals(setOf(TaskDefinitionId("seq-1")), result.tasks[1].dependsOn)
        assertTrue(result.tasks.all { it.approvalPolicy == ApprovalPolicy.HumanApproval })

        assertIs<HabitInvocation.SequenceResult.Refused>(HabitInvocation.proposeSequence(sequence, habits.take(1), "seq"))
        assertIs<HabitInvocation.SequenceResult.Refused>(HabitInvocation.proposeSequence(sequence, habits, " "))
        val mystery = sequence.copy(operations = listOf(a, "mystery:thing"))
        val withMystery = habits + habits.first().copy(operation = "mystery:thing")
        assertIs<HabitInvocation.SequenceResult.Refused>(HabitInvocation.proposeSequence(mystery, withMystery, "seq"))
    }
}
