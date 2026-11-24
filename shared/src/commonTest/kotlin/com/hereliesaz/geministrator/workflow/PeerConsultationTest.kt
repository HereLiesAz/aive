package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
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
}
