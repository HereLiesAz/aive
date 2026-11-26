package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Swarm OS step 4 (docs/Swarm-OS.md): the district, all the way out. Every workflow is a
 * neighbourhood of one tissue, read from its definition and its latest run.
 */
internal data class Neighbourhood(
    val definitionId: String,
    val name: String,
    val creatures: Int,
    /** Its most recent run, or null if it never ran. */
    val latestRunId: String?,
    val state: NeighbourhoodState,
    /** Completed tasks over all tasks in the latest run, 0..1. */
    val growth: Float,
    /** Tasks in the latest run waiting on a person (approval or escalation): the creature at the surface. */
    val needsYou: Int,
    /** Runs that finished, cleared or failed: the scars. */
    val scars: Int,
    /** Up to three of its creatures, by role, to draw inside it. */
    val creatureRoles: List<String> = emptyList(),
)

internal enum class NeighbourhoodState { Idle, Gestating, Hatched, Withered }

internal fun projectDistrict(
    definitions: List<WorkflowDefinition>,
    runs: List<WorkflowRun>,
): List<Neighbourhood> {
    val runsByDefinition = runs.groupBy { it.workflowDefinitionId.value }
    return definitions.map { definition ->
        val own = runsByDefinition[definition.id.value].orEmpty()
        val latest = own.maxByOrNull { it.updatedAtEpochMillis }
        val total = definition.tasks.size.coerceAtLeast(1)
        Neighbourhood(
            definitionId = definition.id.value,
            name = definition.name,
            creatures = definition.tasks.size,
            latestRunId = latest?.id?.value,
            state = when (latest?.status) {
                null, WorkflowRunStatus.Created -> NeighbourhoodState.Idle
                WorkflowRunStatus.Completed -> NeighbourhoodState.Hatched
                WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled -> NeighbourhoodState.Withered
                WorkflowRunStatus.Running, WorkflowRunStatus.AwaitingHuman -> NeighbourhoodState.Gestating
            },
            growth = (latest?.taskRuns?.values?.count { it.status == TaskRunStatus.Completed } ?: 0).toFloat() / total,
            needsYou = latest?.taskRuns?.values?.count {
                it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated
            } ?: 0,
            scars = own.count { it.status in setOf(WorkflowRunStatus.Completed, WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled) },
            creatureRoles = definition.tasks.mapNotNull { it.roleId?.value }.distinct().take(3)
                .map { id -> id.split('-').joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) } },
        )
    }
}

/** Owners are scoped to their repository provider; equal names on different hosts never merge. */
internal data class HiveDirectoryOwner(val key: String, val label: String)

/** Stable repository identity, or an unlinked project identified by its stored ID. */
internal data class HiveDirectoryLocation(val key: String, val label: String, val owner: HiveDirectoryOwner?)

/** The intermediate zoom levels between District and Hive. */
internal sealed interface HiveDirectoryScope {
    data class Owner(val owner: HiveDirectoryOwner) : HiveDirectoryScope
    data class Repository(val location: HiveDirectoryLocation) : HiveDirectoryScope
}

/** A project/repository's hives and run counts, scoped to that location. */
internal data class HiveDirectoryGroup(
    val location: HiveDirectoryLocation,
    val projectNames: List<String>,
    val hives: List<Neighbourhood>,
    val runCount: Int,
)

/** Build identity from repository fields, not branch or mutable project name. */
internal fun hiveDirectoryLocation(project: Project, repository: RepositoryRef?): HiveDirectoryLocation {
    if (repository == null) return HiveDirectoryLocation("project:${project.id.value}", project.name, null)
    val labels = shellRepositoryLabels(repository)
    val label = labels.lastOrNull() ?: project.name
    if (repository.source == RepositorySource.Local) {
        return HiveDirectoryLocation("local:${repository.localPath ?: project.id.value}", label, null)
    }
    val github = repository.source == RepositorySource.GitHub
    val ownerKey = if (github) repository.owner.lowercase() else repository.owner
    val nameKey = if (github) repository.name.lowercase() else repository.name
    val owner = HiveDirectoryOwner("${repository.source.name}:$ownerKey", repository.owner)
    return HiveDirectoryLocation("${owner.key}/$nameKey", label, owner)
}

/**
 * Index each stored hive in every location where it ran. The run's captured repository takes
 * precedence over the project's current link. Never-run definitions have no project association
 * and remain in the District; deleted definitions are not offered as hives.
 */
internal fun projectHiveDirectory(
    definitions: List<WorkflowDefinition>,
    history: List<Pair<Project, List<WorkflowRun>>>,
): List<HiveDirectoryGroup> {
    val definitionIds = definitions.map { it.id }.toSet()
    val records = history.flatMap { (project, runs) ->
        runs.filter { it.workflowDefinitionId in definitionIds }.map { run ->
            Triple(hiveDirectoryLocation(project, run.repositorySnapshot ?: project.repository), project.name, run)
        }
    }
    return records.groupBy { it.first.key }.values.map { group ->
        val latest = group.maxBy { it.third.updatedAtEpochMillis }
        val runs = group.map { it.third }
        val ids = runs.map { it.workflowDefinitionId }.toSet()
        HiveDirectoryGroup(latest.first, group.map { it.second }.distinct().sorted(),
            projectDistrict(definitions.filter { it.id in ids }, runs), runs.size)
    }.sortedWith(compareBy({ it.location.label.lowercase() }, { it.location.key }))
}
