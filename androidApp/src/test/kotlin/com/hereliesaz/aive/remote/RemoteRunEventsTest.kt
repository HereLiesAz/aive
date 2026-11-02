package com.hereliesaz.aive.remote

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
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
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskBecameReady
import com.hereliesaz.geministrator.events.TaskCancelled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteRunEventsTest {
    private val runId = WorkflowRunId("run-1")
    private val build = TaskDefinitionId("build")
    private val secret = TaskDefinitionId("secret")
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("wf"),
        name = "Workflow",
        tasks = listOf(
            TaskDefinition(id = build, name = "Build it", objective = "build", roleId = null),
            TaskDefinition(id = secret, name = "Hold", objective = "hold", roleId = null, holdOutput = true),
        ),
    )
    private val run = runOf(runId)

    private fun runOf(id: WorkflowRunId, plan: String? = null) = WorkflowRun(
        id = id,
        projectId = ProjectId("p"),
        workflowDefinitionId = definition.id,
        objective = "Ship the thing",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            build to TaskRun(
                id = TaskRunId("tr-build"),
                taskDefinitionId = build,
                status = TaskRunStatus.AwaitingApproval,
                assignedRoleId = null,
                providerPlan = plan,
            ),
        ),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private val tasks = definition.tasks.associateBy(TaskDefinition::id)

    private fun map(event: WorkflowEvent, seq: Long = 7, onRun: WorkflowRun = run) =
        event.toRemoteRunEvent(eventSeq = seq, run = onRun, tasks = tasks)

    private fun artifact(text: String?, uri: String? = null, label: String = "Patch") = ArtifactRef(
        id = ArtifactId("a1"),
        kind = ArtifactKind.CodeChange,
        taskRunId = TaskRunId("tr-build"),
        label = label,
        uri = uri,
        textContent = text,
        mediaType = "text/x-diff",
        createdAtEpochMillis = 5,
    )

    @Test
    fun lifecycleEventsMapToRunStartedAndRunFinished() {
        val started = map(WorkflowCreated(runId, objective = null, occurredAtEpochMillis = 10))!!
        assertEquals(
            linkedMapOf<String, Any>("type" to "run_started", "runId" to "run-1", "eventSeq" to 7L, "at" to 10L, "objective" to "Ship the thing"),
            started.fields(),
        )
        assertEquals(
            mapOf<String, Any>("type" to "run_finished", "runId" to "run-1", "eventSeq" to 7L, "at" to 11L, "status" to "Completed"),
            map(WorkflowCompleted(runId, 11))!!.fields(),
        )
        assertEquals("Failed", map(WorkflowFailed(runId, "boom", 12))!!.fields()["status"])
        assertEquals("boom", map(WorkflowFailed(runId, "boom", 12))!!.fields()["reason"])
        assertEquals("Cancelled", map(WorkflowCancelled(runId, 13))!!.fields()["status"])
    }

    @Test
    fun taskEventsMapToTaskChangesWithTheStatusTheyLeave() {
        fun task(event: WorkflowEvent) = map(event) as RemoteRunEvent.TaskChanged
        assertEquals("ready" to "Ready", task(TaskBecameReady(runId, build, 1)).let { it.change to it.status })
        val started = task(TaskStarted(runId, build, attempt = 2, occurredAtEpochMillis = 1))
        assertEquals("started", started.change)
        assertEquals("Running", started.status)
        assertEquals(2, started.attempt)
        assertEquals("Build it", started.taskName)
        assertEquals("r1", task(AgentAssigned(runId, build, RoleDefinitionId("r1"), 1)).roleId)
        assertNull(task(VerificationFailed(runId, build, "tests red", 1)).status)
        assertEquals("tests red", task(VerificationFailed(runId, build, "tests red", 1)).reason)
        val retry = task(RetryScheduled(runId, build, nextAttempt = 3, reason = "flaky", occurredAtEpochMillis = 1))
        assertEquals(Triple("retry_scheduled", "Retrying", 3), Triple(retry.change, retry.status, retry.attempt))
        assertEquals("Escalated", task(TaskEscalated(runId, build, "stuck", occurredAtEpochMillis = 1)).status)
        assertEquals("Completed", task(TaskCompleted(runId, build, 1)).status)
        assertEquals("Failed", task(TaskFailed(runId, build, "no", 1)).status)
        assertEquals("Cancelled", task(TaskCancelled(runId, build, 1)).status)
        // Executor routing is internal; it maps to nothing.
        assertNull(map(ExecutorAssigned(runId, build, TaskExecutor.RoleAgent(RoleDefinitionId("r1")), occurredAtEpochMillis = 1)))
    }

    @Test
    fun gatesSayWhatTheyNeedAndCarryACappedPlanPreview() {
        val plan = "p".repeat(RemoteRunEventLimits.PLAN_PREVIEW_BYTES + 10)
        val gate = map(
            ApprovalRequired(runId, build, ApprovalGateId("g1"), "Approve the plan", 3),
            onRun = runOf(runId, plan = plan),
        ) as RemoteRunEvent.GateWaiting
        assertEquals("approval", gate.need)
        assertEquals("g1", gate.gateId)
        assertEquals("build", gate.taskId)
        assertEquals("Approve the plan", gate.reason)
        assertEquals(RemoteRunEventLimits.PLAN_PREVIEW_BYTES, gate.planPreview!!.length)
        assertTrue(gate.planTruncated)

        val noPlan = map(ApprovalRequired(runId, build, ApprovalGateId("g1"), "ok?", 3))!!.fields()
        assertFalse("planPreview" in noPlan)
        assertFalse("planTruncated" in noPlan)

        val decision = map(HumanDecisionRequired(runId, taskDefinitionId = null, reason = "pick", occurredAtEpochMillis = 4)) as RemoteRunEvent.GateWaiting
        assertEquals("decision", decision.need)
        assertNull(decision.gateId)
        assertNull(decision.taskId)

        val decided = map(ApprovalDecisionReceived(runId, build, ApprovalGateId("g1"), approved = true, decidedByRoleId = null, occurredAtEpochMillis = 5))!!
        assertEquals(
            mapOf<String, Any>("type" to "gate_decided", "runId" to "run-1", "eventSeq" to 7L, "at" to 5L, "gateId" to "g1", "taskId" to "build", "approved" to true),
            decided.fields(),
        )
    }

    @Test
    fun artifactsCarryACappedPreviewAndTheirFullSize() {
        // Two-byte characters: the cap counts UTF-8 bytes and never splits a character.
        val text = "é".repeat(RemoteRunEventLimits.PREVIEW_BYTES)
        val produced = map(ArtifactCreated(runId, build, artifact(text), 6)) as RemoteRunEvent.ArtifactProduced
        assertEquals(RemoteRunEventLimits.PREVIEW_BYTES / 2, produced.preview!!.length)
        assertEquals(RemoteRunEventLimits.PREVIEW_BYTES, produced.preview!!.encodeToByteArray().size)
        assertEquals(text.encodeToByteArray().size.toLong(), produced.sizeBytes)
        assertTrue(produced.truncated)
        assertEquals("CodeChange", produced.kind)
        assertEquals("text/x-diff", produced.mediaType)

        val small = map(ArtifactCreated(runId, build, artifact("diff"), 6))!!.fields()
        assertEquals("diff", small["preview"])
        assertEquals(false, small["truncated"])
        assertEquals(4L, small["sizeBytes"])
        assertFalse("withheld" in small)

        val linkOnly = map(ArtifactCreated(runId, build, artifact(text = null, uri = "https://example.com/pr/1"), 6))!!.fields()
        assertFalse("preview" in linkOnly)
        assertFalse("sizeBytes" in linkOnly)
        // The uri is not sent: it can carry tokens or local paths.
        assertFalse(linkOnly.values.contains("https://example.com/pr/1"))

        val longLabel = map(ArtifactCreated(runId, build, artifact("x", label = "L".repeat(1000)), 6)) as RemoteRunEvent.ArtifactProduced
        assertEquals(RemoteRunEventLimits.NAME_BYTES, longLabel.label.length)
    }

    @Test
    fun aTaskThatHoldsItsOutputSendsNoPreview() {
        val held = map(ArtifactCreated(runId, secret, artifact("top secret"), 6)) as RemoteRunEvent.ArtifactProduced
        assertTrue(held.withheld)
        assertNull(held.preview)
        assertFalse(held.fields().values.contains("top secret"))
    }

    @Test
    fun usageAndCappedReasons() {
        val usage = map(
            ProviderUsageRecorded(
                runId, build, AgentProviderId("kilo"), ProviderRunId("pr"),
                inputTokens = 100, outputTokens = 20, costUsd = 0.5, occurredAtEpochMillis = 9,
            ),
        )!!.fields()
        assertEquals("usage", usage["type"])
        assertEquals("kilo", usage["providerId"])
        assertEquals(100L, usage["inputTokens"])
        assertEquals(20L, usage["outputTokens"])
        assertEquals(0.5, usage["costUsd"])

        val failed = map(TaskFailed(runId, build, "x".repeat(10_000), 1))!!.fields()
        assertEquals(RemoteRunEventLimits.REASON_BYTES, (failed["reason"] as String).length)
    }

    @Test
    fun capUtf8NeverSplitsSurrogatePairs() {
        val emoji = "😀" // 4 UTF-8 bytes
        assertEquals("", capUtf8(emoji, 3))
        assertEquals(emoji, capUtf8(emoji, 4))
        assertEquals("ab", capUtf8("ab$emoji", 5))
    }

    @Test
    fun theLogNumbersEventsByTimelinePositionAndOnlyMapsNewOnes() {
        val timeline = listOf(
            WorkflowCreated(runId, objective = "Ship", occurredAtEpochMillis = 1),
            ExecutorAssigned(runId, build, TaskExecutor.RoleAgent(RoleDefinitionId("r1")), occurredAtEpochMillis = 2),
            TaskStarted(runId, build, 1, 3),
        )
        val log = RemoteRunEventLog.EMPTY.advance(run, definition, timeline)
        assertEquals(listOf(1L, 3L), log.events.map { it.eventSeq })
        assertEquals("run-1", log.runId)
        assertEquals(3, log.timelineSize)

        assertSame(log, log.advance(run, definition, timeline), "an unchanged timeline changes nothing")

        val grown = log.advance(run, definition, timeline + TaskCompleted(runId, build, 4))
        assertEquals(log.generation, grown.generation)
        assertEquals(listOf(1L, 3L, 4L), grown.events.map { it.eventSeq })
        assertSame(log.events[0], grown.events[0], "earlier events are kept, not mapped again")
    }

    @Test
    fun theLogKeepsOnlyTheNewestEventsAndRestartsForAnotherRunOrAShorterTimeline() {
        val many = (1..RemoteRunEventLimits.RETAINED_EVENTS + 50).map { TaskStarted(runId, build, it, it.toLong()) }
        val log = RemoteRunEventLog.EMPTY.advance(run, definition, many)
        assertEquals(RemoteRunEventLimits.RETAINED_EVENTS, log.events.size)
        assertEquals(51L, log.events.first().eventSeq)
        assertEquals(many.size.toLong(), log.events.last().eventSeq)

        val shorter = log.advance(run, definition, many.take(3))
        assertEquals(log.generation + 1, shorter.generation)
        assertEquals(listOf(1L, 2L, 3L), shorter.events.map { it.eventSeq })

        val otherId = WorkflowRunId("run-2")
        val other = shorter.advance(runOf(otherId), definition, listOf(WorkflowCreated(otherId, occurredAtEpochMillis = 1)))
        assertEquals(shorter.generation + 1, other.generation)
        assertEquals("run-2", other.runId)
        assertEquals(listOf(1L), other.events.map { it.eventSeq })
    }

    @Test
    fun aCursorSendsEachEventOnceInOrderAndStartsOverOnANewGeneration() {
        val timeline = (1..3).map { TaskStarted(runId, build, it, it.toLong()) }
        val log = RemoteRunEventLog.EMPTY.advance(run, definition, timeline)
        val cursor = RemoteRunEventCursor()
        assertEquals(listOf(1L, 2L, 3L), cursor.take(log).map { it.eventSeq })
        assertTrue(cursor.take(log).isEmpty())

        val grown = log.advance(run, definition, timeline + TaskCompleted(runId, build, 9))
        assertEquals(listOf(4L), cursor.take(grown).map { it.eventSeq })

        val otherId = WorkflowRunId("run-2")
        val other = grown.advance(runOf(otherId), definition, listOf(WorkflowCreated(otherId, occurredAtEpochMillis = 1)))
        assertEquals(listOf(1L), cursor.take(other).map { it.eventSeq })
        assertTrue(cursor.take(RemoteRunEventLog.EMPTY).isEmpty())
    }

    @Test
    fun aCursorResumesOnlyTheRunItWasGiven() {
        val timeline = (1..5).map { TaskStarted(runId, build, it, it.toLong()) }
        val log = RemoteRunEventLog.EMPTY.advance(run, definition, timeline)
        assertEquals(listOf(4L, 5L), RemoteRunEventCursor(resumeRunId = "run-1", resumeAfterSeq = 3).take(log).map { it.eventSeq })
        assertEquals(5, RemoteRunEventCursor(resumeRunId = "run-other", resumeAfterSeq = 3).take(log).size)
        assertEquals(5, RemoteRunEventCursor(resumeRunId = "run-1", resumeAfterSeq = -9).take(log).size)
    }
}
