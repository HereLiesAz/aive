package com.hereliesaz.geministrator.mcp

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A modern in-memory MCP server whose answers are scripted per method. [answer] returns the
 * `result` JSON, or `ERROR {…}` for an error object. `server/discover` is answered for it.
 */
internal class ScriptedMcpServer(private val answer: (method: String, params: JsonObject) -> String) : McpTransport {
    override val kind = McpTransportKind.StreamableHttp
    val sent = mutableListOf<McpOutgoing>()

    val methods: List<String> get() = sent.map { it.message.getValue("method").jsonPrimitive.content }

    fun params(method: String): List<JsonObject> = sent
        .filter { it.message.getValue("method").jsonPrimitive.content == method }
        .map { it.message.getValue("params").jsonObject }

    fun headers(method: String): List<Map<String, String>> = sent
        .filter { it.message.getValue("method").jsonPrimitive.content == method }
        .map { it.headers }

    override suspend fun exchange(outgoing: McpOutgoing): McpIncoming {
        sent += outgoing
        val message = outgoing.message
        val id = message.getValue("id").jsonPrimitive.content
        val method = message.getValue("method").jsonPrimitive.content
        val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
        val reply = if (method == "server/discover") """{"supportedVersions":["${McpProtocol.MODERN_VERSION}"]}""" else answer(method, params)
        val text = if (reply.startsWith("ERROR ")) {
            """{"jsonrpc":"2.0","id":$id,"error":${reply.removePrefix("ERROR ")}}"""
        } else {
            """{"jsonrpc":"2.0","id":$id,"result":$reply}"""
        }
        return McpIncoming(200, Json.parseToJsonElement(text).jsonObject)
    }

    override suspend fun close() = Unit
}

private fun JsonObject.capabilities(): JsonObject =
    getValue("_meta").jsonObject.getValue(McpProtocol.META_CLIENT_CAPABILITIES).jsonObject

class McpElicitationTest {
    private val nameForm = """{"method":"elicitation/create","params":{"mode":"form","message":"Your GitHub user name?","requestedSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}"""

    @Test
    fun aFormElicitationIsAnsweredAndTheCallRetriedWithTheAnswerAndTheState() = runTest {
        val server = ScriptedMcpServer { method, params ->
            assertEquals("tools/call", method)
            if (params["inputResponses"] == null) {
                """{"resultType":"input_required","inputRequests":{"login":$nameForm},"requestState":"opaque-1"}"""
            } else {
                """{"resultType":"complete","content":[{"type":"text","text":"hello ${params.getValue("inputResponses").jsonObject.getValue("login").jsonObject.getValue("content").jsonObject.getValue("name").jsonPrimitive.content}"}]}"""
            }
        }
        val asked = mutableListOf<JsonObject>()
        val client = McpClient(server)
        val result = client.callTool("greet", buildJsonObject { put("loud", true) }) { params ->
            asked += params
            McpElicitationResponse(McpElicitationAction.Accept, buildJsonObject { put("name", "octocat") }).toJson()
        }

        assertEquals("hello octocat", result.renderText())
        assertEquals("Your GitHub user name?", asked.single().getValue("message").jsonPrimitive.content)
        val (first, retry) = server.params("tools/call")
        assertNull(first["inputResponses"])
        assertNull(first["requestState"])
        assertEquals("opaque-1", retry.getValue("requestState").jsonPrimitive.content, "the state is echoed exactly")
        assertEquals(
            Json.parseToJsonElement("""{"login":{"action":"accept","content":{"name":"octocat"}}}"""),
            retry.getValue("inputResponses"),
        )
        assertEquals(first.getValue("arguments"), retry.getValue("arguments"), "the retry is the same request")
        val ids = server.sent.filter { it.message["method"]?.jsonPrimitive?.content == "tools/call" }.map { it.message.getValue("id") }
        assertNotEquals(ids[0], ids[1], "a retry is a new JSON-RPC request")
        listOf(first, retry).forEach { params ->
            val meta = params.getValue("_meta").jsonObject
            assertEquals(McpProtocol.MODERN_VERSION, meta.getValue(McpProtocol.META_PROTOCOL_VERSION).jsonPrimitive.content)
            val capabilities = params.capabilities()
            assertEquals(Json.parseToJsonElement("""{"form":{},"url":{}}"""), capabilities["elicitation"])
            assertNull(capabilities["sampling"])
            assertNull(capabilities["roots"])
            assertNotNull(capabilities.getValue("extensions").jsonObject[McpProtocol.EXTENSION_TASKS])
        }
        server.headers("tools/call").forEach { headers ->
            assertEquals("tools/call", headers[McpProtocol.HEADER_METHOD])
            assertEquals("greet", headers[McpProtocol.HEADER_NAME])
        }
    }

    @Test
    fun aStateOnlyRoundIsRetriedAtOnceAndAStaleStateIsNeverResent() = runTest {
        var round = 0
        val server = ScriptedMcpServer { _, _ ->
            when (round++) {
                0 -> """{"resultType":"input_required","requestState":"s-1"}"""
                1 -> """{"resultType":"input_required","inputRequests":{"k":$nameForm}}"""
                else -> """{"resultType":"complete","content":[{"type":"text","text":"done"}]}"""
            }
        }
        var asked = 0
        val result = McpClient(server).callTool("t", JsonObject(emptyMap())) {
            asked++
            McpElicitationResponse.Decline.toJson()
        }

        assertEquals("done", result.renderText())
        assertEquals(1, asked)
        val (first, second, third) = server.params("tools/call")
        assertNull(first["requestState"])
        assertEquals("s-1", second.getValue("requestState").jsonPrimitive.content)
        assertNull(second["inputResponses"], "nothing was asked, so nothing is answered")
        assertNull(third["requestState"], "the last answer carried no state, so the retry carries none")
        assertEquals(Json.parseToJsonElement("""{"k":{"action":"decline"}}"""), third.getValue("inputResponses"))
    }

    @Test
    fun samplingOrRootsEndTheCallEvenWithAKeeper() = runTest {
        val server = ScriptedMcpServer { _, _ ->
            """{"resultType":"input_required","inputRequests":{"a":$nameForm,"b":{"method":"sampling/createMessage","params":{"messages":[],"maxTokens":10}}}}"""
        }
        var asked = 0
        assertFailsWith<McpInputRequiredException> {
            McpClient(server).callTool("t", JsonObject(emptyMap())) { asked++; McpElicitationResponse.Cancel.toJson() }
        }
        assertEquals(0, asked, "no part of a request The Aive cannot fully answer is put to the keeper")
        assertEquals(1, server.params("tools/call").size)
    }

    @Test
    fun aServerThatKeepsAskingIsCutOff() = runTest {
        val server = ScriptedMcpServer { _, _ -> """{"resultType":"input_required","inputRequests":{"k":$nameForm}}""" }
        assertFailsWith<McpInputRequiredException> {
            McpClient(server).callTool("t", JsonObject(emptyMap())) { McpElicitationResponse.Cancel.toJson() }
        }
        assertEquals(McpClient.MAX_INPUT_ROUNDS + 1, server.params("tools/call").size)
    }

    @Test
    fun formSchemasBecomeTypedFieldsAndTypedContent() {
        val params = Json.parseToJsonElement(
            """{"message":"Details","requestedSchema":{"type":"object","properties":{
              "name":{"type":"string","title":"Name","minLength":2,"default":"Ann"},
              "email":{"type":"string","format":"email"},
              "age":{"type":"integer","minimum":18,"maximum":130},
              "score":{"type":"number"},
              "subscribe":{"type":"boolean","default":true},
              "colour":{"type":"string","oneOf":[{"const":"#f00","title":"Red"},{"const":"#0f0","title":"Green"}]},
              "size":{"type":"string","enum":["S","M"],"enumNames":["Small","Medium"]},
              "tags":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","enum":["a","b","c"]}},
              "api_key":{"type":"string"}
            },"required":["name","age","tags"]}}""",
        ).jsonObject
        val form = assertIs<McpElicitationRequest.Form>(McpElicitationRequest.parse(params, "id", "Docs", "https://docs.example/mcp", "lookup", "run-1"))
        assertEquals(
            listOf(
                McpElicitationFieldKind.Text, McpElicitationFieldKind.Text, McpElicitationFieldKind.Integer, McpElicitationFieldKind.Number,
                McpElicitationFieldKind.Flag, McpElicitationFieldKind.SingleChoice, McpElicitationFieldKind.SingleChoice,
                McpElicitationFieldKind.MultiChoice, McpElicitationFieldKind.Text,
            ),
            form.fields.map(McpElicitationField::kind),
        )
        assertEquals(listOf("Red", "Green"), form.fields.single { it.key == "colour" }.choices.map(McpElicitationChoice::label))
        assertEquals(listOf("Small", "Medium"), form.fields.single { it.key == "size" }.choices.map(McpElicitationChoice::label))
        assertTrue(form.fields.single { it.key == "api_key" }.looksSecret)
        assertFalse(form.fields.single { it.key == "name" }.looksSecret)

        val start = form.initialInputs()
        assertEquals(McpFieldInput.Text("Ann"), start["name"])
        assertEquals(McpFieldInput.Flag(true), start["subscribe"])
        assertEquals(
            mapOf("age" to McpFieldProblem.Required, "tags" to McpFieldProblem.Required),
            form.problems(start),
        )
        assertNull(form.content(start), "nothing is sent while a field is wrong")

        val wrong = start + mapOf(
            "name" to McpFieldInput.Text("A"),
            "email" to McpFieldInput.Text("not-an-email"),
            "age" to McpFieldInput.Text("17"),
            "score" to McpFieldInput.Text("many"),
            "tags" to McpFieldInput.Choices(setOf("a", "b", "c")),
            "colour" to McpFieldInput.Choices(setOf("#00f")),
        )
        assertEquals(
            mapOf(
                "name" to McpFieldProblem.TooShort,
                "email" to McpFieldProblem.BadEmail,
                "age" to McpFieldProblem.BelowMinimum,
                "score" to McpFieldProblem.NotANumber,
                "colour" to McpFieldProblem.NotAChoice,
                "tags" to McpFieldProblem.TooManyChoices,
            ),
            form.problems(wrong),
        )
        assertEquals(McpFieldProblem.NotAnInteger, form.problems(start + ("age" to McpFieldInput.Text("18.5")))["age"])

        val right = start + mapOf(
            "email" to McpFieldInput.Text("ann@example.com"),
            "age" to McpFieldInput.Text("30"),
            "score" to McpFieldInput.Text("2.5"),
            "colour" to McpFieldInput.Choices(setOf("#0f0")),
            "tags" to McpFieldInput.Choices(setOf("c", "a")),
        )
        assertEquals<kotlinx.serialization.json.JsonElement?>(
            Json.parseToJsonElement(
                """{"name":"Ann","email":"ann@example.com","age":30,"score":2.5,"subscribe":true,"colour":"#0f0","tags":["a","c"]}""",
            ),
            form.content(right),
        )
        assertEquals(JsonPrimitive(30L), form.content(right)!!.getValue("age"), "an integer is sent as a number, not text")
    }

    @Test
    fun aSchemaThatCannotBeShownFaithfullyIsNotShown() {
        fun parse(json: String) = McpElicitationRequest.parse(Json.parseToJsonElement(json).jsonObject, "id", "S", "loc", "t", "r")
        assertNull(parse("""{"message":"m","requestedSchema":{"type":"object","properties":{"address":{"type":"object","properties":{}}}}}"""))
        assertNull(parse("""{"message":"m","requestedSchema":{"type":"object","properties":{"xs":{"type":"array","items":{"type":"string"}}}}}"""))
        assertNull(parse("""{"message":"m"}"""))
        assertNull(parse("""{"mode":"telepathy","message":"m"}"""))
    }

    @Test
    fun aUrlIsOfferedOnlyWhenItIsAWebAddressAndLookAlikesAreFlagged() {
        fun parse(url: String) = McpElicitationRequest.parse(
            buildJsonObject {
                put("mode", "url")
                put("message", "Connect your account")
                put("url", url)
            },
            "id", "Calendar", "https://cal.example/mcp", "add_event", "run-1",
        )
        val plain = assertIs<McpElicitationRequest.Link>(parse("https://cal.example/connect?state=1"))
        assertEquals("cal.example", plain.host)
        assertEquals("https://cal.example/connect?state=1", plain.url, "shown in full, untouched")
        assertFalse(plain.suspicious)
        assertTrue(assertIs<McpElicitationRequest.Link>(parse("https://xn--pple-43d.com/login")).suspicious)
        assertTrue(assertIs<McpElicitationRequest.Link>(parse("http://cal.example/connect")).suspicious)
        assertTrue(assertIs<McpElicitationRequest.Link>(parse("https://cal.example@evil.example/")).suspicious)
        assertNull(parse("javascript:alert(1)"))
        assertNull(parse("file:///etc/passwd"))
        assertNull(parse("intent://x#Intent;end"))
        assertNull(parse(""))
    }

    @Test
    fun responsesCarryContentOnlyForAnAcceptedForm() {
        assertEquals(Json.parseToJsonElement("""{"action":"cancel"}"""), McpElicitationResponse.Cancel.toJson())
        assertEquals(
            Json.parseToJsonElement("""{"action":"decline"}"""),
            McpElicitationResponse(McpElicitationAction.Decline, buildJsonObject { put("x", 1) }).toJson(),
        )
        assertEquals(Json.parseToJsonElement("""{"action":"accept"}"""), McpElicitationResponse(McpElicitationAction.Accept).toJson())
    }

    @Test
    fun thePromptingGateShowsOneRequestAndCancelsWhenUnanswered() = runTest {
        val gate = PromptingMcpElicitationGate(timeoutMillis = 1_000)
        val request = McpElicitationRequest.Link("id", "Calendar", "https://cal.example/mcp", "add_event", "Connect", "run-1", "https://cal.example/c", "cal.example", false)

        val answered = async { gate.elicit(request) }
        assertEquals(request, gate.pending.first { it != null })
        gate.answer(McpElicitationResponse(McpElicitationAction.Accept))
        assertEquals(McpElicitationAction.Accept, answered.await().action)
        assertNull(gate.pending.value)

        assertEquals(McpElicitationResponse.Cancel, gate.elicit(request), "an unanswered request is a cancel")
        assertNull(gate.pending.value)
    }

    @Test
    fun theHostLabelsARequestWithItsServerAndDeclaresElicitationOnlyWithAGate() = runTest {
        val server = ScriptedMcpServer { method, params ->
            when (method) {
                "tools/list" -> """{"tools":[{"name":"book","inputSchema":{"type":"object"}}]}"""
                else -> if (params["inputResponses"] == null) {
                    """{"resultType":"input_required","inputRequests":{"when":{"method":"elicitation/create","params":{"message":"Which day?","requestedSchema":{"type":"object","properties":{"day":{"type":"string","format":"date"}},"required":["day"]}}}}}"""
                } else {
                    """{"content":[{"type":"text","text":"booked ${params.getValue("inputResponses").jsonObject.getValue("when").jsonObject.getValue("content").jsonObject.getValue("day").jsonPrimitive.content}"}]}"""
                }
            }
        }
        val asked = mutableListOf<McpElicitationRequest>()
        val gate = McpElicitationGate { request ->
            asked += request
            val form = request as McpElicitationRequest.Form
            McpElicitationResponse(McpElicitationAction.Accept, form.content(mapOf("day" to McpFieldInput.Text("2026-11-03"))))
        }
        val host = McpHost(
            store = McpServerStore(MapSettings()),
            vault = InMemoryMcpCredentialVault(),
            transports = McpTransportFactory { _, _ -> server },
            consent = { McpConsentDecision.AllowForRun },
            elicitation = gate,
        )
        host.addHttpServer("Calendar", "https://cal.example/mcp", bearerToken = null)

        val outcome = host.toolbox("run-7")!!.call("m1_book", JsonObject(emptyMap()))

        assertFalse(outcome.isError)
        assertTrue(outcome.text.contains("booked 2026-11-03"))
        val request = asked.single()
        assertEquals("Calendar", request.serverName)
        assertEquals("https://cal.example/mcp", request.serverLocation)
        assertEquals("book", request.toolName)
        assertEquals("run-7", request.runKey)
        assertEquals("Which day?", request.message)
        assertNotNull(server.params("tools/call").first().capabilities()["elicitation"])

        val bare = ScriptedMcpServer { method, _ ->
            if (method == "tools/list") """{"tools":[{"name":"book","inputSchema":{"type":"object"}}]}""" else """{"content":[]}"""
        }
        val noGate = McpHost(McpServerStore(MapSettings()), InMemoryMcpCredentialVault(), { _, _ -> bare }, { McpConsentDecision.AllowForRun })
        noGate.addHttpServer("Calendar", "https://cal.example/mcp", bearerToken = null)
        noGate.toolbox("run-8")!!.call("m1_book", JsonObject(emptyMap()))
        assertNull(bare.params("tools/call").single().capabilities()["elicitation"], "no keeper to ask, no elicitation declared")
    }

    @Test
    fun anUnshowableRequestIsCancelledWithoutAskingTheKeeper() = runTest {
        val server = ScriptedMcpServer { method, params ->
            when {
                method == "tools/list" -> """{"tools":[{"name":"book","inputSchema":{"type":"object"}}]}"""
                params["inputResponses"] == null ->
                    """{"resultType":"input_required","inputRequests":{"u":{"method":"elicitation/create","params":{"mode":"url","message":"Go","url":"javascript:alert(1)"}}}}"""
                else -> """{"content":[{"type":"text","text":"${params.getValue("inputResponses").jsonObject.getValue("u").jsonObject.getValue("action").jsonPrimitive.content}"}]}"""
            }
        }
        var asked = 0
        val host = McpHost(
            store = McpServerStore(MapSettings()),
            vault = InMemoryMcpCredentialVault(),
            transports = McpTransportFactory { _, _ -> server },
            consent = { McpConsentDecision.AllowForRun },
            elicitation = { asked++; McpElicitationResponse(McpElicitationAction.Accept) },
        )
        host.addHttpServer("Shady", "https://shady.example/mcp", bearerToken = null)
        val outcome = host.toolbox("run-1")!!.call("m1_book", JsonObject(emptyMap()))
        assertEquals(0, asked)
        assertTrue(outcome.text.contains("cancel"))
    }
}
