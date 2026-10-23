package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.workflow.ApprovalGate
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.FailureEscalationDecisionCommit
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import com.russhwolf.settings.MapSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlWorkflowPersistenceTest {
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("workflow"),
        name = "Workflow",
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("task"),
                name = "Task",
                objective = "Do work",
                roleId = BuiltInRoles.ImplementationEngineer.id,
            ),
        ),
    )

    private fun run(id: String, updatedAt: Long = 2L): WorkflowRun = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = WorkflowRunId(id),
        projectId = project.id,
        objective = "Objective",
        nowEpochMillis = updatedAt,
        taskRunIdFactory = { TaskRunId("$id-task") },
    )

    private fun gate(runId: WorkflowRunId, id: String = "gate", at: Long = 4L) = ApprovalGate(
        id = ApprovalGateId(id),
        workflowRunId = runId,
        taskDefinitionId = TaskDefinitionId("task"),
        kind = ApprovalGateKind.PlanApproval,
        reason = "Review plan",
        createdAtEpochMillis = at,
    )

    private fun artifact(taskRunId: TaskRunId, id: String, at: Long) = ArtifactRef(
        id = ArtifactId(id),
        kind = ArtifactKind.CommandOutput,
        taskRunId = taskRunId,
        label = id,
        textContent = "output $id",
        createdAtEpochMillis = at,
    )

    private fun tempDatabase(): File = Files.createTempDirectory("aive-sql").toFile().resolve("workflows.db")

    @Test
    fun recordsSurviveReopeningTheDatabase() = runBlocking {
        val file = tempDatabase()
        val first = desktopSqlWorkflowPersistence(file)
        val older = run("older", updatedAt = 2L)
        val newer = run("newer", updatedAt = 9L)
        first.projects.put(project)
        first.definitions.put(definition)
        first.runs.put(older)
        first.runs.put(newer)
        first.roles.put(BuiltInRoles.ImplementationEngineer)
        first.approvalGates.put(gate(newer.id))
        first.artifacts.put(artifact(TaskRunId("newer-task"), "b", at = 7L))
        first.artifacts.put(artifact(TaskRunId("newer-task"), "a", at = 5L))
        first.artifacts.put(artifact(TaskRunId("older-task"), "c", at = 6L))

        val restored = desktopSqlWorkflowPersistence(file)
        assertEquals(project, restored.projects.get(project.id))
        assertEquals(definition, restored.definitions.get(definition.id))
        assertEquals(listOf(newer, older), restored.runs.byProject(project.id))
        assertEquals(BuiltInRoles.ImplementationEngineer, restored.roles.get(BuiltInRoles.ImplementationEngineer.id))
        assertEquals(listOf("a", "b"), restored.artifacts.forRun(newer).map { it.id.value })
        assertEquals(listOf(gate(newer.id)), restored.approvalGates.unresolved(newer.id))
        assertNull(restored.runs.get(WorkflowRunId("missing")))
    }

    @Test
    fun eventsComeBackInOccurrenceOrderPerRun() = runBlocking {
        val persistence = desktopSqlWorkflowPersistence(null)
        val runId = WorkflowRunId("run")
        listOf(3L, 1L, 2L).forEach { at ->
            persistence.events.append(TaskStarted(runId, TaskDefinitionId("task"), at.toInt(), at))
        }
        persistence.events.append(TaskStarted(WorkflowRunId("other"), TaskDefinitionId("task"), 9, 0L))

        assertEquals(listOf(1, 2, 3), persistence.events.forRun(runId).map { (it as TaskStarted).attempt })
    }

    @Test
    fun resolvedGatesLeaveTheUnresolvedList() = runBlocking {
        val persistence = desktopSqlWorkflowPersistence(null)
        val runId = WorkflowRunId("run")
        val pending = gate(runId)
        persistence.approvalGates.put(pending)
        persistence.approvalGates.put(pending.approve(null, "ok", 5L))

        assertTrue(persistence.approvalGates.unresolved(runId).isEmpty())
        assertEquals(ApprovalGateStatus.Approved, persistence.approvalGates.get(pending.id)?.status)
    }

    @Test
    fun escalationDecisionHasOneWinner() = runBlocking {
        val persistence = desktopSqlWorkflowPersistence(null)
        val run = run("run")
        val pending = gate(run.id)
        persistence.runs.put(run)
        persistence.approvalGates.put(pending)

        fun commit(approved: Boolean, at: Long) = FailureEscalationDecisionCommit(
            expectedGateId = pending.id,
            decidedGate = if (approved) pending.approve(null, "Retry", at) else pending.reject(null, "Stop", at),
            nextRun = run.copy(updatedAtEpochMillis = at),
            decisionEvent = ApprovalDecisionReceived(run.id, TaskDefinitionId("task"), pending.id, approved, null, at),
        )

        val outcomes = coroutineScope {
            listOf(
                async { persistence.commitFailureEscalationDecision(commit(true, 20L)) },
                async { persistence.commitFailureEscalationDecision(commit(false, 21L)) },
            ).awaitAll()
        }

        assertEquals(1, outcomes.count { it })
        assertEquals(1, persistence.events.forRun(run.id).filterIsInstance<ApprovalDecisionReceived>().size)
        assertFalse(persistence.commitFailureEscalationDecision(commit(true, 22L)))
    }

    @Test
    fun exportImportAndProjectFilesMatchTheSettingsStore() = runBlocking {
        val settings = SettingsWorkflowPersistence(MapSettings())
        val run = run("run")
        settings.projects.put(project)
        settings.definitions.put(definition)
        settings.runs.put(run)
        settings.roles.put(BuiltInRoles.ImplementationEngineer)
        settings.events.append(TaskStarted(run.id, TaskDefinitionId("task"), 1, 3L))
        settings.approvalGates.put(gate(run.id))
        settings.artifacts.put(artifact(TaskRunId("run-task"), "a", at = 5L))

        val sql = desktopSqlWorkflowPersistence(null)
        sql.importJson(settings.exportJson())
        assertEquals(settings.exportJson(), sql.exportJson())

        val projectFile = settings.exportProjectFile(project.id, savedAtEpochMillis = 10L)
        assertEquals(projectFile, sql.exportProjectFile(project.id, savedAtEpochMillis = 10L))

        val other = desktopSqlWorkflowPersistence(null)
        assertEquals(project, other.importProjectFile(projectFile))
        assertEquals(project, other.importProjectFile(projectFile))
        assertEquals(1, other.events.forRun(run.id).size)
        assertEquals(1, other.artifacts.forRun(run).size)
        assertEquals(1, other.approvalGates.unresolved(run.id).size)

        sql.clearWorkflowData()
        assertTrue(sql.projects.all().isEmpty())
        assertTrue(sql.events.forRun(run.id).isEmpty())
    }

    @Test
    fun settingsStateMovesInOnceWithABackup() = runBlocking {
        val previous = SettingsWorkflowPersistence(MapSettings())
        val run = run("run")
        previous.projects.put(project)
        previous.runs.put(run)
        previous.events.append(TaskStarted(run.id, TaskDefinitionId("task"), 1, 3L))
        val expected = previous.exportJson()
        val backups = mutableListOf<String>()
        val file = tempDatabase()

        val sql = desktopSqlWorkflowPersistence(file, LegacySettingsMigration(previous) { backups += it })
        assertEquals(listOf(run), sql.runs.byProject(project.id))
        assertEquals(1, sql.events.forRun(run.id).size)
        assertEquals(listOf(expected), backups)
        assertTrue(previous.projects.all().isEmpty())

        // A later launch with something new in the old store does not migrate again.
        previous.projects.put(project.copy(id = ProjectId("late")))
        val reopened = desktopSqlWorkflowPersistence(file, LegacySettingsMigration(previous) { backups += it })
        assertEquals(listOf(project), reopened.projects.all())
        assertEquals(1, backups.size)
    }

    @Test
    fun failedMigrationDoesNotBrickTheStoreAndIsRetriedLater() = runBlocking {
        val previous = SettingsWorkflowPersistence(MapSettings())
        previous.projects.put(project)
        val file = tempDatabase()

        val sql = desktopSqlWorkflowPersistence(file, LegacySettingsMigration(previous) { error("disk full") })
        assertTrue(sql.projects.all().isEmpty())
        sql.projects.put(project.copy(id = ProjectId("new")))
        assertEquals(listOf(project), previous.projects.all(), "the old store is left for a retry")

        val backups = mutableListOf<String>()
        val reopened = desktopSqlWorkflowPersistence(file, LegacySettingsMigration(previous) { backups += it })
        assertEquals(setOf(project.id, ProjectId("new")), reopened.projects.all().map { it.id }.toSet())
        assertEquals(1, backups.size)
    }

    @Test
    fun aFailureAfterTheMoveDoesNotImportTheOldCopyAgainOrDuplicateEvents() = runBlocking {
        val backing = MapSettings()
        val inner = SettingsWorkflowPersistence(backing)
        val run = run("run")
        inner.projects.put(project)
        inner.runs.put(run)
        inner.events.append(TaskStarted(run.id, TaskDefinitionId("task"), 1, 3L))
        // The old store cannot be cleared, so its copy stays behind after the data has moved.
        val failingSettings = object : com.russhwolf.settings.Settings by backing {
            override fun remove(key: String) {
                error("settings write failed")
            }
        }
        val previous = SettingsWorkflowPersistence(failingSettings)
        val file = tempDatabase()

        val first = desktopSqlWorkflowPersistence(file, LegacySettingsMigration(previous) { })
        assertEquals(1, first.events.forRun(run.id).size)

        val backups = mutableListOf<String>()
        val reopened = desktopSqlWorkflowPersistence(file, LegacySettingsMigration(previous) { backups += it })
        assertEquals(1, reopened.events.forRun(run.id).size, "events are not imported a second time")
        assertTrue(backups.isEmpty(), "the migration does not run again")
    }
}
