package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowFlowchartTest {
    private fun id(value: String) = TaskDefinitionId(value)

    private fun task(
        name: String,
        dependsOn: Set<String> = emptySet(),
        role: String? = "engineer",
        executor: TaskExecutor? = null,
        condition: TaskCondition = TaskCondition.Always,
    ) = TaskDefinition(
        id = id(name), name = name.replaceFirstChar { it.uppercase() }, objective = name,
        roleId = role?.let(::RoleDefinitionId), dependsOn = dependsOn.map(::id).toSet(),
        executor = executor, condition = condition,
    )

    private fun definition(vararg tasks: TaskDefinition) =
        WorkflowDefinition(id = WorkflowDefinitionId("w"), name = "W", tasks = tasks.toList())

    @Test
    fun aStepSitsInTheColumnAfterTheLatestStepItWaitsOn() {
        val flow = WorkflowFlowchart.of(
            definition(
                task("plan"), task("build", setOf("plan")), task("docs", setOf("plan")),
                task("ship", setOf("build", "docs")), task("lint"),
            ),
        )
        val levels = flow.nodes.associate { it.id.value to it.level }
        assertEquals(mapOf("plan" to 0, "lint" to 0, "build" to 1, "docs" to 1, "ship" to 2), levels)
        assertEquals(3, flow.levels)
        assertEquals(2, flow.rows)
        assertEquals(
            setOf("plan>build", "plan>docs", "build>ship", "docs>ship"),
            flow.edges.map { "${it.from.value}>${it.to.value}" }.toSet(),
        )
    }

    @Test
    fun stepsAreOrderedByTheirFeedersThenByTheOrderTheWorkflowListsThem() {
        // b comes first in the list but feeds from the lower row, so it sits below a's child.
        val flow = WorkflowFlowchart.of(
            definition(
                task("a"), task("b"), task("child-b", setOf("b")), task("child-a", setOf("a")),
            ),
        )
        val rows = flow.nodes.filter { it.level == 1 }.associate { it.id.value to it.row }
        assertEquals(mapOf("child-a" to 0, "child-b" to 1), rows)
    }

    @Test
    fun aRunGivesEachBoxItsStatusAndAConditionLabelsItsArrow() {
        val def = definition(
            task("build"),
            task("fix", setOf("build"), condition = TaskCondition.OnFailure(id("build"))),
            task("approve", setOf("build"), role = null, executor = TaskExecutor.HumanApproval()),
        )
        val run = WorkflowRunFactory.create(
            definition = def, workflowRunId = WorkflowRunId("r"), projectId = ProjectId("p"),
            objective = "o", nowEpochMillis = 1L, taskRunIdFactory = { TaskRunId(it.value) },
        )
        val flow = WorkflowFlowchart.of(def, run) { RoleDefinitionId(it.value).value.uppercase() }
        assertEquals(TaskRunStatus.Ready, flow.nodes.first { it.id.value == "build" }.status)
        assertEquals("ENGINEER", flow.nodes.first { it.id.value == "build" }.role)
        assertNull(flow.nodes.first { it.id.value == "approve" }.role)
        assertEquals("HumanApproval", flow.nodes.first { it.id.value == "approve" }.kind)
        assertEquals("RoleAgent", flow.nodes.first { it.id.value == "build" }.kind)
        assertEquals("if it fails", flow.edges.first { it.to.value == "fix" }.label)
        assertNull(flow.edges.first { it.to.value == "approve" }.label)
        // Without a run there is no status, and an empty workflow has no columns.
        assertNull(WorkflowFlowchart.of(def).nodes.first().status)
        assertEquals(WorkflowFlowchart(emptyList(), emptyList(), 0, 0), WorkflowFlowchart.of(definition()))
    }

    @Test
    fun aCycleDoesNotHangTheLayout() {
        val flow = WorkflowFlowchart.of(definition(task("a", setOf("b")), task("b", setOf("a"))))
        assertEquals(2, flow.nodes.size)
    }
}
