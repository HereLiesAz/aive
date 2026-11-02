package com.hereliesaz.aive.remote

import com.hereliesaz.geministrator.ApplicationRuntimeState
import com.hereliesaz.geministrator.LiveWorkflowPresentation
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.WorkflowCreated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiveRemoteBridgeTest {
    @Test
    fun withNothingLinkedAPromptIsNeverSentToAKeylessGateway() {
        assertNull(remoteTextApi(credentials = emptyMap(), providerId = null, model = null))
    }

    @Test
    fun aNamedKeylessProviderNeedsNoCredential() {
        assertNotNull(remoteTextApi(credentials = emptyMap(), providerId = "kilo", model = null))
    }

    @Test
    fun aNamedKeyedProviderWithoutACredentialIsNotLinked() {
        assertNull(remoteTextApi(credentials = emptyMap(), providerId = "openai", model = null))
    }

    @Test
    fun onlyGitHubRepositoryUrlsParse() {
        val ref = assertNotNull(gitHubRepositoryRef("https://github.com/HereLiesAz/IDEaz.git"))
        assertEquals("HereLiesAz", ref.owner)
        assertEquals("IDEaz", ref.name)
        assertEquals(RepositorySource.GitHub, ref.source)
        assertNull(gitHubRepositoryRef("https://gitlab.com/a/b"))
        assertNull(gitHubRepositoryRef("https://github.com/a"))
        assertNull(gitHubRepositoryRef(null))
    }

    @Test
    fun launchesQueueInOrderAndLeaveOnlyWhenCompleted() {
        drain()
        val first = assertNotNull(AiveRemoteBridge.enqueue("P", "first", null))
        val second = assertNotNull(AiveRemoteBridge.enqueue("P", "second", null))
        assertEquals(listOf(first, second), AiveRemoteBridge.pendingLaunches.value.map { it.requestId })
        AiveRemoteBridge.complete(first, failure = null)
        AiveRemoteBridge.complete(second, failure = "Runtime is unavailable")
        assertEquals(emptyList(), AiveRemoteBridge.pendingLaunches.value)
        assertEquals(RemoteLaunchOutcome(first, started = true, error = null), AiveRemoteBridge.outcomeOf(first))
        assertEquals(
            RemoteLaunchOutcome(second, started = false, error = "Runtime is unavailable"),
            AiveRemoteBridge.outcomeOf(second),
        )
    }

    @Test
    fun completingTwiceRecordsOneOutcome() {
        drain()
        val id = assertNotNull(AiveRemoteBridge.enqueue("P", "once", null))
        AiveRemoteBridge.complete(id, failure = null)
        AiveRemoteBridge.complete(id, failure = "late failure")
        assertEquals(1, AiveRemoteBridge.outcomes.replayCache.count { it.requestId == id })
        assertTrue(assertNotNull(AiveRemoteBridge.outcomeOf(id)).started)
    }

    @Test
    fun theQueueIsCapped() {
        drain()
        repeat(AiveRemoteBridge.MAX_PENDING) { assertNotNull(AiveRemoteBridge.enqueue("P", "fill $it", null)) }
        assertNull(AiveRemoteBridge.enqueue("P", "one too many", null))
        assertEquals(AiveRemoteBridge.MAX_PENDING, AiveRemoteBridge.pendingLaunches.value.size)
        drain()
    }

    @Test
    fun staleRequestsExpireInsteadOfLaunching() {
        drain()
        val start = 1_000L
        val stale = assertNotNull(AiveRemoteBridge.enqueue("P", "stale", null, nowMillis = start))
        val fresh = assertNotNull(
            AiveRemoteBridge.enqueue("P", "fresh", null, nowMillis = start + AiveRemoteBridge.PENDING_TTL_MILLIS - 1),
        )
        AiveRemoteBridge.pruneExpired(nowMillis = start + AiveRemoteBridge.PENDING_TTL_MILLIS)
        assertEquals(listOf(fresh), AiveRemoteBridge.pendingLaunches.value.map { it.requestId })
        val outcome = assertNotNull(AiveRemoteBridge.outcomeOf(stale))
        assertFalse(outcome.started)
        assertEquals(AiveRemoteBridge.EXPIRED_MESSAGE, outcome.error)
        drain()
    }

    @Test
    fun invalidLaunchesAreRejectedBeforeQueueing() {
        val queued = mutableListOf<String>()
        val enqueue = { name: String, _: String, _: com.hereliesaz.geministrator.domain.RepositoryRef? ->
            queued += name
            7L
        }
        assertFailsWith<IllegalArgumentException> { queueRemoteLaunch("P", " ", null, enqueue) }
        assertFailsWith<IllegalArgumentException> { queueRemoteLaunch(null, "goal", null, enqueue) }
        assertFailsWith<IllegalArgumentException> { queueRemoteLaunch("P", "goal", "https://gitlab.com/a/b", enqueue) }
        assertEquals(emptyList(), queued)
        assertEquals(7L, queueRemoteLaunch(" P ", "goal", "https://github.com/a/b", enqueue))
        assertEquals(listOf("P"), queued)
        assertFailsWith<IllegalStateException> { queueRemoteLaunch("P", "goal", null) { _, _, _ -> null } }
    }

    @Test
    fun eventWatchHoldsAreCountedAndReleasedOnce() {
        val before = AiveRemoteBridge.eventWatchers.value
        val first = AiveRemoteBridge.holdEventWatch()
        val second = AiveRemoteBridge.holdEventWatch()
        assertEquals(before + 2, AiveRemoteBridge.eventWatchers.value)
        first()
        first()
        assertEquals(before + 1, AiveRemoteBridge.eventWatchers.value)
        second()
        assertEquals(before, AiveRemoteBridge.eventWatchers.value)
    }

    @Test
    fun theTimelineOfTheShownRunBecomesItsEventLogAndOtherRunsAreIgnored() {
        val task = TaskDefinition(id = TaskDefinitionId("t"), name = "Task", objective = "do", roleId = null)
        val definition = WorkflowDefinition(id = WorkflowDefinitionId("wf"), name = "Workflow", tasks = listOf(task))
        val runId = WorkflowRunId("bridge-run-" + System.nanoTime())
        val run = WorkflowRun(
            id = runId,
            projectId = ProjectId("p"),
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = emptyMap(),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        val project = Project(id = ProjectId("p"), name = "P", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
        AiveRemoteBridge.publish(ApplicationRuntimeState.Live(LiveWorkflowPresentation(project, definition, run, roles = emptyList())))

        AiveRemoteBridge.publishTimeline(listOf(WorkflowCreated(runId, objective = "Ship", occurredAtEpochMillis = 1), TaskStarted(runId, task.id, 1, 2)))
        val log = AiveRemoteBridge.runEvents.value
        assertEquals(runId.value, log.runId)
        assertEquals(listOf("run_started", "task"), log.events.map { it.type })

        // A timeline that belongs to another run (loaded while the UI moved on) changes nothing.
        AiveRemoteBridge.publishTimeline(listOf(WorkflowCreated(WorkflowRunId("elsewhere"), occurredAtEpochMillis = 1)))
        assertEquals(log, AiveRemoteBridge.runEvents.value)
    }

    /** Empties the process-wide queue left by other tests. */
    private fun drain() = AiveRemoteBridge.pruneExpired(nowMillis = Long.MAX_VALUE / 2)
}
