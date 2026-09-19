package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.WorkflowRunStatus

internal fun WorkflowRunStatus.isTerminal(): Boolean = when (this) {
    WorkflowRunStatus.Completed,
    WorkflowRunStatus.Failed,
    WorkflowRunStatus.Cancelled,
    -> true

    else -> false
}
