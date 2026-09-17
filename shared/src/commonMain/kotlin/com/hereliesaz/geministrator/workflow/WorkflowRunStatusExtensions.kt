package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.WorkflowRunStatus

internal fun WorkflowRunStatus.isTerminal(): Boolean = this in setOf(
    WorkflowRunStatus.Completed,
    WorkflowRunStatus.Failed,
    WorkflowRunStatus.Cancelled,
)
