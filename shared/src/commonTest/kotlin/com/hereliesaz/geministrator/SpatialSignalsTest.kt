package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
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
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.WebGroup
import com.hereliesaz.geministrator.memory.WebNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hive's light is read from the run's own records: blue for memory in use, pulses for deliveries, a burst for a finish. */
class SpatialSignalsTest {
    private val role = BuiltInRoles.ImplementationEngineer
    private val build = TaskDefinitionId("build")
    private val check = TaskDefinitionId("check")

    private fun def(id: TaskDefinitionId, vararg deps: TaskDefinitionId) = TaskDefinition(
        id = id, name = id.value, objective = "o", roleId = role.id, dependsOn = deps.toSet(),
        executor = TaskExecutor.RoleAgent(role.id),
    )

    private val definition = WorkflowDefinition(WorkflowDefinitionId("w"), "W", tasks = listOf(def(build), def(check, build)))

    private fun taskRun(id: TaskDefinitionId, status: TaskRunStatus = TaskRunStatus.Completed, progress: Float? = null) = TaskRun(
        id = TaskRunId("tr-${id.value}"), taskDefinitionId = id, status = status, assignedRoleId = role.id,
        assignedProviderId = AgentProviderId("p"), providerRunId = ProviderRunId("s-${id.value}"), progress = progress,
        artifacts = listOf(ArtifactRef(ArtifactId("a-${id.value}"), ArtifactKind.Research, TaskRunId("tr-${id.value}"), id.value,
            textContent = "work", createdAtEpochMillis = 1L)),
    )

    private val run = WorkflowRun(
        id = WorkflowRunId("run"), projectId = ProjectId("p"), workflowDefinitionId = definition.id, objective = "o",
        status = WorkflowRunStatus.Running, taskRuns = mapOf(build to taskRun(build), check to taskRun(check)),
        createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L, roleSnapshot = listOf(role), definitionSnapshot = definition,
    )

    private fun report(task: TaskDefinitionId, at: Long, memory: Set<String>? = null, delivered: Set<String>? = emptySet()) =
        ProviderUsageRecorded(run.id, task, AgentProviderId("p"), ProviderRunId("s-${task.value}"), occurredAtEpochMillis = at,
            deliveredArtifactIds = delivered, deliveredMemoryAddresses = memory)

    @Test
    fun carriedMemoryOfEveryKindIsActivatedAndAnUnreportedOneIsNot() {
        val uses = memorySporeUses(run, listOf(report(check, 5_000, memory = setOf("memory-node:tag-1", "memory-node:sum-2")),
            report(build, 1_000, memory = null)))
        assertEquals(listOf("node:sum-2", "node:tag-1"), uses.map { it.sporeId })
        assertEquals("node:x", sporeIdOfAddress("memory-node:x"))
        assertNull(sporeIdOfAddress("artifact:x"))
        assertNull(sporeIdOfAddress("memory-node:"))
    }

    @Test
    fun anActivatedMemoryFlipsFromWarmToBlueHoldsAndReturns() {
        assertEquals(0f, blueGlow(1_000, 1_000)) // still its warm resting glow at the instant
        assertEquals(1f, blueGlow(1_150, 1_000)) // flipped
        assertEquals(1f, blueGlow(2_000, 1_000)) // held
        assertTrue(blueGlow(2_800, 1_000) in 0.01f..0.99f) // easing back to warm
        assertEquals(0f, blueGlow(1_000 + BLUE_GLOW_MILLIS, 1_000))
        assertEquals(0f, blueGlow(500, 1_000)) // never before the event
    }

    @Test
    fun onlyTheSpecificDataThatWasUsedFlipsAndOnlyIfItIsReallyInTheConstellation() {
        val uses = listOf(SporeUse(check, "node:a", 1_000), SporeUse(build, "node:a", 1_100),
            SporeUse(check, "node:gone", 1_000))
        val lit = blueSpores(1_150, uses, setOf("node:a", "node:b"))
        assertEquals(setOf("node:a"), lit.keys) // not the unused neighbour b, and not a node the web does not hold
        assertEquals(1f, lit.getValue("node:a")) // the strongest of the concurrent uses
        assertEquals(emptyMap(), blueSpores(1_000 + BLUE_GLOW_MILLIS + 1, uses, setOf("node:a")))
    }

    @Test
    fun aDeliveryIsAPulseFromTheSenderToTheReceiver() {
        val pulses = transferPulses(run, listOf(report(check, 7_000, delivered = setOf("a-build"))))
        assertEquals(listOf(TransferPulse(build, check, 7_000)), pulses)
        assertEquals(emptyList(), transferPulses(run, listOf(report(check, 7_000, delivered = null))))
        assertEquals(emptyList(), transferPulses(run, listOf(report(check, 7_000, delivered = setOf("unrelated")))))
    }

    @Test
    fun theRateRisesWithRealProgressAndNeverWithoutIt() {
        assertNull(pulseRateHz(null))
        assertNull(pulseRateHz(taskRun(build, TaskRunStatus.Ready)))
        assertNull(pulseRateHz(taskRun(build, TaskRunStatus.Completed))) // a finished one bursts instead
        assertEquals(PULSE_MIN_HZ, pulseRateHz(taskRun(build, TaskRunStatus.Running, progress = null)))
        assertEquals(PULSE_MAX_HZ, pulseRateHz(taskRun(build, TaskRunStatus.Running, progress = 1f)))
        val early = requireNotNull(pulseRateHz(taskRun(build, TaskRunStatus.Running, progress = .2f)))
        val late = requireNotNull(pulseRateHz(taskRun(build, TaskRunStatus.Running, progress = .8f)))
        assertTrue(late > early && late <= PULSE_MAX_HZ)
        assertEquals(PULSE_MAX_HZ, pulseRateHz(taskRun(build, TaskRunStatus.Running, progress = 9f)))
        assertEquals(PULSE_MIN_HZ, pulseRateHz(taskRun(build, TaskRunStatus.Running, progress = Float.NaN)))
    }

    @Test
    fun oneBeatDrivesTheCreatureAndItsPulsesAndReducedMotionStaysStill() {
        val first = advanceBeat(.9f, 2f, .1f, reduced = false) // .9 + .2 crosses a beat
        assertTrue(first.crossed)
        assertEquals(.1f, first.phase, .0001f)
        assertEquals(Beat(.5f, false), advanceBeat(.5f, 2f, .1f, reduced = true))
        assertEquals(Beat(.5f, false), advanceBeat(.5f, 0f, .1f, reduced = false))
        assertEquals(1f, beatLight(0f))
        assertTrue(beatLight(.9f) < beatLight(.1f))
    }

    @Test
    fun pulsesLeaveOnTheSendersBeatsAndTravelTowardTheReceiver() {
        val pulse = TransferPulse(build, check, 10_000)
        assertEquals(emptyList(), pulsesInFlight(9_999, pulse, 2f))
        assertEquals(listOf(0f), pulsesInFlight(10_000, pulse, 2f))
        // 2 a second: a pulse every 500 ms, each in flight for 1.2 s, nearest the receiver first.
        val mid = pulsesInFlight(10_700, pulse, 2f)
        assertEquals(2, mid.size)
        assertTrue(mid[0] > mid[1] && mid.all { it in 0f..1f })
        assertEquals(emptyList(), pulsesInFlight(10_000 + PULSE_WINDOW_MILLIS + PULSE_TRAVEL_MILLIS + 600, pulse, 2f))
        assertEquals(emptyList(), pulsesInFlight(10_100, pulse, 0f))
        // A faster sender sends more in the same moment.
        assertTrue(pulsesInFlight(10_700, pulse, 3f).size > pulsesInFlight(10_700, pulse, .5f).size)
    }

    @Test
    fun aFinishBurstsAndReleasesOnlyTheSporesMadeFromItsOwnWork() {
        val episode = MemoryEpisode(MemoryEpisodeId("ep-1"), sourceSessionId = "sess", taskRunId = "tr-build",
            userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 1L)
        val other = episode.copy(id = MemoryEpisodeId("ep-2"), taskRunId = "tr-check")
        fun node(id: String, vararg sources: String) = WebNode(id, WebGroup.Tag, "NounTag", id, "t", .5f, 1L, sources.toList())
        val nodes = listOf(node("episode:ep-1"), node("node:own", "ep-1 (sess)"), node("node:bare", "ep-1"),
            node("node:theirs", "ep-2 (sess)"), node("node:stray"))
        val done = completionBursts(run, listOf(TaskCompleted(run.id, build, 9_000)), listOf(episode, other), nodes)
        assertEquals(listOf(CompletionBurst(build, 9_000, setOf("episode:ep-1", "node:own", "node:bare"))), done)
        // No memory episode, no spores, but the finish is still real and still bursts.
        assertEquals(emptySet(), completionBursts(run, listOf(TaskCompleted(run.id, build, 9_000)), emptyList(), nodes).single().spores)
        // A completion event for a task the run does not show as completed draws nothing.
        val running = run.copy(taskRuns = run.taskRuns + (build to taskRun(build, TaskRunStatus.Running)))
        assertEquals(emptyList(), completionBursts(running, listOf(TaskCompleted(run.id, build, 9_000)), listOf(episode), nodes))
    }

    @Test
    fun theBurstRisesFastFallsSlowlyAndNeverPrecedesTheFinish() {
        val burst = CompletionBurst(build, 1_000, emptySet())
        assertEquals(0f, burstLight(999, burst))
        assertEquals(0f, burstLight(1_000, burst))
        assertEquals(1f, burstLight(1_150, burst))
        assertTrue(burstLight(2_000, burst) in 0.01f..0.99f)
        assertEquals(0f, burstLight(1_000 + BURST_MILLIS, burst))
    }
}
