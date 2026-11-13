package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.ErrorKind
import com.hereliesaz.geministrator.classifyError
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.orchestration.FailoverOrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.orchestration.PlannerCandidate
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderFailoverTest {
    // --- pure decisions -------------------------------------------------------------------

    @Test
    fun onlyAProvidersOwnFailureFailsOver() {
        listOf(
            "HTTP 402 Payment Required" to true,
            "HTTP 401 Unauthorized" to true,
            "HTTP 429 Too Many Requests" to true,
            "HTTP 503 Service Unavailable" to true,
            "Read timed out" to true,
            "Unable to resolve host api.openai.com" to true,
            // Would fail the same on every provider.
            "HTTP 400 Bad Request: malformed schema" to false,
            "The response was blocked by the content policy" to false,
            "No agent provider satisfies required capabilities" to false,
            null to false,
        ).forEach { (raw, expected) -> assertEquals(expected, ProviderFailover.shouldFailOver(raw), raw) }
        assertEquals(ErrorKind.ServerError, classifyError("HTTP 502 Bad Gateway"))
    }

    @Test
    fun creditAndKeyBenchLongerThanRateLimitAndServerErrors() {
        assertTrue(ProviderFailover.cooldownMillis(ErrorKind.OutOfCredit) > ProviderFailover.cooldownMillis(ErrorKind.RateLimited))
        assertTrue(ProviderFailover.cooldownMillis(ErrorKind.KeyRejected) > ProviderFailover.cooldownMillis(ErrorKind.ServerError))
        assertEquals(0L, ProviderFailover.cooldownMillis(ErrorKind.Other))
    }

    @Test
    fun benchExpiresAfterItsCooldownAndClearsOnSuccess() {
        var now = 0L
        val bench = ProviderBench { now }
        bench.bench(ProviderFailover.failureOf("a", "A", "HTTP 429"))
        assertTrue(bench.isBenched("a"))
        now += ProviderFailover.cooldownMillis(ErrorKind.RateLimited)
        assertFalse(bench.isBenched("a"))
        bench.bench(ProviderFailover.failureOf("a", "A", "HTTP 402"))
        assertTrue(bench.isBenched("a"))
        bench.clear("a")
        assertFalse(bench.isBenched("a"))
        // A failure that is not the provider's own benches nothing.
        bench.bench(ProviderFailover.failureOf("b", "B", "HTTP 400 Bad Request"))
        assertFalse(bench.isBenched("b"))
    }

    @Test
    fun nextCandidateSkipsTriedPrefersHealthyAndKeepsBenchedAsLastResort() {
        val all = listOf("a", "b", "c")
        assertEquals("b", ProviderFailover.nextCandidate(all, { it }, tried = setOf("a"), isBenched = { false }))
        assertEquals("c", ProviderFailover.nextCandidate(all, { it }, tried = setOf("a"), isBenched = { it == "b" }))
        // Everything left is benched: still tried once, so a keeper who added credit is not locked out.
        assertEquals("b", ProviderFailover.nextCandidate(all, { it }, tried = setOf("a"), isBenched = { true }))
        assertNull(ProviderFailover.nextCandidate(all, { it }, tried = all.toSet(), isBenched = { false }))
    }

    @Test
    fun aReplyCutOffAtTheOutputLimitFailsOverButBenchesNobody() {
        listOf(
            "Compatible chat response was truncated because the provider reached its output-token limit",
            "Claude response was truncated at the token limit; output is incomplete",
            "Gemini response was truncated at the token limit; output is incomplete",
            "OpenAI response was truncated at the output-token limit; output is incomplete",
        ).forEach { raw ->
            assertEquals(ErrorKind.OutputTooLong, classifyError(raw), raw)
            assertTrue(ProviderFailover.shouldFailOver(raw), raw)
        }
        // Other early stops, and unrelated "truncated" text, are not this.
        assertEquals(ErrorKind.Other, classifyError("Gemini response stopped early (SAFETY); output is incomplete"))
        assertEquals(ErrorKind.Other, classifyError("[truncated at 4000 characters]"))
        assertEquals(0L, ProviderFailover.cooldownMillis(ErrorKind.OutputTooLong))
        val cut = ProviderFailover.failureOf("gemini", "Gemini", "Gemini response was truncated at the token limit")
        assertEquals("Gemini ran out of room for its answer; continued with Claude.", ProviderFailover.note(cut, "Claude"))
        val bench = ProviderBench { 0L }
        bench.bench(cut)
        assertFalse(bench.isBenched("gemini"))
    }

    @Test
    fun everyProviderTruncatingGivesThePlainMessageNamingThem() {
        val a = ProviderFailover.failureOf("gemini", "Gemini", "Gemini response was truncated at the token limit")
        val b = ProviderFailover.failureOf("openai", "OpenAI", "OpenAI response was truncated at the output-token limit")
        assertEquals(
            "The plan was too long for the source's output limit (tried Gemini, OpenAI). " +
                "Try a shorter description, or a source with a larger limit.",
            ProviderFailover.allFailedMessage(listOf(a, b)),
        )
        // Mixed failures keep the per-provider wording.
        val credit = ProviderFailover.failureOf("claude", "Claude", "HTTP 402")
        val mixed = ProviderFailover.allFailedMessage(listOf(a, credit))
        assertTrue("Gemini ran out of room" in mixed && "Claude is out of credit" in mixed, mixed)
    }

    @Test
    fun messagesAreCalmNameEveryProviderAndSayWhatToDo() {
        val credit = ProviderFailover.failureOf("openai", "OpenAI", "HTTP 402")
        assertEquals("OpenAI is out of credit; continued with Claude.", ProviderFailover.note(credit, "Claude"))
        val key = ProviderFailover.failureOf("anthropic", "Claude", "HTTP 401")
        val all = ProviderFailover.allFailedMessage(listOf(credit, key))
        assertTrue("OpenAI is out of credit" in all && "Add credit" in all, all)
        assertTrue("Claude rejected its key" in all && "Reconnect" in all, all)
        val pinned = ProviderFailover.pinnedMessage(credit)
        assertTrue("did not switch" in pinned && "choose another provider" in pinned, pinned)
    }

    // --- gateway with fake providers ------------------------------------------------------

    private fun request(id: String, selection: ProviderSelectionRequest) = ManagedSessionRequest(
        providerSelection = selection,
        taskRequest = AgentTaskRequest(
            taskRunId = TaskRunId(id),
            objective = "Do the thing",
            roleInstructions = "Do it.",
            acceptanceCriteria = emptyList(),
        ),
    )

    private fun auto(first: String) = ProviderSelectionRequest(
        constraints = ProviderConstraints.RequireProvider(AgentProviderId(first)),
        failoverAllowed = true,
    )

    @Test
    fun startFailureOnOneProviderContinuesOnTheNextAndRecordsANote() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = FailoverFakeProvider("openai", startError = "HTTP 402 Payment Required")
        val b = FailoverFakeProvider("anthropic")
        try {
            val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
            val gateway = ProviderBackedManagedSessionGateway(registry, scope)
            val handle = gateway.createSession(request("t1", auto("openai")))
            assertEquals(AgentProviderId("anthropic"), handle.providerId)
            assertEquals(1, a.starts)
            assertEquals(1, b.starts)
            assertEquals(
                listOf("OpenAI / Codex is out of credit; continued with Claude."),
                registry.failoverNotes.value.map { it.text },
            )
            // Benched: the next automatic choice goes straight to Claude.
            assertEquals(AgentProviderId("anthropic"), registry.select(ProviderSelectionRequest()).id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailureThatWouldRepeatAnywhereDoesNotFailOver() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = FailoverFakeProvider("openai", startError = "HTTP 400 Bad Request: malformed")
        val b = FailoverFakeProvider("anthropic")
        try {
            val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
            val gateway = ProviderBackedManagedSessionGateway(registry, scope)
            assertFailsWith<ManagedSessionFailure> { gateway.createSession(request("t2", auto("openai"))) }
            assertEquals(0, b.starts)
            assertTrue(registry.failoverNotes.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun allProvidersFailingStopsWithAMessageNamingEachOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = FailoverFakeProvider("openai", startError = "HTTP 402 Payment Required")
        val b = FailoverFakeProvider("anthropic", startError = "HTTP 401 Unauthorized")
        try {
            val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
            val gateway = ProviderBackedManagedSessionGateway(registry, scope)
            val failure = assertFailsWith<ManagedSessionFailure> { gateway.createSession(request("t3", auto("openai"))) }
            val message = failure.message.orEmpty()
            assertTrue("OpenAI / Codex is out of credit" in message && "Claude rejected its key" in message, message)
            assertEquals(1, a.starts)
            assertEquals(1, b.starts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSingleProviderIsUnchanged() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = FailoverFakeProvider("openai", startError = "HTTP 402 Payment Required")
        try {
            val registry = AgentProviderRegistry(listOf(a), inferenceSettings = InMemorySettings())
            val gateway = ProviderBackedManagedSessionGateway(registry, scope)
            val failure = assertFailsWith<ManagedSessionFailure> { gateway.createSession(request("t4", auto("openai"))) }
            assertTrue("402" in failure.message.orEmpty())
            assertEquals(1, a.starts)
            assertEquals(FailoverAdvice.NotApplicable, gateway.adviseFailover(a.id, "HTTP 402", auto("openai")))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun midTaskDeathIsAdvisedToRestartOnTheNextProviderButAPinnedCreatureStays() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = FailoverFakeProvider("openai")
        val b = FailoverFakeProvider("anthropic")
        try {
            val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
            val gateway = ProviderBackedManagedSessionGateway(registry, scope)
            val advice = gateway.adviseFailover(a.id, "HTTP 429 Too Many Requests", ProviderSelectionRequest(failoverAllowed = true))
            assertEquals(FailoverAdvice.Restart("OpenAI / Codex is rate limited; continued with Claude."), advice)

            val pinned = gateway.adviseFailover(a.id, "HTTP 429 Too Many Requests", ProviderSelectionRequest(failoverAllowed = false))
            val stop = pinned as FailoverAdvice.Stop
            assertTrue("did not switch" in stop.message, stop.message)

            // Both tried: nothing left, the message names both.
            val exhausted = gateway.adviseFailover(
                b.id,
                "HTTP 401 Unauthorized",
                ProviderSelectionRequest(failoverAllowed = true, excludedProviderIds = setOf(a.id)),
            ) as FailoverAdvice.Stop
            assertTrue("OpenAI / Codex" in exhausted.message && "Claude" in exhausted.message, exhausted.message)

            assertEquals(FailoverAdvice.NotApplicable, gateway.adviseFailover(a.id, "Task blew up", ProviderSelectionRequest(failoverAllowed = true)))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun engineRestartsAFailedTaskOnAnotherProviderWithoutSpendingARetryAndPinnedStays() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = FailoverFakeProvider("openai")
        val b = FailoverFakeProvider("anthropic")
        try {
            val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
            val engine = WorkflowEngine(ProviderBackedManagedSessionGateway(registry, scope), emptyList())
            val taskId = com.hereliesaz.geministrator.domain.TaskDefinitionId("t")
            val failed = com.hereliesaz.geministrator.domain.TaskRun(
                id = TaskRunId("run-t"),
                taskDefinitionId = taskId,
                status = com.hereliesaz.geministrator.domain.TaskRunStatus.Failed,
                assignedRoleId = null,
                assignedProviderId = a.id,
                providerRunId = ProviderRunId("openai-run"),
            )
            val run = com.hereliesaz.geministrator.domain.WorkflowRun(
                id = com.hereliesaz.geministrator.domain.WorkflowRunId("w"),
                projectId = com.hereliesaz.geministrator.domain.ProjectId("p"),
                workflowDefinitionId = com.hereliesaz.geministrator.domain.WorkflowDefinitionId("d"),
                objective = "o",
                status = com.hereliesaz.geministrator.domain.WorkflowRunStatus.Running,
                taskRuns = mapOf(taskId to failed),
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
            )
            val result = engine.failOver(run, taskId, a.id, "HTTP 402 Payment Required", ProviderSelectionRequest(failoverAllowed = true), 1L)
            val restarted = requireNotNull(result.restarted).taskRuns.getValue(taskId)
            assertEquals(com.hereliesaz.geministrator.domain.TaskRunStatus.Retrying, restarted.status)
            assertEquals(listOf(a.id), restarted.failedProviders)
            assertEquals("OpenAI / Codex is out of credit; continued with Claude.", restarted.progressMessage)
            // The restart used no retry: attempt 2 with one failover counts as the first attempt.
            assertEquals(2, restarted.attempt)
            assertEquals(1, restarted.attempt - restarted.failedProviders.size)

            val pinned = engine.failOver(run, taskId, a.id, "HTTP 402 Payment Required", ProviderSelectionRequest(failoverAllowed = false), 1L)
            assertNull(pinned.restarted)
            assertTrue("did not switch" in pinned.stopMessage.orEmpty())
        } finally {
            scope.cancel()
        }
    }

    // --- planner / draft ------------------------------------------------------------------

    private val packet = OrchestrationPacket(
        objective = "Ship it",
        availableAgents = listOf(OrchestrationRole("dev", "Dev", emptyList())),
        instruction = "Plan.",
    )
    private val plan = OrchestrationPlan(listOf(OrchestrationPlanStep("s1", "Step", "Do", "dev")))

    private class FakePlanner(val failWith: String? = null) : OrchestrationAgentRuntime {
        var calls = 0
        override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan {
            calls++
            failWith?.let { error(it) }
            return OrchestrationPlan(listOf(OrchestrationPlanStep("s1", "Step", "Do", "dev")))
        }
        override suspend fun repair(packet: OrchestrationPacket) = plan(packet)
    }

    @Test
    fun plannerFailsOverAndReportsANote() = runBlocking {
        val a = FakePlanner("HTTP 402 Payment Required")
        val b = FakePlanner()
        val notes = mutableListOf<String>()
        val runtime = FailoverOrchestrationAgentRuntime(listOf(PlannerCandidate("openai", "OpenAI", a), PlannerCandidate("anthropic", "Claude", b)))
        val result = withContext(FailoverNotes { notes += it }) { runtime.plan(packet) }
        assertEquals(plan, result)
        assertEquals(listOf("OpenAI is out of credit; continued with Claude."), notes)
        // OpenAI is benched, so the next plan goes straight to Claude.
        runtime.plan(packet)
        assertEquals(1, a.calls)
        assertEquals(2, b.calls)
    }

    @Test
    fun plannerWithAllFailingNamesEveryProviderAndABadPromptIsNotRetried() = runBlocking {
        val a = FakePlanner("HTTP 402 Payment Required")
        val b = FakePlanner("HTTP 503 Service Unavailable")
        val runtime = FailoverOrchestrationAgentRuntime(listOf(PlannerCandidate("openai", "OpenAI", a), PlannerCandidate("anthropic", "Claude", b)))
        val failure = assertFailsWith<AllProvidersFailedException> { runtime.plan(packet) }
        val message = failure.message.orEmpty()
        assertTrue("OpenAI is out of credit" in message && "Claude is having trouble" in message, message)

        val refusing = FakePlanner("HTTP 400 Bad Request")
        val other = FakePlanner()
        val strict = FailoverOrchestrationAgentRuntime(listOf(PlannerCandidate("openai", "OpenAI", refusing), PlannerCandidate("anthropic", "Claude", other)))
        assertFailsWith<IllegalStateException> { strict.plan(packet) }
        assertEquals(0, other.calls)

        val single = FailoverOrchestrationAgentRuntime(listOf(PlannerCandidate("openai", "OpenAI", FakePlanner("HTTP 402"))))
        assertFailsWith<IllegalStateException> { single.plan(packet) }
        Unit
    }
}

private class FailoverFakeProvider(name: String, private val startError: String? = null) : AgentProvider {
    override val id = AgentProviderId(name)
    var starts = 0
    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        starts++
        startError?.let { error(it) }
        return AgentRunHandle(ProviderRunId("${id.value}-run"))
    }
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
