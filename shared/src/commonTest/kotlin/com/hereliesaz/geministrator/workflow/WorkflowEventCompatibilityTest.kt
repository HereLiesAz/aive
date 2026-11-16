package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.ApprovalRequired
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowEventCompatibilityTest {
    @Test
    fun currentApprovalRequiredAlwaysHasGateId() {
        val current = ApprovalRequired(
            workflowRunId = WorkflowRunId("run"),
            taskDefinitionId = TaskDefinitionId("task"),
            gateId = ApprovalGateId("gate"),
            reason = "Review",
            occurredAtEpochMillis = 10L,
        )

        assertEquals(ApprovalGateId("gate"), current.gateId)
    }
}
