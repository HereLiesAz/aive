package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.log.formatLogTime

/** One step a role took in one run: where, what, how it ended and what it cost. */
data class RoleActivityEntry(
    val runId: String,
    val hive: String,
    val task: String,
    val status: TaskRunStatus,
    val attempt: Int,
    val tokens: Long?,
    val costUsd: Double?,
    /** When the run last changed: the nearest time the run records for its steps. */
    val atEpochMillis: Long,
) {
    fun line(): String = buildString {
        append(formatLogTime(atEpochMillis)).append("  ").append(hive).append(" · ").append(task)
            .append(" · ").append(status.name.lowercase())
        if (attempt > 1) append(" · attempt ").append(attempt)
        tokens?.let { append(" · ").append(it).append(" tokens") }
        costUsd?.let { append(" · $").append(it) }
    }
}

/** Everything one role has done across the runs given: counts, cost, and the steps newest first. */
data class RoleActivity(
    val runs: Int,
    val steps: Int,
    val completed: Int,
    val failed: Int,
    val tokens: Long,
    val costUsd: Double?,
    val entries: List<RoleActivityEntry>,
)

object RoleActivities {
    const val DEFAULT_LIMIT = 40

    /**
     * What [role] did in [runs]. A step is the role's when its run assigned it, or, with none
     * assigned, when its workflow's task names it. Counts cover every such step; [limit] cuts only the
     * list. Cost and tokens are what providers reported; a step with none adds nothing. Pure: it reads
     * records that already exist.
     */
    fun of(
        role: RoleDefinitionId,
        definitions: Collection<WorkflowDefinition>,
        runs: List<WorkflowRun>,
        limit: Int = DEFAULT_LIMIT,
    ): RoleActivity {
        val byId = definitions.associateBy { it.id }
        val all = runs.flatMap { run ->
            val definition = byId[run.workflowDefinitionId]
            val tasks = definition?.tasks?.associateBy { it.id }.orEmpty()
            run.taskRuns.values.filter { taskRun ->
                (taskRun.assignedRoleId ?: tasks[taskRun.taskDefinitionId]?.roleId) == role
            }.map { taskRun ->
                RoleActivityEntry(
                    runId = run.id.value,
                    hive = definition?.name ?: run.workflowDefinitionId.value,
                    task = tasks[taskRun.taskDefinitionId]?.name ?: taskRun.taskDefinitionId.value,
                    status = taskRun.status,
                    attempt = taskRun.attempt,
                    tokens = taskRun.usage?.totalTokens?.takeIf { it > 0 },
                    costUsd = taskRun.usage?.costUsd,
                    atEpochMillis = run.updatedAtEpochMillis,
                )
            }
        }
        val sorted = all.sortedWith(compareByDescending<RoleActivityEntry> { it.atEpochMillis }.thenBy { it.runId }.thenBy { it.task })
        val costs = all.mapNotNull { it.costUsd }
        return RoleActivity(
            runs = all.map { it.runId }.distinct().size,
            steps = all.size,
            completed = all.count { it.status == TaskRunStatus.Completed },
            failed = all.count { it.status == TaskRunStatus.Failed || it.status == TaskRunStatus.Escalated },
            tokens = all.sumOf { it.tokens ?: 0L },
            costUsd = costs.takeIf { it.isNotEmpty() }?.sum(),
            entries = sorted.take(limit),
        )
    }
}
