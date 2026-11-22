package com.hereliesaz.geministrator.providers.lfm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Liquid AI's LFM3 chat format, written out by hand so a raw-prompt runtime (LiteRT-LM driven the way
 * `LiteRtLmTextGenerator` drives it: a pre-templated string in, text out) can speak to an LFM3 model.
 *
 * The format is ChatML-shaped: `<|im_start|>ROLE\n...<|im_end|>\n`. Tools are declared as one JSON object per
 * line inside a `<tools>` block appended to the system prompt; the model answers a tool request with
 * `<tool_call><function=NAME><parameter=KEY>VALUE</parameter></function></tool_call>` XML (see [LfmToolCallParser]);
 * a tool's result goes back as a `tool` message holding `<tool_response>...</tool_response>`; and the assistant turn is
 * opened with `<think>` (the model thinks first) or `<think></think>` (it does not).
 *
 * This renders what the model's own `chat_template.jinja` (LiquidAI/LFM3-1.7B) renders. `LfmChatPromptTest` pins it
 * against output of that template run through Jinja, case by case. Three deliberate differences from the template:
 * the start token (`<|startoftext|>`) is left off by default, because LiteRT-LM adds it itself ([includeStartToken]);
 * the template's `additional_tools` / `tool_reference` search features and its `CONTINUE_FINAL_MESSAGE_TAG` are not
 * written; and image or video content parts are written as a plain string only (a vision turn goes through the
 * runtime's own image input, not through this text).
 */
object LfmChatPrompt {
    const val START_TOKEN: String = "<|startoftext|>"
    const val TURN_START: String = "<|im_start|>"
    const val TURN_END: String = "<|im_end|>"

    /** The model's own end-of-turn marker; text generation stops at it. */
    val STOP_MARKERS: List<String> = listOf(TURN_END, "<|endoftext|>")

    /** One turn of a conversation. */
    sealed class Message {
        data class System(val content: String) : Message()
        data class User(val content: String) : Message()

        /**
         * An assistant turn. [thinking] is what the model thought before it answered (null when it has none);
         * [toolCalls] are the calls it made, each with its arguments as JSON.
         */
        data class Assistant(
            val content: String,
            val thinking: String? = null,
            val toolCalls: List<ToolCall> = emptyList(),
        ) : Message()

        /** A tool's result. Results of one assistant turn that share a non-blank [toolCallId] are written as one block. */
        data class Tool(val content: String, val toolCallId: String? = null) : Message()
    }

    data class ToolCall(val name: String, val arguments: JsonObject = JsonObject(emptyMap()))

    /** A tool the model may call: [parameters] is its JSON Schema, as OpenAI-style function tools carry it. */
    data class ToolSpec(val name: String, val description: String? = null, val parameters: JsonObject? = null) {
        internal fun toJsonObject(): JsonObject = JsonObject(
            buildMap {
                put("name", JsonPrimitive(name))
                if (description != null) put("description", JsonPrimitive(description))
                if (parameters != null) put("parameters", parameters)
            },
        )
    }

    /**
     * The prompt for [messages], ending where the model starts to write.
     *
     * @param tools declared to the model in the system prompt, in this order.
     * @param enableThinking true opens the assistant turn with `<think>` so the model reasons first (read its output
     * with `LfmToolCallParser.parse(raw, openedThought = true)`); false closes the block (`<think></think>`) so it answers
     * directly.
     * @param preserveThinking keep the thinking of earlier assistant turns; by default only the turns after the last user
     * message keep theirs, as the model's own template does.
     */
    fun format(
        messages: List<Message>,
        tools: List<ToolSpec> = emptyList(),
        enableThinking: Boolean = true,
        preserveThinking: Boolean = false,
        includeStartToken: Boolean = false,
    ): String = buildString {
        if (includeStartToken) append(START_TOKEN)
        val system = (messages.firstOrNull() as? Message.System)?.content.orEmpty()
        val turns = if (messages.firstOrNull() is Message.System) messages.drop(1) else messages
        var systemPrompt = system
        if (tools.isNotEmpty()) {
            systemPrompt += (if (systemPrompt.isNotEmpty()) "\n\n" else "") +
                "<tools>\n" + tools.joinToString("\n") { toJson(it.toJsonObject()) } + "\n</tools>"
        }
        if (systemPrompt.isNotEmpty()) append(TURN_START).append("system\n").append(systemPrompt).append(TURN_END).append('\n')

        val lastUser = turns.indexOfLast { it is Message.User }
        var pendingTool = emptyList<String>()
        turns.forEachIndexed { index, message ->
            when (message) {
                is Message.User -> append(TURN_START).append("user\n").append(message.content).append(TURN_END).append('\n')
                is Message.System -> append(TURN_START).append("system\n").append(message.content).append(TURN_END).append('\n')
                is Message.Assistant -> {
                    append(TURN_START).append("assistant\n")
                    val keepThinking = preserveThinking || index > lastUser
                    var content = message.content
                    if (message.thinking != null && keepThinking) {
                        append("<think>").append(message.thinking).append("</think>")
                    } else if (keepThinking && "</think>" !in content) {
                        append("<think></think>")
                    }
                    if (!keepThinking) {
                        if ("</think>" in content) content = content.substringAfterLast("</think>").trim()
                        content = "<think></think>$content"
                    }
                    append(content)
                    message.toolCalls.forEachIndexed { i, call ->
                        if (i > 0) append("\n\n")
                        append(renderToolCall(call))
                    }
                    append(TURN_END).append('\n')
                }
                is Message.Tool -> {
                    val next = turns.getOrNull(index + 1) as? Message.Tool
                    val merge = !message.toolCallId.isNullOrEmpty() && next != null && next.toolCallId == message.toolCallId
                    val items = if (message.content.isEmpty()) emptyList() else listOf(message.content)
                    if (merge) {
                        pendingTool = pendingTool + items
                    } else {
                        append(TURN_START).append("tool\n")
                        // Results gathered from earlier messages of the same call are one list (always wrapped); a lone
                        // string that already carries its own <tool_response> block is written as it is.
                        append(if (pendingTool.isEmpty()) renderToolResponse(message.content) else renderToolResponses(pendingTool + items))
                        pendingTool = emptyList()
                        append(TURN_END).append('\n')
                    }
                }
            }
        }
        append(TURN_START).append("assistant\n").append(if (enableThinking) "<think>" else "<think></think>")
    }

    private fun renderToolCall(call: ToolCall): String = buildString {
        append("<tool_call>\n<function=").append(call.name).append(">\n")
        for ((name, value) in call.arguments) {
            append("<parameter=").append(name).append(">\n").append(renderValue(value)).append("\n</parameter>\n")
        }
        append("</function>\n</tool_call>")
    }

    private fun renderToolResponse(content: String): String =
        if ("<tool_response>" in content) content else "<tool_response>\n$content\n</tool_response>"

    private fun renderToolResponses(items: List<String>): String = "<tool_response>\n" + items.joinToString("\n") + "\n</tool_response>"

    /** A parameter value as the model's template writes it: strings as they are, everything else as JSON. */
    internal fun renderValue(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonPrimitive && value.isString -> value.content
        value is JsonPrimitive -> value.content
        else -> toJson(value)
    }

    /**
     * JSON the way the model's template writes it (Python's `json.dumps(x, ensure_ascii=False)`): `", "` between
     * members, `": "` after a key, key order kept, non-ASCII left as it is.
     */
    internal fun toJson(element: JsonElement): String = buildString { writeJson(element, this) }

    private fun writeJson(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (element.isString) writeString(element.content, out) else out.append(element.content)
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, item ->
                    if (i > 0) out.append(", ")
                    writeJson(item, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((key, value) in element) {
                    if (!first) out.append(", ")
                    first = false
                    writeString(key, out)
                    out.append(": ")
                    writeJson(value, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
