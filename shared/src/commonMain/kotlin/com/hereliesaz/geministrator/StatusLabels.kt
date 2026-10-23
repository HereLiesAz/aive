package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.status_run_awaiting_human
import com.hereliesaz.geministrator.resources.status_run_cancelled
import com.hereliesaz.geministrator.resources.status_run_completed
import com.hereliesaz.geministrator.resources.status_run_created
import com.hereliesaz.geministrator.resources.status_run_failed
import com.hereliesaz.geministrator.resources.status_run_running
import com.hereliesaz.geministrator.resources.status_task_awaiting_approval
import com.hereliesaz.geministrator.resources.status_task_blocked
import com.hereliesaz.geministrator.resources.status_task_cancelled
import com.hereliesaz.geministrator.resources.status_task_completed
import com.hereliesaz.geministrator.resources.status_task_created
import com.hereliesaz.geministrator.resources.status_task_escalated
import com.hereliesaz.geministrator.resources.status_task_failed
import com.hereliesaz.geministrator.resources.status_task_planning
import com.hereliesaz.geministrator.resources.status_task_ready
import com.hereliesaz.geministrator.resources.status_task_retrying
import com.hereliesaz.geministrator.resources.status_task_running
import com.hereliesaz.geministrator.resources.status_task_verifying
import org.jetbrains.compose.resources.stringResource

/** Human-readable status for headline pills and activity rows, instead of the enum name. */
@Composable
internal fun WorkflowRunStatus.label(): String = stringResource(
    when (this) {
        WorkflowRunStatus.Created -> Res.string.status_run_created
        WorkflowRunStatus.Running -> Res.string.status_run_running
        WorkflowRunStatus.AwaitingHuman -> Res.string.status_run_awaiting_human
        WorkflowRunStatus.Completed -> Res.string.status_run_completed
        WorkflowRunStatus.Failed -> Res.string.status_run_failed
        WorkflowRunStatus.Cancelled -> Res.string.status_run_cancelled
    },
)

@Composable
internal fun TaskRunStatus.label(): String = stringResource(
    when (this) {
        TaskRunStatus.Created -> Res.string.status_task_created
        TaskRunStatus.Blocked -> Res.string.status_task_blocked
        TaskRunStatus.Ready -> Res.string.status_task_ready
        TaskRunStatus.Planning -> Res.string.status_task_planning
        TaskRunStatus.AwaitingApproval -> Res.string.status_task_awaiting_approval
        TaskRunStatus.Running -> Res.string.status_task_running
        TaskRunStatus.Verifying -> Res.string.status_task_verifying
        TaskRunStatus.Retrying -> Res.string.status_task_retrying
        TaskRunStatus.Completed -> Res.string.status_task_completed
        TaskRunStatus.Failed -> Res.string.status_task_failed
        TaskRunStatus.Escalated -> Res.string.status_task_escalated
        TaskRunStatus.Cancelled -> Res.string.status_task_cancelled
    },
)
