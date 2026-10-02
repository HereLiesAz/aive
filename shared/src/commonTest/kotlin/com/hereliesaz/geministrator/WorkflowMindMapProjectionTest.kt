package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowMindMapProjectionTest {
    private val productId = TaskDefinitionId("product")
    private val implementationId = TaskDefinitionId("implementation")
    private val qaId = TaskDefinitionId("qa")

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("feature"),
        name = "Feature",
        tasks = listOf(
            TaskDefinition(
                id = productId,
                name = "Requirements",
                objective = "Define requirements",
                roleId = BuiltInRoles.ProductManager.id,
            ),
            TaskDefinition(
                id = implementationId,
                name = "Implementation",
                objective = "Implement the feature",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                dependsOn = setOf(productId),
            ),
            TaskDefinition(
                id = qaId,
                name = "Verification",
                objective = "Verify the feature",
                roleId = BuiltInRoles.QaEngineer.id,
                dependsOn = setOf(implementationId),
            ),
        ),
    )

    @Test
    fun projectsDagStatusAndProviderProgressIntoTheMap() {
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Ship feature",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                productId to taskRun(productId, TaskRunStatus.Completed, BuiltInRoles.ProductManager.id.value),
                implementationId to TaskRun(
                    id = TaskRunId("implementation-run"),
                    taskDefinitionId = implementationId,
                    status = TaskRunStatus.Running,
                    assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                    assignedProviderId = AgentProviderId("remote-agent"),
                    progress = .67f,
                    progressMessage = "Writing verification code",
                ),
                qaId to taskRun(qaId, TaskRunStatus.Blocked, BuiltInRoles.QaEngineer.id.value),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val projection = projectWorkflowMindMap(definition, run, BuiltInRoles.all)

        assertEquals(3, projection.bands.size)
        assertEquals(2, projection.edges.size)

        val nodes = projection.bands.flatMap { it.nodes }.associateBy { it.id }
        assertEquals(H2g2WorkflowState.Complete, nodes.getValue("product").state)
        assertEquals(H2g2WorkflowState.Active, nodes.getValue("implementation").state)
        assertEquals(.67f, nodes.getValue("implementation").progress)
        assertTrue(nodes.getValue("implementation").detail.orEmpty().contains("remote-agent"))
        assertTrue(nodes.getValue("implementation").detail.orEmpty().contains("Writing verification code"))
        assertEquals(H2g2WorkflowState.Blocked, nodes.getValue("qa").state)
    }

    @Test
    fun qualitativeProvidersUseLifecyclePlateausInsteadOfInventedPrecision() {
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Ship feature",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                productId to taskRun(productId, TaskRunStatus.Completed, BuiltInRoles.ProductManager.id.value),
                implementationId to TaskRun(
                    id = TaskRunId("implementation-run"),
                    taskDefinitionId = implementationId,
                    status = TaskRunStatus.Verifying,
                    assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                    assignedProviderId = AgentProviderId("remote-agent"),
                    progressMessage = "Frontend verification",
                ),
                qaId to taskRun(qaId, TaskRunStatus.Blocked, BuiltInRoles.QaEngineer.id.value),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val projection = projectWorkflowMindMap(definition, run, BuiltInRoles.all)
        val implementation = projection.bands.flatMap { it.nodes }.first { it.id == "implementation" }

        assertEquals(null, implementation.progress, "no reported progress means no bar, not a guess from status")
        assertEquals(H2g2WorkflowState.Active, implementation.state)
    }

    @Test
    fun focusOnRetainsAncestorsAndDescendantsOnly() {
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Ship feature",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                productId to taskRun(productId, TaskRunStatus.Completed, BuiltInRoles.ProductManager.id.value),
                implementationId to taskRun(implementationId, TaskRunStatus.Running, BuiltInRoles.ImplementationEngineer.id.value),
                qaId to taskRun(qaId, TaskRunStatus.Blocked, BuiltInRoles.QaEngineer.id.value),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val full = projectWorkflowMindMap(definition, run, BuiltInRoles.all)

        // Focus on implementation: should keep product (ancestor) + implementation + qa (descendant)
        val focused = full.focusOn(implementationId, definition)
        val ids = focused.bands.flatMap { it.nodes }.map { it.id }.toSet()
        assertEquals(setOf("product", "implementation", "qa"), ids)
        assertEquals(2, focused.edges.size)
    }

    @Test
    fun focusOnLeafRetainsOnlyAncestors() {
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Ship feature",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                productId to taskRun(productId, TaskRunStatus.Completed, BuiltInRoles.ProductManager.id.value),
                implementationId to taskRun(implementationId, TaskRunStatus.Completed, BuiltInRoles.ImplementationEngineer.id.value),
                qaId to taskRun(qaId, TaskRunStatus.Running, BuiltInRoles.QaEngineer.id.value),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val full = projectWorkflowMindMap(definition, run, BuiltInRoles.all)

        // Focus on qa (leaf): ancestors = product, implementation, qa
        val focused = full.focusOn(qaId, definition)
        val ids = focused.bands.flatMap { it.nodes }.map { it.id }.toSet()
        assertEquals(setOf("product", "implementation", "qa"), ids)
    }

    @Test
    fun focusOnRootRetainsOnlyDescendants() {
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Ship feature",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                productId to taskRun(productId, TaskRunStatus.Running, BuiltInRoles.ProductManager.id.value),
                implementationId to taskRun(implementationId, TaskRunStatus.Blocked, BuiltInRoles.ImplementationEngineer.id.value),
                qaId to taskRun(qaId, TaskRunStatus.Blocked, BuiltInRoles.QaEngineer.id.value),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val full = projectWorkflowMindMap(definition, run, BuiltInRoles.all)

        // Focus on product (root): descendants = product, implementation, qa
        val focused = full.focusOn(productId, definition)
        val ids = focused.bands.flatMap { it.nodes }.map { it.id }.toSet()
        assertEquals(setOf("product", "implementation", "qa"), ids)
    }

    @Test
    fun focusOnNullReturnsFullProjection() {
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Ship feature",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                productId to taskRun(productId, TaskRunStatus.Completed, BuiltInRoles.ProductManager.id.value),
                implementationId to taskRun(implementationId, TaskRunStatus.Running, BuiltInRoles.ImplementationEngineer.id.value),
                qaId to taskRun(qaId, TaskRunStatus.Blocked, BuiltInRoles.QaEngineer.id.value),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val full = projectWorkflowMindMap(definition, run, BuiltInRoles.all)
        val notFocused = full.focusOn(null, definition)
        assertEquals(full.bands.flatMap { it.nodes }.size, notFocused.bands.flatMap { it.nodes }.size)
        assertEquals(full.edges.size, notFocused.edges.size)
    }

    private fun taskRun(
        taskId: TaskDefinitionId,
        status: TaskRunStatus,
        roleId: String,
    ) = TaskRun(
        id = TaskRunId("${taskId.value}-run"),
        taskDefinitionId = taskId,
        status = status,
        assignedRoleId = com.hereliesaz.geministrator.domain.RoleDefinitionId(roleId),
    )
}
