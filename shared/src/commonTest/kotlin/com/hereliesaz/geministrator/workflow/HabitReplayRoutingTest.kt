package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TaskUsage
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowEventSink
import com.hereliesaz.geministrator.memory.procedural.HabitReplayLedger
import com.hereliesaz.geministrator.memory.procedural.HabitReplayPlanner
import com.hereliesaz.geministrator.memory.procedural.ReplayDecision
import com.hereliesaz.geministrator.memory.procedural.ReplayInputs
import com.hereliesaz.geministrator.memory.procedural.ReplayQuery
import com.hereliesaz.geministrator.memory.procedural.ReplaySourceRun
import com.hereliesaz.geministrator.memory.procedural.ReplayStatus
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.policy.PolicyEffect
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.HabitReplayAgentProvider
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HabitReplayRoutingTest {
    private val tail = "- all checks passed and the team approved the rollout plan for this release."
    private val criteria = listOf("Keep it under 40 words")
    private val writer = RoleDefinition(RoleDefinitionId("writer"), "Writer", "Writes", "Write text.")
    private val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private fun source(i: Int, name: String) = ReplaySourceRun(
        "w$i", "t$i", 1, "writer", ReplayInputs("Write the status update for $name", criteria),
        "Research", "Status update for $name $tail", 100, 40, i.toLong(),
    )

    private fun learnedLedger(approve: Boolean = true): HabitReplayLedger {
        var stored: String? = null
        val ledger = HabitReplayLedger({ stored }, { stored = it })
        listOf("Falcon v1.4.2", "Osprey v2.0.1", "Heron v0.9.7").forEachIndexed { i, n -> ledger.recordSource(source(i, n)) }
        if (approve) ledger.approve(ledger.state.value.habits.single().id)
        return ledger
    }

    private fun request(objective: String = "Write the status update for Plover v6.1.0", plan: Boolean = false) = AgentTaskRequest(
        taskRunId = TaskRunId("tr"),
        objective = objective,
        roleInstructions = "",
        acceptanceCriteria = criteria.map(::AcceptanceCriterion),
        requirePlanApproval = plan,
        orchestrationContext = AgentOrchestrationContext(roleId = writer.id),
    )

    private class Other(override val id: AgentProviderId = AgentProviderId("other")) : AgentProvider {
        override suspend fun capabilities() = AgentCapabilities(supported = emptySet())
        override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("other/1"))
        override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
        override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult = ProviderActionResult.Accepted
        override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted
        override suspend fun cancel(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted
    }

    // ---- the provider ------------------------------------------------------------------------------------

    @Test
    fun theReplayIsDeterministicCostsNoTokensAndSaysWhereItCameFrom(): Unit = runBlocking {
        val ledger = learnedLedger()
        val provider = HabitReplayAgentProvider(HabitReplayPlanner(ledger))
        val first = provider.observe(provider.start(request()).providerRunId).toList()
        val second = provider.observe(provider.start(request()).providerRunId).toList()
        val artifact = first.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact
        assertEquals("Status update for Plover v6.1.0 $tail", artifact.textContent)
        assertEquals(artifact.textContent, second.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact.textContent)
        assertTrue(artifact.label.startsWith("Replayed from 3 earlier runs (habit "))
        assertTrue(first.filterIsInstance<AgentEvent.Progress>().single().message.contains("no model call"))
        val usage = first.filterIsInstance<AgentEvent.UsageReported>().single()
        assertEquals(0L, usage.inputTokens)
        assertEquals(0L, usage.outputTokens)
        assertEquals(0.0, usage.costUsd)
        assertEquals(140L, usage.tokensAvoided)
        assertIs<AgentEvent.Completed>(first.last())
        assertEquals(2, ledger.state.value.replays)
        assertEquals(280L, ledger.state.value.tokensAvoided)
    }

    @Test
    fun aTaskThatCannotBeReplayedFailsItsOwnSessionOnlyAndNeverCompletes(): Unit = runBlocking {
        val provider = HabitReplayAgentProvider(HabitReplayPlanner(learnedLedger(approve = false)))
        val events = provider.observe(provider.start(request()).providerRunId).toList()
        assertTrue(events.single() is AgentEvent.Failed)
        assertTrue(events.none { it is AgentEvent.Completed || it is AgentEvent.ArtifactProduced })
        assertEquals(null, provider.draftPlan(request()))
    }

    @Test
    fun aPlanApprovalTaskShowsTheReplayAsItsPlanAndWaitsForTheApproval() = runTest {
        val provider = HabitReplayAgentProvider(HabitReplayPlanner(learnedLedger()))
        assertEquals("Status update for Plover v6.1.0 $tail", provider.draftPlan(request(plan = true)))
        val runId = provider.start(request(plan = true)).providerRunId
        val events = mutableListOf<AgentEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { provider.observe(runId).collect { events += it } }
        advanceUntilIdle()
        assertIs<AgentEvent.PlanGenerated>(events.last())
        assertTrue(events.none { it is AgentEvent.ArtifactProduced || it is AgentEvent.Completed })
        provider.approvePlan(runId)
        advanceUntilIdle()
        assertIs<AgentEvent.Completed>(events.last())
        job.cancel()
    }

    // ---- ranking -----------------------------------------------------------------------------------------

    @Test
    fun theRegistryPutsTheReplayFirstOnlyWhenTheTaskIsEligible(): Unit = runBlocking {
        val replay = HabitReplayAgentProvider(HabitReplayPlanner(learnedLedger()))
        val registry = AgentProviderRegistry(listOf(Other(), replay), inferenceSettings = InMemorySettings())
        assertEquals(HabitReplayAgentProvider.ID, registry.select(ProviderSelectionRequest(habitReplay = true)).id)
        // Never on its own, when it is excluded, or when the task needs what it cannot do.
        assertEquals("other", registry.select(ProviderSelectionRequest()).id.value)
        assertEquals("other", registry.select(ProviderSelectionRequest(habitReplay = true, excludedProviderIds = setOf(HabitReplayAgentProvider.ID))).id.value)
        assertFalse(registry.hasAlternatives())
    }

    // ---- accounting --------------------------------------------------------------------------------------

    @Test
    fun avoidedTokensAreSummedAndSeparateFromTokensSpent() {
        val first = ManagedSessionUsage(inputTokens = 0, outputTokens = 0, costUsd = 0.0, tokensAvoided = 140)
        val sum = first.plusReport(first)
        assertEquals(280L, sum.tokensAvoided)
        val task = (null as TaskUsage?).plus(first).plus(first)
        assertNotNull(task)
        assertEquals(280L, task.tokensAvoided)
        assertEquals(0L, task.totalTokens)
        // A model run reports none.
        assertEquals(0L, ManagedSessionUsage(inputTokens = 5).sanitized().tokensAvoided)
    }

    // ---- learning, quarantine, invocation ----------------------------------------------------------------

    private class Events : WorkflowEventSink {
        val seen = mutableListOf<WorkflowEvent>()
        override suspend fun append(event: WorkflowEvent) { seen += event }
    }

    private val writerTask = TaskDefinitionId("write")
    private val reviewTask = TaskDefinitionId("review")

    private fun definition(objective: String) = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "w",
        tasks = listOf(
            TaskDefinition(
                id = writerTask, name = "Write", objective = objective, roleId = writer.id,
                executor = TaskExecutor.RoleAgent(writer.id),
                acceptanceCriteria = criteria.map(::AcceptanceCriterion),
            ),
            TaskDefinition(
                id = reviewTask, name = "Review", objective = "Verify", roleId = BuiltInRoles.QaEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
                dependsOn = setOf(writerTask), requiredArtifacts = setOf(ArtifactKind.Verification),
            ),
        ),
    )

    private fun run(
        i: Int,
        text: String,
        verdict: String = "VERDICT: approve",
        provider: AgentProviderId? = null,
        metadata: Map<String, String> = emptyMap(),
        reviewStatus: TaskRunStatus = TaskRunStatus.Completed,
    ): WorkflowRun {
        val out = ArtifactRef(ArtifactId("o$i"), ArtifactKind.Research, TaskRunId("tw$i"), "out", textContent = text, metadata = metadata, createdAtEpochMillis = 1L)
        val review = ArtifactRef(ArtifactId("v$i"), ArtifactKind.Verification, TaskRunId("tv$i"), "rev", textContent = verdict, createdAtEpochMillis = 1L)
        return WorkflowRun(
            id = WorkflowRunId("run$i"), projectId = project.id, workflowDefinitionId = WorkflowDefinitionId("w"),
            objective = "o", status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                writerTask to TaskRun(
                    TaskRunId("tw$i"), writerTask, TaskRunStatus.Completed, assignedRoleId = writer.id,
                    assignedProviderId = provider, artifacts = listOf(out), usage = TaskUsage(100, 40),
                ),
                reviewTask to TaskRun(TaskRunId("tv$i"), reviewTask, reviewStatus, assignedRoleId = BuiltInRoles.QaEngineer.id, artifacts = listOf(review)),
            ),
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 50L, roleSnapshot = listOf(writer),
        )
    }

    private val names = listOf("Falcon v1.4.2", "Osprey v2.0.1", "Heron v0.9.7")

    private fun observed(ledger: HabitReplayLedger, events: Events = Events()): HabitReplayObserver =
        HabitReplayObserver({ ledger }, { true }, events)

    @Test
    fun acceptedTextRunsTeachAHabitThatStaysUnapproved(): Unit = runBlocking {
        var stored: String? = null
        val ledger = HabitReplayLedger({ stored }, { stored = it })
        val observer = observed(ledger)
        names.forEachIndexed { i, n ->
            observer.observe(project, definition("Write the status update for $n"), run(i, "Status update for $n $tail"))
        }
        val habit = ledger.state.value.habits.single()
        assertEquals(3, habit.template.sourceRuns)
        assertFalse(habit.approved)
        assertEquals(140L, habit.template.tokensAvoided)
    }

    @Test
    fun aRunNobodyApprovedTeachesNothing(): Unit = runBlocking {
        val ledger = HabitReplayLedger({ null }, {})
        val observer = observed(ledger)
        names.forEachIndexed { i, n ->
            observer.observe(project, definition("Write the status update for $n"), run(i, "Status update for $n $tail", verdict = "Looks fine."))
        }
        assertTrue(ledger.state.value.sources.isEmpty())
    }

    @Test
    fun aRejectedReplayQuarantinesTheHabitAndTheNextTaskRunsNormally(): Unit = runBlocking {
        val ledger = learnedLedger()
        val habit = ledger.state.value.habits.single()
        val planner = HabitReplayPlanner(ledger)
        val query = ReplayQuery("writer", ReplayInputs("Write the status update for Plover v6.1.0", criteria), emptySet(), emptySet(), false, false)
        assertIs<ReplayDecision.Replay>(planner.decide(query))

        val replayed = run(
            9, "Status update for Plover v6.1.0 $tail", verdict = "VERDICT: reject\nwrong",
            provider = HabitReplayAgentProvider.ID,
            metadata = mapOf(HabitReplayAgentProvider.META_HABIT_ID to habit.id),
            reviewStatus = TaskRunStatus.Failed,
        )
        observed(ledger).observe(project, definition("Write the status update for Plover v6.1.0"), replayed)

        assertEquals(ReplayStatus.Quarantined, ledger.state.value.habits.single().status)
        assertIs<ReplayDecision.NotApplicable>(planner.decide(query))
        // Nothing asks for the replay provider now, so the ordinary provider runs the next task.
        val registry = AgentProviderRegistry(listOf(Other(), HabitReplayAgentProvider(planner)), inferenceSettings = InMemorySettings())
        assertEquals("other", registry.select(ProviderSelectionRequest()).id.value)
    }

    @Test
    fun anAgentThatChoosesAnApprovedHabitIsCountedAsAMatcherMissAndAnythingElseIsIgnoredInTheStory(): Unit = runBlocking {
        val ledger = learnedLedger()
        val habit = ledger.state.value.habits.single()
        val events = Events()
        val observer = observed(ledger, events)
        observer.observe(project, definition("Say hello"), run(1, "Hello there.\n/habit ${habit.ref}\n"))
        observer.observe(project, definition("Say hello"), run(2, "Hello there.\n/habit proc:unknown@1\n"))
        assertEquals(1, ledger.state.value.habits.single().matcherMisses)
        val decisions = events.seen.filterIsInstance<PolicyDecisionRecorded>()
        assertEquals(listOf("habit-invoked", "habit-ref-ignored"), decisions.map { it.code })
        assertEquals(listOf(PolicyEffect.Allow, PolicyEffect.Deny), decisions.map { it.effect })
        // An unapproved habit is ignored too.
        ledger.revoke(habit.id)
        observer.observe(project, definition("Say hello"), run(3, "Hello.\n/habit ${habit.ref}\n"))
        assertEquals("habit-ref-ignored", events.seen.filterIsInstance<PolicyDecisionRecorded>().last().code)
        assertEquals(1, ledger.state.value.habits.single().matcherMisses)
    }
}
