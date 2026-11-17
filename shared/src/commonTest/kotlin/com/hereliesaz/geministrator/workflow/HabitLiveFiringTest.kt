package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.CONTEXT_PROVENANCE_METADATA_KEY
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowEventSink
import com.hereliesaz.geministrator.memory.procedural.FormedHabit
import com.hereliesaz.geministrator.memory.procedural.HabitInvocation
import com.hereliesaz.geministrator.memory.procedural.HabitReplayLedger
import com.hereliesaz.geministrator.memory.procedural.HabitStepReplay
import com.hereliesaz.geministrator.memory.procedural.HabitStrength
import com.hereliesaz.geministrator.memory.procedural.ReplayInputs
import com.hereliesaz.geministrator.memory.procedural.ReplaySourceRun
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An agent's `/habit <ref>` line acts on the live run: an approved executor habit adds its step behind its own
 * human-approval task, after the task that chose it; anything else changes nothing and the story says why.
 */
class HabitLiveFiringTest {
    private val op = "github-action:ci.yml"
    private val ref = "proc:$op@1"
    private val writer = RoleDefinition(RoleDefinitionId("writer"), "Writer", "Writes", "Write text.")
    private val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val choosing = TaskDefinitionId("write")
    private val later = TaskDefinitionId("later")

    private class Events : WorkflowEventSink {
        val seen = mutableListOf<WorkflowEvent>()
        override suspend fun append(event: WorkflowEvent) { seen += event }
        val codes: List<String> get() = seen.filterIsInstance<PolicyDecisionRecorded>().map { it.code }
    }

    private fun habit(verified: Int = 3) = FormedHabit(
        executorKind = "GitHubAction", operation = op, uses = verified, completedUses = verified, failedUses = 0,
        verifiedUses = verified, rejectedUses = 0, slots = emptyList(), firstUsedAtEpochMillis = 1L,
        lastUsedAtEpochMillis = 2L, strength = HabitStrength.Low,
    )

    private fun ledger(approved: Boolean = true, quarantined: Boolean = false): HabitReplayLedger {
        val ledger = HabitReplayLedger({ null }, {})
        if (approved) ledger.approveOperation(op, 1)
        if (quarantined) ledger.quarantineOperation(op)
        return ledger
    }

    private fun definition() = WorkflowDefinition(
        id = WorkflowDefinitionId("w"), name = "w",
        tasks = listOf(
            TaskDefinition(
                id = choosing, name = "Write", objective = "Say hello", roleId = writer.id,
                executor = TaskExecutor.RoleAgent(writer.id),
            ),
            TaskDefinition(
                id = later, name = "Later", objective = "Say goodbye", roleId = writer.id,
                executor = TaskExecutor.RoleAgent(writer.id), dependsOn = setOf(choosing),
            ),
        ),
    )

    private fun run(
        text: String,
        status: TaskRunStatus = TaskRunStatus.Completed,
        runStatus: WorkflowRunStatus = WorkflowRunStatus.Running,
        provenance: ContextProvenance = ContextProvenance.AgentArtifact,
    ): WorkflowRun {
        // The runtime labels what a task produced; an unlabelled artifact counts as unknown, which is untrusted.
        val out = ArtifactRef(
            ArtifactId("o"), ArtifactKind.Research, TaskRunId("tw"), "out", textContent = text,
            metadata = mapOf(CONTEXT_PROVENANCE_METADATA_KEY to provenance.name), createdAtEpochMillis = 1L,
        )
        return WorkflowRun(
            id = WorkflowRunId("run"), projectId = project.id, workflowDefinitionId = WorkflowDefinitionId("w"),
            objective = "o", status = runStatus,
            taskRuns = mapOf(
                choosing to TaskRun(TaskRunId("tw"), choosing, status, assignedRoleId = writer.id, artifacts = listOf(out)),
                later to TaskRun(TaskRunId("tl"), later, TaskRunStatus.Blocked, assignedRoleId = writer.id),
            ),
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 50L, roleSnapshot = listOf(writer),
        )
    }

    private fun firing(events: Events, state: HabitReplayLedger, habits: List<FormedHabit> = listOf(habit())) = HabitLiveFiring(
        events = events,
        composition = WorkflowCompositionService(InMemoryWorkflowPersistence()),
        propose = { r, prefix -> HabitStepReplay.planFor(habits, r, state.state.value, prefix) },
    )

    private suspend fun choices(state: HabitReplayLedger, text: String, events: Events = Events(), run: WorkflowRun = run(text)): List<HabitChoice> {
        val observer = HabitReplayObserver({ state }, { true }, events)
        observer.observe(project, definition(), run)
        return observer.takeChoices()
    }

    private class Result(var definition: WorkflowDefinition, var run: WorkflowRun, var fired: Boolean = false)

    private suspend fun fire(f: HabitLiveFiring, choice: HabitChoice, into: Result): Boolean {
        into.fired = f.fire(choice, into.definition, into.run, 100L) { d, r -> into.definition = d; into.run = r }
        return into.fired
    }

    @Test
    fun anApprovedChoiceAddsAnApprovalGatedStepAfterTheChoosingTaskAndChangesNothingElse(): Unit = runBlocking {
        val state = ledger()
        val events = Events()
        val choice = choices(state, "Hello.\n/habit $ref\n", events).single()
        val into = Result(definition(), run("Hello.\n/habit $ref\n"))
        val before = into.run.taskRuns.getValue(choosing)

        assertTrue(fire(firing(events, state), choice, into))

        val step = into.definition.tasks.single { it.name == "Habit: $op" }
        val gate = into.definition.tasks.single { it.id == step.dependsOn.single() }
        assertIs<TaskExecutor.HumanApproval>(gate.executor)
        assertEquals(setOf(choosing), gate.dependsOn, "it starts only once the choosing task is done")
        assertIs<TaskExecutor.GitHubAction>(step.executor)
        // The task that chose it, and the rest of the run, are as they were.
        assertEquals(before, into.run.taskRuns.getValue(choosing))
        assertEquals(TaskRunStatus.Blocked, into.run.taskRuns.getValue(later).status)
        assertEquals(definition().tasks, into.definition.tasks.take(2))
        // The gate is released (its dependency is complete) and waits for a person; the step waits behind it.
        assertTrue(into.run.taskRuns.getValue(gate.id).status in setOf(TaskRunStatus.Ready, TaskRunStatus.AwaitingApproval))
        assertEquals(TaskRunStatus.Blocked, into.run.taskRuns.getValue(step.id).status)
        assertEquals(into.definition, into.run.definitionSnapshot)
        assertEquals(listOf("habit-invoked", "habit-fired"), events.codes)
    }

    @Test
    fun aRepeatForTheSameTaskIsIgnoredWithAStoryLine(): Unit = runBlocking {
        val state = ledger()
        val events = Events()
        val choice = choices(state, "x\n/habit $ref\n").single()
        val into = Result(definition(), run("x\n/habit $ref\n"))
        val f = firing(events, state)
        assertTrue(fire(f, choice, into))
        val tasksAfterFirst = into.definition.tasks
        assertFalse(fire(f, choice, into))
        assertEquals(tasksAfterFirst, into.definition.tasks)
        assertEquals(listOf("habit-fired", "habit-ignored"), events.codes)
    }

    @Test
    fun anUnapprovedOrUnknownOrQuarantinedOrUntrustedChoiceNeverReachesTheRun(): Unit = runBlocking {
        assertTrue(choices(ledger(approved = false), "x\n/habit $ref\n").isEmpty())
        assertTrue(choices(ledger(), "x\n/habit proc:deployment:production@1\n").isEmpty())
        assertTrue(choices(ledger(quarantined = true), "x\n/habit $ref\n").isEmpty())
        val external = run("x\n/habit $ref\n", provenance = ContextProvenance.ExternalContent)
        val events = Events()
        assertTrue(choices(ledger(), "x\n/habit $ref\n", events, external).isEmpty())
        assertEquals(listOf("habit-ref-ignored"), events.codes)
    }

    @Test
    fun aHabitQuarantinedAfterTheChoiceWasQueuedCannotBeFired(): Unit = runBlocking {
        val state = ledger()
        val choice = choices(state, "x\n/habit $ref\n").single()
        state.quarantineOperation(op)
        val events = Events()
        val into = Result(definition(), run("x"))
        assertFalse(fire(firing(events, state), choice, into))
        assertEquals(definition().tasks, into.definition.tasks)
        assertEquals(listOf("habit-ignored"), events.codes)
    }

    @Test
    fun aHabitTheCatalogueNoLongerOffersIsIgnored(): Unit = runBlocking {
        val state = ledger()
        val choice = choices(state, "x\n/habit $ref\n").single()
        val events = Events()
        val into = Result(definition(), run("x"))
        assertFalse(fire(firing(events, state, habits = listOf(habit(verified = 1))), choice, into))
        assertEquals(listOf("habit-ignored"), events.codes)
    }

    @Test
    fun aFinishedRunTakesNothingAndSaysSo(): Unit = runBlocking {
        val state = ledger()
        val choice = choices(state, "x\n/habit $ref\n").single()
        val events = Events()
        val into = Result(definition(), run("x", runStatus = WorkflowRunStatus.Completed))
        assertFalse(fire(firing(events, state), choice, into))
        assertEquals(definition().tasks, into.definition.tasks)
        assertEquals(listOf("habit-not-eligible"), events.codes)
    }

    @Test
    fun aTextHabitChoiceIsNeverAppliedBecauseTheTaskHadAlreadyFinished(): Unit = runBlocking {
        val criteria = listOf("Keep it under 40 words")
        val tail = "- all checks passed and the team approved the rollout plan for this release."
        var stored: String? = null
        val ledger = HabitReplayLedger({ stored }, { stored = it })
        listOf("Falcon v1.4.2", "Osprey v2.0.1", "Heron v0.9.7").forEachIndexed { i, n ->
            ledger.recordSource(
                ReplaySourceRun(
                    "w$i", "t$i", 1, "writer", ReplayInputs("Write the status update for $n", criteria),
                    "Research", "Status update for $n $tail", 100, 40, i.toLong(),
                ),
            )
        }
        val habit = ledger.state.value.habits.single()
        ledger.approve(habit.id)
        val events = Events()
        val observer = HabitReplayObserver({ ledger }, { true }, events)
        val started = run("Hello.\n/habit ${habit.ref}\n")
        observer.observe(project, definition(), started)
        assertTrue(observer.takeChoices().isEmpty())
        assertEquals(listOf("habit-invoked", "habit-not-eligible"), events.codes)
    }
}
