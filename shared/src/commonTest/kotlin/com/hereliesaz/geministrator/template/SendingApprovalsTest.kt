package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.policy.ApprovalEvidence
import com.hereliesaz.geministrator.policy.isAllowed
import com.hereliesaz.geministrator.workflow.decideStepEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sending is effectful and approved per recipient: nothing goes to anyone the keeper did not approve. */
class SendingApprovalsTest {
    private val email = StepPresetsSending.SendEmail

    private fun task(recipients: String, draft: String = "Hello") = StepInstantiation.task(
        email, email.base(), TaskDefinitionId("mail"), "Send an email",
        mapOf("recipients" to recipients, "draft" to draft), tool = "mail.send",
    )

    @Test
    fun sendingTemplatesAreEffectfulGatedAndTriedOnce() {
        StepPresetsSending.all.forEach {
            assertTrue(it.effectful, it.id)
            assertTrue(it.planGate, it.id)
            assertTrue(StepEffect.LeavesDevice in it.effects, it.id)
            assertEquals(ToolPick.OneChange, it.toolPick, it.id)
            assertEquals(1, it.body.maxAttempts, it.id)
            assertTrue(it.id in BuiltInTemplates.byId, it.id)
            assertTrue(it.id !in BuiltInTemplates.forPlanner().map { p -> p.id }, it.id)
        }
        val t = task("a@x.org")
        assertEquals(ApprovalPolicy.HumanApproval, t.approvalPolicy)
        assertEquals(1, t.retryPolicy.maxAttempts)
    }

    @Test
    fun recipientsAreSplitTrimmedAndDeduplicated() {
        assertEquals(listOf("a@x.org", "B@x.org"), SendingApprovals.recipientsOf(" a@x.org, B@x.org;A@X.org\n\n"))
        assertEquals(emptyList(), SendingApprovals.recipientsOf("  ,; "))
        assertEquals(emptyList(), SendingApprovals.recipientsOf("@list.rows"))
    }

    @Test
    fun oneApprovalPerRecipientEachCarryingTheDraft() {
        val approvals = SendingApprovals.approvalsFor("a@x.org, b@x.org", "Dear all")
        assertEquals(listOf("a@x.org", "b@x.org"), approvals.map { it.recipient })
        assertTrue(approvals.all { it.draft == "Dear all" })
        assertEquals(emptyList(), SendingApprovals.approvalsFor("a@x.org", "  "))
    }

    @Test
    fun nothingIsSentWithoutAnApproval() {
        val approvals = SendingApprovals.approvalsFor("a@x.org, b@x.org", "Hi")
        assertEquals(emptyList(), SendingApprovals.sendable(approvals, emptySet()))
        assertEquals(listOf("b@x.org"), SendingApprovals.sendable(approvals, setOf(" B@X.org ")).map { it.recipient })
        // A grant for someone who was never asked about adds nothing.
        assertEquals(emptyList(), SendingApprovals.sendable(approvals, setOf("c@x.org")))
        assertEquals(2, SendingApprovals.sendable(approvals, setOf("a@x.org", "b@x.org", "c@x.org")).size)
    }

    @Test
    fun aSendingStepWithoutApprovalEvidenceIsNotAllowed() {
        assertFalse(decideStepEffect(TaskDefinitionId("mail"), task("a@x.org"), ApprovalEvidence.None).isAllowed)
    }

    @Test
    fun aStepRunsOnlyWithExactlyOneRecipient() {
        assertEquals(emptyList(), StepInstantiation.missingInputs(task("a@x.org").contract!!))
        assertEquals(listOf("one recipient"), StepInstantiation.missingInputs(task("a@x.org, b@x.org").contract!!))
        assertEquals(listOf("recipients"), StepInstantiation.missingInputs(task(" ").contract!!))
        assertTrue(SendingApprovals.readyToAsk(mapOf("recipients" to "a@x.org", "draft" to "Hi")))
        assertFalse(SendingApprovals.readyToAsk(mapOf("recipients" to "a@x.org, b@x.org", "draft" to "Hi")))
        assertFalse(SendingApprovals.readyToAsk(mapOf("recipients" to "a@x.org")))
    }

    @Test
    fun aListOfRecipientsBecomesOneGatedStepEachAndDependentsWaitOnAll() {
        val after = com.hereliesaz.geministrator.domain.TaskDefinition(
            id = TaskDefinitionId("after"), name = "after", objective = "o", roleId = null, dependsOn = setOf(TaskDefinitionId("mail")),
        )
        val split = SendingApprovals.splitPerRecipient(listOf(task("a@x.org, b@x.org, c@x.org", "Hi all"), after), TaskDefinitionId("mail"))
        val sends = split.filter { it.contract != null }
        assertEquals(listOf("mail", "mail-2", "mail-3"), sends.map { it.id.value })
        assertEquals(listOf("a@x.org", "b@x.org", "c@x.org"), sends.map { it.contract!!.inputs["recipients"] })
        sends.forEach {
            assertEquals(ApprovalPolicy.HumanApproval, it.approvalPolicy)
            assertEquals(1, it.retryPolicy.maxAttempts)
            assertTrue(it.objective.contains("draft: Hi all"))
            assertEquals(emptyList(), StepInstantiation.missingInputs(it.contract!!))
        }
        assertEquals(setOf("mail", "mail-2", "mail-3"), split.first { it.id.value == "after" }.dependsOn.map { it.value }.toSet())
    }

    @Test
    fun oneRecipientOrAnotherTemplateIsLeftAlone() {
        val one = listOf(task("a@x.org"))
        assertEquals(one, SendingApprovals.splitPerRecipient(one, TaskDefinitionId("mail")))
        val other = StepInstantiation.task(
            BuiltInTemplates.WriteDocument, BuiltInTemplates.WriteDocument.base(), TaskDefinitionId("w"), "w", mapOf("brief" to "a, b"),
        )
        assertEquals(listOf(other), SendingApprovals.splitPerRecipient(listOf(other), TaskDefinitionId("w")))
    }

    @Test
    fun aListOfRecipientsIsNotSplitUntilTheDraftAndToolAreIn() {
        val noDraft = StepInstantiation.task(
            email, email.base(), TaskDefinitionId("mail"), "Send an email", mapOf("recipients" to "a@x.org, b@x.org"), tool = "mail.send",
        )
        val noTool = StepInstantiation.task(
            email, email.base(), TaskDefinitionId("mail"), "Send an email",
            mapOf("recipients" to "a@x.org, b@x.org", "draft" to "Hi"),
        )
        assertEquals(listOf(noDraft), SendingApprovals.splitPerRecipient(listOf(noDraft), TaskDefinitionId("mail")))
        assertEquals(listOf(noTool), SendingApprovals.splitPerRecipient(listOf(noTool), TaskDefinitionId("mail")))
    }

    @Test
    fun aDraftTooLongToCarryWholeIsRefusedNotCut() {
        val long = task("a@x.org", "x".repeat(StepInstantiation.MAX_INPUT_CHARS + 1)).contract!!
        assertEquals(listOf("a draft of at most 4000 characters"), StepInstantiation.missingInputs(long))
        val exact = task("a@x.org", "x".repeat(StepInstantiation.MAX_INPUT_CHARS)).contract!!
        assertEquals(emptyList(), StepInstantiation.missingInputs(exact))
    }
}
