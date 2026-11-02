package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionActivityFeedsTest {
    private val key = SessionActivityKey(TaskRunId("task"), ProviderRunId("run"))

    @Test
    fun linesAreKeptNewestLastAndBoundedPerSession() {
        val feeds = SessionActivityFeeds(maxLinesPerSession = 3)
        (1..5).forEach { feeds.record(key, SessionActivityKind.Thinking, "thought $it") }

        val lines = feeds.lines(key)
        assertEquals(listOf("thought 3", "thought 4", "thought 5"), lines.map { it.text })
        assertEquals(lines.map { it.id }.sorted(), lines.map { it.id })
        assertEquals("thought 5", feeds.latest(key)?.text)
    }

    @Test
    fun blankLinesAndARepeatOfTheNewestAreIgnoredAndLongLinesAreCapped() {
        val feeds = SessionActivityFeeds(maxLineChars = 10)
        feeds.record(key, SessionActivityKind.Progress, "  ")
        feeds.record(key, SessionActivityKind.Progress, "Running tests")
        feeds.record(key, SessionActivityKind.Progress, "Running tests")
        feeds.record(key, SessionActivityKind.Thinking, "Running tests")

        val lines = feeds.lines(key)
        assertEquals(2, lines.size)
        assertEquals(listOf(SessionActivityKind.Progress, SessionActivityKind.Thinking), lines.map { it.kind })
        assertTrue(lines.all { it.text.length <= 10 && it.text.endsWith("…") })
    }

    @Test
    fun theLeastRecentlyActiveSessionIsDroppedPastTheSessionBound() {
        val feeds = SessionActivityFeeds(maxSessions = 2)
        val a = SessionActivityKey(TaskRunId("a"), ProviderRunId("ra"))
        val b = SessionActivityKey(TaskRunId("b"), ProviderRunId("rb"))
        val c = SessionActivityKey(TaskRunId("c"), ProviderRunId("rc"))
        feeds.record(a, SessionActivityKind.Thinking, "a1")
        feeds.record(b, SessionActivityKind.Thinking, "b1")
        feeds.record(a, SessionActivityKind.Thinking, "a2")
        feeds.record(c, SessionActivityKind.Thinking, "c1")

        assertEquals(setOf(a, c), feeds.feeds.value.keys)
        assertEquals(listOf("a1", "a2"), feeds.lines(a).map { it.text })
        assertNull(feeds.latest(b))
    }

    @Test
    fun aRetryIsANewSessionWithItsOwnFeed() {
        val feeds = SessionActivityFeeds()
        val first = SessionActivityKey(TaskRunId("task"), ProviderRunId("attempt-1"))
        val retry = SessionActivityKey(TaskRunId("task"), ProviderRunId("attempt-2"))
        feeds.record(first, SessionActivityKind.Thinking, "first try")
        feeds.record(retry, SessionActivityKind.Thinking, "second try")

        assertEquals(listOf("first try"), feeds.lines(first).map { it.text })
        assertEquals(listOf("second try"), feeds.lines(retry).map { it.text })
    }

    @Test
    fun onlyThinkingAndProgressAreActivity() {
        val feeds = SessionActivityFeeds()
        val runId = ProviderRunId("run")
        val handle = ManagedSessionHandle(TaskRunId("task"), AgentProviderId("p"), runId)
        feeds.record(handle, AgentEvent.Thinking(runId, "weighing two designs"))
        feeds.record(handle, AgentEvent.Message(runId, "a message"))
        feeds.record(handle, AgentEvent.PlanGenerated(runId, "the plan"))
        feeds.record(handle, AgentEvent.Progress(runId, "Running tests", 0.5f))
        feeds.record(handle, AgentEvent.Failed(runId, "broke"))
        feeds.record(handle, AgentEvent.Completed(runId))

        assertEquals(
            listOf(
                SessionActivityKind.Thinking to "weighing two designs",
                SessionActivityKind.Progress to "Running tests",
            ),
            feeds.lines(handle.activityKey()).map { it.kind to it.text },
        )
    }

    @Test
    fun olderLinesCollapseBehindTheNewest() {
        val lines = (0L until 9L).map { SessionActivityLine(it, SessionActivityKind.Thinking, "line $it") }

        val collapsed = lines.activityWindow(visible = 4, expanded = false)
        assertEquals(5, collapsed.collapsed)
        assertEquals(listOf("line 5", "line 6", "line 7", "line 8"), collapsed.shown.map { it.text })

        val expanded = lines.activityWindow(visible = 4, expanded = true)
        assertEquals(0, expanded.collapsed)
        assertEquals(lines, expanded.shown)

        val short = lines.take(3).activityWindow(visible = 4, expanded = false)
        assertEquals(0, short.collapsed)
        assertEquals(3, short.shown.size)
        assertFailsWith<IllegalArgumentException> { lines.activityWindow(visible = 0, expanded = false) }
    }

    @Test
    fun doingNowIsOneShortLine() {
        val line = SessionActivityLine(0L, SessionActivityKind.Thinking, "Checking\nthe   tests\tfirst")
        assertEquals("Checking the tests first", line.doingNowText())
        assertEquals("Checking…", line.doingNowText(maxChars = 9))
    }

    @Test
    fun thinkingReachesItsTaskFeedWhileSteeringMessagesStaySeparate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = ThinkingProvider()
        val feeds = SessionActivityFeeds()
        val handle = ManagedSessionHandle(TaskRunId("thinking-task"), provider.id, ProviderRunId("thinking-run"))
        val other = SessionActivityKey(TaskRunId("other-task"), ProviderRunId("other-run"))
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings()),
                scope,
                activity = feeds,
            )
            gateway.reconnect(handle, ManagedSessionStatus.Running)
            withTimeout(2_000L) {
                while (gateway.status(handle) != ManagedSessionStatus.Completed) delay(10L)
            }
            assertEquals(ProviderActionResult.Accepted, gateway.message(handle, "Please use the existing helper"))

            assertEquals(
                listOf(
                    SessionActivityKind.Thinking to "Reading the failing test first.",
                    SessionActivityKind.Progress to "Running the test suite",
                    SessionActivityKind.Thinking to "The fix belongs in the parser.",
                ),
                feeds.lines(handle.activityKey()).map { it.kind to it.text },
            )
            // The steering message reached the agent, but neither it nor the agent's reply is activity.
            assertTrue(provider.sentMessages.value.single().startsWith("Please use the existing helper"))
            assertTrue(feeds.lines(handle.activityKey()).none { "helper" in it.text || "Understood" in it.text })
            assertTrue(feeds.lines(other).isEmpty())
        } finally {
            scope.cancel()
        }
    }
}

private class ThinkingProvider : AgentProvider {
    override val id = AgentProviderId("thinking")
    val sentMessages = MutableStateFlow<List<String>>(emptyList())
    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("thinking-run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        emit(AgentEvent.Thinking(runId, "Reading the failing test first."))
        emit(AgentEvent.Progress(runId, "Running the test suite"))
        emit(AgentEvent.Message(runId, "Understood, using the helper."))
        emit(AgentEvent.Thinking(runId, "The fix belongs in the parser."))
        emit(AgentEvent.Completed(runId))
    }
    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult {
        sentMessages.update { it + message }
        return ProviderActionResult.Accepted
    }
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
