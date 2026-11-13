package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.CONTEXT_PROVENANCE_METADATA_KEY
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.domain.labelledAtIntake
import com.hereliesaz.geministrator.domain.provenance
import com.hereliesaz.geministrator.mcp.McpToolOffer
import com.hereliesaz.geministrator.mcp.McpToolOutcome
import com.hereliesaz.geministrator.mcp.McpToolSource
import com.hereliesaz.geministrator.mcp.McpToolbox
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val weatherOffer = McpToolOffer(
    name = "m1_get_weather",
    description = "[MCP server \"Weather\"] Weather now",
    inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}}}""").jsonObject,
    serverId = "mcp-weather01",
    serverName = "Weather",
    toolName = "get_weather",
)

/** Answers every call with fenced text, as the host does. */
private class FakeToolbox : McpToolbox {
    override val offers = listOf(weatherOffer)
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun call(offerName: String, arguments: JsonObject): McpToolOutcome {
        calls += offerName to arguments
        return McpToolOutcome(offerName, weatherOffer.serverId, "Weather", "get_weather", "⟦external⟧ Sunny in ${arguments["city"]?.jsonPrimitive?.content}", isError = false)
    }
}

/** A model that calls the weather tool once, then answers with what it got back. */
private class ScriptedToolModel(private val alwaysCall: Boolean = false) : ToolCallingTextGenerationApi {
    val seen = mutableListOf<Pair<List<ToolRound>, Boolean>>()

    override suspend fun generate(prompt: String): TextGenerationResult = error("tool runs do not stream")

    override suspend fun converse(prompt: String, tools: List<McpToolOffer>, rounds: List<ToolRound>, allowTools: Boolean): ToolTurn {
        seen += rounds.toList() to allowTools
        assertEquals(listOf("m1_get_weather"), tools.map { it.name })
        if (allowTools && (alwaysCall || rounds.isEmpty())) {
            val call = ToolCall("call-${rounds.size}", "m1_get_weather", buildJsonObject { put("city", "Lyon") })
            return ToolTurn("", listOf(call), JsonArray(emptyList()), inputTokens = 10, outputTokens = 2)
        }
        val last = rounds.lastOrNull()?.results?.single()?.content.orEmpty()
        return ToolTurn("Answer: $last", emptyList(), JsonArray(emptyList()), inputTokens = 20, outputTokens = 5)
    }
}

class McpToolLoopTest {
    private val request = AgentTaskRequest(
        taskRunId = TaskRunId("task-weather"),
        objective = "Report the weather in Lyon",
        roleInstructions = "Be brief.",
        acceptanceCriteria = emptyList(),
    )

    @Test
    fun aToolCallRoundTripsThroughAProviderAndMarksItsArtifactExternal() = runTest {
        val toolbox = FakeToolbox()
        val keys = mutableListOf<String>()
        val model = ScriptedToolModel()
        val provider = TextLlmProvider(
            AgentProviderId("fake"),
            "Fake",
            model,
            tools = McpToolSource { runKey -> keys += runKey; toolbox },
        )
        val runId = provider.start(request).providerRunId

        val events = provider.observe(runId).toList()

        assertEquals(listOf("task-weather"), keys, "outside a workflow run, the task run keys consent")
        assertEquals(listOf("m1_get_weather" to buildJsonObject { put("city", "Lyon") }), toolbox.calls)
        assertEquals("Calling get_weather on Weather", events.filterIsInstance<AgentEvent.Progress>().single().message)
        val artifact = events.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact
        assertEquals("Answer: ⟦external⟧ Sunny in Lyon", artifact.textContent)
        assertEquals("external-tool-results", artifact.metadata[MCP_PROVENANCE_METADATA])
        // The label the runtime's taint rule reads (domain/ContextProvenance.kt), not only the descriptive one.
        assertEquals(ContextProvenance.ExternalContent.name, artifact.metadata[CONTEXT_PROVENANCE_METADATA_KEY])
        val takenIn = ArtifactRef(
            id = ArtifactId("answer"),
            kind = ArtifactKind.Research,
            taskRunId = TaskRunId("task-weather"),
            label = artifact.label,
            textContent = artifact.textContent,
            metadata = artifact.metadata,
            createdAtEpochMillis = 1L,
        ).labelledAtIntake(ContextProvenance.AgentArtifact, madeFromUntrustedInput = false)
        assertEquals(ContextProvenance.ExternalContent, takenIn.provenance)
        assertTrue(takenIn.isUntrustedContext(), "a writer step downstream of a tool-assisted answer is held at a gate")
        assertEquals("Weather/get_weather", artifact.metadata[MCP_TOOL_CALLS_METADATA])
        val usage = events.filterIsInstance<AgentEvent.UsageReported>().single()
        assertEquals(30L, usage.inputTokens)
        assertIs<AgentEvent.Completed>(events.last())
        // The second turn saw the call's result under the call's id.
        val (rounds, _) = model.seen.last()
        assertEquals("call-0", rounds.single().results.single().callId)
    }

    @Test
    fun withoutToolsTheProviderStreamsAsBefore() = runTest {
        var streamed = false
        val api = object : ToolCallingTextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult {
                streamed = true
                return TextGenerationResult("plain answer")
            }

            override suspend fun converse(prompt: String, tools: List<McpToolOffer>, rounds: List<ToolRound>, allowTools: Boolean): ToolTurn =
                error("no tools were offered")
        }
        val provider = TextLlmProvider(AgentProviderId("fake"), "Fake", api, tools = McpToolSource { null })
        val events = provider.observe(provider.start(request).providerRunId).toList()
        assertTrue(streamed)
        assertEquals("plain answer", events.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact.textContent)
        assertTrue(events.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact.metadata[MCP_PROVENANCE_METADATA] == null)
        assertTrue(events.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact.metadata[CONTEXT_PROVENANCE_METADATA_KEY] == null)
    }

    @Test
    fun aServiceThatRefusesToolsStillDoesTheWorkWithoutThem() = runTest {
        val api = object : ToolCallingTextGenerationApi {
            override suspend fun generate(prompt: String) = TextGenerationResult("without tools")

            override suspend fun converse(prompt: String, tools: List<McpToolOffer>, rounds: List<ToolRound>, allowTools: Boolean): ToolTurn =
                error("HTTP 400: tools are not supported by this model")
        }
        val toolbox = FakeToolbox()
        val provider = TextLlmProvider(AgentProviderId("fake"), "Fake", api, tools = McpToolSource { toolbox })
        val events = provider.observe(provider.start(request).providerRunId).toList()
        assertEquals("without tools", events.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact.textContent)
        assertTrue(toolbox.calls.isEmpty())
        assertIs<AgentEvent.Completed>(events.last())
    }

    @Test
    fun aFailureThatMayHaveBeenBilledIsNotRetriedWithoutTools() = runTest {
        var plainGenerations = 0
        val api = object : ToolCallingTextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult {
                plainGenerations++
                return TextGenerationResult("a second, paid answer")
            }

            override suspend fun converse(prompt: String, tools: List<McpToolOffer>, rounds: List<ToolRound>, allowTools: Boolean): ToolTurn =
                error("HTTP 503: upstream unavailable")
        }
        val provider = TextLlmProvider(AgentProviderId("fake"), "Fake", api, tools = McpToolSource { FakeToolbox() })
        val events = provider.observe(provider.start(request).providerRunId).toList()
        assertEquals(0, plainGenerations, "the first request may have been billed; it is not asked a second time")
        assertIs<AgentEvent.Failed>(events.last())
    }

    @Test
    fun theLoopStopsOfferingToolsOnceItsTokenBudgetIsSpent() = runTest {
        val model = ScriptedToolModel(alwaysCall = true)
        // Each calling round spends 12 tokens: the third request is already past 20.
        val outcome = McpToolLoop(maxRounds = 8, maxTotalTokens = 20L).run(model, "prompt", FakeToolbox())
        assertEquals(listOf(true, true, false), model.seen.map { it.second })
        assertEquals(2, outcome.calls.size)
    }

    @Test
    fun theLoopIsBoundedAndEndsWithToolsRefused() = runTest {
        val model = ScriptedToolModel(alwaysCall = true)
        val outcome = McpToolLoop(maxRounds = 3).run(model, "prompt", FakeToolbox())
        assertEquals(4, model.seen.size)
        assertEquals(listOf(true, true, true, false), model.seen.map { it.second })
        assertEquals(3, outcome.calls.size)
        assertTrue(outcome.result.text.startsWith("Answer:"))
    }

    @Test
    fun malformedArgumentsAndExcessCallsAreAnsweredWithoutRunning() = runTest {
        val toolbox = FakeToolbox()
        val api = object : ToolCallingTextGenerationApi {
            override suspend fun generate(prompt: String) = error("unused")

            override suspend fun converse(prompt: String, tools: List<McpToolOffer>, rounds: List<ToolRound>, allowTools: Boolean): ToolTurn =
                if (rounds.isEmpty()) {
                    ToolTurn("", listOf(ToolCall("a", "m1_get_weather", null), ToolCall("b", "m1_get_weather", JsonObject(emptyMap()))), JsonArray(emptyList()))
                } else {
                    ToolTurn(rounds.single().results.joinToString("|") { "${it.callId}:${it.isError}" }, emptyList(), JsonArray(emptyList()))
                }
        }
        val outcome = McpToolLoop(maxCallsPerRound = 1).run(api, "prompt", toolbox)
        assertEquals("a:true|b:true", outcome.result.text)
        assertTrue(toolbox.calls.isEmpty())
    }

    @Test
    fun anthropicToolUseRoundTripsOverTheMessagesApi() = runTest {
        withContext(Dispatchers.Default) {
            val bodies = mutableListOf<JsonObject>()
            val engine = MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                bodies += body
                val reply = if (bodies.size == 1) {
                    """{"content":[{"type":"text","text":"Checking."},{"type":"tool_use","id":"toolu_1","name":"m1_get_weather","input":{"city":"Lyon"}}],"stop_reason":"tool_use","usage":{"input_tokens":12,"output_tokens":7}}"""
                } else {
                    """{"content":[{"type":"text","text":"It is sunny."}],"stop_reason":"end_turn","usage":{"input_tokens":30,"output_tokens":4}}"""
                }
                respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val api = AnthropicMessagesApi(LlmApiKeyProvider { "key" }, model = "claude-test", client = HttpClient(engine))
            val outcome = McpToolLoop().run(api, "What is the weather?", FakeToolbox())

            assertEquals("It is sunny.", outcome.result.text)
            assertEquals(42L, outcome.result.inputTokens)
            val first = bodies.first()
            val tool = first.getValue("tools").jsonArray.single().jsonObject
            assertEquals("m1_get_weather", tool.getValue("name").jsonPrimitive.content)
            assertEquals("object", tool.getValue("input_schema").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("auto", first.getValue("tool_choice").jsonObject.getValue("type").jsonPrimitive.content)
            val messages = bodies.last().getValue("messages").jsonArray
            assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals("toolu_1", messages[1].jsonObject.getValue("content").jsonArray[1].jsonObject.getValue("id").jsonPrimitive.content)
            val result = messages[2].jsonObject.getValue("content").jsonArray.single().jsonObject
            assertEquals("tool_result", result.getValue("type").jsonPrimitive.content)
            assertEquals("toolu_1", result.getValue("tool_use_id").jsonPrimitive.content)
            assertTrue(result.getValue("content").jsonPrimitive.content.contains("Sunny in Lyon"))
        }
    }

    @Test
    fun compatibleAndGeminiWireFormatsKeepCallIdsAndSignatures() {
        val compatible = CompatibleToolWire.turn(
            Json.parseToJsonElement(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_9","type":"function","function":{"name":"m1_get_weather","arguments":"{\"city\":\"Lyon\"}"}},{"type":"function","function":{"name":"m1_get_weather","arguments":"not json"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":3}}""",
            ).jsonObject,
        )
        assertEquals(listOf("call_9", "call_1"), compatible.calls.map { it.id })
        assertEquals("Lyon", compatible.calls[0].arguments?.get("city")?.jsonPrimitive?.content)
        assertEquals(null, compatible.calls[1].arguments)
        val followUp = CompatibleToolWire.request(
            "m",
            "p",
            listOf(weatherOffer),
            listOf(ToolRound(compatible, listOf(ToolCallResult("call_9", "m1_get_weather", "ok", false), ToolCallResult("call_1", "m1_get_weather", "bad", true)))),
            allowTools = false,
        )
        val messages = followUp.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "tool", "tool"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("call_1", messages[1].getValue("tool_calls").jsonArray[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("call_1", messages[3].getValue("tool_call_id").jsonPrimitive.content)
        assertEquals(JsonPrimitive("none"), followUp.getValue("tool_choice"))

        val gemini = GeminiToolWire.turn(
            Json.parseToJsonElement(
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"m1_get_weather","args":{"city":"Lyon"}},"thoughtSignature":"sig-1"}]},"finishReason":"STOP"}]}""",
            ).jsonObject,
        )
        assertEquals("m1_get_weather", gemini.calls.single().name)
        val geminiFollowUp = GeminiToolWire.request(
            "p",
            listOf(weatherOffer),
            listOf(ToolRound(gemini, listOf(ToolCallResult(gemini.calls.single().id, "m1_get_weather", "ok", false)))),
            allowTools = true,
        )
        val contents = geminiFollowUp.getValue("contents").jsonArray.map { it.jsonObject }
        val replayed = contents[1].getValue("parts").jsonArray.single().jsonObject
        assertEquals("sig-1", replayed.getValue("thoughtSignature").jsonPrimitive.content, "thought signatures go back as received")
        val response = contents[2].getValue("parts").jsonArray.single().jsonObject.getValue("functionResponse").jsonObject
        assertEquals("m1_get_weather", response.getValue("name").jsonPrimitive.content)
        assertEquals(null, response["id"], "a call Gemini sent without an id is answered by name")
        val declaration = geminiFollowUp.getValue("tools").jsonArray.single().jsonObject.getValue("functionDeclarations").jsonArray.single().jsonObject
        assertEquals("object", declaration.getValue("parametersJsonSchema").jsonObject.getValue("type").jsonPrimitive.content)
    }
}
