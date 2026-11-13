package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
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
import com.hereliesaz.geministrator.domain.WorkflowSpendPause
import com.hereliesaz.geministrator.queen.QueenNote
import com.hereliesaz.geministrator.queen.QueenAction
import com.hereliesaz.geministrator.queen.QueenSuggestionRef
import com.hereliesaz.geministrator.queen.QueenVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The queen's suggestions in the one Messages list and the badge: they count, once each, and never alongside a freeze or a stop. */
class QueenSuggestionRowsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "Swarm",
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("a"), name = "Task a", objective = "Do a",
                roleId = engineer.id, executor = TaskExecutor.RoleAgent(engineer.id),
            ),
        ),
    )

    private fun run(spend: WorkflowSpendPause? = null) = WorkflowRun(
        id = WorkflowRunId("r1"),
        projectId = ProjectId("p"),
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            TaskDefinitionId("a") to TaskRun(
                id = TaskRunId("r-a"), taskDefinitionId = TaskDefinitionId("a"), status = TaskRunStatus.Running, assignedRoleId = engineer.id,
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        spendPause = spend,
    )

    private fun suggestion(id: String) = QueenNote(
        id = id,
        voice = QueenVoice.SuggestRaiseBudget,
        args = listOf("Engineer", "85"),
        suggestion = QueenSuggestionRef(id, QueenAction.RaiseBudget, confirm = false),
    )

    private fun presentation(run: WorkflowRun, vararg notes: QueenNote) = LiveWorkflowPresentation(
        project = Project(run.projectId, "P", createdAtEpochMillis = 0, updatedAtEpochMillis = 0),
        definition = definition,
        run = run,
        roles = listOf(engineer),
        queen = notes.toList(),
    )

    @Test
    fun oneSuggestionCountsOnceInTheBadgeAndTheList() {
        val p = presentation(run(), suggestion("s1"))
        assertEquals(listOf("queen|s1"), queenSuggestionKeys(p))
        assertEquals(listOf("queen|s1"), inboxItemKeys(ApplicationRuntimeState.Live(p)))
        val rows = messageRows(p)
        assertEquals(1, rows.count { it.countsToBadge })
    }

    @Test
    fun aSuggestionIsReadLikeAnyOtherKey() {
        val keys = queenSuggestionKeys(presentation(run(), suggestion("s1")))
        assertEquals(1, unreadInboxCount(keys, emptySet()))
        assertEquals(0, unreadInboxCount(keys, keys.toSet()))
    }

    @Test
    fun theQueensOtherNotesNeverCount() {
        val status = QueenNote("n1", QueenVoice.StatusWorking, listOf("0", "1", "1", "Task a"))
        val p = presentation(run(), status)
        assertTrue(queenSuggestionKeys(p).isEmpty())
        assertFalse(messageRows(p).any { it.countsToBadge })
    }

    @Test
    fun noSuggestionKeysWhileStopped() {
        val stop = WorkflowSpendPause(
            kind = com.hereliesaz.geministrator.domain.SpendCapKind.RunCost, reason = "Cap", capUsd = 1.0, spentUsd = 1.0,
            taskSnapshots = emptyList(), pausedAtEpochMillis = 5L,
        )
        assertTrue(queenSuggestionKeys(presentation(run(stop), suggestion("s1"))).isEmpty())
    }
}
