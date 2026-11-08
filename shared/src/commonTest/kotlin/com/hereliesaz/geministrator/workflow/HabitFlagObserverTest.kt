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
import com.hereliesaz.geministrator.memory.procedural.CautionClerk
import com.hereliesaz.geministrator.memory.procedural.CautionRecognizer
import com.hereliesaz.geministrator.memory.procedural.HabitFormation
import com.hereliesaz.geministrator.memory.procedural.ProcedureFlagSource
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HabitFlagObserverTest {
    private val build = TaskDefinitionId("build")
    private val analyze = TaskDefinitionId("analyze")
    private val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private fun definition(source: TaskExecutor) = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "w",
        tasks = listOf(
            TaskDefinition(id = build, name = "Build", objective = "Run CI", roleId = null, executor = source),
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

    private fun run(
        definition: WorkflowDefinition,
        report: String,
        analyzeStatus: TaskRunStatus = TaskRunStatus.Completed,
        trusted: Boolean = true,
    ) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            build to TaskRun(
                TaskRunId("rb"), build, TaskRunStatus.Completed, assignedRoleId = null,
                executor = definition.tasks[0].executor,
                artifacts = listOf(art("CI output", trusted)),
            ),
            analyze to TaskRun(
                TaskRunId("ra"), analyze, analyzeStatus, assignedRoleId = BuiltInRoles.QaEngineer.id,
                executor = definition.tasks[1].executor,
                artifacts = listOf(art(report, trusted = true)),
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 88L,
    )

    private fun observe(definition: WorkflowDefinition, vararg runs: WorkflowRun): List<ProcedureTrace> = runBlocking {
        val seen = mutableListOf<ProcedureTrace>()
        val observer = HabitFlagObserver { ProcedureTraceSink { seen += it } }
        runs.forEach { observer.observe(project, definition, it) }
        seen
    }

    @Test
    fun anAgentsWarningAboutAnExecutorsResultBecomesAFlagOnItsHabitOnce() {
        val def = definition(TaskExecutor.GitHubAction("ci.yml"))
        val report = run(def, "Summary below. The build passed but it assumed a clean tree.")
        val flags = observe(def, report, report)
        val flag = flags.single()
        assertEquals(ProcedureTracePhase.Flag, flag.phase)
        assertEquals("github-action:ci.yml", flag.operation)
        assertEquals(ProcedureFlagSource.Handled, flag.flag?.source)
        assertEquals(BuiltInRoles.QaEngineer.id.value, flag.flag?.author)
        assertEquals("The build passed but it assumed a clean tree.", flag.flag?.text)
        assertFalse(flag.flag?.tainted == true)
        assertEquals("ra", flag.taskRunId)
        assertEquals(88L, flag.recordedAtEpochMillis)
    }

    @Test
    fun theFlagRecordsThatUntrustedContentWasInFrontOfTheAgent() {
        val def = definition(TaskExecutor.GitHubAction("ci.yml"))
        val flags = observe(def, run(def, "The run failed in step two.", trusted = false))
        assertTrue(flags.single().flag?.tainted == true)
    }

    @Test
    fun calmReportsUnfinishedAgentsAndUnnamedExecutorsLeaveNothing() {
        val named = definition(TaskExecutor.GitHubAction("ci.yml"))
        assertTrue(observe(named, run(named, "The build passed with no errors. All tests passed.")).isEmpty())
        assertTrue(observe(named, run(named, "The build failed.", analyzeStatus = TaskRunStatus.Running)).isEmpty())

        // A test command has no name, so there is no habit to flag.
        val unnamed = definition(TaskExecutor.TestRunner("run the suite"))
        assertTrue(observe(unnamed, run(unnamed, "The build failed.")).isEmpty())
    }

    @Test
    fun aFlagIsNeverCountedAsAUseAndShowsOnTheHabit() {
        val def = definition(TaskExecutor.GitHubAction("ci.yml"))
        val receipts = (1..3).map { n ->
            ProcedureTrace(
                workflowId = "run$n", projectId = "p", taskRunId = "tr$n", taskDefinitionId = "build", attempt = 1,
                phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
                outcome = com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome.Completed,
                recordedAtEpochMillis = n.toLong(), operation = "github-action:ci.yml",
            )
        }
        val flag = observe(def, run(def, "The build failed in step two.")).single()
        val habit = HabitFormation.form(receipts + flag).single()
        assertEquals(3, habit.uses)
        assertEquals(1, habit.flags.handled)
        assertEquals(0, habit.flags.used + habit.flags.keeper)
        assertEquals("The build failed in step two.", habit.flags.newest.single().text)
    }

    @Test
    fun anAgentThatPreparedAFinishedRunHasItsCautionsRecordedAsAUsersFlag() {
        val plan = TaskDefinitionId("plan")
        val def = WorkflowDefinition(
            id = WorkflowDefinitionId("w"),
            name = "w",
            tasks = listOf(
                TaskDefinition(
                    id = plan, name = "Plan", objective = "Choose the CI run", roleId = BuiltInRoles.QaEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
                ),
                TaskDefinition(
                    id = build, name = "Build", objective = "Run CI", roleId = null,
                    executor = TaskExecutor.GitHubAction("ci.yml"), dependsOn = setOf(plan),
                ),
            ),
        )
        val finished = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = def.id,
            objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                plan to TaskRun(
                    TaskRunId("rp"), plan, TaskRunStatus.Completed, assignedRoleId = BuiltInRoles.QaEngineer.id,
                    executor = def.tasks[0].executor,
                    artifacts = listOf(art("I chose the CI run, but it assumes a clean tree.", trusted = true)),
                ),
                build to TaskRun(
                    TaskRunId("rb"), build, TaskRunStatus.Completed, assignedRoleId = null,
                    executor = def.tasks[1].executor, artifacts = listOf(art("CI output", trusted = true)),
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 77L,
        )
        val flags = observe(def, finished, finished)
        val flag = flags.single()
        assertEquals(ProcedureFlagSource.Used, flag.flag?.source)
        assertEquals("github-action:ci.yml", flag.operation)
        assertEquals("rp", flag.taskRunId)
        // The run has not finished, so the window has not opened.
        val running = finished.copy(taskRuns = finished.taskRuns + (build to finished.taskRuns.getValue(build).copy(status = TaskRunStatus.Running)))
        assertTrue(observe(def, running).isEmpty())
        // The two windows are counted apart on the habit.
        val habit = HabitFormation.form(
            (1..3).map { n ->
                ProcedureTrace(
                    workflowId = "x$n", projectId = "p", taskRunId = "t$n", taskDefinitionId = "build", attempt = 1,
                    phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
                    outcome = com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome.Completed,
                    recordedAtEpochMillis = n.toLong(), operation = "github-action:ci.yml",
                )
            } + flag,
        ).single()
        assertEquals(1, habit.flags.used)
        assertEquals(0, habit.flags.handled)
    }

    @Test
    fun aClerkAddsRecallForASentenceTheRecognizerSetAsideAndNeverRewritesIt() {
        val def = definition(TaskExecutor.GitHubAction("ci.yml"))
        // No warning cue the recognizer keeps: it is a plain remark about the run.
        val report = run(def, "The build ran against the old fixtures. Everything else went as planned.")
        assertTrue(observe(def, report).isEmpty())

        val asked = mutableListOf<String>()
        val seen = mutableListOf<ProcedureTrace>()
        runBlocking {
            HabitFlagObserver(
                sink = { ProcedureTraceSink { seen += it } },
                clerk = { CautionClerk { sentence, _ -> asked += sentence; "old fixtures" in sentence } },
            ).observe(project, def, report)
        }
        assertEquals("The build ran against the old fixtures.", seen.single().flag?.text)
        assertTrue(asked.isNotEmpty())

        // A clerk that fails is as good as none, and a calm outcome is never put to it.
        val failing = mutableListOf<ProcedureTrace>()
        runBlocking {
            HabitFlagObserver(clerk = { CautionClerk { _, _ -> error("down") } }, sink = { ProcedureTraceSink { failing += it } })
                .observe(project, def, report)
        }
        assertTrue(failing.isEmpty())
        assertTrue(CautionRecognizer.candidates("The build passed with no errors.", listOf("ci")).isEmpty())
    }
}

