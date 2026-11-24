package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** A redacted advisory note addressed to a specialist, not a provider session. */
@Serializable
internal data class PeerMail(
    val project: String,
    val run: String,
    val provider: String,
    val senderRole: String,
    val senderTask: String,
    val recipientRole: String,
    val text: String,
    val createdAt: Long,
)

/**
 * Durable bounded queue. Claims are removed before delivery: a crash cannot replay an ambiguous
 * provider send. Rejected or interrupted sends are not retried automatically. Scope is deliberately
 * restricted to the originating run and provider; this is not permission to disclose across runs.
 */
internal class PeerMailbox(settings: Settings, private val now: () -> Long) {
    private val store = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()
    private val json = Json
    private companion object {
        const val KEY = "hive.peer.mailbox.v1"
        const val LIMIT = 128
        const val PER_SENDER = 4
        const val TTL = 86_400_000L
    }

    private fun read(): List<PeerMail> =
        store.getStringOrNull(KEY)?.let { json.decodeFromString<List<PeerMail>>(it) }.orEmpty()

    private fun live(entries: List<PeerMail>, time: Long): List<PeerMail> =
        entries.filter { it.createdAt <= time && time - it.createdAt < TTL }

    /** Persist a note before returning success. Duplicate pending messages are not enqueued. */
    suspend fun enqueue(origin: PeerConsultationContext, provider: String, senderTask: String, note: PeerConsultationNote): Boolean =
        mutex.withLock {
            if (origin.roleId == note.roleId) return@withLock false
            val time = now()
            val entries = live(read(), time)
            val mail = PeerMail(origin.projectId, origin.runId, provider, origin.roleId, senderTask,
                note.roleId, ControlRedaction.scrub(note.body), time)
            if (entries.size >= LIMIT || entries.count {
                    it.project == mail.project && it.run == mail.run && it.provider == provider && it.senderTask == senderTask
                } >= PER_SENDER || entries.any { it.copy(createdAt = time) == mail }) return@withLock false
            store.putString(KEY, json.encodeToString(entries + mail))
            true
        }

    /** Atomically remove and return only mail addressed to this exact specialist scope. */
    suspend fun claim(recipient: PeerConsultationContext, provider: String): List<PeerMail> = mutex.withLock {
        val entries = live(read(), now())
        val (claimed, remaining) = entries.partition {
            it.project == recipient.projectId && it.run == recipient.runId &&
                it.provider == provider && it.recipientRole == recipient.roleId
        }
        store.putString(KEY, json.encodeToString(remaining))
        claimed
    }
}
