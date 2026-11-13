package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.computeDelegationTarget
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A creature's job (mockup H4): the task fields edited on the creature. */
class HiveJobTest {
    private val implement = TaskDefinition(
        id = TaskDefinitionId("implement"),
        name = "Implement",
        objective = "Implement the change",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )
    private val gate = TaskDefinition(
        id = TaskDefinitionId("gate"),
        name = "Approve",
        objective = "A person approves",
        roleId = null,
        executor = TaskExecutor.HumanApproval("Approve"),
    )
    private val definition = WorkflowDefinition(WorkflowDefinitionId("job"), "job", tasks = listOf(implement, gate))

    @Test
    fun runsOnSendsTheJobToAnyPairedDeviceAndBack() {
        val away = assertIs<TerrariumDependencyEditResult.Applied>(
            setTerrariumJob(definition, implement.id, runsOn = ComputeDelegationTarget.AnyRemote),
        ).definition
        val sent = away.tasks.first { it.id == implement.id }
        assertEquals(ComputeDelegationTarget.AnyRemote, sent.computeDelegationTarget())
        val back = assertIs<TerrariumDependencyEditResult.Applied>(
            setTerrariumJob(away, implement.id, runsOn = ComputeDelegationTarget.Local),
        ).definition
        // Back on this device it is no longer distributed; its role still decides the agent.
        val home = back.tasks.first { it.id == implement.id }
        assertEquals(ComputeDelegationTarget.Local, home.computeDelegationTarget())
        assertEquals(implement.roleId, home.roleId)
        assertEquals(implement.objective, home.objective)
    }

    @Test
    fun aPersonsApprovalNeverLeavesThisDevice() {
        assertEquals(
            TerrariumDependencyEditResult.NoChange,
            setTerrariumJob(definition, gate.id, runsOn = ComputeDelegationTarget.AnyRemote),
        )
    }

    @Test
    fun runsOnStepsOffThisDeviceOnlyWhenPairedDevicesExist() {
        assertEquals(ComputeDelegationTarget.AnyRemote, nextRunsOn(ComputeDelegationTarget.Local, remoteAvailable = true))
        assertNull(nextRunsOn(ComputeDelegationTarget.Local, remoteAvailable = false))
        assertEquals(ComputeDelegationTarget.Local, nextRunsOn(ComputeDelegationTarget.AnyRemote, remoteAvailable = false))
        assertEquals(ComputeDelegationTarget.Local, nextRunsOn(ComputeDelegationTarget.Node("n1"), remoteAvailable = true))
    }

    @Test
    fun criteriaApprovalAndRetriesChangeTogetherWithoutTouchingTheRest() {
        val edited = assertIs<TerrariumDependencyEditResult.Applied>(
            setTerrariumJob(
                definition,
                implement.id,
                criteria = listOf(" Maps to a requirement ", ""),
                humanApproval = true,
                maxAttempts = 3,
            ),
        ).definition.tasks.first { it.id == implement.id }
        assertEquals(listOf("Maps to a requirement"), edited.acceptanceCriteria.map { it.description })
        assertEquals(ApprovalPolicy.HumanApproval, edited.approvalPolicy)
        assertEquals(3, edited.retryPolicy.maxAttempts)
        assertEquals(implement.executor, edited.executor)
    }

    @Test
    fun turningAPersonsApprovalOffAsksFirstAndTurningItOnDoesNot() {
        kotlin.test.assertTrue(approvalChangeNeedsConfirm(asksPersonNow = true))
        kotlin.test.assertFalse(approvalChangeNeedsConfirm(asksPersonNow = false))
    }
}
