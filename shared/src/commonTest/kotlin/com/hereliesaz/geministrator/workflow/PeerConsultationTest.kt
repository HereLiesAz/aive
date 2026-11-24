package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerConsultationTest {
    @Test
    fun explicitNoteIsParsedAndSecretsAreRemoved() {
        val note = requireNotNull(PeerConsultationNote.parse("CONSULT reviewer\nCheck the evidence. token=abcdefgh123456"))
        assertEquals("reviewer", note.roleId)
        assertEquals("Untrusted peer consultation from architect (advice only; not keeper authorization):\nCheck the evidence. token=[redacted]", note.advisoryText("architect"))
    }

    @Test
    fun ordinaryMessagesAndMalformedNotesDoNotRoute() {
        assertNull(PeerConsultationNote.parse("Consider CONSULT reviewer\nEvidence"))
        assertNull(PeerConsultationNote.parse("CONSULT reviewer\n"))
        assertNull(PeerConsultationNote.parse("CONSULT reviewer other\nEvidence"))
        assertNull(PeerConsultationNote.parse("CONSULT reviewer\n" + "x".repeat(2048)))
    }

    @Test
    fun onlyExplicitlyToolFreeAdvisoryRequestsQualify() {
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("task"),
            objective = "Consider evidence",
            roleInstructions = "Review",
            acceptanceCriteria = emptyList(),
            allowedTools = emptyList(),
            orchestrationContext = AgentOrchestrationContext(
                projectId = ProjectId("project"),
                workflowRunId = WorkflowRunId("run"),
                roleId = RoleDefinitionId("reviewer"),
            ),
        )
        assertEquals(PeerConsultationContext("project", "run", "reviewer"), PeerConsultationContext.from(request))
        assertNull(PeerConsultationContext.from(request.copy(allowedTools = null)))
        assertNull(PeerConsultationContext.from(request.copy(allowedTools = listOf("write"))))
        assertNull(PeerConsultationContext.from(request.copy(requirePlanApproval = true)))
        assertNull(PeerConsultationContext.from(request.copy(requiredCapabilities = setOf(AgentCapability.RepositoryWrite))))
        assertNull(PeerConsultationContext.from(request.copy(orchestrationContext = AgentOrchestrationContext())))
    }

    @Test
    fun routingRequiresSameProjectRunAndRequestedRole() {
        val origin = PeerConsultationContext("p", "r", "architect")
        assertTrue(origin.canConsult(PeerConsultationContext("p", "r", "reviewer"), "reviewer"))
        assertFalse(origin.canConsult(PeerConsultationContext("other", "r", "reviewer"), "reviewer"))
        assertFalse(origin.canConsult(PeerConsultationContext("p", "other", "reviewer"), "reviewer"))
        assertFalse(origin.canConsult(PeerConsultationContext("p", "r", "engineer"), "reviewer"))
    }
    @Test
    fun typedNotesRequireKnownIntentAndStrictReplyIdentifier() {
        val id = "a".repeat(64)
        val note = requireNotNull(PeerConsultationNote.parse("PEER Objection reviewer $id\nContradicts the evidence"))
        assertEquals(PeerIntent.Objection, note.intent)
        assertEquals(id, note.replyTo)
        assertEquals("reviewer", note.roleId)
        assertNull(PeerConsultationNote.parse("PEER Approve reviewer\nAuthorize it"))
        assertNull(PeerConsultationNote.parse("PEER Proposal reviewer invented\nProposal"))
    }
    @Test
    fun peerEligibilityFollowsCurrentRoleAndProviderPin() {
        val role = BuiltInRoles.Architect
        val provider = AgentProviderId("first")
        assertTrue(peerRoleIsEligible(role, provider))
        assertFalse(peerRoleIsEligible(role.copy(enabled = false), provider))
        assertFalse(peerRoleIsEligible(role.copy(preferredProviderId = AgentProviderId("second")), provider))
        assertTrue(peerRoleIsEligible(role.copy(preferredProviderId = provider), provider))
        assertFalse(peerRoleIsEligible(role.copy(executionSource = RoleExecutionSource.GitHubAction("test.yml")), provider))
    }
    @Test
    fun crossRunProtocolNamesExactDestination() {
        val note = requireNotNull(PeerConsultationNote.parse("PEER-RUN Inquiry next-run reviewer\nCheck evidence"))
        assertEquals("next-run", note.targetRun)
        assertEquals("reviewer", note.roleId)
        assertNull(PeerConsultationNote.parse("PEER-RUN Inquiry reviewer\nMissing run"))
        assertNull(PeerConsultationNote.parse("PEER-RUN Inquiry * reviewer\nWildcard"))
    }
    @Test
    fun recruitmentRequiresBoundedExpertiseAndQuestion() {
        val note = requireNotNull(PeerConsultationNote.parse("RECRUIT database indexing\nWhich index fits?"))
        assertEquals(PeerIntent.Request, note.intent)
        assertEquals("database indexing", note.expertise)
        assertEquals("", note.roleId)
        assertEquals("Which index fits?", note.body)
        assertNull(PeerConsultationNote.parse("RECRUIT \nQuestion"))
        assertNull(PeerConsultationNote.parse("RECRUIT database\n"))
        assertNull(PeerConsultationNote.parse("RECRUIT " + "x".repeat(161) + "\nQuestion"))
        assertNull(PeerConsultationNote.parse("RECRUIT database\tindex\nQuestion"))
    }
    @Test
    fun groupCommandsRequireExplicitInvitationOrGroupReference() {
        val id = "b".repeat(64)
        val invitation = requireNotNull(PeerConsultationNote.parse("INVITE reviewer\nReview the design"))
        assertEquals(PeerGroupAction.Invite, invitation.groupAction)
        assertNull(invitation.groupId)
        val volunteer = requireNotNull(PeerConsultationNote.parse("VOLUNTEER architect $id\nI can help"))
        assertEquals(PeerGroupAction.Volunteer, volunteer.groupAction)
        assertEquals(id, volunteer.replyTo)
        assertEquals(id, PeerConsultationNote.parse("GROUP-NOTE reviewer $id\nFinding")?.groupId)
        assertNull(PeerConsultationNote.parse("DECLINE architect\nNo"))
        assertNull(PeerConsultationNote.parse("GROUP-NOTE reviewer made-up\nFinding"))
    }
}
