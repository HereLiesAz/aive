package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TimeLimit
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LiveDeadlineEditTest {
    private val id = TaskDefinitionId("t")
    private fun definition(limit: TimeLimit, objective: String = "o") = WorkflowDefinition(
        WorkflowDefinitionId("d"), "d", tasks = listOf(TaskDefinition(id, "T", objective, null, timeLimit = limit)),
    )
    private fun run(status: TaskRunStatus) = WorkflowRun(
        id = WorkflowRunId("r"), projectId = ProjectId("p"), workflowDefinitionId = WorkflowDefinitionId("d"),
        objective = "o", status = WorkflowRunStatus.Running,
        taskRuns = mapOf(id to TaskRun(TaskRunId("tr"), id, status, assignedRoleId = null)),
        createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
    )

    @Test fun liveEditCanExtendStartedCreatureButNothingElse() {
        val merged = assertIs<LiveEditMerge.Merged>(
            LiveDefinitionEdit.merge(definition(TimeLimit.Auto), definition(TimeLimit.Minutes(60), objective = "changed"), run(TaskRunStatus.Running)),
        )
        val task = merged.definition.tasks.single()
        assertEquals(TimeLimit.Minutes(60), task.timeLimit)
        assertEquals("o", task.objective)
    }

    @Test fun unstartedCreatureTakesJobAndTimeAllowed() {
        val merged = assertIs<LiveEditMerge.Merged>(
            LiveDefinitionEdit.merge(definition(TimeLimit.Auto), definition(TimeLimit.Off, objective = "changed"), run(TaskRunStatus.Ready)),
        )
        assertEquals(TimeLimit.Off, merged.definition.tasks.single().timeLimit)
        assertEquals("changed", merged.definition.tasks.single().objective)
    }

    @Test fun finishedCreatureTakesNothing() {
        assertIs<LiveEditMerge.Nothing>(
            LiveDefinitionEdit.merge(definition(TimeLimit.Auto), definition(TimeLimit.Off), run(TaskRunStatus.Completed)),
        )
    }
}
