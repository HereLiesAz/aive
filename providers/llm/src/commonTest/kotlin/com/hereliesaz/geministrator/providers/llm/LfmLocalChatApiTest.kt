package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.mcp.McpToolOffer
import com.hereliesaz.geministrator.providers.lfm.LfmModelProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The local LFM3 model as a tool-calling text API: it writes the model's format, reads the model's XML, and feeds results back. */
class LfmLocalChatApiTest {
    private val weather = McpToolOffer(
        name = "weather",
        description = "Weather for a city",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") { put("type", "string") }
                putJsonObject("days") { put("type", "integer") }
            }
        },
        serverId = "s", serverName = "S", toolName = "weather",
    )

    private class Script(private vararg val replies: String) {
        val prompts = mutableListOf<String>()
        var stops: List<String> = emptyList()
        var context = 0
        private var next = 0

        suspend fun run(prompt: String, @Suppress("UNUSED_PARAMETER") max: Int, context: Int, stops: List<String>): String {
            prompts += prompt
            this.stops = stops
            this.context = context
            return replies[next++]
        }
    }

    @Test
    fun aToolRequestBecomesATypedCall() = runTest {
        val script = Script("\nneed the weather\n</think>\n<tool_call>\n<function=weather>\n<parameter=city>\nParis\n</parameter>\n<parameter=days>\n3\n</parameter>\n</function>\n</tool_call><|im_end|>")
        val api = LfmLocalChatApi(LfmModelProfile(contextTokens = 2048), script::run)
        val turn = api.converse("Weather in Paris?", listOf(weather), emptyList(), allowTools = true)
        val call = turn.calls.single()
        assertEquals("weather", call.name)
        assertEquals(JsonPrimitive("Paris"), call.arguments!!["city"])
        assertEquals(JsonPrimitive(3), call.arguments!!["days"])
        val prompt = script.prompts.single()
        assertTrue("<tools>" in prompt && "\"name\": \"weather\"" in prompt)
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>"))
        assertEquals(2048, script.context)
        assertTrue("<|im_end|>" in script.stops)
    }

    @Test
    fun theToolsResultGoesBackAsAToolResponse() = runTest {
        val script = Script("It is sunny.<|im_end|>")
        val api = LfmLocalChatApi(LfmModelProfile(thinking = false), script::run)
        val first = ToolTurn(
            text = "",
            calls = listOf(ToolCall("lfm-0-0", "weather", buildJsonObject { put("city", "Paris") })),
            native = kotlinx.serialization.json.JsonNull,
        )
        val rounds = listOf(ToolRound(first, listOf(ToolCallResult("lfm-0-0", "weather", "sunny, 21C", isError = false))))
        val turn = api.converse("Weather in Paris?", listOf(weather), rounds, allowTools = true)
        assertEquals("It is sunny.", turn.text)
        assertTrue(turn.calls.isEmpty())
        val prompt = script.prompts.single()
        assertTrue("<tool_call>\n<function=weather>\n<parameter=city>\nParis\n</parameter>\n</function>\n</tool_call>" in prompt, prompt)
        assertTrue("<tool_response>\nsunny, 21C\n</tool_response>" in prompt, prompt)
        assertTrue(prompt.endsWith("<think></think>"))
    }

    @Test
    fun aFailedToolIsReportedAsAnError() = runTest {
        val script = Script("Sorry.<|im_end|>")
        val api = LfmLocalChatApi(LfmModelProfile(thinking = false), script::run)
        val first = ToolTurn("", listOf(ToolCall("a", "weather", JsonObject(emptyMap()))), kotlinx.serialization.json.JsonNull)
        api.converse("q", listOf(weather), listOf(ToolRound(first, listOf(ToolCallResult("a", "weather", "no such city", isError = true)))), true)
        assertTrue("<tool_response>\nError: no such city\n</tool_response>" in script.prompts.single())
    }

    @Test
    fun theLastWordOffersNoTools() = runTest {
        val script = Script("Done.<|im_end|>")
        val api = LfmLocalChatApi(LfmModelProfile(thinking = false), script::run)
        api.converse("q", listOf(weather), emptyList(), allowTools = false)
        assertFalse("<tools>" in script.prompts.single())
    }

    @Test
    fun plainGenerationReturnsTheAnswerAndItsThinking() = runTest {
        val script = Script("\nhmm\n</think>\nParis.<|im_end|>")
        val result = LfmLocalChatApi(LfmModelProfile(), script::run).generate("Capital of France?")
        assertEquals("Paris.", result.text)
        assertEquals("hmm", result.thinking)
        assertNotNull(result.inputTokens)
        assertFalse(LfmLocalChatApi(LfmModelProfile(), script::run).billsPerToken)
    }
}
