package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TemplateRef
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowRunFactoryHoldTest {
    private fun create(definition: WorkflowDefinition) = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = WorkflowRunId("r"),
        projectId = ProjectId("p"),
        objective = "Go",
        nowEpochMillis = 0L,
        taskRunIdFactory = { id -> TaskRunId("r-${id.value}") },
    )

    @Test
    fun aRootStepMissingARequiredInputStartsHeld() {
        val step = TaskDefinition(
            TaskDefinitionId("doc"), "Doc", "Write", BuiltInRoles.Worker.id,
            contract = TaskContract(TemplateRef("write-document", 1)),
        )
        val run = create(WorkflowDefinition(id = WorkflowDefinitionId("w"), name = "W", tasks = listOf(step)))
        val taskRun = run.taskRuns.getValue(step.id)
        assertEquals(TaskRunStatus.Blocked, taskRun.status)
        assertEquals(WorkflowRunFactory.CONTRACT_INPUT_MISSING, taskRun.blockingReason?.code)
    }

    @Test
    fun aRootStepWithItsInputStartsReady() {
        val step = TaskDefinition(
            TaskDefinitionId("doc"), "Doc", "Write", BuiltInRoles.Worker.id,
            contract = TaskContract(TemplateRef("write-document", 1), inputs = mapOf("brief" to "A note")),
        )
        val run = create(WorkflowDefinition(id = WorkflowDefinitionId("w"), name = "W", tasks = listOf(step)))
        assertEquals(TaskRunStatus.Ready, run.taskRuns.getValue(step.id).status)
    }

    @Test
    fun aRequiredStepAfterASetAsideOptionalStepIsReleased() {
        val optional = TaskDefinition(TaskDefinitionId("a"), "A", "Do a", BuiltInRoles.Worker.id, optional = true)
        val next = TaskDefinition(TaskDefinitionId("b"), "B", "Do b", BuiltInRoles.Worker.id, dependsOn = setOf(optional.id))
        val definition = WorkflowDefinition(id = WorkflowDefinitionId("w"), name = "W", tasks = listOf(optional, next))
        val created = create(definition)
        val skipped = created.copy(
            taskRuns = created.taskRuns + (
                optional.id to created.taskRuns.getValue(optional.id).copy(
                    status = TaskRunStatus.Failed,
                    blockingReason = BlockingReason(OPTIONAL_STEP_SKIPPED, "no reply"),
                )
                ),
        )
        val refreshed = WorkflowRunFactory.refreshReadiness(definition, skipped, nowEpochMillis = 1L)
        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(next.id).status)
    }
}
