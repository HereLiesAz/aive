package com.hereliesaz.geministrator.providers.lfm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LfmToolCallParserTest {
    private val call = "<tool_call>\n<function=get_weather>\n<parameter=city>\nParis\n</parameter>\n</function>\n</tool_call>"

    @Test
    fun aThoughtThenACallIsTakenApart() {
        val reply = LfmToolCallParser.parse("I should ask the weather tool.</think>$call<|im_end|>", openedThought = true)
        assertEquals("I should ask the weather tool.", reply.thought)
        assertEquals("", reply.text)
        assertEquals(listOf(LfmToolCall("get_weather", mapOf("city" to "Paris"))), reply.toolCalls)
        assertTrue(reply.isToolRequest)
        assertTrue(reply.problems.isEmpty())
    }

    @Test
    fun anAnswerWithoutACallIsJustText() {
        val reply = LfmToolCallParser.parse("The capital of Japan is Tokyo.<|im_end|>", openedThought = false)
        assertNull(reply.thought)
        assertEquals("The capital of Japan is Tokyo.", reply.text)
        assertFalse(reply.isToolRequest)
    }

    @Test
    fun textAroundACallIsKeptAndTheCallLeftOut() {
        val reply = LfmToolCallParser.parse("</think>Checking now.\n$call\nDone.", openedThought = true)
        assertEquals("Checking now.\n\nDone.", reply.text)
        assertEquals(1, reply.toolCalls.size)
    }

    @Test
    fun severalCallsAndSeveralParametersKeepTheirOrder() {
        val reply = LfmToolCallParser.parse(
            "</think><tool_call>\n<function=a>\n<parameter=x>\n1\n</parameter>\n<parameter=y>\ntwo\n</parameter>\n</function>\n</tool_call>\n\n" +
                "<tool_call>\n<function=b>\n</function>\n</tool_call>",
            openedThought = true,
        )
        assertEquals(listOf(LfmToolCall("a", linkedMapOf("x" to "1", "y" to "two")), LfmToolCall("b", emptyMap())), reply.toolCalls)
        assertEquals(listOf("x", "y"), reply.toolCalls[0].arguments.keys.toList())
    }

    @Test
    fun aValueKeepsEverythingButTheOneNewlineOnEachSide() {
        val reply = LfmToolCallParser.parse(
            "</think><tool_call>\n<function=write>\n<parameter=code>\n\nfun f() {\n    return 1\n}\n\n</parameter>\n</function>\n</tool_call>",
            openedThought = true,
        )
        assertEquals("\nfun f() {\n    return 1\n}\n", reply.toolCalls.single().arguments.getValue("code"))
    }

    @Test
    fun aValueMayHoldMarkupLikeText() {
        val reply = LfmToolCallParser.parse(
            "</think><tool_call>\n<function=note>\n<parameter=body>\na < b && c > d <b>bold</b>\n</parameter>\n</function>\n</tool_call>",
            openedThought = true,
        )
        assertEquals("a < b && c > d <b>bold</b>", reply.toolCalls.single().arguments.getValue("body"))
    }

    @Test
    fun theFormatWithNoNewlinesStillReads() {
        val reply = LfmToolCallParser.parse("</think><tool_call><function=f><parameter=q>z</parameter></function></tool_call>", true)
        assertEquals(LfmToolCall("f", mapOf("q" to "z")), reply.toolCalls.single())
    }

    @Test
    fun aStrayThinkCloseEndsTheThoughtInsteadOfLeakingIntoTheAnswer() {
        val reply = LfmToolCallParser.parse("17 + 25 = 42.</think>42", openedThought = false)
        assertEquals("17 + 25 = 42.", reply.thought)
        assertEquals("42", reply.text)
    }

    @Test
    fun aThinkBlockWhenThinkingWasOffIsStillSplitOff() {
        val reply = LfmToolCallParser.parse("<think>Bonjour is the word.</think>Merci", openedThought = false)
        assertEquals("Bonjour is the word.", reply.thought)
        assertEquals("Merci", reply.text)
    }

    @Test
    fun runningOutOfTokensWhileThinkingIsReportedNotMistakenForAnAnswer() {
        val reply = LfmToolCallParser.parse("Let me think about this for a long", openedThought = true)
        assertEquals("", reply.text)
        assertTrue(reply.thoughtUnfinished)
        assertEquals("Let me think about this for a long", reply.thought)
        assertTrue(reply.problems.isNotEmpty())

        val off = LfmToolCallParser.parse("<think>still going", openedThought = false)
        assertTrue(off.thoughtUnfinished)
        assertEquals("", off.text)
    }

    @Test
    fun aCallAfterAnUnclosedThoughtIsStillUsed() {
        val reply = LfmToolCallParser.parse("I will call it. $call", openedThought = true)
        assertEquals(1, reply.toolCalls.size)
        assertFalse(reply.thoughtUnfinished)
        assertTrue(reply.problems.any { "never closed" in it })
    }

    @Test
    fun aCallCutOffByTheTokenLimitIsReturnedAndReported() {
        val cut = LfmToolCallParser.parse("</think><tool_call>\n<function=get_weather>\n<parameter=city>\nPar", openedThought = true)
        assertEquals(LfmToolCall("get_weather", mapOf("city" to "Par")), cut.toolCalls.single())
        assertTrue(cut.problems.size >= 2)

        val noFunction = LfmToolCallParser.parse("</think><tool_call>\nsomething else\n</tool_call>", openedThought = true)
        assertTrue(noFunction.toolCalls.isEmpty())
        assertTrue(noFunction.problems.any { "no function" in it })
    }

    @Test
    fun textAfterTheEndOfTurnMarkerIsDropped() {
        val reply = LfmToolCallParser.parse("Done.<|im_end|>\n<|im_start|>user\nmore", openedThought = false)
        assertEquals("Done.", reply.text)
        assertEquals("Done.", LfmToolCallParser.parse("Done.<|endoftext|>garbage", false).text)
    }

    @Test
    fun valuesAreTypedBySchemaAndLeftAsStringsOtherwise() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") { put("type", "string") }
                putJsonObject("days") { put("type", "integer") }
                putJsonObject("ratio") { put("type", "number") }
                putJsonObject("metric") { put("type", "boolean") }
                putJsonObject("tags") { put("type", "array") }
                putJsonObject("opts") { put("type", "object") }
                putJsonObject("zip") { put("type", "string") }
                putJsonObject("n") { put("type", "integer") }
            }
        }
        val call = LfmToolCall(
            "f",
            linkedMapOf(
                "city" to "Paris",
                "days" to " 3 ",
                "ratio" to "0.5",
                "metric" to "True",
                "tags" to "[\"a\", \"b\"]",
                "opts" to "{\"k\": 1}",
                "zip" to "02134",
                "n" to "three",
                "extra" to "{\"x\": 2}",
                "plain" to "hello",
            ),
        )
        val json = LfmToolCallParser.argumentsJson(call, schema)
        assertEquals(JsonPrimitive("Paris"), json["city"])
        assertEquals(JsonPrimitive(3L), json["days"])
        assertEquals(JsonPrimitive(0.5), json["ratio"])
        assertEquals(JsonPrimitive(true), json["metric"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), json["tags"])
        assertEquals(buildJsonObject { put("k", 1) }, json["opts"])
        assertEquals(JsonPrimitive("02134"), json["zip"], "a string-typed value is never turned into a number")
        assertEquals(JsonPrimitive("three"), json["n"], "a value that is not its declared type stays text")
        assertEquals(buildJsonObject { put("x", 2) }, json["extra"], "with no schema entry an object is read as JSON")
        assertEquals(JsonPrimitive("hello"), json["plain"])
        assertEquals(JsonObject(mapOf("q" to JsonPrimitive("1"))), LfmToolCallParser.argumentsJson(LfmToolCall("f", mapOf("q" to "1")), null))
    }

    @Test
    fun whatThePromptWritesTheParserReadsBack() {
        val args = buildJsonObject {
            put("city", "Paris")
            put("note", "two\nlines")
        }
        val prompt = LfmChatPrompt.format(
            listOf(LfmChatPrompt.Message.User("x"), LfmChatPrompt.Message.Assistant("", toolCalls = listOf(LfmChatPrompt.ToolCall("f", args)))),
            enableThinking = false,
        )
        val written = prompt.substringAfter("<think></think>").substringBefore("<|im_end|>")
        val read = LfmToolCallParser.parse(written, openedThought = false).toolCalls.single()
        assertEquals("f", read.name)
        assertEquals(mapOf("city" to "Paris", "note" to "two\nlines"), read.arguments)
    }
}
