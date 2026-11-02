package com.hereliesaz.geministrator.telemetry

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object TraceSamples {
    val run = WorkflowRunId("run-1")
    val build = TaskDefinitionId("build")
    val role = RoleDefinitionId("implementation-engineer")

    fun usage(at: Long, input: Long, output: Long, cost: Double) = ProviderUsageRecorded(
        workflowRunId = run,
        taskDefinitionId = build,
        providerId = AgentProviderId("anthropic"),
        providerRunId = ProviderRunId("session-7"),
        inputTokens = input,
        outputTokens = output,
        costUsd = cost,
        latencyMillis = 400,
        occurredAtEpochMillis = at,
    )

    /** A run whose one task fails its first attempt, retries, and completes. */
    fun completedRun(): List<WorkflowEvent> = listOf(
        WorkflowCreated(run, ProjectId("project-1"), WorkflowDefinitionId("starter"), "SECRET-OBJECTIVE", 1_000),
        AgentAssigned(run, build, role, 1_100),
        ExecutorAssigned(run, build, TaskExecutor.RoleAgent(role), role, 1_100),
        TaskStarted(run, build, attempt = 1, occurredAtEpochMillis = 1_100),
        usage(2_000, input = 100, output = 20, cost = 0.01),
        usage(3_000, input = 50, output = 5, cost = 0.02),
        RetryScheduled(run, build, nextAttempt = 2, reason = "SECRET-RETRY-REASON", occurredAtEpochMillis = 3_100),
        AgentAssigned(run, build, role, 3_200),
        TaskStarted(run, build, attempt = 2, occurredAtEpochMillis = 3_200),
        usage(4_000, input = 30, output = 10, cost = 0.03),
        ArtifactCreated(
            run,
            build,
            ArtifactRef(
                id = ArtifactId("a-1"),
                kind = ArtifactKind.entries.first(),
                taskRunId = TaskRunId("run-1-build"),
                label = "SECRET-LABEL",
                textContent = "SECRET-OUTPUT",
                createdAtEpochMillis = 4_100,
            ),
            4_100,
        ),
        TaskCompleted(run, build, 4_200),
        WorkflowCompleted(run, 4_300),
    )
}

class WorkflowTraceBuilderTest {
    private fun List<OtelSpan>.named(prefix: String) = filter { it.name.startsWith(prefix) }

    private fun OtelSpan.text(key: String) = (attributes[key] as? OtelAttributeValue.Text)?.value
    private fun OtelSpan.int(key: String) = (attributes[key] as? OtelAttributeValue.Integer)?.value

    @Test
    fun aSampleRunBecomesAWorkflowSpanWithAnAgentSpanPerAttemptAndAChatSpanPerUsageReport() {
        val spans = WorkflowTraceBuilder.build(TraceSamples.completedRun())

        val workflow = spans.named("invoke_workflow").single()
        val agents = spans.named("invoke_agent")
        val chats = spans.named("chat")
        assertEquals(1 + 2 + 3, spans.size)
        assertEquals(2, agents.size)
        assertEquals(3, chats.size)

        assertNull(workflow.parentSpanId)
        assertEquals("invoke_workflow starter", workflow.name)
        assertEquals(1_000, workflow.startEpochMillis)
        assertEquals(4_300, workflow.endEpochMillis)
        assertEquals(OtelStatusCode.Ok, workflow.status)
        assertTrue(spans.all { it.traceId == workflow.traceId })

        val (first, second) = agents
        assertTrue(agents.all { it.parentSpanId == workflow.spanId })
        assertEquals("invoke_agent implementation-engineer", first.name)
        assertEquals(1L, first.int("aive.task.attempt"))
        assertEquals(1_100, first.startEpochMillis)
        assertEquals(3_100, first.endEpochMillis)
        assertEquals(OtelStatusCode.Error, first.status)
        assertEquals("retry_scheduled", first.text("error.type"))
        assertEquals(2L, second.int("aive.task.attempt"))
        assertEquals(OtelStatusCode.Ok, second.status)
        assertEquals(4_200, second.endEpochMillis)

        assertEquals(listOf(first.spanId, first.spanId, second.spanId), chats.map { it.parentSpanId })
        assertEquals(OtelSpanKind.Client, chats.first().kind)
        assertEquals(1_600, chats.first().startEpochMillis)
        assertEquals(2_000, chats.first().endEpochMillis)

        // Usage rolls up: each attempt sums its reports, the workflow sums them all.
        assertEquals(150L, first.int("gen_ai.usage.input_tokens"))
        assertEquals(25L, first.int("gen_ai.usage.output_tokens"))
        assertEquals(180L, workflow.int("gen_ai.usage.input_tokens"))
        assertEquals(35L, workflow.int("gen_ai.usage.output_tokens"))
        val cost = workflow.attributes["aive.usage.cost_usd"] as OtelAttributeValue.Decimal
        assertEquals(0.06, cost.value, 1e-9)
    }

    @Test
    fun spansCarryTheGenAiSemanticConventionAttributeNames() {
        val spans = WorkflowTraceBuilder.build(TraceSamples.completedRun())
        val workflow = spans.named("invoke_workflow").single()
        val agent = spans.named("invoke_agent").first()
        val chat = spans.named("chat").first()

        assertEquals("invoke_workflow", workflow.text("gen_ai.operation.name"))
        assertEquals("starter", workflow.text("gen_ai.workflow.name"))
        assertEquals("run-1", workflow.text("gen_ai.conversation.id"))

        assertEquals("invoke_agent", agent.text("gen_ai.operation.name"))
        assertEquals("implementation-engineer", agent.text("gen_ai.agent.name"))
        assertEquals("anthropic", agent.text("gen_ai.provider.name"))
        assertEquals("role_agent", agent.text("aive.executor.kind"))

        assertEquals("chat", chat.text("gen_ai.operation.name"))
        assertEquals("anthropic", chat.text("gen_ai.provider.name"))
        assertEquals(100L, chat.int("gen_ai.usage.input_tokens"))
        assertEquals(20L, chat.int("gen_ai.usage.output_tokens"))
        assertTrue("aive.usage.cost_usd" in chat.attributes)
        assertEquals("session-7", chat.text("aive.provider.run_id"))
    }

    @Test
    fun providerIdsMapToWellKnownProviderNames() {
        assertEquals("gcp.gemini", genAiProviderName("gemini"))
        assertEquals("x_ai", genAiProviderName("xai"))
        assertEquals("openai", genAiProviderName("openai"))
        assertEquals("openrouter", genAiProviderName("openrouter"))
    }

    @Test
    fun idsAreDeterministicPerRunTaskAndAttempt() {
        val once = WorkflowTraceBuilder.build(TraceSamples.completedRun())
        val again = WorkflowTraceBuilder.build(TraceSamples.completedRun())
        assertEquals(once.map { it.spanId }, again.map { it.spanId })
        assertEquals(once.first().traceId, again.first().traceId)

        assertEquals(once.size, once.map { it.spanId }.toSet().size)
        assertTrue(once.all { it.traceId.length == 32 && it.spanId.length == 16 })
        assertTrue(once.all { span -> span.traceId.all { it in "0123456789abcdef" } })
        assertFalse(once.any { it.traceId.all { c -> c == '0' } })

        val otherRun = TraceSamples.completedRun().map { event ->
            if (event is WorkflowCompleted) event.copy(workflowRunId = WorkflowRunId("run-2")) else event
        }
        assertNotEquals(traceIdFor("run-1"), traceIdFor("run-2"))
        // Events of another run in the same list are ignored.
        assertEquals(once.size, WorkflowTraceBuilder.build(otherRun).size)
    }

    @Test
    fun contentIsLeftOutByDefault() {
        val failing = TraceSamples.completedRun().dropLast(3) + listOf(
            TaskFailed(TraceSamples.run, TraceSamples.build, "SECRET-FAILURE", 4_200),
            WorkflowFailed(TraceSamples.run, "SECRET-FAILURE", 4_300),
        )
        val spans = WorkflowTraceBuilder.build(failing)
        val encoded = OtlpJson.traceRequest(spans).toString()
        assertFalse("SECRET" in encoded, encoded)
        assertTrue(spans.all { it.statusMessage == null })
        assertTrue(spans.none { span -> span.attributes.keys.any { it in GenAiAttributes.CONTENT_ATTRIBUTES } })
        assertEquals("workflow_failed", spans.named("invoke_workflow").single().text("error.type"))
    }

    @Test
    fun contentIsIncludedOnlyWhenTheSeparateToggleIsOn() {
        val spans = WorkflowTraceBuilder.build(TraceSamples.completedRun(), TraceContentPolicy(includeContent = true))
        val encoded = OtlpJson.traceRequest(spans).toString()
        assertTrue("SECRET-OBJECTIVE" in encoded)
        assertTrue("SECRET-OUTPUT" in encoded)
        assertTrue("SECRET-RETRY-REASON" in encoded)

        val clipped = WorkflowTraceBuilder.build(
            TraceSamples.completedRun(),
            TraceContentPolicy(includeContent = true, maxContentChars = 6),
        )
        assertEquals("SECRET", clipped.single { it.parentSpanId == null }.text("aive.workflow.objective"))
    }

    @Test
    fun theSettingsContentPolicyNeedsExportOnAndTheContentToggle() {
        assertFalse(TraceExportSettings(enabled = false, includeContent = true).contentPolicy.includeContent)
        assertFalse(TraceExportSettings(enabled = true, includeContent = false).contentPolicy.includeContent)
        assertTrue(TraceExportSettings(enabled = true, includeContent = true).contentPolicy.includeContent)
    }

    @Test
    fun anEmptyJournalHasNoSpans() {
        assertTrue(WorkflowTraceBuilder.build(emptyList()).isEmpty())
    }
}
