package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowCompositionTest {
    private val approval = TaskExecutor.HumanApproval()

    private fun task(
        id: String,
        vararg deps: String,
        roleId: String? = null,
        condition: TaskCondition = TaskCondition.Always,
    ) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = id,
        roleId = roleId?.let(::RoleDefinitionId),
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        condition = condition,
        executor = roleId?.let { TaskExecutor.RoleAgent(RoleDefinitionId(it)) } ?: approval,
    )

    @Test
    fun roleIsAOneNodeWorkflow() {
        val role = RoleDefinition(
            id = RoleDefinitionId("researcher-x"),
            name = "Researcher X",
            description = "Research the assigned question.",
            instructions = "Cite evidence.",
        )

        val workflow = WorkflowComposer.roleAsWorkflow(role)

        assertEquals(1, workflow.tasks.size)
        assertEquals(role.id, workflow.tasks.single().roleId)
        assertEquals(TaskExecutor.RoleAgent(role.id), workflow.tasks.single().executor)
    }

    @Test
    fun nestedWorkflowIsRepresentedByNestedExecutor() {
        val parent = WorkflowDefinition(
            id = WorkflowDefinitionId("parent"),
            name = "Parent",
            tasks = listOf(task("start")),
        )
        val child = WorkflowDefinition(
            id = WorkflowDefinitionId("child"),
            name = "Child",
            tasks = listOf(task("inside")),
        )

        val composed = WorkflowComposer.nest(
            parent = parent,
            child = child,
            taskId = TaskDefinitionId("child-node"),
            dependsOn = setOf(TaskDefinitionId("start")),
        )

        val nested = composed.tasks.single { it.id == TaskDefinitionId("child-node") }
        assertEquals(setOf(TaskDefinitionId("start")), nested.dependsOn)
        assertEquals(TaskExecutor.NestedWorkflow(child.id), nested.executor)
    }

    @Test
    fun inlineNamespacesTasksAndRemapsConditionsAndRoles() {
        val parent = WorkflowDefinition(
            id = WorkflowDefinitionId("parent"),
            name = "Parent",
            tasks = listOf(task("before"), task("after")),
        )
        val child = WorkflowDefinition(
            id = WorkflowDefinitionId("child"),
            name = "Child",
            tasks = listOf(
                task("research", roleId = "researcher"),
                task(
                    "challenge",
                    "research",
                    roleId = "critic",
                    condition = TaskCondition.OnAnyOutcome(TaskDefinitionId("research")),
                ),
            ),
        )

        val composed = WorkflowComposer.inline(
            parent = parent,
            child = child,
            namespace = "evidence",
            connectFrom = setOf(TaskDefinitionId("before")),
            connectTo = setOf(TaskDefinitionId("after")),
            roleOverrides = mapOf(RoleDefinitionId("critic") to RoleDefinitionId("evidence-skeptic")),
        )

        val research = composed.tasks.single { it.id == TaskDefinitionId("evidence.research") }
        val challenge = composed.tasks.single { it.id == TaskDefinitionId("evidence.challenge") }
        val after = composed.tasks.single { it.id == TaskDefinitionId("after") }

        assertTrue(TaskDefinitionId("before") in research.dependsOn)
        assertTrue(TaskDefinitionId("evidence.research") in challenge.dependsOn)
        assertEquals(RoleDefinitionId("evidence-skeptic"), challenge.roleId)
        assertEquals(
            TaskCondition.OnAnyOutcome(TaskDefinitionId("evidence.research")),
            challenge.condition,
        )
        assertTrue(TaskDefinitionId("evidence.challenge") in after.dependsOn)
    }

    @Test
    fun roleTaskCanExpandIntoOrchestratedWorkflow() {
        val roleTaskId = TaskDefinitionId("investigate")
        val parent = WorkflowDefinition(
            id = WorkflowDefinitionId("parent"),
            name = "Parent",
            tasks = listOf(
                task("intake"),
                task("investigate", "intake", roleId = "investigator"),
                task("publish", "investigate"),
            ),
        )
        val replacement = WorkflowDefinition(
            id = WorkflowDefinitionId("investigation-team"),
            name = "Investigation Team",
            tasks = listOf(
                task("collect", roleId = "collector"),
                task("verify", "collect", roleId = "verifier"),
            ),
        )

        val expanded = WorkflowComposer.expandRoleTask(parent, roleTaskId, replacement)

        assertTrue(expanded.tasks.none { it.id == roleTaskId })
        val collect = expanded.tasks.single { it.id == TaskDefinitionId("investigate.collect") }
        val verify = expanded.tasks.single { it.id == TaskDefinitionId("investigate.verify") }
        val publish = expanded.tasks.single { it.id == TaskDefinitionId("publish") }
        assertTrue(TaskDefinitionId("intake") in collect.dependsOn)
        assertTrue(TaskDefinitionId("investigate.collect") in verify.dependsOn)
        assertTrue(TaskDefinitionId("investigate.verify") in publish.dependsOn)
        assertTrue(WorkflowGraphValidator.validate(expanded).isEmpty())
    }
}
