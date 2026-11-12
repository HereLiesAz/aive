package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.mcpbridge.BridgeArgumentException
import com.hereliesaz.geministrator.mcpbridge.ControlTransport
import com.hereliesaz.geministrator.mcpbridge.ControlUnreachable
import com.hereliesaz.geministrator.mcpbridge.Tool
import com.hereliesaz.geministrator.mcpbridge.Tools
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The CLI has no separate workflow logic: these names bind directly to the existing control API tools. */
internal data class Command(val words: List<String>, val toolName: String, val ids: List<String> = emptyList())

internal object Commands {
    private fun command(words: String, tool: String, vararg ids: String) =
        Command(words.split(' '), "aive_$tool", ids.toList())

    val all = listOf(
        command("status", "status"),
        command("hives list", "list_hives"),
        command("hives get", "get_hive", "id"),
        command("hives draft", "draft_hive"),
        command("hives create", "create_hive"),
        command("hives update", "update_hive", "id"),
        command("hives delete", "delete_hive", "id"),
        command("creatures add", "add_creature", "id"),
        command("creatures update", "update_creature", "id", "creatureId"),
        command("creatures remove", "remove_creature", "id", "creatureId"),
        command("roles list", "list_roles"),
        command("roles get", "get_role", "id"),
        command("roles provider", "set_role_provider", "id"),
        command("runs list", "list_runs"),
        command("runs get", "get_run", "id"),
        command("runs events", "list_events", "id"),
        command("runs start", "start_run"),
        command("runs stop", "stop_run", "id"),
        command("approvals", "list_approvals"),
        command("providers", "list_providers"),
        command("settings", "get_settings"),
    )
}

private class UsageError(message: String) : RuntimeException(message)
private class ApiError(message: String) : RuntimeException(message)

/**
 * A pipe-friendly terminal client for the running desktop app.
 *
 * Arguments and authorization are deliberately separate: the CLI never accepts a bearer token as a
 * command argument, and [ControlTransport] owns authentication, loopback enforcement and redaction.
 * Neither this class nor its tests creates a second way to decide a human approval.
 */
class AiveCli(
    private val transport: ControlTransport,
    private val readFile: (String) -> String = { file ->
        if (file == "-") System.`in`.bufferedReader(Charsets.UTF_8).readText()
        else File(file).readText(Charsets.UTF_8)
    },
) {
    private val pretty = Json { prettyPrint = true }
    private val parser = Json

    fun run(args: List<String>, output: Appendable, errors: Appendable): Int {
        return try {
            execute(args, output)
            0
        } catch (e: UsageError) {
            errors.append("aive: ").append(e.message).append('\n')
            2
        } catch (e: BridgeArgumentException) {
            errors.append("aive: ").append(e.message).append('\n')
            2
        } catch (e: ApiError) {
            errors.append("aive: ").append(e.message).append('\n')
            1
        } catch (e: ControlUnreachable) {
            errors.append("aive: ").append(e.message).append('\n')
            3
        } catch (_: Exception) {
            errors.append("aive: request failed; check that The Aive is running and reachable\n")
            3
        }
    }

    private fun execute(args: List<String>, output: Appendable) {
        if (args.isEmpty() || args == listOf("help") || args == listOf("--help") || args == listOf("-h")) {
            output.append(help()).append('\n')
            return
        }
        val command = Commands.all.firstOrNull { args.take(it.words.size) == it.words }
            ?: throw UsageError("Unknown command. Run 'aive --help' for commands.")
        val tool = Tools.byName(command.toolName)
            ?: error("CLI command has no control API tool: ${command.toolName}")
        val tail = args.drop(command.words.size)
        if ("--help" in tail || "-h" in tail) {
            output.append(help(command, tool)).append('\n')
            return
        }

        val positionals = mutableListOf<String>()
        val flags = linkedMapOf<String, String>()
        var file: String? = null
        var yes = false
        var machine = false
        var i = 0
        while (i < tail.size) {
            val token = tail[i++]
            when (token) {
                "--yes" -> yes = true
                "--json" -> machine = true
                "--file" -> {
                    if (file != null) throw UsageError("--file may be specified once")
                    file = tail.getOrNull(i++) ?: throw UsageError("--file needs a path or '-' for stdin")
                }
                else -> {
                    if (token.startsWith("--")) {
                        val name = optionKey(token.substring(2))
                        if (name in flags) throw UsageError("Duplicate option: $token")
                        flags[name] = tail.getOrNull(i++) ?: throw UsageError("$token needs a value")
                    } else {
                        positionals += token
                    }
                }
            }
        }

        if (positionals.size != command.ids.size) {
            throw UsageError("Expected ${command.ids.size} positional ID(s). Usage: ${usage(command)}")
        }
        if (tool.destructive && !yes) {
            throw UsageError("Destructive command requires --yes; nothing was changed")
        }

        val values = linkedMapOf<String, JsonElement>()
        file?.let { path ->
            val text = try {
                readFile(path)
            } catch (_: IOException) {
                throw UsageError("Cannot read --file input")
            }
            if (text.encodeToByteArray().size > 256 * 1024) throw UsageError("JSON input exceeds 256 KiB")
            val obj = try {
                parser.parseToJsonElement(text) as? JsonObject
                    ?: throw UsageError("--file must contain a JSON object")
            } catch (e: UsageError) {
                throw e
            } catch (_: Exception) {
                throw UsageError("--file contains invalid JSON")
            }
            values.putAll(obj)
        }
        flags.forEach { (key, raw) ->
            if (key !in tool.properties) throw UsageError("Unknown option for this command: --${flagName(key)}")
            if (key in values) throw UsageError("Argument '$key' was given in both --file and options")
            values[key] = parseOptionValue(key, raw, tool)
        }
        command.ids.zip(positionals).forEach { (id, value) ->
            if (id in values) throw UsageError("Path argument '$id' must be positional, not in --file")
            if (value.isBlank()) throw UsageError("Path argument '$id' must not be blank")
            values[id] = JsonPrimitive(value)
        }

        val unknown = values.keys - tool.properties.keys
        if (unknown.isNotEmpty()) throw UsageError("Unknown argument(s): ${unknown.sorted().joinToString()}")
        values.forEach { (key, value) -> validateValue(key, value, tool) }
        val missing = tool.required.filter { it !in values || (values[it] is JsonNull && it != "providerId") }
        if (missing.isNotEmpty()) throw UsageError("Missing required argument(s): ${missing.joinToString()}")
        val (method, path, body) = Tools.request(tool, JsonObject(values))
        val reply = transport.send(method, path, body)
        val envelope = runCatching { parser.parseToJsonElement(reply.body) as? JsonObject }.getOrNull()
        if (reply.status !in 200..299) {
            val failure = envelope?.get("error") as? JsonObject
            val code = (failure?.get("code") as? JsonPrimitive)?.contentOrNull ?: "http_${reply.status}"
            val message = (failure?.get("message") as? JsonPrimitive)?.contentOrNull ?: "The Aive rejected the request"
            throw ApiError("$code: $message")
        }
        if (envelope == null || (envelope["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw ApiError("Unexpected response from The Aive")
        }
        val result = envelope["result"] ?: JsonNull
        output.append(if (machine) result.toString() else pretty.encodeToString(JsonElement.serializer(), result)).append('\n')
    }

    private fun optionKey(option: String): String {
        if (!option.matches(Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"))) throw UsageError("Invalid option --$option")
        val parts = option.split('-')
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun flagName(key: String): String = key.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }

    private fun parseOptionValue(key: String, raw: String, tool: Tool): JsonElement {
        val definition = tool.properties[key] as? JsonObject ?: throw UsageError("Unknown option --${flagName(key)}")
        val type = definition["type"]
        return when {
            type == JsonPrimitive("integer") ->
                raw.toLongOrNull()?.let(::JsonPrimitive) ?: throw UsageError("--${flagName(key)} must be an integer")
            type == JsonPrimitive("boolean") ->
                raw.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: throw UsageError("--${flagName(key)} must be true or false")
            type == JsonPrimitive("array") || type == JsonPrimitive("object") -> {
                val element = runCatching { parser.parseToJsonElement(raw) }.getOrNull()
                if ((type == JsonPrimitive("array") && element !is JsonArray) ||
                    (type == JsonPrimitive("object") && element !is JsonObject)
                ) throw UsageError("--${flagName(key)} needs a JSON ${if (type == JsonPrimitive("array")) "array" else "object"}")
                element!!
            }
            key == "providerId" && raw == "null" -> JsonNull
            else -> JsonPrimitive(raw)
        }
    }

    private fun validateValue(key: String, value: JsonElement, tool: Tool) {
        val schema = tool.properties[key] as? JsonObject ?: throw UsageError("Unknown argument: $key")
        val type = schema["type"]
        val allowed = when (type) {
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        val actual = when (value) {
            is JsonNull -> "null"
            is JsonArray -> "array"
            is JsonObject -> "object"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.booleanOrNull != null -> "boolean"
                value.content.toLongOrNull() != null -> "integer"
                else -> "number"
            }
        }
        if (actual !in allowed) throw UsageError("'$key' must be ${allowed.joinToString(" or ")}")
        if (key in commandIds(tool) && value is JsonPrimitive && value.content.isBlank()) {
            throw UsageError("'$key' must not be blank")
        }
    }

    private fun commandIds(tool: Tool): List<String> =
        Commands.all.firstOrNull { it.toolName == tool.name }?.ids.orEmpty()

    private fun usage(command: Command): String = buildString {
        append("aive ").append(command.words.joinToString(" "))
        command.ids.forEach { append(" <").append(it).append('>') }
        append(" [--option value] [--file path|-] [--json]")
        if (Tools.byName(command.toolName)?.destructive == true) append(" --yes")
    }

    private fun help(command: Command, tool: Tool): String = buildString {
        append(usage(command)).append("\n\n").append(tool.description).append("\n\nOptions:\n")
        tool.properties.forEach { (key, _) ->
            if (key !in command.ids) {
                append("  --").append(flagName(key))
                if (key in tool.required) append(" (required)")
                append('\n')
            }
        }
        append("  --file <path|->   Read JSON arguments from a file or stdin\n")
        append("  --json            Emit compact machine-readable JSON\n")
        if (tool.destructive) append("  --yes             Required for destructive actions\n")
    }

    private fun help(): String = buildString {
        append("The Aive CLI\n\nUsage: aive <command> [options]\n\nCommands:\n")
        Commands.all.forEach { append("  ").append(it.words.joinToString(" ")).append('\n') }
        append("\nRun 'aive <command> --help' for arguments.\n")
        append("Commands connect to a running desktop The Aive with AIVE_CONTROL_API=on.\n")
        append("All approvals remain read-only in the CLI.\n")
    }
}
