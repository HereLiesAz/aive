package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus

/*
 * Engine-owned plan gate. For a task that needs plan approval, a provider that does not plan inside
 * its own run drafts the plan first (AgentProvider.draftPlan). The engine holds the task at its plan
 * approval gate with that draft and no provider run; approval makes it Ready, and dispatch starts
 * the run with the approved plan in context. Nothing is started before approval, so restarts and
 * rejections have no provider session to reconnect or cancel.
 */

/** Awaiting approval of an engine-drafted plan; no provider run has started. */
fun TaskRun.isHeldAtEnginePlanGate(): Boolean =
    status == TaskRunStatus.AwaitingApproval &&
        assignedProviderId != null &&
        providerRunId == null &&
        providerPlan != null

/** The approved engine plan of a task that is ready to dispatch, or null. */
internal fun TaskRun.approvedEnginePlan(): String? =
    providerPlan?.takeIf { status == TaskRunStatus.Ready && providerRunId == null }
