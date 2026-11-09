package com.hereliesaz.geministrator.providers.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What one line of a CLI's output means. Text is untrusted: bounded by the reader and scrubbed before it is shown. */
sealed interface CliEvent {
    /** Something the CLI said or did, for the activity feed. */
    data class Progress(val text: String) : CliEvent

    /** A thought or a message, shown live and never kept. */
    data class Thinking(val text: String) : CliEvent

    /** The CLI's final answer; [isError] when it says it failed. */
    data class Final(val text: String, val isError: Boolean = false) : CliEvent

    data class Usage(val inputTokens: Long?, val outputTokens: Long?, val costUsd: Double?) : CliEvent
}

/**
 * Reads a CLI's output one line at a time. Tolerant by design: a line it does not recognise is plain
 * progress, a line that is not JSON in a JSON format is plain progress, and nothing here throws.
 */
class CliOutputReader(private val format: CliOutputFormat) {
    private val plain = StringBuilder()
    private var lastAgentMessage: String? = null
    private var final: CliEvent.Final? = null

    /** The events one [line] holds, in order. */
    fun read(line: String): List<CliEvent> {
        val text = line.trim()
        if (text.isEmpty()) return emptyList()
        return when (format) {
            CliOutputFormat.PlainText -> {
                if (plain.length < MAX_PLAIN) plain.append(text).append('\n')
                listOf(CliEvent.Progress(text))
            }
            CliOutputFormat.ClaudeStreamJson -> json(text)?.let(::claude) ?: listOf(CliEvent.Progress(text))
            CliOutputFormat.CodexJsonl -> json(text)?.let(::codex) ?: listOf(CliEvent.Progress(text))
        }
    }

    /** What the CLI answered, once it has finished: its own final line when it gave one, else what it said last. */
    fun finish(exitCode: Int): CliEvent.Final = final
        ?: when (format) {
            CliOutputFormat.PlainText -> CliEvent.Final(plain.toString().trim().takeLast(MAX_PLAIN), isError = exitCode != 0)
            else -> CliEvent.Final(lastAgentMessage.orEmpty(), isError = exitCode != 0)
        }

    private fun claude(o: JsonObject): List<CliEvent> {
        val out = mutableListOf<CliEvent>()
        when (o.str("type")) {
            "system" -> if (o.str("subtype") == "init") out += CliEvent.Progress("Claude Code started")
            "assistant" -> (o["message"] as? JsonObject)?.get("content").asArray().forEach { block ->
                val b = block as? JsonObject ?: return@forEach
                when (b.str("type")) {
                    "text" -> b.str("text")?.takeIf { it.isNotBlank() }?.let { lastAgentMessage = it; out += CliEvent.Thinking(it) }
                    "tool_use" -> out += CliEvent.Progress("Using ${b.str("name") ?: "a tool"}" + (toolTarget(b["input"] as? JsonObject)?.let { ": $it" } ?: ""))
                }
            }
            "result" -> {
                val isError = o["is_error"].bool() == true || o.str("subtype")?.startsWith("error") == true
                val text = o.str("result") ?: lastAgentMessage.orEmpty()
                final = CliEvent.Final(text, isError)
                val usage = o["usage"] as? JsonObject
                out += CliEvent.Usage(usage?.get("input_tokens").long(), usage?.get("output_tokens").long(), o["total_cost_usd"].double())
            }
        }
        return out
    }

    private fun codex(o: JsonObject): List<CliEvent> {
        val out = mutableListOf<CliEvent>()
        val item = o["item"] as? JsonObject
        val msg = o["msg"] as? JsonObject
        val type = o.str("type") ?: msg?.str("type")
        when {
            type == "item.completed" && item?.str("type") == "agent_message" ->
                item.str("text")?.let { lastAgentMessage = it; out += CliEvent.Thinking(it) }
            type == "item.completed" && item?.str("type") == "reasoning" -> item.str("text")?.let { out += CliEvent.Thinking(it) }
            type == "item.started" || type == "item.completed" ->
                out += CliEvent.Progress(item?.str("type")?.replace('_', ' ')?.let { "Codex: $it" } ?: "Codex working")
            type == "agent_message" -> msg?.str("message")?.let { lastAgentMessage = it; out += CliEvent.Thinking(it) }
            type == "turn.completed" -> {
                val usage = o["usage"] as? JsonObject
                out += CliEvent.Usage(usage?.get("input_tokens").long(), usage?.get("output_tokens").long(), null)
            }
            type == "error" || type == "turn.failed" ->
                (o.str("message") ?: msg?.str("message") ?: (o["error"] as? JsonObject)?.str("message"))?.let { final = CliEvent.Final(it, isError = true); out += CliEvent.Progress("Error: $it") }
            else -> out += CliEvent.Progress(type?.replace('.', ' ') ?: "Codex working")
        }
        return out
    }

    private fun toolTarget(input: JsonObject?): String? =
        (input?.str("file_path") ?: input?.str("path") ?: input?.str("pattern"))?.take(160)

    private companion object {
        const val MAX_PLAIN = 60_000
        val parser = Json { isLenient = true; ignoreUnknownKeys = true }

        fun json(line: String): JsonObject? =
            if (line.startsWith("{")) runCatching { parser.parseToJsonElement(line).jsonObject }.getOrNull() else null
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull
