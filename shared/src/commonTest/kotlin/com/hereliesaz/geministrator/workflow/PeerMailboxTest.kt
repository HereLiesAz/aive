package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.persistence.InMemorySettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerMailboxTest {
    private val sender = PeerConsultationContext("project", "run", "architect")
    private val recipient = PeerConsultationContext("project", "run", "reviewer")

    @Test
    fun dormantActivationRequiresSeparateExactConsentAndCannotBeReplayed() = runBlocking {
        val settings = InMemorySettings()
        val mailbox = PeerMailbox(settings) { 100L }
        val route = PeerRoute("project", "provider", "run", "architect", "run", "reviewer", activation = true)
        val note = PeerConsultationNote("reviewer", "Question", PeerIntent.Request, activation = true)
        assertFalse(mailbox.grant(route.copy(activation = false)))
        assertFalse(mailbox.enqueue(sender, "provider", "task", note, "sender-session"))
        assertTrue(mailbox.grant(route))
        assertFalse(mailbox.enqueue(sender, "other-provider", "task", note, "sender-session"))
        assertTrue(mailbox.enqueue(sender, "provider", "task", note, "sender-session",
            senderSpecialist = "specialist-a"))
        assertTrue(mailbox.claim(recipient, "provider", "live-session").isEmpty())
        assertEquals(null, mailbox.claimActivation(sender, "provider", "task", "wrong-session", "reviewer"))
        val claim = requireNotNull(mailbox.claimActivation(sender, "provider", "task", "sender-session", "reviewer"))
        assertTrue(mailbox.authorized(claim))
        assertEquals("specialist-a", claim.senderSpecialist)
        val restarted = PeerMailbox(settings) { 200L }
        assertEquals(null, restarted.claimActivation(sender, "provider", "task", "sender-session", "reviewer"))
        restarted.revoke(route)
        assertTrue(restarted.grant(route))
        assertFalse(restarted.authorized(claim))
        assertFalse(restarted.enqueue(sender, "provider", "other-task", note.copy(body = "Different"), "new-session"))
        restarted.finish(claim, PeerDelivery.Uncertain)
        assertEquals(PeerDelivery.Uncertain, restarted.receipts(recipient, "provider").single().delivery)
    }

    @Test
    fun messagesSurviveReconstructionAndAreClaimedOnlyOnce() = runBlocking {
        val settings = InMemorySettings()
        val first = PeerMailbox(settings) { 100L }
        assertTrue(first.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Check evidence")))
        val second = PeerMailbox(settings) { 200L }
        val messages = second.claim(recipient, "provider")
        assertEquals(1, messages.size)
        assertEquals("Check evidence", messages.single().text)
        assertEquals("architect", messages.single().senderRole)
        assertTrue(PeerMailbox(settings) { 300L }.claim(recipient, "provider").isEmpty())
    }

    @Test
    fun isolationAndRedactionSurvivePersistence() = runBlocking {
        val mailbox = PeerMailbox(InMemorySettings()) { 100L }
        assertTrue(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "token=abcdefgh123456")))
        assertTrue(mailbox.claim(recipient.copy(projectId = "other"), "provider").isEmpty())
        assertTrue(mailbox.claim(recipient.copy(runId = "other"), "provider").isEmpty())
        assertTrue(mailbox.claim(recipient.copy(roleId = "other"), "provider").isEmpty())
        assertTrue(mailbox.claim(recipient, "other").isEmpty())
        assertEquals("token=[redacted]", mailbox.claim(recipient, "provider").single().text)
    }

    @Test
    fun duplicateSelfAddressedAndExpiredMailIsNotDelivered() = runBlocking {
        var time = 100L
        val mailbox = PeerMailbox(InMemorySettings()) { time }
        val note = PeerConsultationNote("reviewer", "Check")
        assertTrue(mailbox.enqueue(sender, "provider", "task", note))
        assertFalse(mailbox.enqueue(sender, "provider", "task", note))
        assertFalse(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("architect", "Self")))
        time += 86_400_000L
        assertTrue(mailbox.claim(recipient, "provider").isEmpty())
    }

    @Test
    fun senderCannotFillQueueWithoutBound() = runBlocking {
        val mailbox = PeerMailbox(InMemorySettings()) { 100L }
        repeat(4) { index ->
            assertTrue(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Note $index")))
        }
        assertFalse(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Fifth")))
        assertEquals(listOf("Note 0", "Note 1", "Note 2", "Note 3"), mailbox.claim(recipient, "provider").map { it.text })
    }
    @Test
    fun receiptsRetainProvenanceAndSuppressReplayAfterTransport() = runBlocking {
        val settings = InMemorySettings()
        val first = PeerMailbox(settings) { 100L }
        val note = PeerConsultationNote("reviewer", "Check the evidence")
        assertTrue(first.enqueue(sender, "provider", "task", note, "origin-session", "invocation"))
        val claim = first.claim(recipient, "provider", "receiver-session").single()
        assertEquals(PeerDelivery.Claimed, claim.delivery)
        first.finish(claim, PeerDelivery.Accepted)
        val restarted = PeerMailbox(settings) { 200L }
        val receipt = restarted.receipts(recipient, "provider").single()
        assertEquals(PeerDelivery.Accepted, receipt.delivery)
        assertEquals("origin-session", receipt.senderSession)
        assertEquals("receiver-session", receipt.recipientSession)
        assertEquals("invocation", receipt.senderInvocation)
        assertFalse(restarted.enqueue(sender, "provider", "task", note, "origin-session", "invocation"))
        assertTrue(restarted.claim(recipient, "provider").isEmpty())
        assertTrue(restarted.receipts(recipient.copy(projectId = "another"), "provider").isEmpty())
    }

    @Test
    fun interruptedAndRejectedClaimsAreNeverReplayed() = runBlocking {
        val settings = InMemorySettings()
        val mailbox = PeerMailbox(settings) { 100L }
        assertTrue(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "First")))
        assertTrue(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Second")))
        val claims = mailbox.claim(recipient, "provider")
        mailbox.finish(claims.last(), PeerDelivery.Rejected)
        val restarted = PeerMailbox(settings) { 200L }
        assertEquals(listOf(PeerDelivery.Claimed, PeerDelivery.Rejected), restarted.receipts(recipient, "provider").map { it.delivery })
        assertTrue(restarted.claim(recipient, "provider").isEmpty())
    }
    @Test
    fun repliesMustReferenceAnActualDeliveredMessageFromTheSameSession() = runBlocking {
        val mailbox = PeerMailbox(InMemorySettings()) { 100L }
        assertTrue(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Question"), "origin"))
        val claim = mailbox.claim(recipient, "provider", "receiver").single()
        mailbox.finish(claim, PeerDelivery.Accepted)
        val answer = PeerConsultationNote("architect", "Contrary evidence", PeerIntent.Objection, claim.id)
        assertFalse(mailbox.enqueue(recipient, "provider", "task2", answer, "wrong-session"))
        assertFalse(mailbox.enqueue(recipient.copy(projectId = "other"), "provider", "task2", answer, "receiver"))
        assertTrue(mailbox.enqueue(recipient, "provider", "task2", answer, "receiver"))
        val reply = mailbox.claim(sender, "provider", "origin").single()
        assertEquals(PeerIntent.Objection, reply.intent)
        assertEquals(claim.id, reply.replyTo)
        assertEquals(claim.id, reply.correlationId)
        assertTrue(claim.id != reply.id)
    }
    @Test
    fun exactCrossRunConsentSurvivesRestartAndRevocationInvalidatesOldNotes() = runBlocking {
        val settings = InMemorySettings()
        val mailbox = PeerMailbox(settings) { 100L }
        val destination = recipient.copy(runId = "next-run")
        val route = PeerRoute("project", "provider", "run", "architect", "next-run", "reviewer")
        val note = PeerConsultationNote("reviewer", "Check evidence", targetRun = "next-run")
        assertFalse(mailbox.enqueue(sender, "provider", "task", note))
        assertTrue(mailbox.grant(route))
        assertFalse(mailbox.enqueue(sender.copy(projectId = "other"), "provider", "task", note))
        assertFalse(mailbox.enqueue(sender, "other-provider", "task", note))
        assertFalse(mailbox.enqueue(destination, "provider", "task2",
            PeerConsultationNote("architect", "Reverse direction", targetRun = "run")))
        assertTrue(mailbox.enqueue(sender, "provider", "task", note))
        val restarted = PeerMailbox(settings) { 200L }
        assertEquals(listOf(route), restarted.routes())
        assertTrue(restarted.claim(recipient, "provider").isEmpty())
        restarted.revoke(route)
        assertTrue(restarted.grant(route))
        assertTrue(restarted.claim(destination, "provider").isEmpty())
        assertEquals(PeerDelivery.Rejected, restarted.receipts(destination, "provider").single().delivery)
        assertTrue(restarted.enqueue(sender, "provider", "task", note))
        val claim = restarted.claim(destination, "provider", "receiver").single()
        assertEquals("run", claim.run)
        assertEquals("next-run", claim.destinationRun)
        assertTrue(restarted.authorized(claim))
        restarted.revoke(route)
        assertFalse(restarted.authorized(claim))
    }
    @Test
    fun projectReplacementRemovesOnlyItsLocalConsentAndMail() = runBlocking {
        val mailbox = PeerMailbox(InMemorySettings()) { 100L }
        val route = PeerRoute("project", "provider", "run", "architect", "next-run", "reviewer")
        val other = route.copy(project = "other")
        assertTrue(mailbox.grant(route))
        assertTrue(mailbox.grant(other))
        assertTrue(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Old")))
        assertTrue(mailbox.enqueue(sender.copy(projectId = "other"), "provider", "task", PeerConsultationNote("reviewer", "Keep")))
        mailbox.forgetProject("project")
        assertEquals(listOf(other), mailbox.routes())
        assertTrue(mailbox.receipts(recipient, "provider").isEmpty())
        assertEquals("Keep", mailbox.claim(recipient.copy(projectId = "other"), "provider").single().text)
        mailbox.clear()
        assertTrue(mailbox.routes().isEmpty())
        assertTrue(mailbox.receipts(recipient.copy(projectId = "other"), "provider").isEmpty())
    }
    @Test
    fun replayedRecruitmentCannotSelectAnotherPeerAfterRestart() = runBlocking {
        val settings = InMemorySettings()
        val first = PeerMailbox(settings) { 100L }
        val note = PeerConsultationNote("reviewer", "Check evidence", PeerIntent.Request, expertise = "review")
        assertTrue(first.enqueue(sender, "provider", "task", note))
        first.finish(first.claim(recipient, "provider", "receiver").single(), PeerDelivery.Accepted)
        val restarted = PeerMailbox(settings) { 200L }
        assertFalse(restarted.enqueue(sender, "provider", "task", note.copy(roleId = "another")))
        assertTrue(restarted.claim(recipient.copy(roleId = "another"), "provider").isEmpty())
    }

    @Test
    fun pendingPressureIsBoundedAcrossMultipleSendersAndReleasedOnOutcome() = runBlocking {
        val mailbox = PeerMailbox(InMemorySettings()) { 100L }
        repeat(4) { i ->
            assertTrue(mailbox.enqueue(sender.copy(roleId = "sender-$i"), "provider", "task-$i",
                PeerConsultationNote("reviewer", "Question $i")))
        }
        assertEquals(4, mailbox.pendingCount(recipient, "provider"))
        val fifth = PeerConsultationNote("reviewer", "Fifth")
        assertFalse(mailbox.enqueue(sender, "provider", "fifth-task", fifth))
        val claims = mailbox.claim(recipient, "provider", "receiver")
        assertEquals(4, mailbox.pendingCount(recipient, "provider"))
        mailbox.finish(claims.first(), PeerDelivery.Accepted)
        assertEquals(3, mailbox.pendingCount(recipient, "provider"))
        assertTrue(mailbox.enqueue(sender, "provider", "fifth-task", fifth))
        assertEquals(0, mailbox.pendingCount(recipient.copy(runId = "other"), "provider"))
    }
    @Test
    fun groupMembershipRequiresVoluntaryConfirmedInvitationAndExactSession() = runBlocking {
        val settings = InMemorySettings()
        val first = PeerMailbox(settings) { 100L }
        val invitation = PeerConsultationNote("reviewer", "Review the design", PeerIntent.Request,
            groupAction = PeerGroupAction.Invite)
        assertTrue(first.enqueue(sender, "provider", "organizer-task", invitation, "organizer"))
        val delivered = first.claim(recipient, "provider", "reviewer-session").single()
        first.finish(delivered, PeerDelivery.Accepted)
        val group = requireNotNull(delivered.groupId)
        val volunteer = PeerConsultationNote("architect", "I can help", PeerIntent.Observation,
            replyTo = delivered.id, groupAction = PeerGroupAction.Volunteer)
        val groupNote = PeerConsultationNote("reviewer", "Design finding", PeerIntent.Observation,
            groupAction = PeerGroupAction.Note, groupId = group)
        assertFalse(first.enqueue(sender, "provider", "organizer-task", groupNote, "organizer"))
        assertFalse(first.enqueue(recipient, "provider", "review-task", volunteer, "impostor"))
        assertTrue(first.enqueue(recipient, "provider", "review-task", volunteer, "reviewer-session"))
        assertFalse(first.enqueue(recipient, "provider", "review-task", volunteer.copy(groupAction = PeerGroupAction.Decline), "reviewer-session"))
        assertFalse(first.enqueue(sender, "provider", "organizer-task", groupNote, "organizer"))
        first.finish(first.claim(sender, "provider", "organizer").single(), PeerDelivery.Accepted)
        val restarted = PeerMailbox(settings) { 200L }
        assertTrue(restarted.enqueue(sender, "provider", "organizer-task", groupNote, "organizer"))
        assertFalse(restarted.enqueue(sender.copy(projectId = "other"), "provider", "organizer-task", groupNote, "organizer"))
        assertFalse(restarted.enqueue(sender, "provider", "another-task", groupNote.copy(body = "Impostor"), "another-session"))
        assertTrue(restarted.claim(recipient, "provider", "replacement-session").isEmpty())
        assertEquals(PeerDelivery.Rejected, restarted.receipts(recipient, "provider").last().delivery)
    }

    @Test
    fun declinedInviteCannotJoinOrReceiveGroupNotes() = runBlocking {
        val mailbox = PeerMailbox(InMemorySettings()) { 100L }
        assertTrue(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Purpose",
            PeerIntent.Request, groupAction = PeerGroupAction.Invite), "host"))
        val invite = mailbox.claim(recipient, "provider", "guest").single()
        mailbox.finish(invite, PeerDelivery.Accepted)
        val decline = PeerConsultationNote("architect", "No capacity", PeerIntent.Observation,
            replyTo = invite.id, groupAction = PeerGroupAction.Decline)
        assertTrue(mailbox.enqueue(recipient, "provider", "review-task", decline, "guest"))
        mailbox.finish(mailbox.claim(sender, "provider", "host").single(), PeerDelivery.Accepted)
        assertFalse(mailbox.enqueue(recipient, "provider", "review-task", decline.copy(groupAction = PeerGroupAction.Volunteer), "guest"))
        assertFalse(mailbox.enqueue(sender, "provider", "task", PeerConsultationNote("reviewer", "Finding",
            groupAction = PeerGroupAction.Note, groupId = invite.groupId), "host"))
    }
    @Test
    fun groupInviteLimitAndLifetimeCannotBeExtendedByLaterInvitations() = runBlocking {
        var time = 100L
        val mailbox = PeerMailbox(InMemorySettings()) { time }
        val first = PeerConsultationNote("reviewer", "Purpose", PeerIntent.Request, groupAction = PeerGroupAction.Invite)
        assertTrue(mailbox.enqueue(sender, "provider", "task", first, "host"))
        val initial = mailbox.claim(recipient, "provider", "guest").single()
        mailbox.finish(initial, PeerDelivery.Accepted)
        val group = requireNotNull(initial.groupId)
        time += 1000L
        assertTrue(mailbox.enqueue(sender, "provider", "task", first.copy(roleId = "worker", groupId = group), "host"))
        assertTrue(mailbox.enqueue(sender, "provider", "task", first.copy(roleId = "designer", groupId = group), "host"))
        assertFalse(mailbox.enqueue(sender, "provider", "task", first.copy(roleId = "researcher", groupId = group), "host"))
        time = 86_400_100L
        assertTrue(mailbox.claim(recipient.copy(roleId = "worker"), "provider", "worker-session").isEmpty())
        assertFalse(mailbox.enqueue(sender, "provider", "task", first.copy(roleId = "researcher", groupId = group), "host"))
    }
}
