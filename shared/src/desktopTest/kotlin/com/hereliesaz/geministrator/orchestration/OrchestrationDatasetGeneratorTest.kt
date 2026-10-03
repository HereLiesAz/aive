package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestrationDatasetGeneratorTest {
    private val json = OrchestrationDatasetGenerator.json

    @Test
    fun everyRoleHasUniqueIdsAndAllFourSplits() {
        OrchestrationUtilityRole.entries.forEach { role ->
            val rows = OrchestrationDatasetGenerator.generate(role)
            assertEquals(rows.size, rows.map { it.id }.distinct().size, "duplicate ids for $role")
            assertEquals(setOf("train", "validation", "test", "adversarial"), rows.map { it.split }.toSet(), "splits for $role")
        }
    }

    @Test
    fun generationIsDeterministic() {
        OrchestrationUtilityRole.entries.forEach { role ->
            assertEquals(OrchestrationDatasetGenerator.generate(role), OrchestrationDatasetGenerator.generate(role))
        }
    }

    /**
     * The corpus is only useful if a specialist sees exactly the bytes the runtime sends and its
     * label decodes through the same guard. Replaying each label through the guard proves both.
     */
    @Test
    fun inputsAreByteIdenticalToRuntimeRequestsAndLabelsDecode() {
        OrchestrationUtilityRole.entries.forEach { role ->
            OrchestrationDatasetGenerator.generate(role).forEach { row ->
                var received: String? = null
                val guarded = GuardedModelBackedOrchestrationUtilities(
                    runtime = LocalOrchestrationSpecialistRuntime { calledRole, inputJson ->
                        assertEquals(role, calledRole)
                        received = inputJson
                        row.expected
                    },
                )
                replay(role, row, guarded)
                assertEquals(row.input, received, "runtime request differs from corpus input for ${row.id}")
            }
        }
    }

    /**
     * Opt-in export: `AIVE_EXPORT_ORCHESTRATION_DATASETS=<dir> ./gradlew :shared:desktopTest --tests '*OrchestrationDatasetGeneratorTest'`.
     * `AIVE_ORCHESTRATION_DATASET_ROWS` overrides the regular rows per role.
     */
    @Test
    fun exportWhenRequested() {
        val target = System.getenv(EXPORT_ENV)?.takeIf(String::isNotBlank) ?: return
        val root = File(target).apply { mkdirs() }
        val regularRows = System.getenv(ROWS_ENV)?.toIntOrNull() ?: DEFAULT_EXPORT_ROWS
        OrchestrationUtilityRole.entries.forEach { role ->
            val slug = OrchestrationSpecialistIds.specialistId(role).substringAfter(':')
            File(root, "$slug.jsonl").writeText(OrchestrationDatasetGenerator.generate(role, regularRows).toJsonl())
            File(root, "$slug.config.json").writeText(json.encodeToString(OrchestrationDatasetGenerator.config(role)) + "\n")
        }
        assertTrue(root.listFiles().orEmpty().size >= OrchestrationUtilityRole.entries.size * 2)
    }

    private fun replay(role: OrchestrationUtilityRole, row: OrchestrationDatasetRow, family: LocalOrchestrationUtilityFamily) {
        val input = row.input
        val expected = row.expected
        when (role) {
            OrchestrationUtilityRole.MemoryQueryComposer ->
                assertEquals(json.decodeFromString<MemoryQueryPlan>(expected), family.composeMemoryQueries(json.decodeFromString(input)))
            OrchestrationUtilityRole.ContextPacker ->
                assertEquals(json.decodeFromString<ContextPackingPlan>(expected), family.packContext(json.decodeFromString<ContextPackingModelInput>(input).toInput()))
            OrchestrationUtilityRole.AgentRouter ->
                assertEquals(json.decodeFromString<AgentRoute>(expected), family.routeAgent(json.decodeFromString<AgentRoutingModelInput>(input).toInput()))
            OrchestrationUtilityRole.ToolRouter ->
                assertEquals(json.decodeFromString<ToolRoute>(expected), family.routeTool(json.decodeFromString<ToolRoutingModelInput>(input).toInput()))
            OrchestrationUtilityRole.HandoffComposer ->
                assertEquals(json.decodeFromString<HandoffPacket>(expected), family.composeHandoff(json.decodeFromString(input)))
            OrchestrationUtilityRole.EscalationGate ->
                assertEquals(json.decodeFromString<EscalationResult>(expected), family.evaluateEscalation(json.decodeFromString<CapabilityAssessmentModelInput>(input).assessment))
            OrchestrationUtilityRole.CompletionGate ->
                assertEquals(json.decodeFromString<CompletionResult>(expected), family.evaluateCompletion(json.decodeFromString(input)))
            OrchestrationUtilityRole.ExecutionStateSummarizer -> {
                val (definition, run) = rebuild(json.decodeFromString<ExecutionStateModelInput>(input))
                assertEquals(json.decodeFromString<ExecutionStateSummary>(expected), family.summarizeExecution(definition, run))
            }
            OrchestrationUtilityRole.VerificationPlanner ->
                assertEquals(json.decodeFromString<VerificationPlan>(expected), family.planVerification(json.decodeFromString(input)))
        }
    }

    /** The compact input is a projection; any run with the same projection summarizes identically. */
    private fun rebuild(input: ExecutionStateModelInput): Pair<WorkflowDefinition, WorkflowRun> {
        val tasks = input.tasks.mapIndexed { i, task ->
            TaskDefinition(
                id = TaskDefinitionId("t$i"),
                name = task.name,
                objective = task.name,
                roleId = null,
                executor = TaskExecutor.TestRunner(),
            )
        }
        val definition = WorkflowDefinition(id = WorkflowDefinitionId("w"), name = "w", tasks = tasks)
        val runs = input.tasks.mapIndexed { i, task ->
            val runId = TaskRunId("t$i-run")
            tasks[i].id to TaskRun(
                id = runId,
                taskDefinitionId = tasks[i].id,
                status = task.status,
                assignedRoleId = null,
                executor = tasks[i].executor,
                artifacts = task.artifacts.map {
                    ArtifactRef(id = ArtifactId(it), kind = ArtifactKind.TestResult, taskRunId = runId, label = it, textContent = it, createdAtEpochMillis = 1L)
                },
                blockingReason = task.blockingCode?.let { BlockingReason(it, task.blockingMessage.orEmpty()) },
            )
        }.toMap()
        val run = WorkflowRun(
            id = WorkflowRunId("r"),
            projectId = ProjectId("p"),
            workflowDefinitionId = definition.id,
            objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = runs,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        return definition to run
    }

    private companion object {
        const val EXPORT_ENV = "AIVE_EXPORT_ORCHESTRATION_DATASETS"
        const val ROWS_ENV = "AIVE_ORCHESTRATION_DATASET_ROWS"

        /** Regular rows per role in an exported training corpus (adversarial rows are added on top). */
        const val DEFAULT_EXPORT_ROWS = 2_000
    }
}
