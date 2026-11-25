package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarterWorkflowFactoryTest {
    @Test
    fun repositoryFreeObjectiveUsesOneGenericWorkerWithoutCodeOrReleaseSteps() {
        val definition = StarterWorkflowFactory.create(
            id = WorkflowDefinitionId("starter"),
            objective = "Write a poem",
            repositoryLinked = false,
        )
        val task = definition.tasks.single()
        assertEquals("worker", task.roleId?.value)
        assertEquals(ApprovalPolicy.None, task.approvalPolicy)
        assertEquals(com.hereliesaz.geministrator.domain.TestDesignPolicy.None, definition.testDesignPolicy)
        assertEquals("write-document", task.contract?.template?.id)
        assertTrue(task.resources.isEmpty())
        WorkflowGraphValidator.requireValid(definition)
    }

    @Test
    fun teachingTheAppUsesBundledDocumentationRatherThanCodingRoles() {
        val definition = StarterWorkflowFactory.create(
            id = WorkflowDefinitionId("tutorial"),
            objective = "teach me this app",
            repositoryLinked = false,
        )
        val task = definition.tasks.single()
        assertEquals("worker", task.roleId?.value)
        assertEquals(listOf(com.hereliesaz.geministrator.domain.TaskResource.AiveDocs()), task.resources)
        assertEquals(ApprovalPolicy.None, task.approvalPolicy)
        WorkflowGraphValidator.requireValid(definition)
    }

    @Test
    fun implementationRequiresHumanPlanApproval() {
        val definition = StarterWorkflowFactory.create(
            id = WorkflowDefinitionId("starter"),
            objective = "Implement the objective",
        )

        assertEquals(
            ApprovalPolicy.HumanApproval,
            definition.tasks.single { it.id == TaskDefinitionId("implementation") }.approvalPolicy,
        )
    }

    @Test
    fun injectedVerificationTasksDoNotInheritImplementationPlanGate() {
        val definition = StarterWorkflowFactory.create(
            id = WorkflowDefinitionId("starter"),
            objective = "Implement the objective",
        )

        val expanded = WorkflowDefinitionExpander.expand(definition)

        assertEquals(
            ApprovalPolicy.None,
            expanded.tasks.single { it.id == TaskDefinitionId("implementation--pre-code-tests") }.approvalPolicy,
        )
        assertEquals(
            ApprovalPolicy.HumanApproval,
            expanded.tasks.single { it.id == TaskDefinitionId("implementation") }.approvalPolicy,
        )
        assertEquals(
            ApprovalPolicy.None,
            expanded.tasks.single { it.id == TaskDefinitionId("implementation--post-code-tests") }.approvalPolicy,
        )
    }
}
