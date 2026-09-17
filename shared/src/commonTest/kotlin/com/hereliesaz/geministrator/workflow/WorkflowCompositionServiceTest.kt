package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowCompositionServiceTest {
    @Test
    fun anyInstalledRoleCanReplaceAnotherWorkflowRole() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val role = RoleDefinition(
            id = RoleDefinitionId("skeptic"),
            name = "Skeptic",
            description = "Challenges evidence.",
            instructions = "Try to falsify claims.",
        )
        persistence.roles.put(role)
        val service = WorkflowCompositionService(persistence)
        val original = workflow("base", "worker")

        val reassigned = service.reassignRole(
            definition = original,
            taskId = TaskDefinitionId("work"),
            roleId = role.id,
        )

        val task = reassigned.tasks.single()
        assertEquals(role.id, task.roleId)
        assertEquals(TaskExecutor.RoleAgent(role.id), task.executor)
    }

    @Test
    fun roleCannotBeAssignedUntilItExistsInSharedLibrary() = runBlocking {
        val service = WorkflowCompositionService(InMemoryWorkflowPersistence())

        assertFailsWith<IllegalArgumentException> {
            service.reassignRole(
                workflow("base", "worker"),
                TaskDefinitionId("work"),
                RoleDefinitionId("missing"),
            )
        }
    }

    @Test
    fun composedWorkflowCanBeSavedAsReusableDefinition() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val child = WorkflowDefinition(
            id = WorkflowDefinitionId("child"),
            name = "Child",
            tasks = listOf(
                TaskDefinition(
                    id = TaskDefinitionId("child-task"),
                    name = "Child task",
                    objective = "Do child work",
                    roleId = null,
                    executor = TaskExecutor.HumanApproval(),
                ),
            ),
        )
        persistence.definitions.put(child)
        val service = WorkflowCompositionService(persistence)

        val combined = service.inlineWorkflow(
            definition = workflow("base", "worker"),
            childId = child.id,
            namespace = "embedded",
            connectFrom = setOf(TaskDefinitionId("work")),
        )
        val saved = service.saveAs(
            combined,
            id = WorkflowDefinitionId("combined"),
            name = "Combined",
        )

        assertNotNull(persistence.definitions.get(WorkflowDefinitionId("combined")))
        assertTrue(saved.tasks.any { it.id == TaskDefinitionId("embedded.child-task") })
    }

    private fun workflow(id: String, roleId: String) = WorkflowDefinition(
        id = WorkflowDefinitionId(id),
        name = id,
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("work"),
                name = "Work",
                objective = "Do the work",
                roleId = RoleDefinitionId(roleId),
                executor = TaskExecutor.RoleAgent(RoleDefinitionId(roleId)),
            ),
        ),
    )
}
