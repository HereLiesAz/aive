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

/** Directional Keeper consent for two exact runs and roles on one provider. No wildcard scope. */
@Serializable
internal data class PeerRoute(
    val project: String,
    val provider: String,
    val sourceRun: String,
    val sourceRole: String,
    val targetRun: String,
    val targetRole: String,
)

/** A grant generation is never reused after revocation. */
@Serializable
internal data class PeerRouteGrant(val route: PeerRoute, val generation: Long)

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
    val targetRun: String? = null,
    val grantGeneration: Long? = null,
    /** Host-derived deduplication identity for an expertise request, independent of selected peer. */
    val recruitmentKey: String? = null,
) {
    /** Stable content-derived identifier for this scoped, deduplicated envelope. */
    val id: String get() = Sha256.hex(Json.encodeToString(listOf(project, run, provider, senderRole,
        senderTask, recipientRole, text, intent.name, replyTo.orEmpty()) +
        (targetRun?.let { listOf(it, grantGeneration.toString()) } ?: emptyList())).encodeToByteArray())
    val destinationRun: String get() = targetRun ?: run
    val route: PeerRoute get() = PeerRoute(project, provider, run, senderRole, destinationRun, recipientRole)
}

/**
 * Durable bounded queue and receipt ledger. A claim is persisted before transport: a crash cannot
 * replay an ambiguous send. Claimed notes remain visible until retention expires; rejected and
 * uncertain sends are not retried automatically. Scope is deliberately
 * restricted to the originating project and provider; a different run requires an exact, current Keeper grant.
 */
internal class PeerMailbox(settings: Settings, private val now: () -> Long) {
    private val store = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()
    private val json = Json
    private companion object {
        const val KEY = "hive.peer.mailbox.v1"
        const val GRANTS = "hive.peer.grants.v1"
        const val GENERATION = "hive.peer.grant-generation.v1"
        const val MAX_GRANTS = 64
        const val LIMIT = 128
        const val PER_SENDER = 4
        const val MAX_PENDING_PER_ROLE = 4
        const val TTL = 86_400_000L
    }

    private fun read(): List<PeerMail> =
        store.getStringOrNull(KEY)?.let { json.decodeFromString<List<PeerMail>>(it) }.orEmpty()

    private fun live(entries: List<PeerMail>, time: Long): List<PeerMail> =
        entries.filter { it.createdAt <= time && time - it.createdAt < TTL }

    private fun save(entries: List<PeerMail>) = store.putString(KEY, json.encodeToString(entries))

    private fun grants(): List<PeerRouteGrant> =
        store.getStringOrNull(GRANTS)?.let { json.decodeFromString<List<PeerRouteGrant>>(it) }.orEmpty()

    /** Only a local Keeper surface may call this; route values must be verified against the run catalog there. */
    suspend fun grant(route: PeerRoute): Boolean = mutex.withLock {
        if (route.sourceRun == route.targetRun || route.sourceRole == route.targetRole ||
            listOf(route.project, route.provider, route.sourceRun, route.sourceRole, route.targetRun, route.targetRole)
                .any { !Regex("[A-Za-z0-9_.:-]{1,128}").matches(it) }) return@withLock false
        val current = grants()
        if (current.any { it.route == route }) return@withLock true
        if (current.size >= MAX_GRANTS) return@withLock false
        val generation = store.getLong(GENERATION, 0L) + 1L
        check(generation > 0L)
        store.putLong(GENERATION, generation)
        store.putString(GRANTS, json.encodeToString(current + PeerRouteGrant(route, generation)))
        true
    }

    /** Revoking a route invalidates every queued note under that grant, including after re-grant. */
    suspend fun revoke(route: PeerRoute) = mutex.withLock {
        store.putString(GRANTS, json.encodeToString(grants().filterNot { it.route == route }))
    }

    suspend fun routes(): List<PeerRoute> = mutex.withLock { grants().map { it.route } }

    /** Forget local consent and mail before replacing workflow data. Keep the grant counter monotonic. */
    suspend fun clear() = mutex.withLock {
        store.remove(GRANTS)
        store.remove(KEY)
    }

    /** Forget consent and messages for a replaced project without touching other projects. */
    suspend fun forgetProject(project: String) = mutex.withLock {
        store.putString(GRANTS, json.encodeToString(grants().filterNot { it.route.project == project }))
        save(read().filterNot { it.project == project })
    }

    /** Called again immediately before provider delivery; a stale grant is not authority. */
    suspend fun authorized(mail: PeerMail): Boolean = mutex.withLock {
        mail.destinationRun == mail.run || grants().any { it.route == mail.route && it.generation == mail.grantGeneration }
    }

    /** Retained receipt history for one exact scope; no payload crosses project or provider boundaries. */
    suspend fun receipts(scope: PeerConsultationContext, provider: String): List<PeerMail> = mutex.withLock {
        live(read(), now()).filter {
            it.project == scope.projectId && it.provider == provider &&
                ((it.run == scope.runId && it.senderRole == scope.roleId) ||
                    (it.destinationRun == scope.runId && it.recipientRole == scope.roleId))
        }
    }

    /** Pending advisory pressure, not a claim about the specialist's compute utilization. */
    suspend fun pendingCount(scope: PeerConsultationContext, provider: String): Int = mutex.withLock {
        live(read(), now()).count { it.project == scope.projectId && it.destinationRun == scope.runId &&
            it.provider == provider && it.recipientRole == scope.roleId &&
            it.delivery in setOf(PeerDelivery.Queued, PeerDelivery.Claimed) }
    }

    /** Persist a note before returning success. Duplicate pending messages are not enqueued. */
    suspend fun enqueue(origin: PeerConsultationContext, provider: String, senderTask: String, note: PeerConsultationNote, senderSession: String? = null, senderInvocation: String? = null): Boolean =
        mutex.withLock {
            if (note.roleId.isBlank() || origin.roleId == note.roleId) return@withLock false
            if (note.expertise != null && (note.targetRun != null || note.replyTo != null)) return@withLock false
            val time = now()
            val entries = live(read(), time)
            val targetRun = note.targetRun ?: origin.runId
            val route = PeerRoute(origin.projectId, provider, origin.runId, origin.roleId, targetRun, note.roleId)
            val generation = if (targetRun == origin.runId) null else
                grants().singleOrNull { it.route == route }?.generation ?: return@withLock false
            val parent = note.replyTo?.let { id ->
                entries.singleOrNull { it.id == id && it.project == origin.projectId && it.destinationRun == origin.runId && it.run == targetRun &&
                    it.provider == provider && it.recipientRole == origin.roleId && it.senderRole == note.roleId &&
                    it.recipientSession == senderSession && it.delivery in setOf(PeerDelivery.Claimed, PeerDelivery.Accepted) }
                    ?: return@withLock false
            }
            val recruitmentKey = note.expertise?.let {
                Sha256.hex(Json.encodeToString(listOf(origin.projectId, origin.runId, provider, origin.roleId,
                    senderTask, ControlRedaction.scrub(it), ControlRedaction.scrub(note.body))).encodeToByteArray())
            }
            val mail = PeerMail(origin.projectId, origin.runId, provider, origin.roleId, senderTask,
                note.roleId, ControlRedaction.scrub(note.body), time, senderSession, senderInvocation,
                intent = note.intent, replyTo = parent?.id, correlationId = parent?.correlationId ?: parent?.id,
                targetRun = targetRun.takeIf { it != origin.runId }, grantGeneration = generation,
                recruitmentKey = recruitmentKey)
            if (entries.size >= LIMIT || entries.count {
                    it.project == mail.project && it.run == mail.run && it.provider == provider && it.senderTask == senderTask
                } >= PER_SENDER || entries.any { it.id == mail.id ||
                    (recruitmentKey != null && it.recruitmentKey == recruitmentKey) } || entries.count {
                    it.project == mail.project && it.destinationRun == mail.destinationRun && it.provider == provider &&
                        it.recipientRole == mail.recipientRole && it.delivery in setOf(PeerDelivery.Queued, PeerDelivery.Claimed)
                } >= MAX_PENDING_PER_ROLE) return@withLock false
            save(entries + mail)
            true
        }

    /** Persist claims before transport, preventing replay even after an interrupted send. */
    suspend fun claim(recipient: PeerConsultationContext, provider: String, session: String? = null): List<PeerMail> = mutex.withLock {
        val entries = live(read(), now())
        val activeGrants = grants()
        val claimed = mutableListOf<PeerMail>()
        val updated = entries.map {
            if (it.delivery == PeerDelivery.Queued && it.project == recipient.projectId && it.destinationRun == recipient.runId &&
                it.provider == provider && it.recipientRole == recipient.roleId) {
                if (it.run != it.destinationRun && activeGrants.none { grant -> grant.route == it.route && grant.generation == it.grantGeneration }) {
                    it.copy(delivery = PeerDelivery.Rejected)
                } else it.copy(delivery = PeerDelivery.Claimed, recipientSession = session).also(claimed::add)
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
