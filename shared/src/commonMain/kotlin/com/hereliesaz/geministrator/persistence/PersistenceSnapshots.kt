package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.repairKnownNodeCreatureRigCycle
import com.hereliesaz.geministrator.workflow.ApprovalGate
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.humanReadable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The whole-store snapshot shared by every WorkflowPersistence backend: run/JSON export and
 * import, .ive project files, and the schema migrations applied to anything read from an older
 * version. Backends differ only in where the records live.
 */

private const val CURRENT_SCHEMA_VERSION: Int = SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION

/** Encodes one project, its runs, the definitions they (transitively) use and every role as a .ive file. */
internal fun encodeProjectFile(
    full: PersistenceSnapshot,
    projectId: ProjectId,
    savedAtEpochMillis: Long,
    json: Json,
): String {
    val project = requireNotNull(full.projects.firstOrNull { it.id == projectId }) {
        "Project ${projectId.value} was not found"
    }
    val projectRuns = full.runs.filter { it.projectId == projectId }
    val runIds = projectRuns.mapTo(linkedSetOf()) { it.id }

    val definitionsById = full.definitions.associateBy { it.id }
    val definitionIds = projectRuns.mapTo(linkedSetOf()) { it.workflowDefinitionId }
    var expanded = true
    while (expanded) {
        expanded = false
        definitionIds.toList().forEach { definitionId ->
            val definition = definitionsById[definitionId] ?: return@forEach
            definition.tasks.forEach { task ->
                val nestedId = task.executor?.nestedWorkflowDefinitionId()
                if (nestedId != null && definitionIds.add(nestedId)) {
                    expanded = true
                }
            }
        }
    }

    val taskRunIds = projectRuns
        .flatMap { it.taskRuns.values }
        .mapTo(linkedSetOf()) { it.id }

    val projectSnapshot = PersistenceSnapshot(
        version = CURRENT_SCHEMA_VERSION,
        projects = listOf(project),
        definitions = full.definitions.filter { it.id in definitionIds },
        runs = projectRuns,
        events = full.events.filter { it.workflowRunId in runIds },
        // Role definitions are small and are part of the semantics of saved workflows.
        // Include the whole active/custom role catalog so a project remains executable.
        roles = full.roles,
        artifacts = full.artifacts.filter { it.taskRunId in taskRunIds },
        approvalGates = full.approvalGates.filter { it.workflowRunId in runIds },
    )
    return json.encodeToString(
        IveProjectDocument.serializer(),
        IveProjectDocument(
            projectId = project.id.value,
            projectName = project.name,
            savedAtEpochMillis = savedAtEpochMillis,
            snapshotJson = json.encodeToString(PersistenceSnapshot.serializer(), projectSnapshot),
        ),
    )
}

/** Decodes and migrates a .ive file; the result holds exactly the file's one project. */
internal fun decodeProjectFile(encoded: String, json: Json): PersistenceSnapshot {
    val document = try {
        json.decodeFromString(IveProjectDocument.serializer(), encoded)
    } catch (failure: Exception) {
        throw PersistenceCorruptionException("Project file could not be parsed: ${failure.message}", failure)
    }
    require(document.format == SettingsWorkflowPersistence.IVE_PROJECT_FORMAT) {
        "Not a The Aive project file (format=${document.format})"
    }
    require(document.version <= SettingsWorkflowPersistence.IVE_PROJECT_VERSION) {
        "Project file version ${document.version} is not supported (max ${SettingsWorkflowPersistence.IVE_PROJECT_VERSION})"
    }

    val decoded = try {
        json.decodeFromString(PersistenceSnapshot.serializer(), document.snapshotJson)
    } catch (failure: Exception) {
        throw PersistenceCorruptionException("Project snapshot could not be parsed: ${failure.message}", failure)
    }
    require(decoded.version <= CURRENT_SCHEMA_VERSION) {
        "Project snapshot schema ${decoded.version} is not supported (max $CURRENT_SCHEMA_VERSION)"
    }
    val imported = migrate(decoded).copy(version = CURRENT_SCHEMA_VERSION)
    val project = requireNotNull(imported.projects.singleOrNull()) {
        "A .ive project file must contain exactly one project"
    }
    require(project.id.value == document.projectId) {
        "Project file metadata does not match the embedded project"
    }
    return imported.asUntrustedImport(document.savedAtEpochMillis)
}

/**
 * A .ive file is untrusted input: anyone can hand one over, and the app imports a detected one on
 * first launch. Its definitions must pass the same graph validation as authored ones, and its runs
 * arrive as history, never as work in progress: a run that had not finished is imported cancelled
 * (unfinished tasks cancelled, finished ones kept), and every gate still pending or applying is
 * rejected. Approvals, approved plans and Ready tasks in the file therefore cannot make anything
 * execute here; continuing the work takes a new run that a person starts and approves.
 */
internal fun PersistenceSnapshot.asUntrustedImport(importedAtEpochMillis: Long): PersistenceSnapshot {
    val validDefinitions = definitions.map { definition ->
        repairKnownNodeCreatureRigCycle(definition).also { repaired ->
            val errors = WorkflowGraphValidator.validate(repaired)
            require(errors.isEmpty()) {
                "Project file workflow '${repaired.name}' is invalid: " +
                    errors.joinToString(" ") { it.humanReadable() }
            }
        }
    }
    val stopped = BlockingReason(
        code = IMPORTED_RUN_STOPPED_CODE,
        message = "Imported from a project file. Start a new run to continue this work.",
    )
    return copy(
        definitions = validDefinitions,
        runs = runs.map { run ->
            if (run.status == WorkflowRunStatus.Completed ||
                run.status == WorkflowRunStatus.Failed ||
                run.status == WorkflowRunStatus.Cancelled
            ) {
                run
            } else {
                run.copy(
                    status = WorkflowRunStatus.Cancelled,
                    taskRuns = run.taskRuns.mapValues { (_, taskRun) ->
                        if (taskRun.status == TaskRunStatus.Completed || taskRun.status == TaskRunStatus.Failed) {
                            taskRun
                        } else {
                            taskRun.copy(status = TaskRunStatus.Cancelled, blockingReason = stopped)
                        }
                    },
                )
            }
        },
        approvalGates = approvalGates.map { gate ->
            if (gate.status == ApprovalGateStatus.Pending || gate.status == ApprovalGateStatus.Applying) {
                gate.reject(
                    decidedByRoleId = null,
                    note = "Imported from a project file; approvals are never carried over.",
                    nowEpochMillis = importedAtEpochMillis,
                )
            } else {
                gate
            }
        },
    )
}

internal const val IMPORTED_RUN_STOPPED_CODE = "imported-run-stopped"

/** Decodes and migrates a whole-store export written by exportJson(). */
internal fun decodeImportSnapshot(encoded: String, json: Json): PersistenceSnapshot {
    val snapshot = try {
        json.decodeFromString(PersistenceSnapshot.serializer(), encoded)
    } catch (failure: Exception) {
        throw PersistenceCorruptionException("Import data could not be parsed: ${failure.message}", failure)
    }
    require(snapshot.version <= CURRENT_SCHEMA_VERSION) {
        "Import schema version ${snapshot.version} is not supported (max $CURRENT_SCHEMA_VERSION)"
    }
    return migrate(snapshot).copy(version = CURRENT_SCHEMA_VERSION)
}

internal fun migrate(snapshot: PersistenceSnapshot): PersistenceSnapshot {
    var migrated = snapshot
    if (migrated.version < 2) {
        migrated = migrated.copy(
            version = 2,
            definitions = migrated.definitions.map { definition ->
                definition.copy(
                    tasks = definition.tasks.map { task ->
                        if (task.executor != null || task.roleId == null) {
                            task
                        } else {
                            task.copy(executor = TaskExecutor.RoleAgent(task.roleId))
                        }
                    },
                )
            },
            runs = migrated.runs.map { run ->
                run.copy(
                    taskRuns = run.taskRuns.mapValues { (_, taskRun) ->
                        if (taskRun.executor != null || taskRun.assignedRoleId == null) {
                            taskRun
                        } else {
                            taskRun.copy(executor = TaskExecutor.RoleAgent(taskRun.assignedRoleId))
                        }
                    },
                )
            },
        )
    }
    if (migrated.version < 3) {
        val attemptsByTaskRunId = migrated.runs
            .flatMap { it.taskRuns.values }
            .associate { it.id to it.attempt }
        migrated = migrated.copy(
            version = 3,
            runs = migrated.runs.map { run ->
                run.copy(
                    taskRuns = run.taskRuns.mapValues { (_, taskRun) ->
                        taskRun.copy(
                            artifacts = taskRun.artifacts
                                .map { it.withAttemptScopedId(taskRun.attempt) }
                                .distinctBy { it.id },
                        )
                    },
                )
            },
            artifacts = migrated.artifacts
                .map { artifact ->
                    artifact.withAttemptScopedId(attemptsByTaskRunId[artifact.taskRunId] ?: 1)
                }
                .distinctBy { it.id },
        )
    }
    return migrated
}

private fun ArtifactRef.withAttemptScopedId(attempt: Int): ArtifactRef {
    val rawId = id.value
    val genericPrefix = "${taskRunId.value}:${kind}:"
    if (rawId.startsWith(genericPrefix)) {
        val suffix = rawId.removePrefix(genericPrefix)
        if (':' !in suffix) {
            return copy(id = ArtifactId("$genericPrefix$attempt:$suffix"))
        }
        return this
    }

    val githubPrefix = "${taskRunId.value}:github-action:"
    if (rawId.startsWith(githubPrefix)) {
        val suffix = rawId.removePrefix(githubPrefix)
        if (!suffix.startsWith("$attempt:")) {
            return copy(id = ArtifactId("$githubPrefix$attempt:$suffix"))
        }
    }
    return this
}

@Serializable
internal data class PersistenceSnapshot(
    val version: Int = SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION,
    val projects: List<Project> = emptyList(),
    val definitions: List<WorkflowDefinition> = emptyList(),
    val runs: List<WorkflowRun> = emptyList(),
    val events: List<WorkflowEvent> = emptyList(),
    val roles: List<RoleDefinition> = emptyList(),
    val artifacts: List<ArtifactRef> = emptyList(),
    val approvalGates: List<ApprovalGate> = emptyList(),
)

@Serializable
internal data class IveProjectDocument(
    val format: String = SettingsWorkflowPersistence.IVE_PROJECT_FORMAT,
    val version: Int = SettingsWorkflowPersistence.IVE_PROJECT_VERSION,
    val projectId: String,
    val projectName: String,
    val savedAtEpochMillis: Long,
    val snapshotJson: String,
)

private fun TaskExecutor.nestedWorkflowDefinitionId(): WorkflowDefinitionId? = when (this) {
    is TaskExecutor.NestedWorkflow -> workflowDefinitionId
    is TaskExecutor.Distributed -> delegate.nestedWorkflowDefinitionId()
    else -> null
}

internal inline fun <T> List<T>.upsert(value: T, matches: (T) -> Boolean): List<T> {
    val index = indexOfFirst(matches)
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}
