package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aServerLanTest {
    private class Host : A2aWorkflowHost {
        var busy = false
        override suspend fun run(runId: String): Pair<WorkflowRun, WorkflowDefinition?>? = null
        override suspend fun busy() = busy
        override suspend fun cancel(runId: String) = false
        override suspend fun roles(): List<RoleDefinition> = emptyList()
    }

    private val host = Host()
    private var open = true
    private val allowed = mutableSetOf<String>()
    private var clock = 0L
    private val server = A2aServer(
        host = host,
        token = { TOKEN },
        baseUrl = { "http://127.0.0.1:41241" },
        agentVersion = "1",
        lanOpen = { open },
        lanHosts = { setOf(LAN_HOST) },
        lanBaseUrl = { host -> "http://$host:41241" },
        isAllowedClient = { it in allowed },
        sendLimiter = A2aRateLimiter(maxPerWindow = 2, windowMillis = 60_000, nowMillis = { clock }),
    )
    private val neighbour = A2aCaller("192.168.1.20", isLoopback = false)
    private val lanHeaders = mapOf("Host" to "$LAN_HOST:41241", "Authorization" to "Bearer $TOKEN")

    private fun send(text: String) =
        A2aCodec.rpcRequest(
            JsonPrimitive(1),
            "SendMessage",
            buildJsonObject {
                put("message", A2aCodec.encodeMessage(A2aMessage("m", A2aRole.User, listOf(A2aPart.Text(text))), A2aVersion.V1_0))
            },
        ).toString()

    private suspend fun cardUrl(headers: Map<String, String>, caller: A2aCaller): String {
        val response = server.handle("GET", A2aCodec.WELL_KNOWN_CARD_PATH, headers, "", caller)
        assertEquals(200, response.status)
        return A2aCodec.decodeCard(Json.parseToJsonElement(response.body)).interfaces.single().url
    }

    @Test
    fun aNetworkCallerIsRefusedEntirelyWhileTheServerIsNotOpenToTheNetwork() = runTest {
        open = false
        allowed += neighbour.address
        assertEquals(403, server.handle("GET", A2aCodec.WELL_KNOWN_CARD_PATH, lanHeaders, "", neighbour).status)
        assertEquals(403, server.handle("POST", "/a2a", lanHeaders, send("x"), neighbour).status)
        // A LAN Host header is not accepted from this computer either while closed.
        assertEquals(403, server.handle("GET", A2aCodec.WELL_KNOWN_CARD_PATH, lanHeaders, "").status)
    }

    @Test
    fun theCardAdvertisesTheNetworkAddressOnlyToCallersThatUsedIt() = runTest {
        assertEquals("http://$LAN_HOST:41241/a2a", cardUrl(lanHeaders, neighbour))
        assertEquals("http://127.0.0.1:41241/a2a", cardUrl(mapOf("Host" to "127.0.0.1:41241"), A2aCaller.Loopback))
        open = false
        assertEquals("http://127.0.0.1:41241/a2a", cardUrl(mapOf("Host" to "localhost:41241"), A2aCaller.Loopback))
    }

    @Test
    fun hostHeadersThatAreNotThisComputersAddressesAreRefused() = runTest {
        for (name in listOf("evil.example", "evil.example:41241", "10.9.9.9:41241", "")) {
            val status = server.handle("GET", A2aCodec.WELL_KNOWN_CARD_PATH, mapOf("Host" to name), "", neighbour).status
            assertEquals(403, status, "Host $name")
        }
    }

    @Test
    fun aNewClientWithTheTokenWaitsForTheKeeperAndOneWithoutIsNotListed() = runTest {
        val noToken = server.handle("POST", "/a2a", mapOf("Host" to "$LAN_HOST:41241"), send("x"), neighbour)
        assertEquals(401, noToken.status)
        assertTrue(server.waitingClients.value.isEmpty())

        val refused = server.handle("POST", "/a2a", lanHeaders, send("Do this"), neighbour)
        assertEquals(403, refused.status)
        assertEquals(listOf("192.168.1.20"), server.waitingClients.value)
        assertTrue(server.pendingLaunches.value.isEmpty())

        allowed += "192.168.1.20"
        server.forgetWaitingClient("192.168.1.20")
        val accepted = server.handle("POST", "/a2a", lanHeaders, send("Do this"), neighbour)
        assertEquals(200, accepted.status)
        assertEquals("Do this", server.pendingLaunches.value.single().objective)
        assertTrue(server.waitingClients.value.isEmpty())
    }

    @Test
    fun thisComputerNeedsNoAllowlistEntryWhileOpen() = runTest {
        val response = server.handle("POST", "/a2a", mapOf("Host" to "127.0.0.1:41241", "Authorization" to "Bearer $TOKEN"), send("Local"))
        assertEquals(200, response.status)
        assertEquals(1, server.pendingLaunches.value.size)
    }

    @Test
    fun sendMessageIsRateLimitedPerClient() = runTest {
        host.busy = true
        allowed += neighbour.address
        repeat(2) { assertEquals(200, server.handle("POST", "/a2a", lanHeaders, send("n$it"), neighbour).status) }
        val limited = server.handle("POST", "/a2a", lanHeaders, send("again"), neighbour)
        assertEquals(429, limited.status)
        assertEquals("60", limited.headers["Retry-After"])

        // Another client has its own allowance, and reading a task is not limited.
        val local = mapOf("Host" to "127.0.0.1:41241", "Authorization" to "Bearer $TOKEN")
        assertEquals(200, server.handle("POST", "/a2a", local, send("local")).status)
        val get = A2aCodec.rpcRequest(JsonPrimitive(2), "GetTask", buildJsonObject { put("id", "none") }).toString()
        assertEquals(200, server.handle("POST", "/a2a", lanHeaders, get, neighbour).status)

        clock += 60_000
        assertEquals(200, server.handle("POST", "/a2a", lanHeaders, send("later"), neighbour).status)
    }

    @Test
    fun rateLimiterForgetsCallsOutsideTheWindowAndBoundsItsMemory() {
        var now = 0L
        val limiter = A2aRateLimiter(maxPerWindow = 1, windowMillis = 1_000, maxClients = 2, nowMillis = { now })
        assertNull(limiter.tryAcquire("a"))
        assertEquals(1_000L, limiter.tryAcquire("a"))
        now = 400
        assertEquals(600L, limiter.tryAcquire("a"))
        now = 1_000
        assertNull(limiter.tryAcquire("a"))
        assertNull(limiter.tryAcquire("b"))
        assertNull(limiter.tryAcquire("c"))
        // "a" was dropped to keep two clients, so it starts afresh.
        assertNull(limiter.tryAcquire("a"))
    }

    @Test
    fun onlyAddressLiteralsCanBeAllowed() {
        assertEquals("192.168.1.20", normalizeA2aClientAddress(" 192.168.1.20 "))
        assertEquals("192.168.1.20", normalizeA2aClientAddress("/192.168.1.20"))
        assertEquals("fe80::1", normalizeA2aClientAddress("[FE80::1%en0]"))
        assertNull(normalizeA2aClientAddress("laptop.local"))
        assertNull(normalizeA2aClientAddress("300.1.1.1"))
        assertNull(normalizeA2aClientAddress("1.2.3"))
        assertNull(normalizeA2aClientAddress(""))
        assertNull(normalizeA2aClientAddress(null))
    }

    private companion object {
        const val TOKEN = "lan-token"
        const val LAN_HOST = "192.168.1.5"
    }
}
