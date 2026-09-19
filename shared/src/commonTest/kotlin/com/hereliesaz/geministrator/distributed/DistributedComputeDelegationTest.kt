package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.ComputePlatform
import com.hereliesaz.geministrator.domain.DistributedComputeRequirements
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DistributedComputeDelegationTest {
    private val roleId = RoleDefinitionId("implementation-engineer")

    @Test
    fun exactNodeAssignmentBecomesHardRelayConstraint() {
        val task = task("implement", roleId)
            .withComputeDelegation(ComputeDelegationTarget.Node("desktop-a"))

        val distributed = assertIs<TaskExecutor.Distributed>(task.executor)
        assertEquals(setOf("desktop-a"), distributed.requirements.requiredNodeIds)
        assertEquals(setOf("desktop-a"), distributed.requirements.preferredNodeIds)

        val correctNode = node("desktop-a")
        val wrongNode = node("desktop-b")
        assertTrue(correctNode.canRun(distributed.requirements, distributed.delegate))
        assertFalse(wrongNode.canRun(distributed.requirements, distributed.delegate))
    }

    @Test
    fun localAssignmentRestoresImplicitRoleExecutor() {
        val remote = task("implement", roleId)
            .withComputeDelegation(ComputeDelegationTarget.Node("desktop-a"))
        val local = remote.withComputeDelegation(ComputeDelegationTarget.Local)

        assertNull(local.executor)
        assertEquals(ComputeDelegationTarget.Local, local.computeDelegationTarget())
    }

    @Test
    fun roleAssignmentChangesOnlyMatchingRoleTasks() {
        val reviewer = RoleDefinitionId("code-reviewer")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow"),
            name = "Workflow",
            tasks = listOf(
                task("implement", roleId),
                task("review", reviewer),
            ),
        )

        val delegated = definition.withRoleComputeDelegation(
            roleId = reviewer,
            target = ComputeDelegationTarget.Node("desktop-review"),
        )

        assertEquals(ComputeDelegationTarget.Local, delegated.tasks[0].computeDelegationTarget())
        assertEquals(
            ComputeDelegationTarget.Node("desktop-review"),
            delegated.tasks[1].computeDelegationTarget(),
        )
    }

    @Test
    fun humanApprovalNeverLeavesOriginDevice() {
        val approval = TaskDefinition(
            id = TaskDefinitionId("approval"),
            name = "Approve",
            objective = "Approve release",
            roleId = null,
            executor = TaskExecutor.HumanApproval("Release approval"),
        )

        val delegated = approval.withComputeDelegation(ComputeDelegationTarget.AnyRemote)

        assertEquals(approval, delegated)
        assertFalse(delegated.isComputeDelegatable())
    }

    private fun task(id: String, roleId: RoleDefinitionId): TaskDefinition = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = id,
        roleId = roleId,
    )

    private fun node(id: String): ComputeNodeDescriptor = ComputeNodeDescriptor(
        nodeId = id,
        displayName = id,
        platform = ComputePlatform.Desktop,
        architecture = "x86_64",
        logicalProcessors = 8,
        memoryMiB = 16_384,
        supportedExecutorKinds = setOf("role-agent"),
    )
}
