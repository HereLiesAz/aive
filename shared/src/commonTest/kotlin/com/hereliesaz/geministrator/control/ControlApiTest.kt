package com.hereliesaz.geministrator.control

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fake app state: what the control API serves when no real runtime is behind it. */
class FakeControlHost : ControlHost {
    val calls = mutableListOf<String>()
    var leak = "nothing"
    private val hives = mutableMapOf("h1" to "First hive")

    private fun obj(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
    override suspend fun status() = obj("app" to "The Aive").also { calls += "status" }
    override suspend fun listHives(): JsonElement = JsonArray(hives.map { (id, n) -> obj("id" to id, "name" to n) }).also { calls += "listHives" }
    override suspend fun getHive(id: String) = obj("id" to id, "name" to (hives[id] ?: controlError(ControlErrorCode.NotFound, "No hive $id")))
    override suspend fun listRoles(): JsonElement = JsonArray(emptyList())
    override suspend fun getRole(id: String) = obj("id" to id, "note" to leak)
    override suspend fun listRuns(hiveId: String?, limit: Int): JsonElement = JsonArray(emptyList())
    override suspend fun getRun(id: String) = obj("id" to id, "output" to leak)
    override suspend fun listEvents(runId: String, afterMillis: Long?, limit: Int): JsonElement = JsonArray(emptyList())
    override suspend fun listApprovals(): JsonElement = JsonArray(listOf(obj("gateId" to "g1", "kind" to "PlanApproval")))
    override suspend fun listProviders() = obj("providers" to "none")
    override suspend fun settings() = obj("a2a" to "on")
    override suspend fun createHive(spec: JsonObject): JsonObject {
        val name = spec["name"]?.jsonPrimitive?.content ?: controlError(ControlErrorCode.InvalidArgument, "name is required")
        hives["h${hives.size + 1}"] = name
        calls += "createHive"
        return obj("name" to name)
    }
    override suspend fun updateHive(id: String, patch: JsonObject) = obj("id" to id)
    override suspend fun deleteHive(id: String): JsonObject { calls += "deleteHive:$id"; hives.remove(id); return obj("deleted" to id) }
    override suspend fun addCreature(hiveId: String, spec: JsonObject) = obj("id" to hiveId)
    override suspend fun updateCreature(hiveId: String, creatureId: String, patch: JsonObject) = obj("id" to creatureId)
    override suspend fun removeCreature(hiveId: String, creatureId: String) = obj("id" to creatureId)
    override suspend fun draftHive(prompt: String) = obj("suggestedName" to prompt)
    override suspend fun setRoleProvider(roleId: String, providerId: String?) = obj("id" to roleId)
    override suspend fun startRun(spec: JsonObject) = obj("id" to "r1")
    override suspend fun stopRun(runId: String): JsonObject { calls += "stopRun:$runId"; return obj("stopped" to runId) }
}

class ControlApiTest {
    private val token = "tok-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val host = FakeControlHost()
    private val api = ControlApi(host) { token }
    private val good = mapOf("Host" to "127.0.0.1:41242", "Authorization" to "Bearer $token")

    private suspend fun call(
        method: String,
        path: String,
        body: String = "",
        headers: Map<String, String> = good,
        caller: ControlCaller = ControlCaller.Loopback,
    ) = api.handle(method, ControlApi.PREFIX + path, headers, body, caller)

    private fun code(r: ControlHttpResponse) = Json.parseToJsonElement(r.body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test
    fun aGoodCallIsServed() = runTest {
        val r = call("GET", "/hives")
        assertEquals(200, r.status)
        assertTrue(Json.parseToJsonElement(r.body).jsonObject["ok"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun missingOrWrongTokenIsRefused() = runTest {
        for (headers in listOf(
            mapOf("Host" to "127.0.0.1"),
            mapOf("Host" to "127.0.0.1", "Authorization" to "Bearer wrong"),
            mapOf("Host" to "127.0.0.1", "Authorization" to "Basic $token"),
            mapOf("Host" to "127.0.0.1", "Authorization" to "Bearer "),
        )) {
            val r = call("GET", "/hives", headers = headers)
            assertEquals(401, r.status)
            assertEquals("unauthorized", code(r))
            assertEquals("Bearer", r.headers["WWW-Authenticate"])
        }
    }

    @Test
    fun noConfiguredTokenRefusesEverything() = runTest {
        val open = ControlApi(host) { null }
        assertEquals(401, open.handle("GET", ControlApi.PREFIX + "/hives", good, "", ControlCaller.Loopback).status)
        val blank = ControlApi(host) { "  " }
        assertEquals(401, blank.handle("GET", ControlApi.PREFIX + "/hives", mapOf("Host" to "127.0.0.1", "Authorization" to "Bearer   "), "", ControlCaller.Loopback).status)
    }

    @Test
    fun aCallerThatIsNotLoopbackIsRefusedEvenWithTheToken() = runTest {
        val r = call("GET", "/hives", caller = ControlCaller("192.168.1.20", isLoopback = false))
        assertEquals(403, r.status)
        assertEquals("forbidden", code(r))
        assertFalse("listHives" in host.calls)
    }

    @Test
    fun aHostThatIsNotThisComputerIsRefused() = runTest {
        for (h in listOf("evil.example", "192.168.1.5:41242", "127.0.0.1.evil.example", "")) {
            assertEquals(403, call("GET", "/hives", headers = good + ("Host" to h)).status, h)
        }
        for (h in listOf("127.0.0.1", "localhost:1234", "[::1]:41242")) {
            assertEquals(200, call("GET", "/hives", headers = good + ("Host" to h)).status, h)
        }
    }

    @Test
    fun aBrowserOriginIsRefused() = runTest {
        assertEquals(403, call("GET", "/hives", headers = good + ("Origin" to "https://evil.example")).status)
    }

    @Test
    fun unknownPathsAndWrongMethodsAreTyped() = runTest {
        assertEquals("not_found", code(call("GET", "/nope")))
        assertEquals("not_found", code(api.handle("GET", "/other", good, "", ControlCaller.Loopback)))
        assertEquals("method_not_allowed", code(call("POST", "/status")))
        assertEquals("invalid_argument", code(call("POST", "/hives", body = "[1]")))
        assertEquals("invalid_argument", code(call("POST", "/hives", body = "{not json")))
        assertEquals("not_found", code(call("GET", "/hives/missing")))
    }

    @Test
    fun anOversizedBodyIsRefused() = runTest {
        val r = call("POST", "/hives", body = "x".repeat(ControlApi.MAX_BODY_BYTES + 1))
        assertEquals(413, r.status)
    }

    @Test
    fun theTokenAndKeyShapesNeverLeaveInAnAnswer() = runTest {
        host.leak = "log: Authorization: Bearer $token and sk-proj-abcdefghijklmnopqrstuvwx and ghp_abcdefghijklmnopqrstuvwxyz0123 api_key=supersecretvalue1"
        val r = call("GET", "/runs/r1")
        assertEquals(200, r.status)
        assertFalse(token in r.body)
        assertFalse("sk-proj" in r.body)
        assertFalse("ghp_" in r.body)
        assertFalse("supersecretvalue1" in r.body)
        assertTrue("api_key=" in r.body, "The name of a key stays, its value goes")
    }

    @Test
    fun anErrorNeverCarriesInternalDetail() = runTest {
        val broken = ControlApi(object : ControlHost by FakeControlHost() {
            override suspend fun status(): JsonObject = error("secret internal path /home/user/.aive")
        }) { token }
        val r = broken.handle("GET", ControlApi.PREFIX + "/status", good, "", ControlCaller.Loopback)
        assertEquals(500, r.status)
        assertFalse("/home/user" in r.body)
        assertEquals("internal", code(r))
    }

    @Test
    fun theRouteTableOffersNothingThatDecidesAGate() {
        val forbidden = Regex("approv|reject|resolve|decide|release|lift|grant|allow|deny", RegexOption.IGNORE_CASE)
        val offending = ControlApi.routes.filter { forbidden.containsMatchIn(it.name + it.pattern) }
        // The one place the word appears is the read-only list.
        assertEquals(listOf(ControlMethod.GET to "/approvals"), offending.map { it.method to it.pattern })
        assertFalse(offending.single().mutating)
    }

    @Test
    fun noRequestCanReachAnApprovalExceptToReadIt() = runTest {
        val paths = listOf(
            "/approvals", "/approvals/g1", "/approvals/g1/approve", "/approvals/g1/reject", "/approvals/g1/decision",
            "/gates/g1/approve", "/runs/r1/approve", "/runs/r1/approvals/g1", "/runs/r1/gates/g1/resolve", "/hives/h1/approve",
        )
        for (path in paths) for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val r = call(method, path, body = """{"decision":"approve"}""")
            assertTrue(r.status in setOf(404, 405), "$method $path answered ${r.status}")
        }
        assertEquals(200, call("GET", "/approvals").status)
        assertEquals(emptyList(), host.calls.filter { "approv" in it.lowercase() })
    }
}
