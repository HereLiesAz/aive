package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CONTEXT_PROVENANCE_METADATA_KEY
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.memory.procedural.HabitDecisions
import com.hereliesaz.geministrator.memory.procedural.HabitFormation
import com.hereliesaz.geministrator.memory.procedural.ProcedureDeliberationOutcome
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitDecisionObserverTest {
    private val build = TaskDefinitionId("build")
    private val analyze = TaskDefinitionId("analyze")
    private val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val ci = "github-action:ci.yml"

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "w",
        tasks = listOf(
            TaskDefinition(id = build, name = "Build", objective = "Run CI", roleId = null, executor = TaskExecutor.GitHubAction("ci.yml")),
            TaskDefinition(
                id = analyze,
                name = "Analyze",
                objective = "Read the CI result",
                roleId = BuiltInRoles.QaEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
                dependsOn = setOf(build),
            ),
        ),
    )

    private fun art(text: String, trusted: Boolean) = ArtifactRef(
        id = ArtifactId("a${text.hashCode()}"),
        kind = ArtifactKind.TaskPlan,
        taskRunId = TaskRunId("r"),
        label = "x",
        textContent = text,
        metadata = if (trusted) mapOf(CONTEXT_PROVENANCE_METADATA_KEY to "AgentArtifact") else emptyMap(),
        createdAtEpochMillis = 1L,
    )

    private fun run(report: String, trusted: Boolean = true, status: TaskRunStatus = TaskRunStatus.Completed) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            build to TaskRun(
                TaskRunId("rb"), build, TaskRunStatus.Completed, assignedRoleId = null,
                executor = definition.tasks[0].executor, artifacts = listOf(art("CI output", trusted)),
            ),
            analyze to TaskRun(
                TaskRunId("ra"), analyze, status, assignedRoleId = BuiltInRoles.QaEngineer.id,
                executor = definition.tasks[1].executor, artifacts = listOf(art(report, trusted = true)),
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 88L,
    )

    private fun observe(vararg runs: WorkflowRun): List<ProcedureTrace> = runBlocking {
        val seen = mutableListOf<ProcedureTrace>()
        val observer = HabitDecisionObserver { ProcedureTraceSink { seen += it } }
        runs.forEach { observer.observe(project, definition, it) }
        seen
    }

    @Test
    fun theThreeKindsOfDecisionParseAndAnythingElseIsIgnored() {
        val parsed = HabitDecisions.parse(
            """
            Notes first.
            HABIT-DECISION: $ci | stands | The runner image changed.
            habit decision: $ci | Supersedes | It needs the integration job first.
            HABIT-DECISION: $ci | overrides | Wrong stage. | Use the v2 job. | github-action:ci-v2.yml
            """.trimIndent(),
        )
        assertEquals(
            listOf(ProcedureDeliberationOutcome.Stands, ProcedureDeliberationOutcome.Superseded, ProcedureDeliberationOutcome.Overrides),
            parsed.map { it.outcome },
        )
        assertEquals("Use the v2 job.", parsed[2].alternative)
        assertEquals("github-action:ci-v2.yml", parsed[2].alternativeOperation)
        assertNull(parsed[0].alternative)

        listOf(
            "HABIT-DECISION: not an operation | stands | x",
            "HABIT-DECISION: $ci | maybe | x",
            "HABIT-DECISION: $ci | stands |",
            "HABIT-DECISION: $ci | stands | x | extra",
            "HABIT-DECISION: $ci | overrides | x",
            "HABIT-DECISION: $ci | overrides | x | y | Not A Name",
            "HABIT-DECISION: $ci | stands",
        ).forEach { assertTrue(HabitDecisions.parse(it).isEmpty(), it) }
    }

    @Test
    fun aDecisionBecomesADeliberationReceiptOncePerTaskRunAndOperation() {
        val report = run("HABIT-DECISION: $ci | supersedes | It needs the integration job first.")
        val receipt = observe(report, report).single()
        assertEquals(ProcedureTracePhase.Deliberation, receipt.phase)
        assertEquals(ci, receipt.operation)
        assertEquals(ProcedureDeliberationOutcome.Superseded, receipt.deliberation?.outcome)
        assertEquals(BuiltInRoles.QaEngineer.id.value, receipt.deliberation?.author)
        assertEquals("ra", receipt.taskRunId)
        assertEquals(88L, receipt.recordedAtEpochMillis)
    }

    @Test
    fun untrustedContentInFrontOfTheTaskMeansNothingIsRecorded() {
        assertTrue(observe(run("HABIT-DECISION: $ci | supersedes | x.", trusted = false)).isEmpty())
    }

    @Test
    fun aTaskThatReadUntrustedContentItselfRecordsNothingWhateverItsDeclaredInputs() {
        val text = "HABIT-DECISION: $ci | supersedes | x."
        val clean = run(text)
        fun withOwn(vararg metadata: Pair<String, String>) = clean.copy(
            taskRuns = clean.taskRuns + (analyze to clean.taskRuns.getValue(analyze).copy(
                artifacts = listOf(art(text, trusted = true).copy(metadata = mapOf(*metadata))),
            )),
        )
        // Its own artifact was made from external content (a tool read, not a declared input).
        assertTrue(
            observe(
                withOwn(
                    CONTEXT_PROVENANCE_METADATA_KEY to "AgentArtifact",
                    com.hereliesaz.geministrator.domain.CONTEXT_UNTRUSTED_INPUT_METADATA_KEY to "true",
                ),
            ).isEmpty(),
        )
        // Or it is external, or unlabelled (which counts as external).
        assertTrue(observe(withOwn(CONTEXT_PROVENANCE_METADATA_KEY to "ExternalContent")).isEmpty())
        assertTrue(observe(withOwn()).isEmpty())
    }

    @Test
    fun unfinishedTasksAndPlainTextLeaveNothing() {
        assertTrue(observe(run("HABIT-DECISION: $ci | stands | x.", status = TaskRunStatus.Running)).isEmpty())
        assertTrue(observe(run("The build failed in step two.")).isEmpty())
    }

    @Test
    fun aModelsSupersedingDecisionStartsANewVersionOfTheHabit() {
        val uses = (1..3).map { n ->
            ProcedureTrace(
                workflowId = "run$n", projectId = "p", taskRunId = "tr$n", taskDefinitionId = "build", attempt = 1,
                phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
                outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = n.toLong(), operation = ci,
            )
        }
        val decision = observe(run("HABIT-DECISION: $ci | supersedes | It needs the integration job first.")).single()
        val habit = HabitFormation.form(uses + decision).single()
        assertEquals(2, habit.version)
        assertEquals(3, habit.earlierUses)
        assertEquals(0, habit.uses)
    }
}
