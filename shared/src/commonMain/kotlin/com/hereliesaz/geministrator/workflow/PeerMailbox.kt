package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Transport acceptance is not proof of reading, agreement or work. */
@Serializable
internal enum class PeerDelivery { Queued, Claimed, Accepted, Rejected, Uncertain, Expired }

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
    val senderSession: String? = null,
    val senderInvocation: String? = null,
    val delivery: PeerDelivery = PeerDelivery.Queued,
    val recipientSession: String? = null,
    val intent: PeerIntent = PeerIntent.Inquiry,
    val replyTo: String? = null,
    val correlationId: String? = null,
) {
    /** Stable content-derived identifier for this scoped, deduplicated envelope. */
    val id: String get() = Sha256.hex(Json.encodeToString(listOf(project, run, provider, senderRole,
        senderTask, recipientRole, text, intent.name, replyTo.orEmpty())).encodeToByteArray())
}

/**
 * Durable bounded queue and receipt ledger. A claim is persisted before transport: a crash cannot
 * replay an ambiguous send. Claimed notes remain visible until retention expires; rejected and
 * uncertain sends are not retried automatically. Scope is deliberately
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

    private fun save(entries: List<PeerMail>) = store.putString(KEY, json.encodeToString(entries))

    /** Retained receipt history for one exact scope; no payload crosses project or provider boundaries. */
    suspend fun receipts(scope: PeerConsultationContext, provider: String): List<PeerMail> = mutex.withLock {
        live(read(), now()).filter {
            it.project == scope.projectId && it.run == scope.runId && it.provider == provider &&
                (it.senderRole == scope.roleId || it.recipientRole == scope.roleId)
        }
    }

    /** Persist a note before returning success. Duplicate pending messages are not enqueued. */
    suspend fun enqueue(origin: PeerConsultationContext, provider: String, senderTask: String, note: PeerConsultationNote, senderSession: String? = null, senderInvocation: String? = null): Boolean =
        mutex.withLock {
            if (origin.roleId == note.roleId) return@withLock false
            val time = now()
            val entries = live(read(), time)
            val parent = note.replyTo?.let { id ->
                entries.singleOrNull { it.id == id && it.project == origin.projectId && it.run == origin.runId &&
                    it.provider == provider && it.recipientRole == origin.roleId && it.senderRole == note.roleId &&
                    it.recipientSession == senderSession && it.delivery in setOf(PeerDelivery.Claimed, PeerDelivery.Accepted) }
                    ?: return@withLock false
            }
            val mail = PeerMail(origin.projectId, origin.runId, provider, origin.roleId, senderTask,
                note.roleId, ControlRedaction.scrub(note.body), time, senderSession, senderInvocation,
                intent = note.intent, replyTo = parent?.id, correlationId = parent?.correlationId ?: parent?.id)
            if (entries.size >= LIMIT || entries.count {
                    it.project == mail.project && it.run == mail.run && it.provider == provider && it.senderTask == senderTask
                } >= PER_SENDER || entries.any { it.id == mail.id }) return@withLock false
            save(entries + mail)
            true
        }

    /** Persist claims before transport, preventing replay even after an interrupted send. */
    suspend fun claim(recipient: PeerConsultationContext, provider: String, session: String? = null): List<PeerMail> = mutex.withLock {
        val entries = live(read(), now())
        val claimed = mutableListOf<PeerMail>()
        val updated = entries.map {
            if (it.delivery == PeerDelivery.Queued && it.project == recipient.projectId && it.run == recipient.runId &&
                it.provider == provider && it.recipientRole == recipient.roleId) {
                it.copy(delivery = PeerDelivery.Claimed, recipientSession = session).also(claimed::add)
            } else it
        }
        save(updated)
        claimed
    }

    /** Record the transport outcome only for an exact persisted claim; never requeue it. */
    suspend fun finish(claim: PeerMail, outcome: PeerDelivery) = mutex.withLock {
        require(outcome in setOf(PeerDelivery.Accepted, PeerDelivery.Rejected, PeerDelivery.Uncertain))
        require(claim.delivery == PeerDelivery.Claimed)
        val entries = live(read(), now())
        save(entries.map { if (it == claim) it.copy(delivery = outcome) else it })
    }
}
