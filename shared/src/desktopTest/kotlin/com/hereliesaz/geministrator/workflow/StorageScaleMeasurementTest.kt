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
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.persistence.desktopSqlWorkflowPersistence
import java.nio.file.Files
import com.russhwolf.settings.MapSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Measures the Settings and SQL workflow stores against the triggers in docs/architecture/STORAGE_SCALE.md.
 * The snapshot holds every run, so per-write cost grows with history, not with the active run.
 * Results go to build/reports/storage-scale.txt; the test only fails if persistence itself fails.
 *
 * The Settings store rewrites its whole snapshot on every write, so the full sweep is quadratic and
 * takes most of an hour. Ordinary test runs do a small smoke sweep; set AIVE_STORAGE_SCALE=full to
 * take the measurements recorded in STORAGE_SCALE.md.
 */
class StorageScaleMeasurementTest {
    @Test
    fun measureSnapshotGrowthWithRunHistory() = runBlocking {
        val report = StringBuilder("store\truns\tstored_kb\tput_ms_median\tget_ms_median\n")
        for (store in listOf("settings", "sql")) for (runCount in RUN_COUNTS) {
            val settings = MapSettings()
            val databaseFile = Files.createTempDirectory("aive-scale").toFile().resolve("workflows.db")
            val persistence: WorkflowPersistence =
                if (store == "sql") desktopSqlWorkflowPersistence(databaseFile) else SettingsWorkflowPersistence(settings)
            val project = Project(ProjectId("p"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
            persistence.projects.put(project)
            val definition = definition()
            persistence.definitions.put(definition)
            var lastRun = WorkflowRunFactory.create(
                definition = definition,
                workflowRunId = WorkflowRunId("seed"),
                projectId = project.id,
                objective = "seed",
                nowEpochMillis = 1L,
                taskRunIdFactory = { TaskRunId("seed-t") },
            )
            repeat(runCount) { index ->
                var taskIndex = 0
                lastRun = WorkflowRunFactory.create(
                    definition = definition,
                    workflowRunId = WorkflowRunId("run-$index"),
                    projectId = project.id,
                    objective = OBJECTIVE,
                    nowEpochMillis = index.toLong(),
                    taskRunIdFactory = { TaskRunId("run-$index-task-${taskIndex++}") },
                )
                persistence.runs.put(lastRun)
                repeat(TASKS * ARTIFACTS_PER_TASK) { artifact ->
                    persistence.artifacts.put(
                        ArtifactRef(
                            id = ArtifactId("run-$index-artifact-$artifact"),
                            kind = ArtifactKind.CommandOutput,
                            taskRunId = TaskRunId("run-$index-task-${artifact / ARTIFACTS_PER_TASK}"),
                            label = "Output $artifact",
                            textContent = ARTIFACT_TEXT,
                            createdAtEpochMillis = index.toLong(),
                        ),
                    )
                }
            }
            val storedKb = if (store == "sql") {
                databaseFile.length() / 1024
            } else {
                settings.keys.sumOf { key -> settings.getStringOrNull(key)?.length ?: 0 } / 1024L
            }
            val putTimes = (1..9).map { timed { persistence.runs.put(lastRun) } }.sorted()
            val getTimes = (1..9).map { timed { persistence.runs.get(lastRun.id) } }.sorted()
            report.append("$store\t$runCount\t$storedKb\t${putTimes[4]}\t${getTimes[4]}\n")
        }
        File("build/reports").mkdirs()
        File("build/reports/storage-scale.txt").writeText(report.toString())
        println(report)
    }

    private suspend fun timed(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun definition() = WorkflowDefinition(
        id = WorkflowDefinitionId("workflow"),
        name = "Workflow",
        tasks = (0 until TASKS).map { index ->
            TaskDefinition(
                id = TaskDefinitionId("task-$index"),
                name = "Task $index",
                objective = "Implement part $index of the objective and verify it.",
                roleId = BuiltInRoles.ImplementationEngineer.id,
            )
        },
    )

    private companion object {
        val RUN_COUNTS = if (System.getenv("AIVE_STORAGE_SCALE") == "full") listOf(10, 50, 150, 300) else listOf(5)
        const val TASKS = 15
        const val ARTIFACTS_PER_TASK = 3
        val OBJECTIVE = "Build the feature described in the issue, with tests and documentation. ".repeat(8)

        // A typical provider text artifact: a few KB of summary, diff or log.
        val ARTIFACT_TEXT = "line of provider output describing changes and evidence\n".repeat(70)
    }
}
