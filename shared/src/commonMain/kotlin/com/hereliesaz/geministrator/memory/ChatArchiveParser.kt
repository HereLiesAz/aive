package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.control.ControlRedaction
import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** An explicitly selected textual member of a provider export; never a path to open automatically. */
data class ChatArchiveDocument(val name: String, val text: String)

/** Counts actual newly queued and already retained source utterances. */
data class ChatArchiveImportResult(val added: Int, val alreadyRetained: Int)

internal data class ArchivedChatMessage(
    val source: String, val id: String, val label: String, val text: String, val occurredAt: Long?,
    val kind: MemorySourceKind,
)

/**
 * Decode the user's selected conversation history into the same source kinds as native memory.
 * Speaker and timestamps describe the recorded experience. Non-text attachments are not decoded.
 */
internal object ChatArchiveParser {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_MESSAGES = 20_000

    fun parse(documents: List<ChatArchiveDocument>): List<ArchivedChatMessage> {
        require(documents.isNotEmpty() && documents.size <= 256) { "Choose 1–256 history files." }
        require(documents.sumOf { it.text.encodeToByteArray().size.toLong() } <= MAX_BYTES) { "History exceeds 32 MiB; split the export." }
        val result = mutableListOf<ArchivedChatMessage>()
        for (document in documents) {
            val content = document.text.trim().removePrefix("\uFEFF")
            if (content.isBlank()) continue
            val source = "archive:" + Sha256.hex(content.encodeToByteArray())
            val filename = ControlRedaction.scrub(document.name.substringAfterLast('/').substringAfterLast('\\')).take(160)
            var index = 0
            fun add(speaker: String, text: String, time: Long?, conversation: String) {
                val clean = ControlRedaction.scrub(text).trim()
                if (clean.isBlank()) return
                require(result.size < MAX_MESSAGES) { "History exceeds 20,000 messages; split the export." }
                val label = ControlRedaction.scrub("Chat history; file=$filename; speaker=$speaker; conversation=$conversation").take(512)
                val kind = when (speaker.lowercase()) {
                    "user", "human" -> MemorySourceKind.UserPrompt
                    "assistant", "model", "bot", "ai" -> MemorySourceKind.AgentNote
                    else -> MemorySourceKind.Message
                }
                result += ArchivedChatMessage(source, "$source:message:${index++}", label, clean, time, kind)
            }
            val ext = filename.substringAfterLast('.', "").lowercase()
            when {
                ext == "jsonl" || ext == "ndjson" -> content.lineSequence().filter { it.isNotBlank() }.forEachIndexed { n, line ->
                    val element = Json.parseToJsonElement(line)
                    require(read(element, "line ${n + 1}", ::add)) { "Unsupported JSON Lines record ${n + 1}." }
                }
                ext == "json" || (ext.isEmpty() && (content.startsWith("{") || content.startsWith("["))) -> {
                    require(read(Json.parseToJsonElement(content), "export", ::add)) { "Unsupported chat JSON structure: $filename" }
                }
                ext in setOf("txt", "md", "markdown") || ext.isEmpty() -> {
                    // Preserve transcript order without inventing speakers or timestamps.
                    // The ordinary intake chunks the full text.
                    add("unspecified", content, null, "transcript")
                }
                else -> error("Unsupported history format: $filename")
            }
        }
        return result.distinctBy { it.id }
    }

    private fun read(element: JsonElement, conversation: String,
        emit: (String, String, Long?, String) -> Unit): Boolean {
        if (element is JsonArray) {
            if (element.isEmpty()) return true
            var recognized = false
            element.forEachIndexed { i, item ->
                if (read(item, "$conversation/${i + 1}", emit)) recognized = true
            }
            return recognized
        }
        val obj = element as? JsonObject ?: return false
        val title = obj.string("title") ?: obj.string("name") ?: conversation
        val mapping = obj["mapping"] as? JsonObject
        if (mapping != null) {
            // ChatGPT exports preserve branches as a graph. Retain each visible message once,
            // rather than duplicate shared ancestors for every branch.
            mapping.values.mapNotNull { (it as? JsonObject)?.get("message") as? JsonObject }
                .sortedBy { timestamp(it["create_time"]) ?: Long.MAX_VALUE }
                .forEach { message(it, title, emit) }
            return true
        }
        val messages = (obj["chat_messages"] ?: obj["messages"] ?: obj["turns"]) as? JsonArray
        if (messages != null) {
            messages.forEach { (it as? JsonObject)?.let { m -> message(m, title, emit) } }
            return true
        }
        val conversations = obj["conversations"] ?: obj["chats"]
        if (conversations is JsonArray) return read(conversations, title, emit)
        return message(obj, title, emit)
    }

    private fun message(obj: JsonObject, title: String, emit: (String, String, Long?, String) -> Unit): Boolean {
        val author = obj["author"] as? JsonObject
        val role = author?.string("role") ?: obj.string("role") ?: obj.string("sender") ?: return false
        val body = obj["content"] ?: obj["text"] ?: obj["message"] ?: return true
        val text = textOf(body)
        if (text.isNotBlank()) emit(role, text, timestamp(obj["create_time"] ?: obj["created_at"] ?: obj["timestamp"]), title)
        return true
    }

    private fun textOf(element: JsonElement): String = when (element) {
        is JsonPrimitive -> if (element.isString) element.content else ""
        is JsonArray -> element.map(::textOf).filter { it.isNotBlank() }.joinToString("\n")
        is JsonObject -> {
            val type = element.string("type") ?: element.string("content_type")
            if (type in setOf("image", "image_url", "image_asset_pointer", "audio", "file", "file_attachment")) ""
            else element["parts"]?.let(::textOf) ?: element["text"]?.let(::textOf)
                ?: element["thinking"]?.let(::textOf) ?: element["content"]?.let(::textOf) ?: ""
        }
        else -> ""
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    @OptIn(ExperimentalTime::class)
    private fun timestamp(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return runCatching { Instant.parse(primitive.content).toEpochMilliseconds() }.getOrNull()
        val number = primitive.doubleOrNull?.takeIf { it.isFinite() && it >= 0 && it <= 253402300799000.0 } ?: return null
        return (if (number < 100_000_000_000.0) number * 1000 else number).toLong()
    }
}
