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
    /** Separate permission to invoke one dormant same-run advisory specialist. */
    val activation: Boolean = false,
)

/**
 * Directional, scoped observations from retained consultation receipts. Counts are per distinct
 * initial inquiry/request, not per message or vote. Missing replies are censored observations,
 * not failures; a returned objection counts exactly like a returned supporting opinion.
 */
internal data class PeerCollaborationEvidence(
    val senderRole: String,
    val senderSpecialist: String,
    val recipientRole: String,
    val recipientSpecialist: String,
    val accepted: Int,
    val rejected: Int,
    val uncertain: Int,
    val claimed: Int,
    val replied: Int,
    val returned: Int,
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
    val groupAction: PeerGroupAction? = null,
    val groupId: String? = null,
    val groupCreatedAt: Long? = null,
    /** Exact invited/member session for directed group traffic; null for initial invitations. */
    val requiredRecipientSession: String? = null,
    val activation: Boolean = false,
    val senderSpecialist: String? = null,
    val recipientSpecialist: String? = null,
) {
    /** Stable content-derived identifier for this scoped, deduplicated envelope. */
    val id: String get() = Sha256.hex(Json.encodeToString(listOf(project, run, provider, senderRole,
        senderTask, recipientRole, text, intent.name, replyTo.orEmpty()) +
        (targetRun?.let { listOf(it, grantGeneration.toString()) } ?: emptyList()) +
        (groupAction?.let { listOf(it.name, groupId.orEmpty()) } ?: emptyList()) +
        (if (activation) listOf("activation", grantGeneration.toString()) else emptyList())).encodeToByteArray())
    val destinationRun: String get() = targetRun ?: run
    val route: PeerRoute get() = PeerRoute(project, provider, run, senderRole, destinationRun, recipientRole, activation)
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
        const val MAX_INVITEES = 3
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
        if ((route.sourceRun == route.targetRun) != route.activation || route.sourceRole == route.targetRole ||
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
        mail in live(read(), now()) && mail.delivery == PeerDelivery.Claimed &&
            (mail.groupCreatedAt?.let { now() - it < TTL } ?: true) &&
            ((mail.destinationRun == mail.run && !mail.activation) || grants().any { it.route == mail.route && it.generation == mail.grantGeneration })
    }

    /** Retained receipt history for one exact scope; no payload crosses project or provider boundaries. */
    suspend fun receipts(scope: PeerConsultationContext, provider: String): List<PeerMail> = mutex.withLock {
        live(read(), now()).filter {
            it.project == scope.projectId && it.provider == provider &&
                ((it.run == scope.runId && it.senderRole == scope.roleId) ||
                    (it.destinationRun == scope.runId && it.recipientRole == scope.roleId))
        }
    }

    /**
     * Read-only projection of the bounded receipt ledger, never a second reputation database.
     * Only exact same-project/run/provider ordinary consultations with host-attributed instances
     * and sessions qualify. Exclude invitations, wakes, cross-run routes and unattributed history.
     * Count at most one authored reply and one delivered return per accepted initial inquiry.
     */
    suspend fun collaborationEvidence(project: String, run: String, provider: String): List<PeerCollaborationEvidence> =
        mutex.withLock {
            val entries = live(read(), now()).filter {
                it.project == project && it.run == run && it.destinationRun == run &&
                    it.provider == provider && !it.activation && it.groupAction == null
            }
            val identity = Regex("[a-f0-9]{64}")
            val initial = entries.filter {
                it.replyTo == null && it.intent in setOf(PeerIntent.Inquiry, PeerIntent.Request) &&
                    it.senderSpecialist?.matches(identity) == true && it.recipientSpecialist?.matches(identity) == true &&
                    !it.senderSession.isNullOrBlank() && !it.recipientSession.isNullOrBlank() &&
                    it.delivery in setOf(PeerDelivery.Claimed, PeerDelivery.Accepted, PeerDelivery.Rejected, PeerDelivery.Uncertain)
            }
            val repliesByParent = entries.filter { it.replyTo != null }.groupBy { it.replyTo }
            initial.groupBy { listOf(it.senderRole, checkNotNull(it.senderSpecialist),
                it.recipientRole, checkNotNull(it.recipientSpecialist)) }.map { (pair, requests) ->
                val accepted = requests.filter { it.delivery == PeerDelivery.Accepted }
                var replied = 0
                var returned = 0
                for (request in accepted) {
                    val replies = repliesByParent[request.id].orEmpty().filter {
                        it.senderRole == request.recipientRole && it.recipientRole == request.senderRole &&
                            it.senderSession == request.recipientSession &&
                            it.senderSpecialist == request.recipientSpecialist &&
                            it.createdAt >= request.createdAt &&
                            it.intent in setOf(PeerIntent.Observation, PeerIntent.Hypothesis, PeerIntent.Objection,
                                PeerIntent.Proposal, PeerIntent.Verdict, PeerIntent.Resolution)
                    }
                    if (replies.isNotEmpty()) replied++
                    if (replies.any { it.delivery == PeerDelivery.Accepted &&
                            it.recipientSession == request.senderSession &&
                            it.recipientSpecialist == request.senderSpecialist }) returned++
                }
                PeerCollaborationEvidence(pair[0], pair[1], pair[2], pair[3],
                    accepted.size, requests.count { it.delivery == PeerDelivery.Rejected },
                    requests.count { it.delivery == PeerDelivery.Uncertain },
                    requests.count { it.delivery == PeerDelivery.Claimed }, replied, returned)
            }.sortedWith(compareBy<PeerCollaborationEvidence> { it.senderRole }.thenBy { it.recipientRole }
                .thenBy { it.senderSpecialist }.thenBy { it.recipientSpecialist })
        }

    /** Pending advisory pressure, not a claim about the specialist's compute utilization. */
    suspend fun pendingCount(scope: PeerConsultationContext, provider: String): Int = mutex.withLock {
        live(read(), now()).count { it.project == scope.projectId && it.destinationRun == scope.runId &&
            it.provider == provider && it.recipientRole == scope.roleId &&
            it.delivery in setOf(PeerDelivery.Queued, PeerDelivery.Claimed) }
    }

    /** Persist a note before returning success. Duplicate pending messages are not enqueued. */
    suspend fun enqueue(origin: PeerConsultationContext, provider: String, senderTask: String, note: PeerConsultationNote, senderSession: String? = null, senderInvocation: String? = null, senderSpecialist: String? = null): Boolean =
        mutex.withLock {
            if (note.roleId.isBlank() || origin.roleId == note.roleId) return@withLock false
            if (note.expertise != null && (note.targetRun != null || note.replyTo != null)) return@withLock false
            val time = now()
            val entries = live(read(), time)
            val targetRun = note.targetRun ?: origin.runId
            if (note.activation && (note.targetRun != null || note.replyTo != null || note.groupAction != null ||
                    note.expertise != null || senderSession.isNullOrBlank() || note.intent != PeerIntent.Request)) return@withLock false
            val route = PeerRoute(origin.projectId, provider, origin.runId, origin.roleId, targetRun, note.roleId, note.activation)
            val generation = if (targetRun == origin.runId && !note.activation) null else
                grants().singleOrNull { it.route == route }?.generation ?: return@withLock false
            val parent = note.replyTo?.let { id ->
                entries.singleOrNull { it.id == id && it.project == origin.projectId && it.destinationRun == origin.runId && it.run == targetRun &&
                    it.provider == provider && it.recipientRole == origin.roleId && it.senderRole == note.roleId &&
                    it.recipientSession == senderSession && it.delivery in setOf(PeerDelivery.Claimed, PeerDelivery.Accepted) }
                    ?: return@withLock false
            }
            var requiredRecipientSession: String? = null
            var groupCreatedAt: Long? = null
            val groupId = when (note.groupAction) {
                null -> {
                    if (note.groupId != null) return@withLock false
                    null
                }
                PeerGroupAction.Invite -> {
                    if (note.targetRun != null || note.replyTo != null || note.expertise != null ||
                        senderSession.isNullOrBlank()) return@withLock false
                    val id = note.groupId ?: Sha256.hex(Json.encodeToString(listOf("group", origin.projectId,
                        origin.runId, provider, origin.roleId, senderTask, senderSession, note.roleId,
                        ControlRedaction.scrub(note.body))).encodeToByteArray())
                    val invitations = entries.filter { it.groupId == id && it.groupAction == PeerGroupAction.Invite }
                    if (note.groupId != null && invitations.isEmpty()) return@withLock false
                    groupCreatedAt = invitations.firstOrNull()?.groupCreatedAt ?: time
                    if (time - checkNotNull(groupCreatedAt) >= TTL) return@withLock false
                    if (invitations.any { it.project != origin.projectId || it.run != origin.runId ||
                            it.provider != provider || it.senderRole != origin.roleId ||
                            it.senderTask != senderTask || it.senderSession != senderSession } ||
                        invitations.size >= MAX_INVITEES || invitations.any { it.recipientRole == note.roleId }) return@withLock false
                    id
                }
                PeerGroupAction.Volunteer, PeerGroupAction.Decline -> {
                    if (note.targetRun != null || note.groupId != null || note.expertise != null ||
                        senderSession.isNullOrBlank() || parent == null || parent.groupAction != PeerGroupAction.Invite ||
                        parent.groupId == null || parent.senderSession.isNullOrBlank()) return@withLock false
                    if (entries.any { it.replyTo == parent.id &&
                            it.groupAction in setOf(PeerGroupAction.Volunteer, PeerGroupAction.Decline) }) return@withLock false
                    groupCreatedAt = parent.groupCreatedAt ?: return@withLock false
                    if (time - checkNotNull(groupCreatedAt) >= TTL) return@withLock false
                    requiredRecipientSession = parent.senderSession
                    parent.groupId
                }
                PeerGroupAction.Note -> {
                    if (note.targetRun != null || note.replyTo != null || note.expertise != null ||
                        senderSession.isNullOrBlank() || note.groupId == null) return@withLock false
                    val invitations = entries.filter { it.project == origin.projectId && it.run == origin.runId &&
                        it.provider == provider && it.groupId == note.groupId && it.groupAction == PeerGroupAction.Invite }
                    val first = invitations.firstOrNull() ?: return@withLock false
                    groupCreatedAt = first.groupCreatedAt ?: return@withLock false
                    if (time - checkNotNull(groupCreatedAt) >= TTL) return@withLock false
                    val members = mutableMapOf(first.senderRole to first.senderSession)
                    invitations.forEach { invitation ->
                        val accepted = entries.singleOrNull { it.replyTo == invitation.id &&
                            it.groupId == note.groupId && it.groupAction == PeerGroupAction.Volunteer &&
                            it.delivery == PeerDelivery.Accepted }
                        if (accepted != null) members[invitation.recipientRole] = invitation.recipientSession
                    }
                    if (members[origin.roleId] != senderSession) return@withLock false
                    requiredRecipientSession = members[note.roleId]?.takeIf { it.isNotBlank() } ?: return@withLock false
                    note.groupId
                }
            }
            val recruitmentKey = note.expertise?.let {
                Sha256.hex(Json.encodeToString(listOf(origin.projectId, origin.runId, provider, origin.roleId,
                    senderTask, ControlRedaction.scrub(it), ControlRedaction.scrub(note.body))).encodeToByteArray())
            }
            val mail = PeerMail(origin.projectId, origin.runId, provider, origin.roleId, senderTask,
                note.roleId, ControlRedaction.scrub(note.body), time, senderSession, senderInvocation,
                intent = note.intent, replyTo = parent?.id, correlationId = parent?.correlationId ?: parent?.id,
                targetRun = targetRun.takeIf { it != origin.runId }, grantGeneration = generation,
                recruitmentKey = recruitmentKey, groupAction = note.groupAction, groupId = groupId,
                requiredRecipientSession = requiredRecipientSession, groupCreatedAt = groupCreatedAt,
                activation = note.activation, senderSpecialist = senderSpecialist)
            if (note.activation && entries.any { it.activation && it.project == mail.project && it.run == mail.run &&
                    it.provider == provider && it.recipientRole == mail.recipientRole }) return@withLock false
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
    suspend fun claim(recipient: PeerConsultationContext, provider: String, session: String? = null, specialist: String? = null): List<PeerMail> = mutex.withLock {
        val entries = live(read(), now())
        val activeGrants = grants()
        val claimed = mutableListOf<PeerMail>()
        val updated = entries.map {
            if (!it.activation && it.delivery == PeerDelivery.Queued && it.project == recipient.projectId && it.destinationRun == recipient.runId &&
                it.provider == provider && it.recipientRole == recipient.roleId) {
                if ((it.groupCreatedAt?.let { created -> now() - created >= TTL } ?: false) ||
                    (it.requiredRecipientSession != null && it.requiredRecipientSession != session)) {
                    it.copy(delivery = PeerDelivery.Rejected)
                } else if (it.run != it.destinationRun && activeGrants.none { grant -> grant.route == it.route && grant.generation == it.grantGeneration }) {
                    it.copy(delivery = PeerDelivery.Rejected)
                } else it.copy(delivery = PeerDelivery.Claimed, recipientSession = session, recipientSpecialist = specialist).also(claimed::add)
            } else it
        }
        save(updated)
        claimed
    }

    /**
     * Claim an exact sender's dormant invocation before inference. Ordinary live delivery cannot
     * claim this note; revocation or restart cannot turn an old claim into fresh inference.
     */
    suspend fun claimActivation(origin: PeerConsultationContext, provider: String, task: String,
        session: String, target: String): PeerMail? = mutex.withLock {
        val entries = live(read(), now())
        val note = entries.singleOrNull {
            it.activation && it.delivery == PeerDelivery.Queued && it.project == origin.projectId &&
                it.run == origin.runId && it.provider == provider && it.senderRole == origin.roleId &&
                it.senderTask == task && it.senderSession == session && it.recipientRole == target
        } ?: return@withLock null
        val allowed = grants().any { it.route == note.route && it.generation == note.grantGeneration }
        val next = note.copy(delivery = if (allowed) PeerDelivery.Claimed else PeerDelivery.Rejected)
        save(entries.map { if (it == note) next else it })
        next.takeIf { allowed }
    }

    /** Record the transport outcome only for an exact persisted claim; never requeue it. */
    suspend fun finish(claim: PeerMail, outcome: PeerDelivery, specialist: String? = null) = mutex.withLock {
        require(outcome in setOf(PeerDelivery.Accepted, PeerDelivery.Rejected, PeerDelivery.Uncertain))
        require(claim.delivery == PeerDelivery.Claimed)
        val entries = live(read(), now())
        save(entries.map { if (it == claim) it.copy(delivery = outcome, recipientSpecialist = specialist ?: it.recipientSpecialist) else it })
    }
}
