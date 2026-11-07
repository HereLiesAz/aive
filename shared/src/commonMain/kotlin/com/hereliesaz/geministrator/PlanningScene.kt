package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
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
import com.hereliesaz.geministrator.orchestration.PlanningPhase
import com.hereliesaz.geministrator.orchestration.PlanningStage
import com.hereliesaz.geministrator.orchestration.PlanningVerdict
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory

/**
 * The planning of a launch drawn as a small hive that exists only while the plan is made
 * (docs/Node-Creatures.md, Planning), so the field's own eggs and hatching tell it:
 *
 * - **Plan** is the planner's own work. The reviewer's egg is gestated by it: it grows while the plan
 *   is drafted and hatches, with the reviewer inside, when the plan is complete.
 * - **Review** is the reviewer's. The first step's egg is gestated by the planner and the reviewer
 *   together, as any join is, so it cannot hatch until both are done.
 * - **First step** is the role that does the plan's first step. It hatches when the keeper starts the run.
 *
 * Every status here is read from the recorded [PlanningStage]: the scene invents nothing, and the
 * same projection (`projectBirths`) that draws a running hive draws this one.
 */
internal data class PlanningScene(
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    /** The roles the three creatures wear. */
    val roles: List<RoleDefinition>,
)

internal const val PLANNING_PLAN_ID = "plan"
internal const val PLANNING_REVIEW_ID = "review"
internal const val PLANNING_FIRST_ID = "first"

internal fun planningScene(stage: PlanningStage): PlanningScene {
    val firstRole = stage.firstStepRole ?: stage.reviewer
    fun task(id: String, name: String, role: RoleDefinition, vararg after: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = name,
        objective = name,
        roleId = role.id,
        dependsOn = after.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(role.id),
    )
    val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("planning"),
        name = "Planning",
        tasks = listOf(
            task(PLANNING_PLAN_ID, "Plan", stage.planner),
            task(PLANNING_REVIEW_ID, "Review the plan", stage.reviewer, PLANNING_PLAN_ID),
            // Gestated by the planner and the reviewer together. While the planner is still drafting there is no plan
            // for it to be gestated by, so it waits on the reviewer alone, who is still an egg: nothing is drawn yet.
            task(
                PLANNING_FIRST_ID,
                stage.firstStepName ?: "First step",
                firstRole,
                *(if (stage.phase == PlanningPhase.Planning) arrayOf(PLANNING_REVIEW_ID) else arrayOf(PLANNING_PLAN_ID, PLANNING_REVIEW_ID)),
            ),
        ),
    )

    val (plan, review, first) = when (stage.phase) {
        // The planner drafts: the reviewer is still an egg, and nothing of the first step is drawn.
        PlanningPhase.Planning -> Triple(TaskRunStatus.Running, TaskRunStatus.Blocked, TaskRunStatus.Blocked)
        // The plan is complete: the reviewer hatched from it and reads it. The first step's egg begins.
        PlanningPhase.Reviewing -> Triple(TaskRunStatus.Completed, TaskRunStatus.Running, TaskRunStatus.Blocked)
        // Sent back: the planner works again; the reviewer waits on it, hatched.
        PlanningPhase.Reworking -> Triple(TaskRunStatus.Running, TaskRunStatus.Retrying, TaskRunStatus.Blocked)
        // Over: the reviewer rises to the surface for the keeper (approved), or needs a person (it did not
        // approve), or failed (it could not be reached, so the first step's egg withers). None of them lets it hatch.
        PlanningPhase.AwaitingKeeper -> Triple(
            TaskRunStatus.Completed,
            when (stage.verdict) {
                PlanningVerdict.Approved, null -> TaskRunStatus.AwaitingApproval
                PlanningVerdict.NotApproved -> TaskRunStatus.Escalated
                PlanningVerdict.Unreachable -> TaskRunStatus.Failed
            },
            TaskRunStatus.Blocked,
        )
        // The keeper started the run: both gestators are done, so the first step is released and hatches.
        PlanningPhase.Started -> Triple(TaskRunStatus.Completed, TaskRunStatus.Completed, TaskRunStatus.Ready)
    }
    val statuses = mapOf(PLANNING_PLAN_ID to plan, PLANNING_REVIEW_ID to review, PLANNING_FIRST_ID to first)
    val taskRuns = definition.tasks.associate { task ->
        val status = statuses.getValue(task.id.value)
        task.id to TaskRun(
            id = TaskRunId("planning-${task.id.value}"),
            taskDefinitionId = task.id,
            status = status,
            assignedRoleId = task.roleId,
            // No provider is drawn: the planning field has no capillaries, only the three creatures.
            assignedProviderId = null,
            blockingReason = if (status == TaskRunStatus.Blocked) {
                BlockingReason(WorkflowRunFactory.WAITING_FOR_DEPENDENCIES, "Waiting for the plan.")
            } else {
                null
            },
        )
    }
    val run = WorkflowRun(
        id = WorkflowRunId("planning"),
        projectId = ProjectId("planning"),
        workflowDefinitionId = definition.id,
        objective = "Plan",
        status = WorkflowRunStatus.Running,
        taskRuns = taskRuns,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = stage.round.toLong(),
    )
    return PlanningScene(definition, run, listOf(stage.planner, stage.reviewer, firstRole).distinctBy { it.id })
}
