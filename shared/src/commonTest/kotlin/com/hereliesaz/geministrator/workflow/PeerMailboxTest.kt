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
}
