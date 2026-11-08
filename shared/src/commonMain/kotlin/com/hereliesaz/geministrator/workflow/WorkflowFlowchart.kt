package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceRedactor

/** One step of the workflow as a box: where it sits, who does it, and (with a run) how it stands. */
data class FlowNode(
    val id: TaskDefinitionId,
    val name: String,
    /** The role doing it, or null when no role is (a programmatic step or an approval). */
    val role: String?,
    /** What does the work: the executor's kind, as receipts name it. */
    val kind: String,
    /** Columns from the left: 0 for a step nothing waits on. */
    val level: Int,
    /** Position within its column, top first. */
    val row: Int,
    val status: TaskRunStatus?,
)

/** An arrow from the step that must finish first to the one that waits on it. */
data class FlowEdge(val from: TaskDefinitionId, val to: TaskDefinitionId, val label: String? = null)

/** A workflow as boxes and arrows, laid out in columns by how many steps must come before each. */
data class WorkflowFlowchart(
    val nodes: List<FlowNode>,
    val edges: List<FlowEdge>,
    val levels: Int,
    val rows: Int,
) {
    companion object {
        /**
         * The flowchart of [definition]. A step sits in the column after the latest step it waits on;
         * within a column steps are ordered by where their feeders sit (so arrows cross less), then by
         * the order the workflow lists them. With a [run] each box carries its step's status. Pure: it
         * reads and decides nothing.
         */
        fun of(
            definition: WorkflowDefinition,
            run: WorkflowRun? = null,
            roleName: (RoleDefinitionId) -> String = { it.value },
        ): WorkflowFlowchart {
            val tasks = definition.tasks
            val byId = tasks.associateBy { it.id }
            val level = HashMap<TaskDefinitionId, Int>()
            fun levelOf(task: TaskDefinition, path: Set<TaskDefinitionId>): Int =
                level.getOrPut(task.id) {
                    task.dependsOn.mapNotNull { byId[it] }
                        .filter { it.id !in path }
                        .maxOfOrNull { levelOf(it, path + task.id) + 1 } ?: 0
                }
            tasks.forEach { levelOf(it, emptySet()) }

            val order = tasks.withIndex().associate { it.value.id to it.index }
            val rowOf = HashMap<TaskDefinitionId, Int>()
            val columns = tasks.groupBy { level.getValue(it.id) }.toSortedMap()
            for ((_, column) in columns) {
                val sorted = column.sortedWith(
                    compareBy(
                        { task ->
                            val parents = task.dependsOn.mapNotNull { rowOf[it] }
                            if (parents.isEmpty()) Double.MAX_VALUE else parents.average()
                        },
                        { order.getValue(it.id) },
                    ),
                )
                sorted.forEachIndexed { index, task -> rowOf[task.id] = index }
            }

            val nodes = tasks.map { task ->
                FlowNode(
                    id = task.id,
                    name = task.name,
                    role = task.roleId?.let(roleName),
                    kind = task.executor?.let(ProcedureTraceRedactor::kindOf) ?: "RoleAgent",
                    level = level.getValue(task.id),
                    row = rowOf.getValue(task.id),
                    status = run?.taskRuns?.get(task.id)?.status,
                )
            }.sortedWith(compareBy({ it.level }, { it.row }))

            val edges = tasks.flatMap { task ->
                task.dependsOn.filter { it in byId }.sortedBy { order.getValue(it) }.map { from ->
                    FlowEdge(from, task.id, conditionLabel(task.condition, from))
                }
            }
            return WorkflowFlowchart(
                nodes = nodes,
                edges = edges,
                levels = (columns.keys.maxOrNull() ?: -1) + 1,
                rows = columns.values.maxOfOrNull { it.size } ?: 0,
            )
        }

        private fun conditionLabel(condition: TaskCondition, from: TaskDefinitionId): String? = when (condition) {
            TaskCondition.Always -> null
            is TaskCondition.OnFailure -> if (condition.ofTask == from) "if it fails" else null
            is TaskCondition.OnAnyOutcome -> if (condition.ofTask == from) "whatever it does" else null
        }
    }
}
