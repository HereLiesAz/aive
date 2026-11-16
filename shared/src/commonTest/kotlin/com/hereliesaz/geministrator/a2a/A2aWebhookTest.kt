package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.workflow.schedule.InMemoryWorkflowScheduleStore
import com.hereliesaz.geministrator.workflow.schedule.ScheduleCadence
import com.hereliesaz.geministrator.workflow.schedule.ScheduleTrigger
import com.hereliesaz.geministrator.workflow.schedule.TriggerIntake
import com.hereliesaz.geministrator.workflow.schedule.WorkflowSchedule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aWebhookTest {
    private class Host : A2aWorkflowHost {
        override suspend fun run(runId: String): Pair<WorkflowRun, WorkflowDefinition?>? = null
        override suspend fun busy() = false
        override suspend fun cancel(runId: String) = false
        override suspend fun roles(): List<RoleDefinition> = emptyList()
    }

    private var now = 1_000_000_000L
    private val store = InMemoryWorkflowScheduleStore()
    private val intake = TriggerIntake(store) { now }
    private var attached: TriggerIntake? = intake
    private val server = A2aServer(
        host = Host(),
        token = { TOKEN },
        baseUrl = { "http://127.0.0.1:41241" },
        agentVersion = "1",
        webhooks = { attached },
        hookLimiter = A2aRateLimiter(maxPerWindow = 3),
    )
    private val auth = mapOf("Host" to "127.0.0.1:41241", "Authorization" to "Bearer $TOKEN")

    private suspend fun schedule(enabled: Boolean = true) = store.put(
        WorkflowSchedule(
            id = "s1", workflowId = "h", name = "Hook", objective = "Go", cadence = ScheduleCadence.everyHours(1),
            enabled = enabled, createdAtEpochMillis = 0L, trigger = ScheduleTrigger(kind = ScheduleTrigger.Kind.Webhook),
        ),
    )

    private suspend fun post(path: String = "/hooks/s1", body: String = "hello") =
        server.handle("POST", path, auth, body, A2aCaller.Loopback)

    @Test
    fun anAuthenticatedCallIsAcceptedOnceAndARepeatIsADuplicate() = runTest {
        schedule()
        assertEquals(202, post().status)
        val again = post()
        assertEquals(200, again.status)
        assertTrue("\"duplicate\":true" in again.body)
    }

    @Test
    fun noTokenOrTheWrongMethodIsRefusedBeforeTheBodyIsRead() = runTest {
        schedule()
        assertEquals(401, server.refusalBeforeBody("POST", "/hooks/s1", mapOf("Host" to "127.0.0.1:41241"), A2aCaller.Loopback)?.status)
        assertEquals(405, server.refusalBeforeBody("GET", "/hooks/s1", auth, A2aCaller.Loopback)?.status)
        assertNull(server.refusalBeforeBody("POST", "/hooks/s1", auth, A2aCaller.Loopback))
    }

    @Test
    fun aNewEventInsideTheMinimumGapIsTooSoonAndTheClientLimitAnswersWithAWait() = runTest {
        schedule()
        assertEquals(202, post(body = "a").status)
        val tooSoon = post(body = "b")
        assertEquals(429, tooSoon.status)
        assertTrue(tooSoon.headers.containsKey("Retry-After"))
        assertEquals(429, post(body = "c").status)
        // The fourth call from this client in the window is stopped by the client limit (3 here) before the schedule is asked.
        val limited = post(body = "d")
        assertEquals(429, limited.status)
        assertTrue("too many requests" in limited.body)
    }

    @Test
    fun aScheduleThatIsOffOrUnknownOrWithoutTheTickerIsNotStarted() = runTest {
        schedule(enabled = false)
        assertEquals(409, post().status)
        assertEquals(404, post(path = "/hooks/nope").status)
        attached = null
        assertEquals(503, post(body = "x").status)
    }

    @Test
    fun anOversizedBodyIsRefused() = runTest {
        schedule()
        assertEquals(413, post(body = "x".repeat(A2aServer.MAX_HOOK_BODY_BYTES + 1)).status)
    }

    private companion object {
        const val TOKEN = "test-token-for-the-hook"
    }
}
