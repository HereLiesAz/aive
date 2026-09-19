package com.hereliesaz.geministrator.workflow

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
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsWorkflowPersistenceTest {
    @Test
    fun repositoriesRoundTripAcrossPersistenceRecreation() = runBlocking {
        val settings = MapSettings()
        val first = SettingsWorkflowPersistence(settings)
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val definition = WorkflowDefinition(
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
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Objective",
            nowEpochMillis = 2L,
            taskRunIdFactory = { TaskRunId("task-run") },
        )

        first.projects.put(project)
        first.definitions.put(definition)
        first.runs.put(run)
        first.roles.put(BuiltInRoles.ImplementationEngineer)
        first.events.append(TaskStarted(run.id, TaskDefinitionId("task"), 1, 3L))
        first.approvalGates.put(
            ApprovalGate(
                id = com.hereliesaz.geministrator.domain.ApprovalGateId("gate"),
                workflowRunId = run.id,
                taskDefinitionId = TaskDefinitionId("task"),
                kind = ApprovalGateKind.PlanApproval,
                reason = "Review plan",
                createdAtEpochMillis = 4L,
            ),
        )

        val restored = SettingsWorkflowPersistence(settings)

        assertEquals(project, restored.projects.get(project.id))
        assertEquals(definition, restored.definitions.get(definition.id))
        assertEquals(run, restored.runs.get(run.id))
        assertEquals(
            BuiltInRoles.ImplementationEngineer,
            restored.roles.get(BuiltInRoles.ImplementationEngineer.id),
        )
        assertEquals(1, restored.events.forRun(run.id).size)
        assertNotNull(
            restored.approvalGates.get(
                com.hereliesaz.geministrator.domain.ApprovalGateId("gate"),
            ),
        )
        assertEquals(SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION, restored.snapshotVersion())
    }

    @Test
    fun legacyV2ArtifactIdsMigrateToAttemptScopedIdentityAndWriteBackImmediately() = runBlocking {
        val settings = MapSettings()
        val persistence = SettingsWorkflowPersistence(settings)
        val taskId = TaskDefinitionId("task")
        val taskRunId = TaskRunId("task-run")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow-artifacts"),
            name = "Artifacts",
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Task",
                    objective = "Do work",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
            ),
        )
        val baseRun = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run-artifacts"),
            projectId = ProjectId("project"),
            objective = "Objective",
            nowEpochMillis = 2L,
            taskRunIdFactory = { taskRunId },
        )
        val legacyProviderArtifact = ArtifactRef(
            id = ArtifactId("task-run:CodeChange:0"),
            kind = ArtifactKind.CodeChange,
            taskRunId = taskRunId,
            label = "Patch",
            textContent = "diff",
            createdAtEpochMillis = 3L,
        )
        val legacyGitHubArtifact = ArtifactRef(
            id = ArtifactId("task-run:github-action:artifact-7"),
            kind = ArtifactKind.CommandOutput,
            taskRunId = taskRunId,
            label = "CI output",
            uri = "https://example.test/artifact-7",
            createdAtEpochMillis = 4L,
        )
        val legacyRun = baseRun.copy(
            taskRuns = baseRun.taskRuns + (
                taskId to baseRun.taskRuns.getValue(taskId).copy(
                    artifacts = listOf(legacyProviderArtifact, legacyGitHubArtifact),
                )
            ),
        )

        persistence.runs.put(legacyRun)
        persistence.artifacts.put(legacyProviderArtifact)
        persistence.artifacts.put(legacyGitHubArtifact)
        val encoded = assertNotNull(settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY))
        settings.putString(
            SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY,
            encoded.replace(
                "\"version\":${SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION}",
                "\"version\":2",
            ),
        )

        val restored = SettingsWorkflowPersistence(settings)
        val migratedRun = assertNotNull(restored.runs.get(legacyRun.id))
        val migratedIds = migratedRun.taskRuns.getValue(taskId).artifacts.map { it.id }.toSet()
        val providerId = ArtifactId("task-run:CodeChange:1:0")
        val githubId = ArtifactId("task-run:github-action:1:artifact-7")

        assertEquals(setOf(providerId, githubId), migratedIds)
        assertNotNull(restored.artifacts.get(providerId))
        assertNotNull(restored.artifacts.get(githubId))
        assertNull(restored.artifacts.get(legacyProviderArtifact.id))
        assertNull(restored.artifacts.get(legacyGitHubArtifact.id))
        assertEquals(3, restored.snapshotVersion())
        assertTrue(
            assertNotNull(settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY))
                .contains("\"version\":3"),
        )
    }

    @Test
    fun legacyStorageKeyIsPromotedAndRetiredOnRead() = runBlocking {
        val settings = MapSettings()
        val persistence = SettingsWorkflowPersistence(settings)
        val project = Project(
            id = ProjectId("legacy-project"),
            name = "Legacy Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        persistence.projects.put(project)
        val encoded = assertNotNull(settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY))
        settings.remove(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY)
        settings.putString(SettingsWorkflowPersistence.LEGACY_STORAGE_KEY_V1, encoded)

        val restored = SettingsWorkflowPersistence(settings)

        assertEquals(project, restored.projects.get(project.id))
        assertNotNull(settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY))
        assertFalse(settings.hasKey(SettingsWorkflowPersistence.LEGACY_STORAGE_KEY_V1))
    }

    @Test
    fun customStorageNamespaceDoesNotConsumeDefaultLegacyData() = runBlocking {
        val settings = MapSettings()
        val defaultPersistence = SettingsWorkflowPersistence(settings)
        val project = Project(
            id = ProjectId("legacy-default-project"),
            name = "Default Legacy Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        defaultPersistence.projects.put(project)
        val encoded = assertNotNull(settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY))
        settings.remove(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY)
        settings.putString(SettingsWorkflowPersistence.LEGACY_STORAGE_KEY_V1, encoded)

        val customKey = "test.workflow.persistence"
        val custom = SettingsWorkflowPersistence(settings, storageKey = customKey)

        assertTrue(custom.projects.all().isEmpty())
        assertFalse(settings.hasKey(customKey))
        assertEquals(encoded, settings.getStringOrNull(SettingsWorkflowPersistence.LEGACY_STORAGE_KEY_V1))

        custom.clearWorkflowData()
        assertEquals(encoded, settings.getStringOrNull(SettingsWorkflowPersistence.LEGACY_STORAGE_KEY_V1))

        val restoredDefault = SettingsWorkflowPersistence(settings)
        assertEquals(project, restoredDefault.projects.get(project.id))
        assertFalse(settings.hasKey(SettingsWorkflowPersistence.LEGACY_STORAGE_KEY_V1))
    }

    @Test
    fun futureSchemaIsRejectedWithoutOverwritingStoredData() = runBlocking {
        val settings = MapSettings()
        val persistence = SettingsWorkflowPersistence(settings)
        val project = Project(
            id = ProjectId("future-project"),
            name = "Future Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        persistence.projects.put(project)
        val current = assertNotNull(settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY))
        val futureVersion = SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION + 1
        val future = current.replace(
            "\"version\":${SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION}",
            "\"version\":$futureVersion",
        )
        settings.putString(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY, future)

        val restored = SettingsWorkflowPersistence(settings)
        val failure = assertFailsWith<IllegalArgumentException> {
            restored.snapshotVersion()
        }

        assertTrue(failure.message.orEmpty().contains("Unsupported workflow persistence schema $futureVersion"))
        assertEquals(future, settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY))
    }

    @Test
    fun eventJournalDoesNotRewriteWholeSnapshotForEveryAppend() = runBlocking {
        val settings = MapSettings()
        val persistence = SettingsWorkflowPersistence(settings)
        val project = Project(
            id = ProjectId("project-journal"),
            name = "Journal Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val runId = WorkflowRunId("run/with:characters")
        val taskId = TaskDefinitionId("task")

        persistence.projects.put(project)
        val snapshotBeforeEvents = assertNotNull(
            settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY),
        )

        repeat(200) { index ->
            persistence.events.append(
                TaskStarted(
                    workflowRunId = runId,
                    taskDefinitionId = taskId,
                    attempt = index + 1,
                    occurredAtEpochMillis = index.toLong(),
                ),
            )
        }

        assertEquals(
            snapshotBeforeEvents,
            settings.getStringOrNull(SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY),
        )
        assertTrue(settings.keys.any { it.startsWith("${SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY}.events.") })

        val restored = SettingsWorkflowPersistence(settings)
        val events = restored.events.forRun(runId)
        assertEquals(200, events.size)
        assertEquals((1..200).toList(), events.map { (it as TaskStarted).attempt })

        restored.clearWorkflowData()
        assertTrue(restored.events.forRun(runId).isEmpty())
        assertTrue(settings.keys.none { it.startsWith("${SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY}.events.") })
    }

    @Test
    fun iveProjectRoundTripPreservesProjectStateWithoutReplacingOtherProjects() = runBlocking {
        val source = SettingsWorkflowPersistence(MapSettings())
        val project = Project(
            id = ProjectId("portable-project"),
            name = "Portable Project",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("portable-workflow"),
            name = "Portable Workflow",
            tasks = listOf(
                TaskDefinition(
                    id = TaskDefinitionId("portable-task"),
                    name = "Portable Task",
                    objective = "Survive export and import",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
            ),
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("portable-run"),
            projectId = project.id,
            objective = "Portable objective",
            nowEpochMillis = 30L,
            taskRunIdFactory = { TaskRunId("portable-task-run") },
        )
        source.projects.put(project)
        source.definitions.put(definition)
        source.runs.put(run)
        source.roles.put(BuiltInRoles.ImplementationEngineer)
        source.events.append(
            TaskStarted(
                workflowRunId = run.id,
                taskDefinitionId = TaskDefinitionId("portable-task"),
                attempt = 1,
                occurredAtEpochMillis = 31L,
            ),
        )

        val encoded = source.exportProjectFile(project.id, savedAtEpochMillis = 40L)
        assertTrue(encoded.contains("\"format\":\"the-aive-project\""))

        val destination = SettingsWorkflowPersistence(MapSettings())
        val existingProject = Project(
            id = ProjectId("existing-project"),
            name = "Existing Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )
        destination.projects.put(existingProject)

        val imported = destination.importProjectFile(encoded)

        assertEquals(project, imported)
        assertEquals(project, destination.projects.get(project.id))
        assertEquals(existingProject, destination.projects.get(existingProject.id))
        assertEquals(definition, destination.definitions.get(definition.id))
        assertEquals(run, destination.runs.get(run.id))
        assertEquals(1, destination.events.forRun(run.id).size)
        assertEquals(
            BuiltInRoles.ImplementationEngineer,
            destination.roles.get(BuiltInRoles.ImplementationEngineer.id),
        )
    }

}
