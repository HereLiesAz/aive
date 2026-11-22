package com.hereliesaz.geministrator.providers.lfm

import com.hereliesaz.geministrator.providers.lfm.LfmChatPrompt.Message
import com.hereliesaz.geministrator.providers.lfm.LfmChatPrompt.ToolCall
import com.hereliesaz.geministrator.providers.lfm.LfmChatPrompt.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/** [LfmChatPrompt] against what the model's own template renders ([LfmChatPromptGolden]). */
class LfmChatPromptTest {
    private val weather = ToolSpec(
        name = "get_weather",
        description = "Current weather in a city.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name")
                }
                putJsonObject("days") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("city")) }
        },
    )

    @Test
    fun aSystemAndAUserTurnOpenAThought() {
        assertEquals(
            LfmChatPromptGolden.PLAIN_THINK_ON,
            LfmChatPrompt.format(listOf(Message.System("Be brief."), Message.User("Hi"))),
        )
    }

    @Test
    fun withoutThinkingTheAssistantTurnOpensClosed() {
        assertEquals(
            LfmChatPromptGolden.PLAIN_THINK_OFF,
            LfmChatPrompt.format(listOf(Message.User("Hi")), enableThinking = false),
        )
    }

    @Test
    fun toolsAreOneJsonLineEachInsideTheSystemPrompt() {
        assertEquals(
            LfmChatPromptGolden.TOOLS,
            LfmChatPrompt.format(
                listOf(Message.System("Be brief."), Message.User("Weather in Paris?")),
                tools = listOf(weather),
                enableThinking = false,
            ),
        )
    }

    @Test
    fun toolsAloneMakeTheSystemPrompt() {
        assertEquals(
            LfmChatPromptGolden.TOOLS_NO_SYSTEM,
            LfmChatPrompt.format(listOf(Message.User("Weather in Paris?")), tools = listOf(weather)),
        )
    }

    @Test
    fun aToolRoundTripWritesTheCallAsXmlAndTheResultAsAToolResponse() {
        val call = ToolCall(
            "get_weather",
            buildJsonObject {
                put("city", "Paris")
                put("days", 3)
                put("metric", true)
                put("note", JsonNull)
                put("tags", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
                putJsonObject("opts") { put("k", 1) }
                put("text", "line1\nline2")
            },
        )
        assertEquals(
            LfmChatPromptGolden.TOOL_ROUND_TRIP,
            LfmChatPrompt.format(
                listOf(
                    Message.User("Weather in Paris?"),
                    Message.Assistant(content = "", thinking = "I should call the tool.", toolCalls = listOf(call)),
                    Message.Tool("Paris: 18 C, light rain"),
                ),
                tools = listOf(weather),
            ),
        )
    }

    @Test
    fun onlyTheTurnsAfterTheLastUserMessageKeepTheirThinking() {
        assertEquals(
            LfmChatPromptGolden.MULTI_TURN,
            LfmChatPrompt.format(
                listOf(
                    Message.User("Q1"),
                    Message.Assistant("A1", thinking = "early thought"),
                    Message.User("Q2"),
                    Message.Assistant("A2 <think>x</think>tail", thinking = "late thought"),
                    Message.User("Q3"),
                ),
                enableThinking = false,
            ),
        )
        assertEquals(
            LfmChatPromptGolden.PRESERVE,
            LfmChatPrompt.format(
                listOf(Message.User("Q1"), Message.Assistant("A1", thinking = "T"), Message.User("Q2")),
                preserveThinking = true,
            ),
        )
    }

    @Test
    fun resultsOfOneCallAreOneToolResponse() {
        assertEquals(
            LfmChatPromptGolden.ASSISTANT_AFTER_USER_NO_THINK,
            LfmChatPrompt.format(
                listOf(
                    Message.User("Q1"),
                    Message.Assistant("A1"),
                    Message.Tool("r1", toolCallId = "c1"),
                    Message.Tool("r2", toolCallId = "c1"),
                ),
            ),
        )
    }

    @Test
    fun severalCallsInOneTurnAreSeparatedByABlankLine() {
        assertEquals(
            LfmChatPromptGolden.TWO_CALLS,
            LfmChatPrompt.format(
                listOf(
                    Message.User("x"),
                    Message.Assistant(
                        content = "",
                        toolCalls = listOf(
                            ToolCall("a"),
                            ToolCall("b", buildJsonObject { put("q", "z") }),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun theStartTokenIsOptional() {
        val without = LfmChatPrompt.format(listOf(Message.User("Hi")))
        assertEquals("<|startoftext|>$without", LfmChatPrompt.format(listOf(Message.User("Hi")), includeStartToken = true))
    }

    @Test
    fun jsonIsWrittenTheWayThePythonTemplateWritesIt() {
        val json = LfmChatPrompt.toJson(
            buildJsonObject {
                put("a", "tab\there \"quoted\" back\\slash é ✓ \u0001")
                put("n", 1.5)
                putJsonArray("l") { add(JsonPrimitive(1)); add(JsonPrimitive(false)); add(JsonNull) }
            },
        )
        assertEquals("""{"a": "tab\there \"quoted\" back\\slash é ✓ \u0001", "n": 1.5, "l": [1, false, null]}""", json)
        assertEquals("{}", LfmChatPrompt.toJson(JsonObject(emptyMap())))
    }
}
