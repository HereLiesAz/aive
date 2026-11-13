package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.ROLE_COLLECTION_MARKER_ID
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.policy.ApprovalEvidence
import com.hereliesaz.geministrator.policy.InProcessPolicyDecisionPoint
import com.hereliesaz.geministrator.policy.PolicyAction
import com.hereliesaz.geministrator.policy.PolicyContext
import com.hereliesaz.geministrator.policy.PolicyDecision
import com.hereliesaz.geministrator.policy.PolicyRequest
import com.hereliesaz.geministrator.policy.PolicyResource
import com.hereliesaz.geministrator.policy.PolicySubject
import com.hereliesaz.geministrator.policy.isAllowed
import com.hereliesaz.geministrator.policy.isDenied

object WorkflowRunFactory {
    const val EXECUTOR_INTEGRATION_UNAVAILABLE = "EXECUTOR_INTEGRATION_UNAVAILABLE"
    const val APPROVAL_NOT_GRANTED = "APPROVAL_NOT_GRANTED"
    /** A task skipped because its condition was not met: a branch not taken, not a failure. */
    const val CONDITION_NOT_MET = "CONDITION_NOT_MET"
    /** A task not yet released: something it depends on has not settled. */
    const val WAITING_FOR_DEPENDENCIES = "WAITING_FOR_DEPENDENCIES"

    fun create(
        definition: WorkflowDefinition,
        workflowRunId: WorkflowRunId,
        projectId: ProjectId,
        objective: String,
        nowEpochMillis: Long,
        taskRunIdFactory: (TaskDefinitionId) -> TaskRunId,
        repository: RepositoryRef? = null,
        roles: Collection<RoleDefinition> = emptyList(),
        /** Runs this run continues (one) or merges (several); empty for a root run. */
        parentWorkflowRunIds: List<WorkflowRunId> = emptyList(),
        /** Where [objective] came from; anything but the keeper's own typing counts as an input to every task. */
        objectiveProvenance: ContextProvenance = ContextProvenance.KeeperWritten,
    ): WorkflowRun {
        WorkflowGraphValidator.requireValid(definition)
        val rolesById = roles.associateBy(RoleDefinition::id)

        val taskRuns = definition.tasks.associate { task ->
            val conditionTask = when (val c = task.condition) {
                is TaskCondition.Always -> null
                is TaskCondition.OnAnyOutcome -> c.ofTask
                is TaskCondition.OnFailure -> c.ofTask
            }
            val blocked = task.dependsOn.isNotEmpty() ||
                (conditionTask != null && conditionTask != task.id)
            val status = if (blocked) TaskRunStatus.Blocked else TaskRunStatus.Ready
            task.id to TaskRun(
                id = taskRunIdFactory(task.id),
                taskDefinitionId = task.id,
                status = status,
                assignedRoleId = task.roleId,
                executor = task.effectiveExecutor(task.roleId?.let(rolesById::get)),
                blockingReason = if (status == TaskRunStatus.Blocked) {
                    BlockingReason(
                        code = WAITING_FOR_DEPENDENCIES,
                        message = "Waiting for task dependencies.",
                    )
                } else {
                    null
                },
            )
        }

        return WorkflowRun(
            id = workflowRunId,
            projectId = projectId,
            workflowDefinitionId = definition.id,
            objective = objective,
            status = WorkflowRunStatus.Created,
            taskRuns = taskRuns,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            repositorySnapshot = repository,
            definitionSnapshot = definition,
            roleSnapshot = roles
                .filterNot { it.id.value == ROLE_COLLECTION_MARKER_ID }
                .distinctBy(RoleDefinition::id),
            parentWorkflowRunIds = parentWorkflowRunIds.distinct(),
            objectiveProvenance = objectiveProvenance,
        )
    }

    /**
     * Releases blocked tasks whose dependencies settled. [onPolicyDecision] hears each non-allow
     * policy decision made here (a repository mutation cancelled for want of a granted approval),
     * so a caller with an event sink can record it on the run timeline.
     *
     * A task in [undecidedFailures] has just been marked Failed and its failure policy has not run
     * yet (it may be retried, restarted on another provider or reassigned), so for what waits on it
     * it is still working: a failure handler or an any-outcome task is released only once the
     * failure is settled, not while the failed task is about to run again.
     */
    fun refreshReadiness(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        nowEpochMillis: Long,
        undecidedFailures: Set<TaskDefinitionId> = emptySet(),
        onPolicyDecision: ((PolicyRequest, PolicyDecision) -> Unit)? = null,
    ): WorkflowRun {
        val definitionsById = definition.tasks.associateBy { it.id }
        val outcomes = if (undecidedFailures.isEmpty()) {
            run.taskRuns
        } else {
            run.taskRuns.mapValues { (id, taskRun) ->
                if (id in undecidedFailures && taskRun.status == TaskRunStatus.Failed) {
                    taskRun.copy(status = TaskRunStatus.Running)
                } else {
                    taskRun
                }
            }
        }
        val refreshed = run.taskRuns.mapValues { (taskId, taskRun) ->
            if (taskRun.status != TaskRunStatus.Blocked) return@mapValues taskRun
            if (taskRun.blockingReason?.code == EXECUTOR_INTEGRATION_UNAVAILABLE) return@mapValues taskRun
            // Held by a spend circuit breaker, not by its dependencies: only that breaker releases it.
            if (taskRun.blockingReason?.code == ROLE_BUDGET_SPENT) return@mapValues taskRun
            if (taskRun.blockingReason?.code == SPEND_CAP_STOP) return@mapValues taskRun

            val task = definitionsById[taskId] ?: return@mapValues taskRun
            val dependencyRuns = task.dependsOn.mapNotNull(outcomes::get)
            val allTerminal = dependencyRuns.size == task.dependsOn.size &&
                dependencyRuns.all { it.status.isTerminal() }

            // A dependency skipped because its own condition was not met is a branch that was not
            // taken, not a failure: an Always join after it proceeds on the branches that did run.
            val allCompletedOrSkipped = allTerminal &&
                dependencyRuns.all { it.status == TaskRunStatus.Completed || it.isSkippedBranch() }
            val anyCompleted = dependencyRuns.isEmpty() ||
                dependencyRuns.any { it.status == TaskRunStatus.Completed }

            val conditionTargetRun = when (val c = task.condition) {
                is TaskCondition.Always -> null
                is TaskCondition.OnAnyOutcome -> outcomes[c.ofTask]
                is TaskCondition.OnFailure -> outcomes[c.ofTask]
            }
            // A condition reference is a real scheduling edge even when it is not duplicated in
            // dependsOn. Do not decide the branch until both explicit dependencies and the
            // condition target have reached terminal state.
            val conditionSettled = allTerminal &&
                (conditionTargetRun == null || conditionTargetRun.status.isTerminal())
            val conditionMet: Boolean = when (task.condition) {
                is TaskCondition.Always -> allCompletedOrSkipped && anyCompleted
                is TaskCondition.OnAnyOutcome -> conditionSettled && conditionTargetRun != null
                is TaskCondition.OnFailure -> conditionSettled && conditionTargetRun != null &&
                    conditionTargetRun.isFailureOutcome()
            }

            // Always-conditioned tasks stay Blocked when a dep fails rather than being cancelled:
            // the happy-path semantics let the workflow continue via failure handlers without
            // prematurely cancelling tasks that haven't had a chance to run yet. Once every
            // dependency is Completed or Cancelled, though, nothing can unblock them, so a join
            // that no completed branch reaches is skipped instead of waiting forever.
            val conditionUnreachable = if (task.condition is TaskCondition.Always) {
                allTerminal && !conditionMet &&
                    dependencyRuns.all { it.status == TaskRunStatus.Completed || it.status == TaskRunStatus.Cancelled }
            } else {
                conditionSettled && !conditionMet
            }
            // A repository mutation runs only after a person approved it. The validator demands an
            // approval ancestor, but a skipped or cancelled approval also settles as "terminal", so
            // the policy decision point checks that one was actually granted before releasing it.
            val repositoryOperation = task.executor.withoutPlacement() as? TaskExecutor.RepositoryOperation
            val repositoryDenial = if (conditionMet && repositoryOperation != null && repositoryOperation.isMutation()) {
                val request = PolicyRequest(
                    subject = PolicySubject.System,
                    action = PolicyAction.RunRepositoryOperation(repositoryOperation.operation),
                    resource = PolicyResource.task(taskId, run.repositorySnapshot),
                    context = PolicyContext(
                        workflowRunId = run.id,
                        taskDefinitionId = taskId,
                        approval = if (hasGrantedApprovalAncestor(taskId, definitionsById, run)) {
                            ApprovalEvidence.Granted
                        } else {
                            ApprovalEvidence.None
                        },
                    ),
                )
                InProcessPolicyDecisionPoint.decide(request)
                    .takeUnless { it.isAllowed }
                    ?.also { onPolicyDecision?.invoke(request, it) }
            } else {
                null
            }
            // A step that declares an effect runs only with a person's approval: its plan gate, or an
            // approval step before it that was actually granted (invariant 4a).
            val stepDenial = if (conditionMet && task.isEffectfulStep()) {
                val granted = hasGrantedApprovalAncestor(taskId, definitionsById, run)
                val request = PolicyRequest(
                    subject = PolicySubject.System,
                    action = PolicyAction.CallTool(
                        tool = task.contract?.allowedTools?.firstOrNull() ?: task.name,
                        mutating = true,
                        stepEffect = true,
                    ),
                    resource = PolicyResource.task(taskId, run.repositorySnapshot),
                    context = PolicyContext(
                        workflowRunId = run.id,
                        taskDefinitionId = taskId,
                        approval = stepApprovalEvidence(task, hasApprovalAncestor = granted, granted = granted),
                    ),
                )
                InProcessPolicyDecisionPoint.decide(request)
                    .takeIf { it is PolicyDecision.RequireApproval || it.isDenied }
                    ?.also { onPolicyDecision?.invoke(request, it) }
            } else {
                null
            }
            val mutationDenial = repositoryDenial ?: stepDenial
            when {
                mutationDenial != null -> taskRun.copy(
                    status = TaskRunStatus.Cancelled,
                    blockingReason = BlockingReason(
                        code = APPROVAL_NOT_GRANTED,
                        message = mutationDenial.reason,
                    ),
                )
                conditionMet -> taskRun.copy(
                    status = TaskRunStatus.Ready,
                    blockingReason = null,
                )
                conditionUnreachable -> taskRun.copy(
                    status = TaskRunStatus.Cancelled,
                    blockingReason = BlockingReason(
                        code = CONDITION_NOT_MET,
                        message = "Task condition was not satisfied; task will not run.",
                    ),
                )
                else -> taskRun
            }
        }

        // Unchanged readiness leaves the run untouched, so an idle cycle can recognise it has
        // nothing to do instead of rewriting the run every second.
        if (refreshed == run.taskRuns) return run
        return run.copy(
            taskRuns = refreshed,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    /** Whether a human-approval task upstream of [taskId] (through dependsOn) completed, i.e. was approved. */
    private fun hasGrantedApprovalAncestor(
        taskId: TaskDefinitionId,
        definitionsById: Map<TaskDefinitionId, TaskDefinition>,
        run: WorkflowRun,
    ): Boolean {
        val visited = mutableSetOf<TaskDefinitionId>()
        fun visit(id: TaskDefinitionId): Boolean {
            if (!visited.add(id)) return false
            val task = definitionsById[id] ?: return false
            return task.dependsOn.any { dependencyId ->
                val dependency = definitionsById[dependencyId] ?: return@any false
                (dependency.executor is TaskExecutor.HumanApproval &&
                    run.taskRuns[dependencyId]?.status == TaskRunStatus.Completed) ||
                    visit(dependencyId)
            }
        }
        return visit(taskId)
    }

    /**
     * Whether a settled task is one an on-failure handler is for: it failed, was escalated, or was
     * cancelled. A branch that was not taken ([CONDITION_NOT_MET]) and a change nobody approved
     * ([APPROVAL_NOT_GRANTED]) are cancelled too, but nothing failed there, so there is nothing to handle.
     */
    private fun TaskRun.isFailureOutcome() = when (status) {
        TaskRunStatus.Failed, TaskRunStatus.Escalated -> true
        TaskRunStatus.Cancelled -> blockingReason?.code.let { it != CONDITION_NOT_MET && it != APPROVAL_NOT_GRANTED }
        else -> false
    }

    private fun TaskRun.isSkippedBranch() =
        status == TaskRunStatus.Cancelled && blockingReason?.code == CONDITION_NOT_MET
}
