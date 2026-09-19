package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.workflow.ApprovalGate
import com.hereliesaz.geministrator.workflow.ApprovalGateRepository
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.FailureEscalationDecisionCommit
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PersistenceCorruptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private val settingsWorkflowPersistenceMutex = Mutex()

class SettingsWorkflowPersistence(
    private val settings: Settings,
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = defaultJson,
) : WorkflowPersistence {

    override val projects: ProjectRepository = object : ProjectRepository {
        override suspend fun put(project: Project) = update { snapshot ->
            snapshot.copy(projects = snapshot.projects.upsert(project) { it.id == project.id })
        }

        override suspend fun get(id: ProjectId): Project? = read().projects.firstOrNull { it.id == id }
        override suspend fun all(): List<Project> = read().projects
    }

    override val definitions: WorkflowDefinitionRepository = object : WorkflowDefinitionRepository {
        override suspend fun put(definition: WorkflowDefinition) = update { snapshot ->
            snapshot.copy(definitions = snapshot.definitions.upsert(definition) { it.id == definition.id })
        }

        override suspend fun get(id: WorkflowDefinitionId): WorkflowDefinition? =
            read().definitions.firstOrNull { it.id == id }

        override suspend fun all(): List<WorkflowDefinition> = read().definitions
    }

    override val runs: WorkflowRunRepository = object : WorkflowRunRepository {
        override suspend fun put(run: WorkflowRun) = update { snapshot ->
            snapshot.copy(runs = snapshot.runs.upsert(run) { it.id == run.id })
        }

        override suspend fun get(id: WorkflowRunId): WorkflowRun? = read().runs.firstOrNull { it.id == id }

        override suspend fun byProject(projectId: ProjectId): List<WorkflowRun> =
            read().runs.filter { it.projectId == projectId }.sortedByDescending { it.updatedAtEpochMillis }
    }

    override val events: WorkflowEventRepository = object : WorkflowEventRepository {
        override suspend fun append(event: WorkflowEvent) {
            settingsWorkflowPersistenceMutex.withLock { appendEventUnlocked(event) }
        }

        override suspend fun forRun(workflowRunId: WorkflowRunId): List<WorkflowEvent> =
            settingsWorkflowPersistenceMutex.withLock {
                val legacyEmbedded = readUnlocked().events.filter { it.workflowRunId == workflowRunId }
                val journaled = readJournalEventsUnlocked(workflowRunId)
                (legacyEmbedded + journaled).sortedBy { it.occurredAtEpochMillis }
            }
    }

    override val roles: RoleRepository = object : RoleRepository {
        override suspend fun put(role: RoleDefinition) = update { snapshot ->
            snapshot.copy(roles = snapshot.roles.upsert(role) { it.id == role.id })
        }

        override suspend fun get(id: RoleDefinitionId): RoleDefinition? = read().roles.firstOrNull { it.id == id }
        override suspend fun all(): List<RoleDefinition> = read().roles
    }

    override val artifacts: ArtifactRepository = object : ArtifactRepository {
        override suspend fun put(artifact: ArtifactRef) = update { snapshot ->
            snapshot.copy(artifacts = snapshot.artifacts.upsert(artifact) { it.id == artifact.id })
        }

        override suspend fun get(id: ArtifactId): ArtifactRef? = read().artifacts.firstOrNull { it.id == id }

        override suspend fun forRun(run: WorkflowRun): List<ArtifactRef> {
            val taskRunIds = run.taskRuns.values.mapTo(mutableSetOf()) { it.id }
            return read().artifacts.filter { it.taskRunId in taskRunIds }.sortedBy { it.createdAtEpochMillis }
        }
    }

    override suspend fun replaceCatalog(
        definitions: List<WorkflowDefinition>,
        roles: List<RoleDefinition>,
    ) {
        update { snapshot ->
            snapshot.copy(
                definitions = definitions.distinctBy(WorkflowDefinition::id),
                roles = roles.distinctBy(RoleDefinition::id),
            )
        }
    }

    override val approvalGates: ApprovalGateRepository = object : ApprovalGateRepository {
        override suspend fun put(gate: ApprovalGate) = update { snapshot ->
            snapshot.copy(approvalGates = snapshot.approvalGates.upsert(gate) { it.id == gate.id })
        }

        override suspend fun get(id: ApprovalGateId): ApprovalGate? =
            read().approvalGates.firstOrNull { it.id == id }

        override suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate> =
            read().approvalGates.filter {
                it.workflowRunId == workflowRunId &&
                    (it.status == ApprovalGateStatus.Pending || it.status == ApprovalGateStatus.Applying)
            }.sortedBy { it.createdAtEpochMillis }
    }

    override suspend fun commitFailureEscalationDecision(
        commit: FailureEscalationDecisionCommit,
    ): Boolean = settingsWorkflowPersistenceMutex.withLock {
        val snapshot = readUnlocked()
        val current = snapshot.approvalGates.firstOrNull { it.id == commit.expectedGateId }
            ?: return@withLock false
        if (current.status != ApprovalGateStatus.Pending) return@withLock false
        require(commit.decidedGate.id == current.id) {
            "Escalation decision gate ${commit.decidedGate.id.value} does not match ${current.id.value}"
        }
        require(commit.nextRun.id == current.workflowRunId) {
            "Escalation decision run ${commit.nextRun.id.value} does not match ${current.workflowRunId.value}"
        }
        require(commit.decisionEvent.gateId == current.id) {
            "Escalation decision event does not match gate ${current.id.value}"
        }

        val next = snapshot.copy(
            version = CURRENT_SCHEMA_VERSION,
            approvalGates = snapshot.approvalGates.upsert(commit.decidedGate) {
                it.id == commit.decidedGate.id
            },
            runs = snapshot.runs.upsert(commit.nextRun) { it.id == commit.nextRun.id },
            events = snapshot.events + commit.decisionEvent,
        )
        settings.putString(
            storageKey,
            json.encodeToString(PersistenceSnapshot.serializer(), next),
        )
        true
    }

    suspend fun exportJson(): String = settingsWorkflowPersistenceMutex.withLock {
        json.encodeToString(PersistenceSnapshot.serializer(), fullSnapshotUnlocked())
    }

    suspend fun exportProjectFile(projectId: ProjectId, savedAtEpochMillis: Long): String =
        settingsWorkflowPersistenceMutex.withLock {
            val full = fullSnapshotUnlocked()
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
            json.encodeToString(
                IveProjectDocument.serializer(),
                IveProjectDocument(
                    projectId = project.id.value,
                    projectName = project.name,
                    savedAtEpochMillis = savedAtEpochMillis,
                    snapshotJson = json.encodeToString(PersistenceSnapshot.serializer(), projectSnapshot),
                ),
            )
        }

    suspend fun importProjectFile(encoded: String): Project {
        val document = try {
            json.decodeFromString(IveProjectDocument.serializer(), encoded)
        } catch (failure: Exception) {
            throw PersistenceCorruptionException("Project file could not be parsed: ${failure.message}", failure)
        }
        require(document.format == IVE_PROJECT_FORMAT) {
            "Not a The Aive project file (format=${document.format})"
        }
        require(document.version <= IVE_PROJECT_VERSION) {
            "Project file version ${document.version} is not supported (max $IVE_PROJECT_VERSION)"
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

        settingsWorkflowPersistenceMutex.withLock {
            val current = readUnlocked()
            val importedRunIds = imported.runs.mapTo(linkedSetOf()) { it.id }
            val importedTaskRunIds = imported.runs
                .flatMap { it.taskRuns.values }
                .mapTo(linkedSetOf()) { it.id }

            // Imported runs replace their own journal so repeated loads are idempotent.
            importedRunIds.forEach { runId ->
                val prefix = eventRunPrefix(runId)
                settings.keys.filter { it.startsWith(prefix) }.forEach(settings::remove)
            }

            val merged = current.copy(
                version = CURRENT_SCHEMA_VERSION,
                projects = imported.projects.fold(current.projects) { values, value ->
                    values.upsert(value) { it.id == value.id }
                },
                definitions = imported.definitions.fold(current.definitions) { values, value ->
                    values.upsert(value) { it.id == value.id }
                },
                runs = imported.runs.fold(current.runs) { values, value ->
                    values.upsert(value) { it.id == value.id }
                },
                events = current.events.filterNot { it.workflowRunId in importedRunIds } + imported.events,
                roles = imported.roles.fold(current.roles) { values, value ->
                    values.upsert(value) { it.id == value.id }
                },
                artifacts = current.artifacts.filterNot { it.taskRunId in importedTaskRunIds } + imported.artifacts,
                approvalGates = current.approvalGates.filterNot { it.workflowRunId in importedRunIds } +
                    imported.approvalGates,
            )
            settings.putString(
                storageKey,
                json.encodeToString(PersistenceSnapshot.serializer(), merged),
            )
        }
        return project
    }

    suspend fun importJson(encoded: String) {
        val snapshot = try {
            json.decodeFromString(PersistenceSnapshot.serializer(), encoded)
        } catch (failure: Exception) {
            throw PersistenceCorruptionException("Import data could not be parsed: ${failure.message}", failure)
        }
        require(snapshot.version <= CURRENT_SCHEMA_VERSION) {
            "Import schema version ${snapshot.version} is not supported (max $CURRENT_SCHEMA_VERSION)"
        }
        val migrated = migrate(snapshot).copy(version = CURRENT_SCHEMA_VERSION)
        settingsWorkflowPersistenceMutex.withLock {
            // Clear existing per-run journal entries before restoring to prevent event mixing.
            settings.keys
                .filter { it.startsWith(eventJournalRoot()) }
                .forEach(settings::remove)
            settings.putString(storageKey, json.encodeToString(PersistenceSnapshot.serializer(), migrated))
        }
    }

    suspend fun clearWorkflowData() {
        settingsWorkflowPersistenceMutex.withLock {
            settings.remove(storageKey)
            if (storageKey == DEFAULT_STORAGE_KEY) {
                settings.remove(LEGACY_STORAGE_KEY_V1)
            }
            settings.keys
                .filter { it.startsWith(eventJournalRoot()) }
                .forEach(settings::remove)
        }
    }

    suspend fun snapshotVersion(): Int = read().version

    private suspend fun read(): PersistenceSnapshot = settingsWorkflowPersistenceMutex.withLock { readUnlocked() }

    private fun fullSnapshotUnlocked(): PersistenceSnapshot {
        val snapshot = readUnlocked()
        val journaledEvents = snapshot.runs.flatMap { run ->
            readJournalEventsUnlocked(run.id)
        }
        return snapshot.copy(
            events = (snapshot.events + journaledEvents)
                .distinctBy { event -> event.workflowRunId to event }
                .sortedBy { it.occurredAtEpochMillis },
        )
    }

    private suspend fun update(transform: (PersistenceSnapshot) -> PersistenceSnapshot) {
        settingsWorkflowPersistenceMutex.withLock {
            val next = transform(readUnlocked()).copy(version = CURRENT_SCHEMA_VERSION)
            settings.putString(storageKey, json.encodeToString(PersistenceSnapshot.serializer(), next))
        }
    }

    private fun appendEventUnlocked(event: WorkflowEvent) {
        val prefix = eventRunPrefix(event.workflowRunId)
        val countKey = "$prefix.count"
        var index = settings.getStringOrNull(countKey)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        while (settings.hasKey(eventKey(prefix, index))) {
            index += 1
        }

        settings.putString(
            eventKey(prefix, index),
            json.encodeToString(WorkflowEvent.serializer(), event),
        )
        settings.putString(countKey, (index + 1).toString())
    }

    private fun readJournalEventsUnlocked(workflowRunId: WorkflowRunId): List<WorkflowEvent> {
        val prefix = eventRunPrefix(workflowRunId)
        val countKey = "$prefix.count"
        val storedCount = settings.getStringOrNull(countKey)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        var recoveredCount = storedCount

        while (settings.hasKey(eventKey(prefix, recoveredCount))) {
            recoveredCount += 1
        }
        if (recoveredCount != storedCount) {
            settings.putString(countKey, recoveredCount.toString())
        }

        return (0 until recoveredCount).map { index ->
            val encoded = requireNotNull(settings.getStringOrNull(eventKey(prefix, index))) {
                "Workflow event journal is missing entry $index for ${workflowRunId.value}"
            }
            json.decodeFromString(WorkflowEvent.serializer(), encoded)
        }
    }

    private fun eventJournalRoot(): String = "$storageKey.events."

    private fun eventRunPrefix(workflowRunId: WorkflowRunId): String =
        "${eventJournalRoot()}${workflowRunId.value.toSettingsKeyToken()}"

    private fun eventKey(prefix: String, index: Int): String = "$prefix.$index"

    private fun String.toSettingsKeyToken(): String = buildString(length * 4) {
        for (character in this@toSettingsKeyToken) {
            append(character.code.toString(16).padStart(4, '0'))
        }
    }

    var lastCorruptionError: Throwable? = null
        private set

    suspend fun recoverFromCorruption(): Boolean {
        val hadData = settingsWorkflowPersistenceMutex.withLock {
            val had = settings.getStringOrNull(storageKey) != null ||
                settings.getStringOrNull(LEGACY_STORAGE_KEY_V1) != null
            settings.remove(storageKey)
            settings.remove(LEGACY_STORAGE_KEY_V1)
            had
        }
        lastCorruptionError = null
        return hadData
    }

    private fun readUnlocked(): PersistenceSnapshot {
        val currentEncoded = settings.getStringOrNull(storageKey)
        val legacyEncoded = if (currentEncoded == null && storageKey == DEFAULT_STORAGE_KEY) {
            settings.getStringOrNull(LEGACY_STORAGE_KEY_V1)
        } else {
            null
        }
        val encoded = currentEncoded ?: legacyEncoded ?: return PersistenceSnapshot()

        val snapshot = try {
            json.decodeFromString(PersistenceSnapshot.serializer(), encoded)
        } catch (failure: Exception) {
            lastCorruptionError = failure
            throw PersistenceCorruptionException(
                "Workflow persistence data is unreadable and must be recovered. " +
                    "Call recoverFromCorruption() to clear corrupted state.",
                failure,
            )
        }

        require(snapshot.version <= CURRENT_SCHEMA_VERSION) {
            "Unsupported workflow persistence schema ${snapshot.version}; maximum supported is $CURRENT_SCHEMA_VERSION"
        }

        val migrated = migrate(snapshot)
        if (currentEncoded == null || migrated != snapshot) {
            settings.putString(
                storageKey,
                json.encodeToString(PersistenceSnapshot.serializer(), migrated),
            )
            if (legacyEncoded != null) {
                settings.remove(LEGACY_STORAGE_KEY_V1)
            }
        }
        return migrated
    }

    private fun migrate(snapshot: PersistenceSnapshot): PersistenceSnapshot {
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

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 3
        const val DEFAULT_STORAGE_KEY: String = "geministrator.workflow.persistence.v2"
        internal const val LEGACY_STORAGE_KEY_V1: String = "geministrator.workflow.persistence.v1"
        const val IVE_PROJECT_FORMAT: String = "the-aive-project"
        const val IVE_PROJECT_VERSION: Int = 1

        val defaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            allowStructuredMapKeys = true
        }

        fun createDefault(): SettingsWorkflowPersistence = SettingsWorkflowPersistence(Settings())
    }
}

@Serializable
private data class PersistenceSnapshot(
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
private data class IveProjectDocument(
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

private inline fun <T> List<T>.upsert(value: T, matches: (T) -> Boolean): List<T> {
    val index = indexOfFirst(matches)
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}
