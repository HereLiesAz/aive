package com.hereliesaz.geministrator.mcpbridge

import com.hereliesaz.geministrator.control.ControlApi
import com.hereliesaz.geministrator.control.ControlCaller
import com.hereliesaz.geministrator.control.ControlErrorCode
import com.hereliesaz.geministrator.control.ControlException
import com.hereliesaz.geministrator.control.ControlHost
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An app with a few hives and one run waiting at a gate, held in memory. */
private class FakeApp : ControlHost {
    val hives = linkedMapOf("plan" to "Plan")
    var gateStatus = "Pending"
    var runLive = true
    val log = mutableListOf<String>()
    var leak = "ok"

    private fun o(vararg p: Pair<String, JsonElement>) = JsonObject(mapOf(*p))
    private fun s(v: String) = JsonPrimitive(v)
    override suspend fun status() = o("app" to s("The Aive"), "runtimeReady" to JsonPrimitive(true))
    override suspend fun listHives(): JsonElement = JsonArray(hives.map { (id, n) -> o("id" to s(id), "name" to s(n)) })
    override suspend fun getHive(id: String) = o("id" to s(id), "name" to s(hives[id] ?: throw ControlException(ControlErrorCode.NotFound, "No hive $id")))
    override suspend fun listRoles(): JsonElement = JsonArray(listOf(o("id" to s("product-manager"))))
    override suspend fun getRole(id: String) = o("id" to s(id), "instructions" to s(leak))
    override suspend fun listRuns(hiveId: String?, limit: Int): JsonElement = JsonArray(listOf(o("id" to s("r1"), "hive" to s(hiveId ?: "any"), "limit" to JsonPrimitive(limit))))
    override suspend fun getRun(id: String) = o("id" to s(id), "status" to s(if (runLive) "AwaitingHuman" else "Cancelled"), "log" to s(leak))
    override suspend fun listEvents(runId: String, afterMillis: Long?, limit: Int): JsonElement = JsonArray(listOf(o("after" to JsonPrimitive(afterMillis ?: -1))))
    override suspend fun listApprovals(): JsonElement = JsonArray(listOf(o("gateId" to s("g1"), "status" to s(gateStatus))))
    override suspend fun listProviders() = o("providers" to JsonArray(emptyList()))
    override suspend fun settings() = o("a2aServerEnabled" to JsonPrimitive(true))
    override suspend fun createHive(spec: JsonObject): JsonObject {
        val name = spec["name"]?.jsonPrimitive?.content ?: throw ControlException(ControlErrorCode.InvalidArgument, "name is required")
        hives[name.lowercase()] = name
        log += "create:$name:${spec["creatures"]?.jsonArray?.size}"
        return o("id" to s(name.lowercase()))
    }
    override suspend fun updateHive(id: String, patch: JsonObject): JsonObject { log += "update:$id:$patch"; return o("id" to s(id)) }
    override suspend fun deleteHive(id: String): JsonObject {
        if (runLive) throw ControlException(ControlErrorCode.Conflict, "A run of this hive is live; stop it first")
        hives.remove(id); log += "delete:$id"; return o("deleted" to s(id))
    }
    override suspend fun addCreature(hiveId: String, spec: JsonObject): JsonObject { log += "add:$hiveId:$spec"; return o("id" to s(hiveId)) }
    override suspend fun updateCreature(hiveId: String, creatureId: String, patch: JsonObject): JsonObject { log += "uc:$hiveId:$creatureId:$patch"; return o("id" to s(creatureId)) }
    override suspend fun removeCreature(hiveId: String, creatureId: String): JsonObject { log += "rc:$hiveId:$creatureId"; return o("id" to s(creatureId)) }
    override suspend fun draftHive(prompt: String) = o("suggestedName" to s(prompt), "saved" to JsonPrimitive(false))
    override suspend fun setRoleProvider(roleId: String, providerId: String?): JsonObject { log += "provider:$roleId:$providerId"; return o("id" to s(roleId)) }
    override suspend fun startRun(spec: JsonObject): JsonObject { log += "start:$spec"; return o("id" to s("r2")) }
    override suspend fun stopRun(runId: String): JsonObject { runLive = false; log += "stop:$runId"; return o("stopped" to s(runId)) }
}

class BridgeAgainstControlApiTest {
    private val token = "tok-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val app = FakeApp()
    private val api = ControlApi(app) { token }
    private val http: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { server ->
        server.createContext("/") { ex ->
            val body = ex.requestBody.readNBytes(ControlApi.MAX_BODY_BYTES + 1).decodeToString()
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k to v.firstOrNull().orEmpty() }
            val remote = ex.remoteAddress.address
            val reply = runBlocking {
                api.handle(ex.requestMethod, ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""), headers, body, ControlCaller(remote.hostAddress, remote.isLoopbackAddress))
            }
            val bytes = reply.body.encodeToByteArray()
            ex.sendResponseHeaders(reply.status, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }
        server.start()
    }
    private val url = "http://127.0.0.1:${http.address.port}"
    private var currentToken: String? = token
    private val mcp = McpServer(HttpControlTransport(url, token = { currentToken }))

    @AfterTest
    fun stop() = http.stop(0)

    private var nextId = 1
    private fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val line = buildJsonObject { put("jsonrpc", "2.0"); put("id", nextId++); put("method", method); put("params", params) }.toString()
        return Json.parseToJsonElement(mcp.handle(line)!!).jsonObject
    }

    private fun callTool(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject =
        rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) })["result"]!!.jsonObject

    private fun text(result: JsonObject) = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    private fun isError(result: JsonObject) = result["isError"]!!.jsonPrimitive.content.toBoolean()

    @Test
    fun initializeNegotiatesAndAdvertisesToolsAndResources() {
        val result = rpc("initialize", buildJsonObject { put("protocolVersion", "2025-06-18") })["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        assertNotNull(result["capabilities"]!!.jsonObject["resources"])
        assertEquals(null, mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), "a notification gets no reply")
        assertEquals(-32601, rpc("nope")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun everyToolMapsToARealRouteAndNoneDecidesAnApproval() {
        val tools = rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        assertTrue(tools.size >= 20)
        val bad = Regex("approve|reject|resolve|decide|release|lift|grant|allow|deny", RegexOption.IGNORE_CASE)
        for (t in tools) {
            val name = t["name"]!!.jsonPrimitive.content
            assertFalse(bad.containsMatchIn(name), name)
            assertEquals("object", t["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertNotNull(t["annotations"]!!.jsonObject["readOnlyHint"])
        }
        // Every tool is one control API route, with the same method and path shape, and the other way round.
        val routeKeys = ControlApi.routes.map { it.method.name to it.pattern.replace("{cid}", "{creatureId}") }.toSet()
        val toolKeys = Tools.all.map { it.method to it.path }.toSet()
        assertEquals(routeKeys, toolKeys)
        // The only approval tool reads.
        val approvalTools = Tools.all.filter { "approval" in it.name }
        assertEquals(listOf("aive_list_approvals"), approvalTools.map { it.name })
        assertEquals("GET", approvalTools.single().method)
        assertTrue(approvalTools.single().readOnly)
    }

    @Test
    fun destructiveToolsSayTheyAreAndAreAnnotated() {
        val tools = rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        val destructive = tools.filter { it["annotations"]!!.jsonObject["destructiveHint"]?.jsonPrimitive?.content == "true" }
        assertEquals(setOf("aive_delete_hive", "aive_remove_creature", "aive_stop_run"), destructive.map { it["name"]!!.jsonPrimitive.content }.toSet())
        destructive.forEach { assertTrue(it["description"]!!.jsonPrimitive.content.startsWith("DESTRUCTIVE"), it.toString()) }
        for (route in ControlApi.routes.filter { it.destructive }) assertTrue(Tools.all.any { it.destructive && it.method == route.method.name })
    }

    @Test
    fun readsComeBackThroughRealHttp() {
        assertEquals("Plan", Json.parseToJsonElement(text(callTool("aive_get_hive", buildJsonObject { put("id", "plan") }))).jsonObject["name"]!!.jsonPrimitive.content)
        val runs = Json.parseToJsonElement(text(callTool("aive_list_runs", buildJsonObject { put("hive", "plan"); put("limit", 5) }))).jsonArray.single().jsonObject
        assertEquals("plan", runs["hive"]!!.jsonPrimitive.content)
        assertEquals(5, runs["limit"]!!.jsonPrimitive.content.toInt())
        val events = Json.parseToJsonElement(text(callTool("aive_list_events", buildJsonObject { put("id", "r1"); put("after", 42) }))).jsonArray.single().jsonObject
        assertEquals(42, events["after"]!!.jsonPrimitive.content.toInt())
        assertFalse(isError(callTool("aive_list_providers")))
        assertFalse(isError(callTool("aive_get_settings")))
    }

    @Test
    fun resourcesReadTheSameState() {
        val uris = rpc("resources/list")["result"]!!.jsonObject["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
        assertTrue("aive://approvals" in uris && "aive://hives" in uris)
        fun read(uri: String) = rpc("resources/read", buildJsonObject { put("uri", uri) })
        val hives = read("aive://hives")["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Plan" in hives)
        val hive = read("aive://hives/plan")["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("\"plan\"" in hive)
        assertEquals(-32002, read("aive://hives/missing")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(-32002, read("aive://nothing")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(-32002, read("aive://hives/a/b")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val templates = rpc("resources/templates/list")["result"]!!.jsonObject["resourceTemplates"]!!.jsonArray
        assertTrue(templates.isNotEmpty())
    }

    @Test
    fun authoringReachesTheAppAndErrorsAreTyped() {
        val made = callTool(
            "aive_create_hive",
            buildJsonObject {
                put("name", "Docs")
                put("creatures", JsonArray(listOf(buildJsonObject { put("name", "writer"); put("roleId", "product-manager") })))
            },
        )
        assertFalse(isError(made))
        assertEquals(listOf("create:Docs:1"), app.log)

        callTool("aive_add_creature", buildJsonObject { put("id", "plan"); put("creatureId", "x"); put("name", "X"); put("roleId", "product-manager") })
        assertTrue(app.log.last().startsWith("add:plan:") && "\"id\":\"x\"" in app.log.last(), app.log.last())
        callTool("aive_update_creature", buildJsonObject { put("id", "plan"); put("creatureId", "x"); put("name", "Y") })
        assertEquals("uc:plan:x:{\"name\":\"Y\"}", app.log.last())
        callTool("aive_set_role_provider", buildJsonObject { put("id", "product-manager"); put("providerId", JsonPrimitive(null as String?)) })
        assertEquals("provider:product-manager:null", app.log.last())

        val refused = callTool("aive_delete_hive", buildJsonObject { put("id", "plan") })
        assertTrue(isError(refused))
        assertTrue("conflict" in text(refused), text(refused))
        assertTrue("plan" in app.hives)

        val missing = callTool("aive_get_hive", buildJsonObject { put("id", "ghost") })
        assertTrue(isError(missing) && "not_found" in text(missing))
        val invalid = callTool("aive_create_hive", buildJsonObject { put("creatures", JsonArray(emptyList())) })
        assertTrue(isError(invalid) && "invalid_argument" in text(invalid))
        val extra = callTool("aive_get_hive", buildJsonObject { put("id", "plan"); put("sneaky", "1") })
        assertTrue(isError(extra) && "invalid_argument" in text(extra))
        assertEquals(-32602, rpc("tools/call", buildJsonObject { put("name", "aive_approve_gate") })["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun stoppingARunWorksAndLeavesTheGateForThePerson() {
        assertEquals("Pending", Json.parseToJsonElement(text(callTool("aive_list_approvals"))).jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
        assertFalse(isError(callTool("aive_stop_run", buildJsonObject { put("id", "r1") })))
        assertEquals("Cancelled", Json.parseToJsonElement(text(callTool("aive_get_run", buildJsonObject { put("id", "r1") }))).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("Pending", app.gateStatus)
        assertFalse(isError(callTool("aive_delete_hive", buildJsonObject { put("id", "plan") })))
    }

    @Test
    fun theTokenNeverReachesAnAgent() {
        app.leak = "Authorization: Bearer $token sk-live-abcdefghijklmnopqrstuvwx"
        val out = listOf(
            text(callTool("aive_get_run", buildJsonObject { put("id", "r1") })),
            text(callTool("aive_get_role", buildJsonObject { put("id", "product-manager") })),
        )
        out.forEach { assertFalse(token in it, it); assertFalse("sk-live" in it, it) }
        currentToken = "wrong-token-value"
        val refused = callTool("aive_status")
        assertTrue(isError(refused) && "unauthorized" in text(refused))
        assertFalse(token in text(refused))
        currentToken = null
        val none = callTool("aive_status")
        assertTrue(isError(none) && "No control token" in text(none))
    }

    @Test
    fun anAppThatIsNotRunningIsReportedPlainly() {
        http.stop(0)
        val r = callTool("aive_status")
        assertTrue(isError(r) && "app_unreachable" in text(r), text(r))
    }

    @Test
    fun theBridgeWillNotSendTheTokenOffThisComputer() {
        assertFailsWith<IllegalArgumentException> { HttpControlTransport("http://192.168.1.10:41242", token = { token }) }
        assertFailsWith<IllegalArgumentException> { HttpControlTransport("http://evil.example", token = { token }) }
    }

    @Test
    fun noToolArgumentsEscapeThePath() {
        val (_, path, _) = Tools.request(Tools.byName("aive_get_hive")!!, buildJsonObject { put("id", "../../approvals/x") })
        assertEquals("/hives/..%2F..%2Fapprovals%2Fx", path)
    }
}
