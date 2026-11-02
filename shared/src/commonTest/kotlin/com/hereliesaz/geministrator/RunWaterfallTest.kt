package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunWaterfallTest {
    private val run = WorkflowRunId("run")
    private fun id(task: String) = TaskDefinitionId(task)
    private fun started(task: String, at: Long, attempt: Int = 1) = TaskStarted(run, id(task), attempt, at)
    private fun completed(task: String, at: Long) = TaskCompleted(run, id(task), at)
    private fun segments(bar: WaterfallBar) = bar.segments.map { Triple(it.phase, it.startMillis, it.endMillis) }
    private fun bars(waterfall: RunWaterfall, task: String) = waterfall.rows.first { it.taskId == id(task) }.bars

    @Test
    fun parallelTasksOverlapOnOneAxis() {
        val waterfall = runWaterfall(
            listOf(
                WorkflowCreated(run, occurredAtEpochMillis = 0),
                started("contract", 0),
                started("research", 50),
                completed("contract", 100),
                completed("research", 150),
                WorkflowCompleted(run, 150),
            ),
            nowEpochMillis = 10_000,
        )
        assertEquals(listOf(id("contract"), id("research")), waterfall.rows.map { it.taskId })
        val contract = bars(waterfall, "contract").single()
        val research = bars(waterfall, "research").single()
        assertEquals(0L to 100L, contract.startMillis to contract.endMillis)
        assertEquals(50L to 150L, research.startMillis to research.endMillis)
        assertTrue(contract.endMillis > research.startMillis, "parallel attempts overlap")
        assertEquals(WaterfallOutcome.Completed, contract.outcome)
        assertFalse(waterfall.live)
        // The run ended: nothing runs on to "now".
        assertEquals(0L to 150L, waterfall.startMillis to waterfall.endMillis)
    }

    @Test
    fun aFailedCheckRetriesInANewBar() {
        val artifact = ArtifactRef(ArtifactId("a"), ArtifactKind.Research, TaskRunId("r"), "notes", textContent = "x", createdAtEpochMillis = 40)
        val waterfall = runWaterfall(
            listOf(
                started("build", 0),
                ArtifactCreated(run, id("build"), artifact, 40),
                VerificationFailed(run, id("build"), "tests red", 60),
                RetryScheduled(run, id("build"), nextAttempt = 2, reason = "tests red", occurredAtEpochMillis = 60),
                started("build", 80, attempt = 2),
                completed("build", 120),
                WorkflowCompleted(run, 120),
            ),
            nowEpochMillis = 0,
        )
        val (first, second) = bars(waterfall, "build")
        assertEquals(1, first.attempt)
        assertEquals(
            listOf(
                Triple(WaterfallPhase.Running, 0L, 40L),
                Triple(WaterfallPhase.Verifying, 40L, 60L),
                Triple(WaterfallPhase.Retrying, 60L, 80L),
            ),
            segments(first),
        )
        assertEquals(WaterfallOutcome.Failed, first.outcome)
        assertEquals(2, second.attempt)
        assertEquals(listOf(Triple(WaterfallPhase.Running, 80L, 120L)), segments(second))
        assertEquals(WaterfallOutcome.Completed, second.outcome)
    }

    @Test
    fun aFailureAndItsRetryStayOnTheFailedAttempt() {
        val waterfall = runWaterfall(
            listOf(
                started("build", 0),
                TaskFailed(run, id("build"), "429", 30),
                RetryScheduled(run, id("build"), nextAttempt = 2, reason = "429", occurredAtEpochMillis = 30),
                started("build", 50, attempt = 2),
            ),
            nowEpochMillis = 90,
        )
        val (first, second) = bars(waterfall, "build")
        assertEquals(listOf(Triple(WaterfallPhase.Running, 0L, 30L), Triple(WaterfallPhase.Retrying, 30L, 50L)), segments(first))
        assertEquals(WaterfallOutcome.Failed, first.outcome)
        assertFalse(first.ongoing)
        assertEquals(50L to 90L, second.startMillis to second.endMillis)
        assertTrue(second.ongoing)
    }

    @Test
    fun aGateIsAPauseAndWhatCameBeforeItIsPlanning() {
        val gate = ApprovalGateId("g")
        val waterfall = runWaterfall(
            listOf(
                started("plan", 0),
                ApprovalRequired(run, id("plan"), gate, "Approve the plan", 20),
                ApprovalDecisionReceived(run, id("plan"), gate, approved = true, decidedByRoleId = null, occurredAtEpochMillis = 50),
                completed("plan", 100),
            ),
            nowEpochMillis = 100,
        )
        val bar = bars(waterfall, "plan").single()
        assertEquals(
            listOf(
                Triple(WaterfallPhase.Planning, 0L, 20L),
                Triple(WaterfallPhase.WaitingOnYou, 20L, 50L),
                Triple(WaterfallPhase.Running, 50L, 100L),
            ),
            segments(bar),
        )
        assertEquals(30L, bar.waitingMillis)
    }

    @Test
    fun aGateBeforeTheStartOpensTheAttemptEarly() {
        val gate = ApprovalGateId("g")
        val role = BuiltInRoles.Architect.id
        val waterfall = runWaterfall(
            listOf(
                HumanDecisionRequired(run, id("plan"), "Plan approval", 0),
                ApprovalDecisionReceived(run, id("plan"), gate, approved = true, decidedByRoleId = null, occurredAtEpochMillis = 40),
                AgentAssigned(run, id("plan"), role, 45),
                ExecutorAssigned(run, id("plan"), TaskExecutor.RoleAgent(role), role, 45),
                started("plan", 45),
                completed("plan", 60),
            ),
            nowEpochMillis = 60,
        )
        val bar = bars(waterfall, "plan").single()
        assertEquals(1, bar.attempt)
        assertEquals(
            listOf(
                Triple(WaterfallPhase.WaitingOnYou, 0L, 40L),
                Triple(WaterfallPhase.Planning, 40L, 45L),
                Triple(WaterfallPhase.Running, 45L, 60L),
            ),
            segments(bar),
        )
    }

    @Test
    fun anUnfinishedTaskRunsToNow() {
        val events = listOf<WorkflowEvent>(started("build", 10), started("review", 20), completed("review", 30))
        val waterfall = runWaterfall(events, nowEpochMillis = 500)
        val build = bars(waterfall, "build").single()
        assertEquals(10L to 500L, build.startMillis to build.endMillis)
        assertTrue(build.ongoing)
        assertNull(build.outcome)
        assertTrue(waterfall.live)
        assertEquals(500L, waterfall.endMillis)
        // A clock behind the journal never cuts a bar short.
        assertEquals(30L, runWaterfall(events, nowEpochMillis = 0).endMillis)
        // Journal order does not matter.
        assertEquals(waterfall, runWaterfall(events.reversed(), nowEpochMillis = 500))
    }

    @Test
    fun aCancelledRunClosesWhatWasOpen() {
        val waterfall = runWaterfall(listOf(started("build", 0), WorkflowCancelled(run, 40)), nowEpochMillis = 900)
        val bar = bars(waterfall, "build").single()
        assertEquals(40L, bar.endMillis)
        assertEquals(WaterfallOutcome.Cancelled, bar.outcome)
        assertFalse(bar.ongoing)
    }

    @Test
    fun usageIsSummedOntoItsAttempt() {
        fun usage(at: Long, input: Long, output: Long, cost: Double?) = ProviderUsageRecorded(
            run, id("build"), AgentProviderId("claude"), ProviderRunId("p"),
            inputTokens = input, outputTokens = output, costUsd = cost, occurredAtEpochMillis = at,
        )
        val waterfall = runWaterfall(
            listOf(started("build", 0), usage(10, 1_000, 200, 0.02), usage(20, 500, 100, null), completed("build", 30)),
            nowEpochMillis = 30,
        )
        val bar = bars(waterfall, "build").single()
        assertEquals(WaterfallUsage(1_500, 300, 0.02), bar.usage)
        assertEquals(1_800L, bar.usage?.totalTokens)
        assertNull(bars(runWaterfall(listOf(started("x", 0)), 5), "x").single().usage)
    }

    @Test
    fun emptyJournalIsAnEmptyWaterfall() {
        assertTrue(runWaterfall(emptyList(), 100).isEmpty)
        assertTrue(runWaterfall(listOf(WorkflowCreated(run, occurredAtEpochMillis = 1)), 100).isEmpty)
    }

    @Test
    fun formatsDurationsAndCosts() {
        assertEquals("3m 12s", formatWaterfallDuration(192_400))
        assertEquals("0s", formatWaterfallDuration(400))
        assertEquals("\$0.04", formatWaterfallCost(0.0412))
        assertEquals("\$1.50", formatWaterfallCost(1.5))
        assertEquals("<\$0.01", formatWaterfallCost(0.001))
    }

    @Test
    fun everyPhaseColourReadsOnInk() {
        WaterfallPhase.entries.forEach { phase ->
            assertTrue(contrastRatio(waterfallPhaseColor(phase), Azphalt.Ink) >= 3f, "$phase below 3:1 on Ink")
        }
        assertEquals(WaterfallPhase.entries.size, WaterfallPhase.entries.map(::waterfallPhaseColor).toSet().size)
    }
}
