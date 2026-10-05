package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchLineageStateTest {
    private val a = WorkflowRunId("run-a")
    private val b = WorkflowRunId("run-b")
    private val c = WorkflowRunId("run-c")

    @Test
    fun newLaunchIsARootAndIgnoresTaps() {
        val state = LaunchLineageState().toggle(a)
        assertNull(state.validationError)
        assertEquals(emptyList(), state.continuesWorkflowRunIds)
    }

    @Test
    fun continueNeedsExactlyOneRunAndReplacesTheChoice() {
        val empty = LaunchLineageState().withMode(LaunchLineageMode.Continue)
        assertNotNull(empty.validationError)
        assertFailsWith<IllegalStateException> { empty.continuesWorkflowRunIds }
        val picked = empty.toggle(a).toggle(b)
        assertEquals(listOf(b), picked.continuesWorkflowRunIds)
        assertNotNull(picked.toggle(b).validationError, "tapping the chosen run again clears it")
    }

    @Test
    fun combineNeedsTwoOrMoreAndKeepsSelectionOrder() {
        val one = LaunchLineageState().withMode(LaunchLineageMode.Combine).toggle(c)
        assertNotNull(one.validationError)
        val three = one.toggle(a).toggle(b)
        assertEquals(listOf(c, a, b), three.continuesWorkflowRunIds)
        assertEquals(listOf(c, b), three.toggle(a).continuesWorkflowRunIds)
    }

    @Test
    fun switchingModesNarrowsOrClearsTheSelection() {
        val combined = LaunchLineageState().withMode(LaunchLineageMode.Combine).toggle(a).toggle(b)
        assertEquals(listOf(a), combined.withMode(LaunchLineageMode.Continue).selected)
        assertEquals(LaunchLineageState(), combined.withMode(LaunchLineageMode.New))
        assertEquals(listOf(a), LaunchLineageState.continuing(a).continuesWorkflowRunIds)
    }

    @Test
    fun selectionsOfRunsNoLongerOfferedAreDropped() {
        val state = LaunchLineageState(LaunchLineageMode.Combine, listOf(a, b)).retainOnly(listOf(b, c))
        assertEquals(listOf(b), state.selected)
        assertNotNull(state.validationError)
    }

    @Test
    fun onlyTerminalRunsAreOfferedForContinueFromTheirView() {
        assertTrue(WorkflowRunStatus.Completed.isFinished())
        assertTrue(WorkflowRunStatus.Failed.isFinished())
        assertTrue(WorkflowRunStatus.Cancelled.isFinished())
        assertFalse(WorkflowRunStatus.Running.isFinished())
        assertFalse(WorkflowRunStatus.AwaitingHuman.isFinished())
    }

    @Test
    fun launchableRunsListEveryRunNewestFirstWithWorkflowProjectAndStatus() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            runtime.launchStarterWorkflow("Alpha", "First")
            val first = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation
            delay(3)
            runtime.launchStarterWorkflow("Beta", "Second")
            val second = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation

            val runs = runtime.loadLaunchableRuns()
            assertEquals(listOf(second.run.id, first.run.id), runs.map(LaunchableRun::id))
            val top = runs.first()
            assertEquals("Beta", top.projectName)
            assertEquals(second.definition.name, top.workflowName)
            assertEquals(second.run.status, top.status)
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(top.dateLabel))

            val state = LaunchLineageState().withMode(LaunchLineageMode.Combine).toggle(runs[0].id).toggle(runs[1].id)
            runtime.launchStarterWorkflow("Beta", "Merge", continuesWorkflowRunIds = state.continuesWorkflowRunIds)
            val merged = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation.run
            assertEquals(listOf(second.run.id, first.run.id), merged.parentWorkflowRunIds)
        } finally {
            scope.cancel()
        }
    }
}
