package com.hereliesaz.geministrator.providers.lfm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One call the model asked for: its [name] and its arguments as the model wrote them (text; see [LfmToolCallParser.argumentsJson]). */
data class LfmToolCall(val name: String, val arguments: Map<String, String>)

/**
 * What an LFM3 model wrote, taken apart: what it [thought], what it [text] to the keeper, and the [toolCalls] it asked for.
 * [problems] lists what was wrong with the output (a call cut off before it closed, a parameter with no end tag): the
 * caller decides whether to use the rest, retry, or fail the step. [thoughtUnfinished] is true when the model was still
 * thinking when it stopped (it ran out of tokens), so [text] is empty and the answer was never written.
 */
data class LfmReply(
    val thought: String?,
    val text: String,
    val toolCalls: List<LfmToolCall>,
    val problems: List<String> = emptyList(),
    val thoughtUnfinished: Boolean = false,
) {
    val isToolRequest: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * Reads the text an LFM3 model writes: its `<think>` block and its XML tool calls,
 *
 * ~~~
 * <tool_call>
 * <function=get_weather>
 * <parameter=city>
 * Paris
 * </parameter>
 * </function>
 * </tool_call>
 * ~~~
 *
 * (Liquid describes the format as a superset of Qwen3-Coder's.) The model, not a server, writes these: the LiteRT-LM files
 * declare a generic model type, so the runtime hands back plain text and the app reads it.
 *
 * It is deliberately forgiving, because small models are untidy. A parameter value is everything between its tags with
 * one leading and one trailing newline taken off (the model writes the value on its own line; anything else in it,
 * including code and blank lines, is kept as it is). A call or a parameter that was cut off at the token limit is still
 * returned, and reported in [LfmReply.problems]. A `</think>` with no `<think>` before it, which the 1.7B model's int4
 * file writes now and then even when asked not to think, ends the thought instead of leaking into the answer.
 */
object LfmToolCallParser {
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"
    private const val CALL_OPEN = "<tool_call>"
    private const val CALL_CLOSE = "</tool_call>"
    private const val FUNCTION_OPEN = "<function="
    private const val FUNCTION_CLOSE = "</function>"
    private const val PARAMETER_OPEN = "<parameter="
    private const val PARAMETER_CLOSE = "</parameter>"

    /**
     * Takes [raw], the text the model generated after the prompt, apart. [openedThought] says whether the prompt ended with
     * an open `<think>` ([LfmChatPrompt.format] with `enableThinking = true`), in which case the output starts inside the
     * thought and its first `</think>` ends it.
     */
    fun parse(raw: String, openedThought: Boolean): LfmReply {
        val text = cutAtStop(raw)
        val problems = mutableListOf<String>()
        var thought: String? = null
        var unfinished = false
        var rest = text

        if (openedThought) {
            val close = text.indexOf(THINK_CLOSE)
            if (close >= 0) {
                thought = text.substring(0, close)
                rest = text.substring(close + THINK_CLOSE.length)
            } else {
                val call = text.indexOf(CALL_OPEN)
                if (call >= 0) {
                    // It went straight to a call without closing the thought: use the call.
                    thought = text.substring(0, call)
                    rest = text.substring(call)
                    problems += "The thought was never closed before the tool call."
                } else {
                    thought = text
                    rest = ""
                    unfinished = true
                    problems += "The model was still thinking when it stopped."
                }
            }
        } else {
            val trimmed = text.trimStart()
            val close = text.indexOf(THINK_CLOSE)
            if (trimmed.startsWith(THINK_OPEN)) {
                val open = text.indexOf(THINK_OPEN)
                val end = text.indexOf(THINK_CLOSE, open)
                if (end >= 0) {
                    thought = text.substring(open + THINK_OPEN.length, end)
                    rest = text.substring(end + THINK_CLOSE.length)
                } else {
                    thought = text.substring(open + THINK_OPEN.length)
                    rest = ""
                    unfinished = true
                    problems += "The model was still thinking when it stopped."
                }
            } else if (close >= 0) {
                // A stray </think>: what is before it was the thought, what is after it the answer.
                thought = text.substring(0, close)
                rest = text.substring(close + THINK_CLOSE.length)
            }
        }

        val (answer, calls) = extractCalls(rest, problems)
        return LfmReply(
            thought = thought?.trim()?.takeIf { it.isNotEmpty() },
            text = answer.trim(),
            toolCalls = calls,
            problems = problems,
            thoughtUnfinished = unfinished,
        )
    }

    /** The text up to the model's end-of-turn marker: anything after it is not the answer. */
    private fun cutAtStop(raw: String): String {
        var end = raw.length
        for (marker in LfmChatPrompt.STOP_MARKERS) {
            val at = raw.indexOf(marker)
            if (at in 0 until end) end = at
        }
        return raw.substring(0, end)
    }

    private fun extractCalls(rest: String, problems: MutableList<String>): Pair<String, List<LfmToolCall>> {
        val answer = StringBuilder()
        val calls = mutableListOf<LfmToolCall>()
        var cursor = 0
        while (cursor < rest.length) {
            val open = rest.indexOf(CALL_OPEN, cursor)
            if (open < 0) {
                answer.append(rest, cursor, rest.length)
                break
            }
            answer.append(rest, cursor, open)
            val bodyStart = open + CALL_OPEN.length
            val close = rest.indexOf(CALL_CLOSE, bodyStart)
            val body = if (close >= 0) rest.substring(bodyStart, close) else rest.substring(bodyStart)
            if (close < 0) problems += "A tool call was cut off before </tool_call>."
            parseCall(body, problems)?.let { calls += it }
            cursor = if (close >= 0) close + CALL_CLOSE.length else rest.length
        }
        return answer.toString() to calls
    }

    private fun parseCall(body: String, problems: MutableList<String>): LfmToolCall? {
        val functionAt = body.indexOf(FUNCTION_OPEN)
        if (functionAt < 0) {
            problems += "A tool call named no function."
            return null
        }
        val nameStart = functionAt + FUNCTION_OPEN.length
        val nameEnd = body.indexOf('>', nameStart)
        if (nameEnd < 0) {
            problems += "A tool call's function tag was never closed."
            return null
        }
        val name = body.substring(nameStart, nameEnd).trim()
        if (name.isEmpty()) {
            problems += "A tool call named no function."
            return null
        }
        val functionEnd = body.indexOf(FUNCTION_CLOSE, nameEnd)
        val inner = if (functionEnd >= 0) body.substring(nameEnd + 1, functionEnd) else body.substring(nameEnd + 1)
        if (functionEnd < 0) problems += "The call to $name was cut off before </function>."

        val arguments = LinkedHashMap<String, String>()
        var cursor = 0
        while (true) {
            val open = inner.indexOf(PARAMETER_OPEN, cursor)
            if (open < 0) break
            val keyStart = open + PARAMETER_OPEN.length
            val keyEnd = inner.indexOf('>', keyStart)
            if (keyEnd < 0) {
                problems += "A parameter tag of $name was never closed."
                break
            }
            val key = inner.substring(keyStart, keyEnd).trim()
            val valueStart = keyEnd + 1
            val close = inner.indexOf(PARAMETER_CLOSE, valueStart)
            val next = inner.indexOf(PARAMETER_OPEN, valueStart)
            val valueEnd = when {
                close >= 0 && (next < 0 || close < next) -> close
                next >= 0 -> next
                else -> inner.length
            }
            if (valueEnd != close) problems += "The parameter $key of $name has no </parameter>."
            if (key.isNotEmpty()) arguments[key] = trimOneNewline(inner.substring(valueStart, valueEnd))
            cursor = if (valueEnd == close) close + PARAMETER_CLOSE.length else valueEnd
        }
        return LfmToolCall(name, arguments)
    }

    /** A value sits on its own line: take off the newline after the opening tag and the one before the closing tag, no more. */
    private fun trimOneNewline(value: String): String {
        var v = value
        if (v.startsWith("\r\n")) v = v.substring(2) else if (v.startsWith("\n")) v = v.substring(1)
        if (v.endsWith("\r\n")) v = v.substring(0, v.length - 2) else if (v.endsWith("\n")) v = v.substring(0, v.length - 1)
        return v
    }

    /**
     * [call]'s arguments as JSON, typed by the tool's [schema] (its `parameters` JSON Schema). The model writes every value as
     * text, so a number, a boolean or an array is read according to what the schema says it is; a value that does not read as
     * its declared type stays a string (the tool then says what is wrong with it). Without a schema, or for a parameter the
     * schema does not list, objects and arrays are read as JSON and everything else stays a string.
     */
    fun argumentsJson(call: LfmToolCall, schema: JsonObject?): JsonObject {
        val properties = schema?.get("properties") as? JsonObject
        return JsonObject(
            call.arguments.mapValues { (key, value) ->
                coerce(value, ((properties?.get(key) as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull)
            },
        )
    }

    private val lenient = Json { isLenient = false }

    private fun coerce(value: String, type: String?): JsonElement {
        val text = value.trim()
        return when (type) {
            "string" -> JsonPrimitive(value)
            "integer" -> text.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
            "number" -> text.toLongOrNull()?.let(::JsonPrimitive) ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonPrimitive(value)
            "boolean" -> when (text.lowercase()) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> JsonPrimitive(value)
            }
            "null" -> if (text == "null") JsonNull else JsonPrimitive(value)
            "array", "object" -> parseJsonOrNull(text)?.takeIf { (type == "array" && it is JsonArray) || (type == "object" && it is JsonObject) } ?: JsonPrimitive(value)
            else -> if (text.startsWith("{") || text.startsWith("[")) parseJsonOrNull(text) ?: JsonPrimitive(value) else JsonPrimitive(value)
        }
    }

    private fun parseJsonOrNull(text: String): JsonElement? = runCatching { lenient.parseToJsonElement(text) }.getOrNull()
}
