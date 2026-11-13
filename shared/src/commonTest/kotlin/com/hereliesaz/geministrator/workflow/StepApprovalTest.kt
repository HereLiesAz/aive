package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TemplateRef
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.policy.ApprovalEvidence
import com.hereliesaz.geministrator.policy.PolicyDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StepApprovalTest {
    private val worker = BuiltInRoles.Worker

    private fun step(
        id: String,
        effects: Set<StepEffect>,
        approval: ApprovalPolicy = ApprovalPolicy.None,
        dependsOn: Set<TaskDefinitionId> = emptySet(),
    ) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "o",
        roleId = worker.id,
        dependsOn = dependsOn,
        approvalPolicy = approval,
        contract = TaskContract(TemplateRef("change-connected-source", 1), effects = effects),
    )

    private fun workflow(vararg tasks: TaskDefinition) =
        WorkflowDefinition(WorkflowDefinitionId("w"), "w", tasks = tasks.toList())

    @Test
    fun aStepWithAnEffectAndNoApprovalIsInvalid() {
        val errors = WorkflowGraphValidator.validate(workflow(step("a", setOf(StepEffect.MutatesConnectedSource))))
        assertEquals(listOf(WorkflowValidationError.MissingStepApproval(TaskDefinitionId("a"))), errors)
    }

    @Test
    fun aPlanGateOrAnApprovalAncestorMakesItValid() {
        val gated = step("a", setOf(StepEffect.MutatesConnectedSource), approval = ApprovalPolicy.HumanApproval)
        assertTrue(WorkflowGraphValidator.validate(workflow(gated)).isEmpty())
        val approval = TaskDefinition(
            id = TaskDefinitionId("ok"),
            name = "ok",
            objective = "o",
            roleId = null,
            executor = TaskExecutor.HumanApproval(),
        )
        val after = step("b", setOf(StepEffect.DestructiveConnectedSource), dependsOn = setOf(approval.id))
        assertTrue(WorkflowGraphValidator.validate(workflow(approval, after)).isEmpty())
    }

    @Test
    fun aStepThatOnlyReadsNeedsNoApproval() {
        assertTrue(WorkflowGraphValidator.validate(workflow(step("a", setOf(StepEffect.ReadsConnectedSource)))).isEmpty())
        assertTrue(WorkflowGraphValidator.validate(workflow(step("a", setOf(StepEffect.None)))).isEmpty())
    }

    @Test
    fun theDecisionNeedsEvidence() {
        val task = step("a", setOf(StepEffect.MutatesConnectedSource))
        assertIs<PolicyDecision.RequireApproval>(decideStepEffect(task.id, task, ApprovalEvidence.None))
        assertTrue(decideStepEffect(task.id, task, ApprovalEvidence.Granted) is PolicyDecision.Allow)
    }

    @Test
    fun evidenceFollowsThePlanGateAndTheAncestor() {
        val plain = step("a", setOf(StepEffect.MutatesConnectedSource))
        val gated = plain.copy(approvalPolicy = ApprovalPolicy.HumanApproval)
        assertEquals(ApprovalEvidence.None, stepApprovalEvidence(plain, hasApprovalAncestor = false, granted = false))
        assertEquals(ApprovalEvidence.Declared, stepApprovalEvidence(plain, hasApprovalAncestor = true, granted = false))
        assertEquals(ApprovalEvidence.Granted, stepApprovalEvidence(plain, hasApprovalAncestor = true, granted = true))
        assertEquals(ApprovalEvidence.Granted, stepApprovalEvidence(gated, hasApprovalAncestor = false, granted = false))
    }

    @Test
    fun effectsMakeAStepAbleToMutateEvenForAGenericRole() {
        val executor = TaskExecutor.RoleAgent(worker.id)
        val reader = step("r", setOf(StepEffect.ReadsConnectedSource))
        val writer = step("w", setOf(StepEffect.MutatesConnectedSource))
        val plain = TaskDefinition(TaskDefinitionId("p"), "p", "o", worker.id)
        assertFalse(executor.canMutate(plain, worker))
        assertFalse(executor.canMutate(reader, worker))
        assertTrue(executor.canMutate(writer, worker))
        assertTrue(executor.canMutate(step("l", setOf(StepEffect.LeavesDevice)), worker))
    }
}
