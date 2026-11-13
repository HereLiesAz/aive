package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskClock
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TimeLimit
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.domain.WorkflowSpendPause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val MIN = 60_000L
private val auto8 = Allowance(8 * MIN, 16 * MIN, Basis.Baseline)

class ActiveClockTest {
    @Test fun activeTimeIsElapsedMinusWaits() {
        val c = ActiveClock.begin(1, 0, auto8).copy(waitedMillis = 3 * MIN)
        assertEquals(7 * MIN, ActiveClock.active(c, 10 * MIN))
    }

    @Test fun openWaitIsNotCounted() {
        val c = ActiveClock.begin(1, 0, auto8).copy(waitingSinceEpochMillis = 4 * MIN)
        assertEquals(4 * MIN, ActiveClock.active(c, 100 * MIN))
    }

    @Test fun waitOpensAndClosesOnQuestion() {
        var c = ActiveClock.begin(1, 0, auto8)
        c = ActiveClock.step(c, 1 * MIN, counting = true)
        c = ActiveClock.step(c, 2 * MIN, counting = false)
        assertEquals(2 * MIN, c.waitingSinceEpochMillis)
        c = ActiveClock.step(c, 30 * MIN, counting = true)
        assertNull(c.waitingSinceEpochMillis)
        assertEquals(28 * MIN, c.waitedMillis)
        assertEquals(2 * MIN, ActiveClock.active(c, 30 * MIN))
    }

    @Test fun gapOverTwoMinutesIsAwayNotActive() {
        var c = ActiveClock.begin(1, 0, auto8)
        c = ActiveClock.step(c, 1 * MIN, counting = true)
        c = ActiveClock.step(c, 61 * MIN, counting = true)
        assertEquals(1 * MIN, ActiveClock.active(c, 61 * MIN))
    }

    @Test fun afterRestartAddsDowntimeToWaited() {
        val c = ActiveClock.afterRestart(ActiveClock.begin(1, 0, auto8), 90 * MIN)
        assertEquals(90 * MIN, c.waitedMillis)
        assertEquals(0L, ActiveClock.active(c, 90 * MIN))
    }

    @Test fun clockStepBackwardsNeverGoesNegative() {
        val c = ActiveClock.begin(1, 10 * MIN, auto8)
        assertEquals(0L, ActiveClock.active(c, 5 * MIN))
        assertSame(c, ActiveClock.step(c, 5 * MIN, counting = true))
    }

    @Test fun heartbeatWrittenAtMostOncePerMinute() {
        val c = ActiveClock.begin(1, 0, auto8)
        assertSame(c, ActiveClock.step(c, 30_000, counting = true))
        val beat = ActiveClock.step(c, MIN, counting = true)
        assertEquals(MIN, beat.lastSeenEpochMillis)
        assertSame(beat, ActiveClock.step(beat, MIN + 30_000, counting = true))
    }
}

class DeadlinePolicyTest {
    private val none = SourceTrait()

    @Test fun baselineByKind() {
        assertEquals(2 * MIN, DeadlinePolicy.baseline(WorkKind.HostedModel, StepSize(), none))
        assertEquals(10 * MIN, DeadlinePolicy.baseline(WorkKind.CliAgent, StepSize(), none))
        assertEquals(20 * MIN, DeadlinePolicy.baseline(WorkKind.ExternalAgent, StepSize(), none))
        assertEquals(30 * MIN, DeadlinePolicy.baseline(WorkKind.LongCreative, StepSize(), none))
    }

    @Test fun contextAndUpstreamRaiseBaseline() {
        val small = DeadlinePolicy.baseline(WorkKind.CliAgent, StepSize(), none)
        val big = DeadlinePolicy.baseline(WorkKind.CliAgent, StepSize(contextTokens = 30_000, upstreamArtifacts = 2), none)
        assertEquals(small + 3 * MIN + MIN, big)
    }

    @Test fun keylessAddsFactorAndPaceSlack() {
        val plain = DeadlinePolicy.baseline(WorkKind.HostedModel, StepSize(), none)
        val keyless = DeadlinePolicy.baseline(WorkKind.HostedModel, StepSize(), SourceTrait(keyless = true))
        assertEquals(plain * 3 / 2 + 2 * MIN, keyless)
        val paced = DeadlinePolicy.baseline(WorkKind.HostedModel, StepSize(), SourceTrait(true, 30_000))
        assertEquals(keyless + 30_000, paced)
    }

    @Test fun humanAndNestedAreUnlimited() {
        assertTrue(DeadlinePolicy.resolveAuto(WorkKind.Human, StepSize(), none).unlimited)
        assertTrue(DeadlinePolicy.resolveAuto(WorkKind.Nested, StepSize(), none).unlimited)
    }

    @Test fun keeperMinutesWinOverEverything() {
        val auto = DeadlinePolicy.resolveAuto(WorkKind.CliAgent, StepSize(), none)
        val e = DeadlinePolicy.effective(TimeLimit.Minutes(90), auto)
        assertEquals(90 * MIN, e.millis)
        assertEquals(180 * MIN, e.hardMillis)
        assertEquals(Basis.Keeper, e.basis)
        assertEquals(auto, DeadlinePolicy.effective(TimeLimit.Auto, auto))
    }

    @Test fun offReturnsNoLimit() {
        assertTrue(DeadlinePolicy.effective(TimeLimit.Off, auto8).unlimited)
    }

    @Test fun allowanceNeverBelowKindMinimumOrAboveMaximum() {
        val huge = StepSize(contextTokens = 100_000_000, upstreamArtifacts = 1_000_000)
        assertEquals(20 * MIN, DeadlinePolicy.baseline(WorkKind.HostedModel, huge, SourceTrait(true, 10 * MIN)))
        assertEquals(2 * MIN, DeadlinePolicy.baseline(WorkKind.HostedModel, StepSize(), none, rolePercent = 1))
    }

    @Test fun roleMultiplierBounded() {
        val base = DeadlinePolicy.baseline(WorkKind.CliAgent, StepSize(contextTokens = 100_000), none)
        val low = DeadlinePolicy.baseline(WorkKind.CliAgent, StepSize(contextTokens = 100_000), none, rolePercent = 10)
        val high = DeadlinePolicy.baseline(WorkKind.CliAgent, StepSize(contextTokens = 100_000), none, rolePercent = 900)
        assertEquals(base * 75 / 100, low)
        assertEquals(minOf(base * 150 / 100, 60 * MIN), high)
    }

    @Test fun identicalInputsGiveIdenticalAllowance() {
        val a = DeadlinePolicy.resolveAuto(WorkKind.ToolLoopModel, StepSize(5000, 1), none, listOf(1, 2, 3))
        assertEquals(a, DeadlinePolicy.resolveAuto(WorkKind.ToolLoopModel, StepSize(5000, 1), none, listOf(1, 2, 3)))
    }

    @Test fun hardIsTwiceOrFiveMinutesPast() {
        assertEquals(10 * MIN, DeadlinePolicy.hardFor(MIN * 5))
        assertEquals(40 * MIN, DeadlinePolicy.hardFor(20 * MIN))
    }
}

class DeadlineAdaptTest {
    private val base = 10 * MIN

    @Test fun fewerThanFiveSamplesKeepsBaseline() {
        assertEquals(base, DeadlinePolicy.adapt(base, List(4) { 40 * MIN }, WorkKind.CliAgent))
    }

    @Test fun p90TimesSlackBlendedByCount() {
        // 10 samples of 12 min: p90 12, x1.5 = 18, weight 10/20: (10 + 18) / 2 = 14 minutes.
        assertEquals(14 * MIN, DeadlinePolicy.adapt(base, List(10) { 12 * MIN }, WorkKind.CliAgent))
    }

    @Test fun boundedToHalfAndTripleBaseline() {
        assertEquals(30 * MIN, DeadlinePolicy.adapt(base, List(40) { 500 * MIN }, WorkKind.CliAgent))
        // The kind's own minimum holds even when half the baseline is lower.
        assertEquals(10 * MIN, DeadlinePolicy.adapt(base, List(40) { MIN }, WorkKind.CliAgent))
    }

    @Test fun censoredExpiredAttemptsRaiseTheEstimate() {
        val fast = List(10) { 4 * MIN }
        val withExpired = fast + List(10) { 20 * MIN }
        assertTrue(DeadlinePolicy.adapt(20 * MIN, withExpired, WorkKind.ExternalAgent) >
            DeadlinePolicy.adapt(20 * MIN, fast, WorkKind.ExternalAgent))
    }
}

class DeadlineReducerTest {
    private val id = TaskDefinitionId("t")

    private fun clock(soft: Long? = null, progressAt: Long = 0, waitingSince: Long? = null) =
        ActiveClock.begin(1, 0, auto8).copy(
            softAtEpochMillis = soft, progressAtEpochMillis = progressAt, waitingSinceEpochMillis = waitingSince,
        )

    private fun verdict(c: TaskClock, now: Long, limit: TimeLimit = TimeLimit.Auto, counting: Boolean = true, progress: Long? = null) =
        DeadlineReducer.verdict(c, limit, now, counting, progress)

    @Test fun okBeforeAllowance() = assertEquals(DeadlineVerdict.Ok, verdict(clock(), 7 * MIN))

    @Test fun softOnceAtAllowance() {
        assertEquals(DeadlineVerdict.Soft, verdict(clock(), 8 * MIN))
        assertEquals(DeadlineVerdict.Ok, verdict(clock(soft = 8 * MIN), 9 * MIN))
    }

    @Test fun softNeverForOffHumanNested() {
        assertEquals(DeadlineVerdict.Ok, verdict(clock(), 500 * MIN, limit = TimeLimit.Off))
        val none = ActiveClock.begin(1, 0, Allowance.None)
        assertEquals(DeadlineVerdict.Ok, verdict(none, 500 * MIN))
    }

    @Test fun hardAtCeiling() = assertEquals(DeadlineVerdict.Hard(Why.Ceiling), verdict(clock(), 16 * MIN))

    @Test fun hardWhenPastAllowanceAndSilent() {
        assertEquals(DeadlineVerdict.Hard(Why.Silent), verdict(clock(progressAt = 0), 11 * MIN, progress = 0))
    }

    @Test fun aliveCreatureRunsToCeiling() {
        assertEquals(DeadlineVerdict.Ok, verdict(clock(soft = 8 * MIN), 12 * MIN, progress = 11 * MIN))
        assertEquals(DeadlineVerdict.Hard(Why.Ceiling), verdict(clock(soft = 8 * MIN), 16 * MIN, progress = 15 * MIN))
    }

    @Test fun silenceAloneBeforeAllowanceNeverStops() =
        assertEquals(DeadlineVerdict.Ok, verdict(clock(), 7 * MIN, progress = 0))

    @Test fun noProgressKindsUseCeilingOnly() {
        assertEquals(DeadlineVerdict.Soft, verdict(clock(), 12 * MIN, progress = null))
    }

    @Test fun waitingOnKeeperNeverAdvances() {
        assertEquals(DeadlineVerdict.Ok, verdict(clock(), 500 * MIN, counting = false))
        val c = ActiveClock.step(clock(), 1 * MIN, counting = false)
        assertEquals(1 * MIN, ActiveClock.active(c, 500 * MIN))
    }

    @Test fun keeperExtensionAppliesToARunningAttempt() {
        assertEquals(DeadlineVerdict.Ok, verdict(clock(), 20 * MIN, limit = TimeLimit.Minutes(60)))
    }

    /** [seen] is when the cycle last looked, a minute before the test's `now`, so the gap is not read as the app being away. */
    private fun fixture(status: TaskRunStatus = TaskRunStatus.Running, spendPause: WorkflowSpendPause? = null, seen: Long = 0): Pair<WorkflowDefinition, WorkflowRun> {
        val def = WorkflowDefinition(
            id = WorkflowDefinitionId("d"), name = "d",
            tasks = listOf(TaskDefinition(id = id, name = "Step", objective = "o", roleId = null)),
        )
        val run = WorkflowRun(
            id = WorkflowRunId("r"), projectId = ProjectId("p"), workflowDefinitionId = def.id, objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(id to TaskRun(TaskRunId("tr"), id, status, assignedRoleId = null, clock = clock().copy(lastSeenEpochMillis = seen))),
            createdAtEpochMillis = 0, updatedAtEpochMillis = 0, spendPause = spendPause,
        )
        return def to run
    }

    @Test fun hardMarksFailedWithTimedOutWording() {
        val (def, run) = fixture(seen = 15 * MIN)
        val r = DeadlineReducer.reduce(def, run, 16 * MIN, { null }, autoFor = { _, _ -> auto8 })
        val t = r.run.taskRuns.getValue(id)
        assertEquals(TaskRunStatus.Failed, t.status)
        assertEquals("Timed out after 16 minutes of work (allowed 8 minutes)", t.progressMessage)
        assertEquals(1, t.timedOutAttempts)
        assertEquals(Why.Ceiling, r.hardStops[id])
        assertNotNull(t.clock?.activeMillisAtEnd)
        assertEquals(com.hereliesaz.geministrator.ErrorKind.TimedOut, com.hereliesaz.geministrator.classifyError(t.progressMessage))
    }

    @Test fun softMarksOnceAndLeavesRunning() {
        val (def, run) = fixture(seen = 8 * MIN)
        val r = DeadlineReducer.reduce(def, run, 9 * MIN, { null }, autoFor = { _, _ -> auto8 })
        assertEquals(setOf(id), r.softened)
        val t = r.run.taskRuns.getValue(id)
        assertEquals(TaskRunStatus.Running, t.status)
        assertEquals(9 * MIN, t.clock?.softAtEpochMillis)
        assertTrue(DeadlineReducer.reduce(def, r.run, 9 * MIN + 1, { null }, autoFor = { _, _ -> auto8 }).softened.isEmpty())
    }

    @Test fun spendPausedRunNeverAdvances() {
        val (def, run) = fixture(spendPause = WorkflowSpendPause(SpendCapKind.RunCost, "cap", 1.0, 1.0, emptyList(), 0))
        val r = DeadlineReducer.reduce(def, run, 500 * MIN, { null }, autoFor = { _, _ -> auto8 })
        assertTrue(r.hardStops.isEmpty())
        assertEquals(TaskRunStatus.Running, r.run.taskRuns.getValue(id).status)
    }

    @Test fun preUpgradeRunGetsAFreshFullAllowance() {
        val (def, run) = fixture()
        val old = run.copy(taskRuns = run.taskRuns.mapValues { it.value.copy(clock = null) })
        val r = DeadlineReducer.reduce(def, old, 500 * MIN, { null }, autoFor = { _, _ -> auto8 })
        assertTrue(r.hardStops.isEmpty())
        assertEquals(500 * MIN, r.run.taskRuns.getValue(id).clock?.startedAtEpochMillis)
    }
}
