package com.hereliesaz.geministrator.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a creature works from, decided by the queen when she plans (docs/Keepers-Room.md). A task with no resources gets
 * exactly the context it always got; a resource only adds or narrows what is retrieved for that one task.
 */
@Serializable
sealed interface TaskResource {
    /** The app's own bundled docs, narrowed by [topics] (words matched against a section's heading and text); empty means all. */
    @Serializable
    @SerialName("aive_docs")
    data class AiveDocs(val topics: List<String> = emptyList()) : TaskResource

    /** Only these files of the linked repository: paths or globs (the .md files under docs, say). */
    @Serializable
    @SerialName("repository_files")
    data class RepositoryFiles(val paths: List<String>) : TaskResource

    /** The outputs of earlier steps: [fromTasks], or, when empty, all the task's dependencies. */
    @Serializable
    @SerialName("upstream_artifacts")
    data class UpstreamArtifacts(val fromTasks: List<TaskDefinitionId> = emptyList()) : TaskResource
}

/** Bounds on a task's resources: small on purpose, since the planner writes them. */
object TaskResourceLimits {
    const val MAX_RESOURCES = 6
    const val MAX_ITEMS = 8
    const val MAX_CHARS = 120
}

object TaskResources {
    const val KIND_AIVE_DOCS = "aive_docs"
    const val KIND_REPOSITORY_FILES = "repository_files"
    const val KIND_UPSTREAM = "upstream_artifacts"

    /** [resources] within the limits: items trimmed, de-duplicated and capped; one resource of each kind; a file list with no paths dropped. */
    fun bounded(resources: List<TaskResource>): List<TaskResource> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<TaskResource>()
        for (resource in resources) {
            val kind = when (resource) {
                is TaskResource.AiveDocs -> KIND_AIVE_DOCS
                is TaskResource.RepositoryFiles -> KIND_REPOSITORY_FILES
                is TaskResource.UpstreamArtifacts -> KIND_UPSTREAM
            }
            if (!seen.add(kind)) continue
            val cleaned: TaskResource = when (resource) {
                is TaskResource.AiveDocs -> TaskResource.AiveDocs(clean(resource.topics))
                is TaskResource.RepositoryFiles -> {
                    val paths = clean(resource.paths)
                    if (paths.isEmpty()) continue
                    TaskResource.RepositoryFiles(paths)
                }
                is TaskResource.UpstreamArtifacts ->
                    TaskResource.UpstreamArtifacts(clean(resource.fromTasks.map { it.value }).map(::TaskDefinitionId))
            }
            out += cleaned
            if (out.size >= TaskResourceLimits.MAX_RESOURCES) break
        }
        return out
    }

    private fun clean(items: List<String>): List<String> =
        items.map { it.trim().take(TaskResourceLimits.MAX_CHARS) }.filter(String::isNotEmpty).distinct().take(TaskResourceLimits.MAX_ITEMS)

    /**
     * The resources a planner (or a control request) wrote, as JSON objects `{"kind": "aive_docs", "topics": [...]}`,
     * `{"kind": "repository_files", "paths": [...]}` or `{"kind": "upstream_artifacts", "fromTasks": [...]}`. Anything else
     * is dropped and nothing throws. [resolveTask] maps a written step id to a real one, or null for a step that does not exist.
     */
    fun fromJson(raw: List<JsonElement>, resolveTask: (String) -> TaskDefinitionId?): List<TaskResource> {
        val parsed = raw.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.replace('-', '_')
                ?: return@mapNotNull null
            when (kind) {
                KIND_AIVE_DOCS -> TaskResource.AiveDocs(strings(obj["topics"] ?: obj["items"]))
                KIND_REPOSITORY_FILES -> TaskResource.RepositoryFiles(strings(obj["paths"] ?: obj["items"]))
                KIND_UPSTREAM -> TaskResource.UpstreamArtifacts(strings(obj["fromTasks"] ?: obj["items"]).mapNotNull(resolveTask))
                else -> null
            }
        }
        return bounded(parsed)
    }

    private fun strings(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    /** One short line for the plan the keeper and the reviewer read: "Works from: the Aive's docs (hives, queen); step a's output". Null when none. */
    fun worksFromLine(resources: List<TaskResource>): String? {
        if (resources.isEmpty()) return null
        return "Works from: " + resources.joinToString("; ") { resource ->
            when (resource) {
                is TaskResource.AiveDocs ->
                    "the Aive's docs" + if (resource.topics.isEmpty()) "" else " (" + resource.topics.joinToString(", ") + ")"
                is TaskResource.RepositoryFiles -> "repository files (" + resource.paths.joinToString(", ") + ")"
                is TaskResource.UpstreamArtifacts ->
                    if (resource.fromTasks.isEmpty()) {
                        "its earlier steps' output"
                    } else {
                        resource.fromTasks.joinToString(", ") { "step ${it.value}'s output" }
                    }
            }
        }
    }

    fun hasAiveDocs(resources: List<TaskResource>): Boolean = resources.any { it is TaskResource.AiveDocs }

    /** The planner's words about resources, added to every plan's instruction (a new objective, a saved hive's rough design, a drafted swarm). */
    const val PLAN_INSTRUCTION: String =
        " For each step you may add `resources`: what that creature works from, a list of objects. Use" +
            " {\"kind\":\"aive_docs\",\"topics\":[\"hives\",\"queen\"]} when the step needs facts about how this app works (hives, the queen, roles," +
            " Sources, Messages, settings, how to set up a swarm), with a few topic words; {\"kind\":\"repository_files\",\"paths\":[\"docs/*.md\"]}" +
            " for named files of the linked repository; {\"kind\":\"upstream_artifacts\",\"fromTasks\":[\"step-id\"]} for the output of earlier steps." +
            " Leave `resources` out when the step needs none."
}
