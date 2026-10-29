package com.hereliesaz.geministrator.duplex

import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Peer names are connection slots, never provider or role identities. */
enum class DuplexPeer { A, B }
enum class DuplexKind { Message, Event, Request, Response, Cancel }

data class DuplexEnvelope(
    val seq: Long,
    val id: String,
    val from: DuplexPeer,
    val kind: DuplexKind,
    val body: String,
    val replyTo: String? = null,
    val time: Long,
)

/**
 * A bounded, in-process two-party conversation. The independent queues make both directions live
 * simultaneously. Acknowledgement, not delivery, frees capacity; an unacknowledged message may be
 * seen again after reconnect. A process restart loses the queue. Receiving never confers authority.
 *
 * Transport adapters must authenticate each peer before invoking this bus.
 */
class DuplexAgentBus(
    private val nowMillis: () -> Long,
    private val maxInbox: Int = 128,
    private val maxBodyBytes: Int = 32_768,
    private val maxPendingRequests: Int = 256,
    private val pendingTtlMillis: Long = 10 * 60_000L,
) {
    init {
        require(maxInbox > 0 && maxBodyBytes > 0 && maxPendingRequests > 0 && pendingTtlMillis > 0)
    }

    private data class Inbox(
        var next: Long = 1,
        var acked: Long = 0,
        val queued: MutableList<DuplexEnvelope> = mutableListOf(),
        val signals: MutableSharedFlow<DuplexEnvelope> = MutableSharedFlow(extraBufferCapacity = 128),
    )
    private data class Outstanding(val from: DuplexPeer, val createdAt: Long)

    private val mutex = Mutex()
    private val inboxes = mapOf(DuplexPeer.A to Inbox(), DuplexPeer.B to Inbox())
    private val outstanding = mutableMapOf<String, Outstanding>()
    private val instanceId = Random.nextLong().toULong().toString(16)
    private var nextId = 1L

    suspend fun send(from: DuplexPeer, kind: DuplexKind, body: String, replyTo: String? = null): DuplexEnvelope =
        mutex.withLock {
            require(body.encodeToByteArray().size <= maxBodyBytes) { "duplex message exceeds byte limit" }
            require(replyTo == null || replyTo.length <= 128) { "invalid replyTo" }
            require((kind == DuplexKind.Response || kind == DuplexKind.Cancel) == (replyTo != null)) {
                "responses and cancellations must reference an outstanding request"
            }
            val target = inboxes.getValue(from.opposite())
            check(target.queued.size < maxInbox) { "peer inbox full; acknowledge messages to proceed" }
            val now = nowMillis()
            outstanding.entries.removeAll { now - it.value.createdAt >= pendingTtlMillis }
            if (kind == DuplexKind.Request) check(outstanding.size < maxPendingRequests) {
                "too many pending requests"
            }
            if (kind == DuplexKind.Response) {
                require(outstanding[replyTo]?.from == from.opposite()) { "unknown request from other peer" }
            }
            if (kind == DuplexKind.Cancel) {
                require(outstanding[replyTo]?.from == from) { "unknown request owned by this peer" }
            }
            val envelope = DuplexEnvelope(
                seq = target.next++,
                id = "duplex-$instanceId-${nextId++}",
                from = from,
                kind = kind,
                body = body,
                replyTo = replyTo,
                time = now,
            )
            if (kind == DuplexKind.Request) outstanding[envelope.id] = Outstanding(from, now)
            if (kind == DuplexKind.Response || kind == DuplexKind.Cancel) outstanding.remove(replyTo)
            target.queued.add(envelope)
            target.signals.tryEmit(envelope)
            envelope
        }

    suspend fun receive(peer: DuplexPeer, after: Long? = null, limit: Int = 50): List<DuplexEnvelope> =
        mutex.withLock {
            val box = inboxes.getValue(peer)
            val cursor = after ?: box.acked
            require(cursor in box.acked until box.next) { "invalid or acknowledged cursor" }
            require(limit in 1..100) { "limit must be 1..100" }
            box.queued.asSequence().filter { it.seq > cursor }.take(limit).toList()
        }

    suspend fun acknowledge(peer: DuplexPeer, through: Long): Int = mutex.withLock {
        val box = inboxes.getValue(peer)
        require(through in box.acked until box.next) { "acknowledgement outside delivered sequence" }
        val before = box.queued.size
        box.queued.removeAll { it.seq <= through }
        box.acked = through
        before - box.queued.size
    }

    suspend fun status(peer: DuplexPeer): DuplexInboxStatus = mutex.withLock {
        val box = inboxes.getValue(peer)
        DuplexInboxStatus(box.queued.size, box.acked, box.next - 1)
    }

    /** Lossy wake-up only; always call [receive] after subscribing or reconnecting. */
    fun notifications(peer: DuplexPeer): Flow<DuplexEnvelope> = inboxes.getValue(peer).signals.asSharedFlow()
}

data class DuplexInboxStatus(val queued: Int, val acknowledgedThrough: Long, val latestSequence: Long)

private fun DuplexPeer.opposite(): DuplexPeer = if (this == DuplexPeer.A) DuplexPeer.B else DuplexPeer.A
