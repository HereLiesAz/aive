package com.hereliesaz.geministrator.mcpbridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The MCP tools the bridge offers. Each maps to exactly one control API route. There is no tool
 * that approves, rejects or releases an approval: approvals are listed (`aive_list_approvals`)
 * and decided in the app, and a test keeps it that way.
 */
class Tool(
    val name: String,
    val description: String,
    val method: String,
    /** Path under `/control/v1`; `{x}` segments are filled from the argument `x`. */
    val path: String,
    val properties: JsonObject = JsonObject(emptyMap()),
    val required: List<String> = emptyList(),
    /** Arguments sent in the query string instead of the body. */
    val query: Set<String> = emptySet(),
    val readOnly: Boolean = true,
    val destructive: Boolean = false,
    val idempotent: Boolean = readOnly,
) {
    fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }
}

private fun str(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
private fun int(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
private fun bool(description: String) = buildJsonObject { put("type", "boolean"); put("description", description) }
private fun strList(description: String) = buildJsonObject {
    put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", description)
}
private fun props(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

private val creatureProps = arrayOf(
    "name" to str("The creature's name (at most 60 characters)."),
    "objective" to str("What this creature does, in a sentence or two."),
    "kind" to buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(listOf(JsonPrimitive("agent"), JsonPrimitive("approval"))))
        put("description", "agent (default): an agent working as a role. approval: a gate where a person must approve in the app. Scripts, deployments and repository changes cannot be made here.")
    },
    "roleId" to str("For an agent creature: the id of a switched-on role (see aive_list_roles)."),
    "dependsOn" to strList("Ids of the creatures whose output this one needs first."),
    "acceptanceCriteria" to strList("What must be true for this creature's work to count."),
    "requiresHumanApproval" to bool("Make a person approve this creature's work in the app. Can be turned on here but never off."),
    "label" to str("For an approval creature: the label shown on the gate."),
)

object Tools {
    val all: List<Tool> = listOf(
        Tool("aive_status", "Summarise the running Aive app: version, whether it is ready, counts of hives, runs, live runs and pending approvals.", "GET", "/status"),
        Tool("aive_list_hives", "List every hive (workflow) with its name, creature count and whether it was made in the app.", "GET", "/hives"),
        Tool(
            "aive_get_hive", "Get one hive with all its creatures: each one's kind, role, dependencies and approval.", "GET", "/hives/{id}",
            props("id" to str("The hive id.")), required = listOf("id"),
        ),
        Tool("aive_list_roles", "List the swarm's roles (names, authorities, preferred provider). Instructions are left out; get one role for them.", "GET", "/roles"),
        Tool("aive_get_role", "Get one role, including its instructions.", "GET", "/roles/{id}", props("id" to str("The role id.")), required = listOf("id")),
        Tool(
            "aive_list_runs", "List runs, newest first, optionally only those of one hive.", "GET", "/runs",
            props("hive" to str("Only runs of this hive id."), "limit" to int("At most this many (1 to 500, default 50).")), query = setOf("hive", "limit"),
        ),
        Tool(
            "aive_get_run", "Get one run with every step (creature): status, attempt, provider, providers it failed over from, progress, blocking reason and artifacts. Use it to report status and failover.",
            "GET", "/runs/{id}", props("id" to str("The run id.")), required = listOf("id"),
        ),
        Tool(
            "aive_list_events", "The run's log: its events in order. Page with `after`.", "GET", "/runs/{id}/events",
            props("id" to str("The run id."), "after" to int("Only events after this time (epoch milliseconds)."), "limit" to int("At most this many (1 to 500, default 200).")),
            required = listOf("id"), query = setOf("after", "limit"),
        ),
        Tool(
            "aive_list_approvals",
            "List approvals waiting on a person. READ-ONLY: this interface cannot approve or reject anything. Approvals are decided in the Aive app.",
            "GET", "/approvals",
        ),
        Tool("aive_list_providers", "List providers with whether each is connected, what it can do, and recent failover notes. Never includes keys.", "GET", "/providers"),
        Tool("aive_get_settings", "Read the app settings that hold no keys or tokens.", "GET", "/settings"),
        Tool(
            "aive_draft_hive", "Ask the app's linked planner to propose creatures for a prompt. Only proposes: nothing is saved or run. Pass the result to aive_create_hive to keep it.",
            "POST", "/hives/draft", props("prompt" to str("What the hive should do.")), required = listOf("prompt"), readOnly = false, idempotent = false,
        ),
        Tool(
            "aive_create_hive", "Create a hive from creatures (agents working as roles, and approval gates). It is saved but not run. Cannot make scripts, deployments or repository changes.",
            "POST", "/hives",
            props(
                "name" to str("The hive's name (at most 60 characters)."),
                "description" to str("What the hive is for."),
                "id" to str("Optional id; made from the name when omitted."),
                "creatures" to buildJsonObject {
                    put("type", "array")
                    put("description", "1 to 24 creatures.")
                    put("items", buildJsonObject { put("type", "object"); put("properties", props(*creatureProps, "id" to str("Optional creature id; dependsOn refers to these."))); put("required", JsonArray(listOf(JsonPrimitive("name")))) })
                },
            ),
            required = listOf("name", "creatures"), readOnly = false,
        ),
        Tool(
            "aive_update_hive", "Rename a hive or change its description. Only hives made in the app can be changed, and not while a run of it is live.",
            "PATCH", "/hives/{id}", props("id" to str("The hive id."), "name" to str("New name."), "description" to str("New description.")),
            required = listOf("id"), readOnly = false, idempotent = true,
        ),
        Tool(
            "aive_delete_hive", "DESTRUCTIVE: delete a hive and its place in the library. Its past runs stay in history. Refused while a run is live, when another hive uses it, or when it came from a package.",
            "DELETE", "/hives/{id}", props("id" to str("The hive id.")), required = listOf("id"), readOnly = false, destructive = true, idempotent = true,
        ),
        Tool(
            "aive_add_creature", "Add a creature to a hive.", "POST", "/hives/{id}/creatures",
            props("id" to str("The hive id."), *creatureProps, "creatureId" to str("Optional id for the new creature.")),
            required = listOf("id", "name"), readOnly = false,
        ),
        Tool(
            "aive_update_creature", "Change a creature's name, objective, role, dependencies or criteria. A human approval can be added but not removed; its kind cannot change.",
            "PATCH", "/hives/{id}/creatures/{creatureId}",
            props("id" to str("The hive id."), "creatureId" to str("The creature id."), *creatureProps.filter { it.first != "kind" }.toTypedArray()),
            required = listOf("id", "creatureId"), readOnly = false, idempotent = true,
        ),
        Tool(
            "aive_remove_creature", "DESTRUCTIVE: remove an agent creature from a hive, with every strand touching it. A creature that waits for a person cannot be removed here.",
            "DELETE", "/hives/{id}/creatures/{creatureId}", props("id" to str("The hive id."), "creatureId" to str("The creature id.")),
            required = listOf("id", "creatureId"), readOnly = false, destructive = true, idempotent = true,
        ),
        Tool(
            "aive_set_role_provider", "Choose which provider a role prefers (same as the provider picker in the app), or null to leave the choice to the app, which then fails over between connected providers.",
            "PUT", "/roles/{id}/provider",
            props("id" to str("The role id."), "providerId" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))); put("description", "A provider id from aive_list_providers, or null.") }),
            required = listOf("id", "providerId"), readOnly = false, idempotent = true,
        ),
        Tool(
            "aive_start_run", "Start a run of a saved hive with an objective. It works through its steps, and waits at every approval for a person in the app. Refused while another run is unfinished.",
            "POST", "/runs",
            props("hiveId" to str("The hive id."), "objective" to str("What this run should achieve."), "projectName" to str("Optional project name.")),
            required = listOf("hiveId", "objective"), readOnly = false, idempotent = false,
        ),
        Tool(
            "aive_stop_run", "DESTRUCTIVE: stop the active run, cancelling every unfinished creature and provider session. It cannot be resumed.",
            "POST", "/runs/{id}/stop", props("id" to str("The run id.")), required = listOf("id"), readOnly = false, destructive = true, idempotent = true,
        ),
    )

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }

    /** Builds the control API path and body for [tool] from the client's [arguments]. */
    fun request(tool: Tool, arguments: JsonObject): Triple<String, String, String?> {
        var path = tool.path
        val used = mutableSetOf<String>()
        Regex("\\{(\\w+)\\}").findAll(tool.path).forEach { m ->
            val key = m.groupValues[1]
            val value = (arguments[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                ?: throw BridgeArgumentException("$key is required")
            path = path.replace("{$key}", encode(value))
            used += key
        }
        val queryPairs = tool.query.mapNotNull { k ->
            (arguments[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { "${encode(k)}=${encode(it.content)}" }
        }
        used += tool.query
        val bodyArgs = arguments.filterKeys { it !in used }.toMutableMap()
        // The creature id in the path is `creatureId`; in a body it is `id`.
        if (tool.name == "aive_add_creature") bodyArgs.remove("creatureId")?.let { bodyArgs["id"] = it }
        val body = if (tool.method == "GET" || tool.method == "DELETE") null else JsonObject(bodyArgs).toString()
        val full = path + if (queryPairs.isEmpty()) "" else "?" + queryPairs.joinToString("&")
        return Triple(tool.method, full, body)
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

class BridgeArgumentException(message: String) : RuntimeException(message)
