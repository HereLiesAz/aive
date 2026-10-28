package com.hereliesaz.geministrator

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Where The Aive keeps the transcripts of its runs for Entire, one JSONL file per session
 * (docs/Entire.md). Each line is one record: `session`, `prompt`, `response`, `files` or `usage`.
 */
internal class AiveEntireSessions(val directory: File = defaultDirectory()) {
    fun file(sessionId: String): File = File(directory, "${safeSessionId(sessionId)}.jsonl")

    /** Starts [sessionId]'s transcript, replacing any earlier one. */
    fun begin(sessionId: String, repoPath: String, model: String?) {
        directory.mkdirs()
        file(sessionId).writeText("")
        append(sessionId, record("session") {
            put("session_id", sessionId)
            put("repo_path", repoPath)
            model?.let { put("model", it) }
        })
    }

    fun prompt(sessionId: String, text: String) = append(sessionId, record("prompt") { put("text", text) })

    fun response(sessionId: String, text: String) = append(sessionId, record("response") { put("text", text) })

    fun files(sessionId: String, modified: List<String>, new: List<String>, deleted: List<String>) =
        append(sessionId, record("files") {
            putJsonArray("modified") { modified.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("new") { new.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("deleted") { deleted.forEach { add(JsonPrimitive(it)) } }
        })

    fun usage(sessionId: String, inputTokens: Long?, outputTokens: Long?) {
        if (inputTokens == null && outputTokens == null) return
        append(sessionId, record("usage") {
            put("input_tokens", inputTokens ?: 0)
            put("output_tokens", outputTokens ?: 0)
        })
    }

    private fun append(sessionId: String, line: JsonObject) {
        file(sessionId).appendText(line.toString() + "\n")
    }

    private fun record(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", type)
        put("timestamp", Instant.now().toString())
        body()
    }

    companion object {
        /** Where The Aive tells the plugin to look, else `~/.aive/entire/sessions`. */
        fun defaultDirectory(): File = System.getenv(DIRECTORY_VARIABLE)?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".aive/entire/sessions")

        const val DIRECTORY_VARIABLE = "AIVE_ENTIRE_SESSIONS"

        /** Session IDs name files: only letters, digits, `-`, `_` and `.` survive, never a leading dot. */
        fun safeSessionId(raw: String): String =
            raw.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
                .joinToString("")
                .trimStart('.')
                .take(160)
                .ifEmpty { "session" }
    }
}

/**
 * The Aive as an Entire external agent (`entire-agent-aive`, Entire's external agent protocol v1):
 * each subcommand reads JSON or bytes on stdin and answers on stdout. Entire runs it to read the
 * transcripts [AiveEntireSessions] keeps; The Aive reports its runs with `entire session attach`, so it
 * declares no hooks. Returns the exit code.
 */
internal fun runEntireAgentPlugin(
    args: List<String>,
    stdin: InputStream = System.`in`,
    stdout: OutputStream = System.out,
    stderr: OutputStream = System.err,
    sessions: AiveEntireSessions = AiveEntireSessions(),
): Int {
    val command = args.firstOrNull() ?: return fail(stderr, "missing subcommand")
    val flags = args.drop(1).flagMap()
    fun json(element: JsonElement) = stdout.write((element.toString() + "\n").toByteArray()).let { 0 }
    fun input(): JsonObject = EntireJson.parseToJsonElement(stdin.readBytes().decodeToString().ifBlank { "{}" }).jsonObject
    fun transcript(path: String?): File? = path?.let(::File)?.takeIf { it.isFile && it.insideOf(sessions.directory) }

    return try {
        when (command) {
            "info" -> json(buildJsonObject {
                put("protocol_version", 1)
                put("name", "aive")
                put("type", "The Aive")
                put("description", "The Aive - a hive of node creatures working your repository")
                putJsonArray("protected_dirs") {}
                putJsonArray("hook_names") {}
                putJsonObject("capabilities") {
                    put("hooks", false)
                    put("transcript_analyzer", true)
                    put("transcript_preparer", false)
                    put("token_calculator", true)
                    put("text_generator", false)
                    put("hook_response_writer", false)
                    put("subagent_aware_extractor", false)
                }
            })
            "detect" -> json(buildJsonObject { put("present", true) })
            "get-session-id" -> json(buildJsonObject { put("session_id", input().string("session_id").orEmpty()) })
            "get-session-dir" -> json(buildJsonObject { put("session_dir", sessions.directory.absolutePath) })
            "resolve-session-file" -> {
                val id = flags["session-id"] ?: return fail(stderr, "--session-id is required")
                val dir = flags["session-dir"]?.let(::File) ?: sessions.directory
                json(buildJsonObject { put("session_file", File(dir, "${AiveEntireSessions.safeSessionId(id)}.jsonl").absolutePath) })
            }
            "read-session" -> {
                val hook = input()
                val id = hook.string("session_id").orEmpty()
                val file = transcript(hook.string("session_ref")) ?: sessions.file(id).takeIf { it.isFile }
                    ?: return fail(stderr, "no Aive session $id")
                val records = file.records(0)
                val head = records.firstOrNull { it.string("type") == "session" }
                val files = records.filter { it.string("type") == "files" }
                json(buildJsonObject {
                    put("session_id", id.ifEmpty { head?.string("session_id").orEmpty() })
                    put("agent_name", "aive")
                    put("repo_path", head?.string("repo_path").orEmpty())
                    put("session_ref", file.absolutePath)
                    put("start_time", head?.string("timestamp") ?: Instant.now().toString())
                    put("native_data", JsonNull)
                    put("modified_files", files.flatMap { it.strings("modified") }.distinct().toJsonArray())
                    put("new_files", files.flatMap { it.strings("new") }.distinct().toJsonArray())
                    put("deleted_files", files.flatMap { it.strings("deleted") }.distinct().toJsonArray())
                })
            }
            "write-session" -> {
                // Restoring a session (`entire session resume`): put its transcript back where it was.
                val session = input()
                val target = session.string("session_ref")?.let(::File)
                val native = session.string("native_data")
                if (target != null && native != null && target.insideOf(sessions.directory)) {
                    target.parentFile?.mkdirs()
                    target.writeBytes(Base64.getDecoder().decode(native))
                }
                0
            }
            "read-transcript" -> {
                val file = transcript(flags["session-ref"]) ?: return fail(stderr, "transcript not found")
                stdout.write(file.readBytes())
                0
            }
            "chunk-transcript" -> {
                val max = flags["max-size"]?.toIntOrNull()?.takeIf { it > 0 } ?: return fail(stderr, "--max-size is required")
                json(buildJsonObject {
                    put("chunks", chunk(stdin.readBytes(), max).map { JsonPrimitive(Base64.getEncoder().encodeToString(it)) }.let(::JsonArray))
                })
            }
            "reassemble-transcript" -> {
                input()["chunks"]?.jsonArray?.forEach { stdout.write(Base64.getDecoder().decode(it.jsonPrimitive.content)) }
                0
            }
            "format-resume-command" -> json(buildJsonObject { put("command", "open The Aive and start a new task") })
            "get-transcript-position" -> json(buildJsonObject { put("position", transcript(flags["path"])?.length() ?: 0L) })
            "extract-modified-files" -> {
                val file = transcript(flags["path"])
                val offset = flags["offset"]?.toLongOrNull() ?: 0L
                val records = file?.records(offset).orEmpty().filter { it.string("type") == "files" }
                json(buildJsonObject {
                    put("files", records.flatMap { it.strings("modified") + it.strings("new") + it.strings("deleted") }.distinct().toJsonArray())
                    put("current_position", file?.length() ?: offset)
                })
            }
            "extract-prompts" -> {
                val records = transcript(flags["session-ref"])?.records(flags["offset"]?.toLongOrNull() ?: 0L).orEmpty()
                json(buildJsonObject {
                    put("prompts", records.filter { it.string("type") == "prompt" }.mapNotNull { it.string("text") }.toJsonArray())
                })
            }
            "calculate-tokens" -> {
                val offset = flags["offset"]?.toLongOrNull() ?: 0L
                val bytes = stdin.readBytes()
                val usage = parseRecords(bytes.copyOfRange(offset.coerceIn(0, bytes.size.toLong()).toInt(), bytes.size))
                    .filter { it.string("type") == "usage" }
                json(buildJsonObject {
                    put("input_tokens", usage.sumOf { it.long("input_tokens") })
                    put("output_tokens", usage.sumOf { it.long("output_tokens") })
                    put("api_call_count", usage.size)
                })
            }
            else -> fail(stderr, "unsupported subcommand $command")
        }
    } catch (failure: Exception) {
        fail(stderr, failure.message ?: failure::class.simpleName.orEmpty())
    } finally {
        stdout.flush()
    }
}

private val EntireJson = Json { ignoreUnknownKeys = true }

private fun fail(stderr: OutputStream, message: String): Int {
    stderr.write("entire-agent-aive: $message\n".toByteArray())
    stderr.flush()
    return 1
}

/** `--name value` pairs; a flag with no value maps to "". */
private fun List<String>.flagMap(): Map<String, String> {
    val flags = mutableMapOf<String, String>()
    var i = 0
    while (i < size) {
        val arg = this[i]
        if (arg.startsWith("--")) {
            val name = arg.removePrefix("--").substringBefore('=')
            val inline = arg.substringAfter('=', missingDelimiterValue = "\u0000")
            when {
                inline != "\u0000" -> flags[name] = inline
                i + 1 < size && !this[i + 1].startsWith("--") -> flags[name] = this[++i]
                else -> flags[name] = ""
            }
        }
        i++
    }
    return flags
}

private fun File.insideOf(directory: File): Boolean =
    canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())

private fun File.records(offset: Long): List<JsonObject> {
    val bytes = readBytes()
    return parseRecords(bytes.copyOfRange(offset.coerceIn(0, bytes.size.toLong()).toInt(), bytes.size))
}

private fun parseRecords(bytes: ByteArray): List<JsonObject> = bytes.decodeToString().lineSequence()
    .filter(String::isNotBlank)
    .mapNotNull { runCatching { EntireJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    .toList()

/** Splits a transcript into pieces of at most [max] bytes, on line boundaries where a line fits. */
private fun chunk(bytes: ByteArray, max: Int): List<ByteArray> {
    if (bytes.isEmpty()) return emptyList()
    val chunks = mutableListOf<ByteArray>()
    var start = 0
    while (start < bytes.size) {
        var end = minOf(start + max, bytes.size)
        if (end < bytes.size) {
            val newline = (end - 1 downTo start).firstOrNull { bytes[it] == '\n'.code.toByte() }
            if (newline != null) end = newline + 1
        }
        chunks += bytes.copyOfRange(start, end)
        start = end
    }
    return chunks
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
private fun List<String>.toJsonArray() = JsonArray(map(::JsonPrimitive))
