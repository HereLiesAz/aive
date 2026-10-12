package com.hereliesaz.geministrator.persistence

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.persistence.db.WorkflowDatabase
import com.hereliesaz.geministrator.workflow.ApprovalGate
import com.hereliesaz.geministrator.workflow.ApprovalGateRepository
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.FailureEscalationDecisionCommit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Workflow state in SQLite, one row per record (see Workflow.sq). A write touches only the rows it
 * changes, so its cost no longer grows with the user's run history the way the Settings snapshot's
 * did (docs/architecture/STORAGE_SCALE.md). Rows hold the same JSON as the Settings snapshot, so
 * export, import and .ive files are identical across backends.
 *
 * One writer at a time, as before: every operation holds [mutex], and multi-row changes are one
 * transaction.
 */
class SqlWorkflowPersistence(
    driver: SqlDriver,
    private val json: Json = SettingsWorkflowPersistence.defaultJson,
    migrateFrom: LegacySettingsMigration? = null,
) : DurableWorkflowPersistence {
    private val database = WorkflowDatabase(driver)
    private val queries = database.workflowQueries
    private val mutex = Mutex()

    /** Runs once, inside the first locked operation, so nothing reads before the move is complete. */
    private var pendingMigration: LegacySettingsMigration? = migrateFrom

    override var lastCorruptionError: Throwable? = null
        private set

    override val projects: ProjectRepository = object : ProjectRepository {
        override suspend fun put(project: Project) = locked { writeProject(project) }
        override suspend fun get(id: ProjectId): Project? =
            locked { queries.project(id.value).awaitAsOneOrNull()?.decode(Project.serializer()) }
        override suspend fun all(): List<Project> =
            locked { queries.allProjects().awaitAsList().map { it.decode(Project.serializer()) } }
    }

    override val definitions: WorkflowDefinitionRepository = object : WorkflowDefinitionRepository {
        override suspend fun put(definition: WorkflowDefinition) = locked { writeDefinition(definition) }
        override suspend fun get(id: WorkflowDefinitionId): WorkflowDefinition? =
            locked { queries.definition(id.value).awaitAsOneOrNull()?.decode(WorkflowDefinition.serializer()) }
        override suspend fun all(): List<WorkflowDefinition> =
            locked { queries.allDefinitions().awaitAsList().map { it.decode(WorkflowDefinition.serializer()) } }
    }

    override val runs: WorkflowRunRepository = object : WorkflowRunRepository {
        override suspend fun put(run: WorkflowRun) = locked { writeRun(run) }
        override suspend fun get(id: WorkflowRunId): WorkflowRun? =
            locked { queries.run(id.value).awaitAsOneOrNull()?.decode(WorkflowRun.serializer()) }
        override suspend fun byProject(projectId: ProjectId): List<WorkflowRun> = locked {
            queries.runsByProject(projectId.value).awaitAsList().map { it.decode(WorkflowRun.serializer()) }
        }
    }

    override val events: WorkflowEventRepository = object : WorkflowEventRepository {
        override suspend fun append(event: WorkflowEvent) = locked { writeEvent(event) }
        override suspend fun forRun(workflowRunId: WorkflowRunId): List<WorkflowEvent> = locked {
            queries.eventsForRun(workflowRunId.value).awaitAsList().map { it.decode(WorkflowEvent.serializer()) }
        }
    }

    override val roles: RoleRepository = object : RoleRepository {
        override suspend fun put(role: RoleDefinition) = locked { writeRole(role) }
        override suspend fun get(id: RoleDefinitionId): RoleDefinition? =
            locked { queries.role(id.value).awaitAsOneOrNull()?.decode(RoleDefinition.serializer()) }
        override suspend fun all(): List<RoleDefinition> =
            locked { queries.allRoles().awaitAsList().map { it.decode(RoleDefinition.serializer()) } }
    }

    override val artifacts: ArtifactRepository = object : ArtifactRepository {
        override suspend fun put(artifact: ArtifactRef) = locked { writeArtifact(artifact) }
        override suspend fun get(id: ArtifactId): ArtifactRef? =
            locked { queries.artifact(id.value).awaitAsOneOrNull()?.decode(ArtifactRef.serializer()) }
        override suspend fun forRun(run: WorkflowRun): List<ArtifactRef> = locked {
            run.taskRuns.values.map { it.id }.distinct()
                .flatMap { taskRunId -> queries.artifactsForTaskRun(taskRunId.value).awaitAsList() }
                .sortedBy { it.created_at }
                .map { it.payload.decode(ArtifactRef.serializer()) }
        }
    }

    override val approvalGates: ApprovalGateRepository = object : ApprovalGateRepository {
        override suspend fun put(gate: ApprovalGate) = locked { writeGate(gate) }
        override suspend fun get(id: ApprovalGateId): ApprovalGate? =
            locked { queries.gate(id.value).awaitAsOneOrNull()?.decode(ApprovalGate.serializer()) }
        override suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate> = locked {
            queries.openGatesForRun(workflowRunId.value).awaitAsList().map { it.decode(ApprovalGate.serializer()) }
        }
    }

    override suspend fun replaceCatalog(definitions: List<WorkflowDefinition>, roles: List<RoleDefinition>) = locked {
        database.transaction {
            queries.deleteDefinitions()
            queries.deleteRoles()
            definitions.distinctBy(WorkflowDefinition::id).forEach { writeDefinition(it) }
            roles.distinctBy(RoleDefinition::id).forEach { writeRole(it) }
        }
    }

    override suspend fun commitFailureEscalationDecision(commit: FailureEscalationDecisionCommit): Boolean = locked {
        database.transactionWithResult {
            val current = queries.gate(commit.expectedGateId.value).awaitAsOneOrNull()
                ?.decode(ApprovalGate.serializer())
                ?: return@transactionWithResult false
            if (current.status != ApprovalGateStatus.Pending) return@transactionWithResult false
            require(commit.decidedGate.id == current.id) {
                "Escalation decision gate ${commit.decidedGate.id.value} does not match ${current.id.value}"
            }
            require(commit.nextRun.id == current.workflowRunId) {
                "Escalation decision run ${commit.nextRun.id.value} does not match ${current.workflowRunId.value}"
            }
            require(commit.decisionEvent.gateId == current.id) {
                "Escalation decision event does not match gate ${current.id.value}"
            }
            writeGate(commit.decidedGate)
            writeRun(commit.nextRun)
            writeEvent(commit.decisionEvent)
            true
        }
    }

    override suspend fun exportJson(): String = locked {
        json.encodeToString(PersistenceSnapshot.serializer(), snapshotUnlocked())
    }

    override suspend fun exportProjectFile(projectId: ProjectId, savedAtEpochMillis: Long): String = locked {
        encodeProjectFile(snapshotUnlocked(), projectId, savedAtEpochMillis, json)
    }

    override suspend fun importProjectFile(encoded: String): Project {
        val imported = decodeProjectFile(encoded, json)
        locked {
            database.transaction {
                // Imported runs replace their own events, artifacts and gates so repeated loads are idempotent.
                imported.runs.forEach { run ->
                    queries.deleteEventsForRun(run.id.value)
                    queries.deleteGatesForRun(run.id.value)
                    run.taskRuns.values.forEach { queries.deleteArtifactsForTaskRun(it.id.value) }
                }
                writeSnapshot(imported)
            }
        }
        return imported.projects.single()
    }

    override suspend fun importJson(encoded: String) {
        val migrated = decodeImportSnapshot(encoded, json)
        locked {
            database.transaction {
                clearUnlocked()
                writeSnapshot(migrated)
            }
        }
    }

    override suspend fun clearWorkflowData() = locked { database.transaction { clearUnlocked() } }

    override suspend fun recoverFromCorruption(): Boolean {
        val hadData = locked {
            val had = queries.allProjects().awaitAsList().isNotEmpty() ||
                queries.allRuns().awaitAsList().isNotEmpty()
            database.transaction { clearUnlocked() }
            had
        }
        lastCorruptionError = null
        return hadData
    }

    /**
     * Moves workflow state out of the Settings store this replaces, once. The previous store is
     * handed to [LegacySettingsMigration.backup] as an export before it is cleared, so nothing is
     * lost if the move is ever questioned. Unreadable Settings state is left where it is.
     */
    private suspend fun migrateUnlocked(migration: LegacySettingsMigration) {
        if (queries.meta(MIGRATED_FROM_SETTINGS).awaitAsOneOrNull() != null) return
        val exported = try {
            migration.previous.exportJson()
        } catch (failure: PersistenceCorruptionException) {
            null
        }
        if (exported != null) {
            val snapshot = decodeImportSnapshot(exported, json)
            if (snapshot.hasContent()) {
                migration.backup(exported)
                database.transaction { writeSnapshot(snapshot) }
            }
            migration.previous.clearWorkflowData()
        }
        queries.putMeta(MIGRATED_FROM_SETTINGS, if (exported != null) "done" else "skipped-unreadable")
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        pendingMigration?.let { migration ->
            migrateUnlocked(migration)
            pendingMigration = null
        }
        block()
    }

    private suspend fun snapshotUnlocked(): PersistenceSnapshot = PersistenceSnapshot(
        version = SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION,
        projects = queries.allProjects().awaitAsList().map { it.decode(Project.serializer()) },
        definitions = queries.allDefinitions().awaitAsList().map { it.decode(WorkflowDefinition.serializer()) },
        runs = queries.allRuns().awaitAsList().map { it.decode(WorkflowRun.serializer()) },
        events = queries.allEvents().awaitAsList().map { it.decode(WorkflowEvent.serializer()) },
        roles = queries.allRoles().awaitAsList().map { it.decode(RoleDefinition.serializer()) },
        artifacts = queries.allArtifacts().awaitAsList().map { it.decode(ArtifactRef.serializer()) },
        approvalGates = queries.allGates().awaitAsList().map { it.decode(ApprovalGate.serializer()) },
    )

    private suspend fun writeSnapshot(snapshot: PersistenceSnapshot) {
        snapshot.projects.forEach { writeProject(it) }
        snapshot.definitions.forEach { writeDefinition(it) }
        snapshot.runs.forEach { writeRun(it) }
        snapshot.events.sortedBy { it.occurredAtEpochMillis }.forEach { writeEvent(it) }
        snapshot.roles.forEach { writeRole(it) }
        snapshot.artifacts.forEach { writeArtifact(it) }
        snapshot.approvalGates.forEach { writeGate(it) }
    }

    private suspend fun clearUnlocked() {
        queries.clearProjects()
        queries.deleteDefinitions()
        queries.clearRuns()
        queries.clearEvents()
        queries.deleteRoles()
        queries.clearArtifacts()
        queries.clearGates()
    }

    private suspend fun writeProject(project: Project) {
        queries.upsertProject(project.id.value, project.encode(Project.serializer()))
    }

    private suspend fun writeDefinition(definition: WorkflowDefinition) {
        queries.upsertDefinition(definition.id.value, definition.encode(WorkflowDefinition.serializer()))
    }

    private suspend fun writeRun(run: WorkflowRun) {
        queries.upsertRun(run.id.value, run.projectId.value, run.updatedAtEpochMillis, run.encode(WorkflowRun.serializer()))
    }

    private suspend fun writeEvent(event: WorkflowEvent) {
        queries.appendEvent(event.workflowRunId.value, event.occurredAtEpochMillis, event.encode(WorkflowEvent.serializer()))
    }

    private suspend fun writeRole(role: RoleDefinition) {
        queries.upsertRole(role.id.value, role.encode(RoleDefinition.serializer()))
    }

    private suspend fun writeArtifact(artifact: ArtifactRef) {
        queries.upsertArtifact(
            artifact.id.value,
            artifact.taskRunId.value,
            artifact.createdAtEpochMillis,
            artifact.encode(ArtifactRef.serializer()),
        )
    }

    private suspend fun writeGate(gate: ApprovalGate) {
        val open = gate.status == ApprovalGateStatus.Pending || gate.status == ApprovalGateStatus.Applying
        queries.upsertGate(gate.id.value, gate.workflowRunId.value, if (open) 1L else 0L, gate.createdAtEpochMillis, gate.encode(ApprovalGate.serializer()))
    }

    private fun <T> T.encode(serializer: KSerializer<T>): String = json.encodeToString(serializer, this)

    private fun <T> String.decode(serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, this)
    } catch (failure: Exception) {
        lastCorruptionError = failure
        throw PersistenceCorruptionException(
            "Workflow persistence data is unreadable and must be recovered. " +
                "Call recoverFromCorruption() to clear corrupted state.",
            failure,
        )
    }

    private fun PersistenceSnapshot.hasContent(): Boolean =
        projects.isNotEmpty() || definitions.isNotEmpty() || runs.isNotEmpty() || events.isNotEmpty() ||
            roles.isNotEmpty() || artifacts.isNotEmpty() || approvalGates.isNotEmpty()

    private companion object {
        const val MIGRATED_FROM_SETTINGS = "migrated-from-settings"
    }
}

/** The Settings store a [SqlWorkflowPersistence] replaces, and where its pre-move export is kept. */
class LegacySettingsMigration(
    val previous: SettingsWorkflowPersistence,
    val backup: suspend (exportJson: String) -> Unit,
)
