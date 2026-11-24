package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Local advisory attention checkpoints. Only a caller with a stable conversation and participant
 * identity can resume one; scope is part of the key, not permission to read a memory bank.
 * No prompt, reply, raw tag, raw identity or memory address is stored. A bounded, expiring ledger
 * uses the existing generation-based Settings persistence.
 */
internal class DirectAttentionStore(settings: Settings, private val storageKey: String = "aive.memory.direct-attention.v1") {
    private val store = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()

    @Serializable
    private data class Entry(val key: String, val updatedAt: Long, val checkpoint: AttentionCheckpoint)

    private companion object {
        const val LIMIT = 64
        const val TTL_MILLIS = 7 * 24 * 60 * 60 * 1000L
        val serializer = ListSerializer(Entry.serializer())
    }

    /** Serialize mutation and checkpoint replacement so concurrent turns cannot lose novelty state. */
    suspend fun <T> advance(
        workflowIds: List<String>,
        roleId: String,
        conversationId: String,
        participantId: String,
        now: Long,
        policy: MemoryAttentionPolicy,
        level: Float,
        block: suspend (AttentionGatedRecall) -> T,
    ): T = mutex.withLock {
        require(conversationId.isNotBlank() && participantId.isNotBlank())
        val key = fingerprint(listOf(conversationId, participantId, roleId) + workflowIds.distinct().sorted())
        val entries = retained(now)
        val gate = AttentionGatedRecall(policy, MemoryAttentionState(baselineLevel = level),
            entries.lastOrNull { it.key == key }?.checkpoint)
        val result = block(gate)
        write(entries, key, now, gate.checkpoint())
        result
    }

    private fun fingerprint(parts: List<String>): String =
        Sha256.hex(Json.encodeToString(ListSerializer(String.serializer()), parts).encodeToByteArray())

    private fun retained(now: Long): List<Entry> =
        store.getStringOrNull(storageKey)?.let { Json.decodeFromString(serializer, it) }.orEmpty()
            .filter { it.updatedAt in 0..now && now - it.updatedAt < TTL_MILLIS }

    private fun write(entries: List<Entry>, key: String, now: Long, checkpoint: AttentionCheckpoint) {
        store.putString(storageKey, Json.encodeToString(serializer,
            (entries.filterNot { it.key == key } + Entry(key, now, checkpoint)).takeLast(LIMIT)))
    }

    /** Exact host session scope only. No recall authority is derived from the stored checkpoint. */
    suspend fun load(parts: List<String>, now: Long): AttentionCheckpoint? = mutex.withLock {
        retained(now).lastOrNull { it.key == fingerprint(parts) }?.checkpoint
    }

    /** Snapshot under the ledger lock, preventing older concurrent snapshots from overwriting newer state. */
    suspend fun save(parts: List<String>, now: Long, gate: AttentionGatedRecall) = mutex.withLock {
        write(retained(now), fingerprint(parts), now, gate.checkpoint())
    }

    /** Terminal sessions cannot resume their previous attention state. */
    suspend fun remove(parts: List<String>, now: Long) = mutex.withLock {
        store.putString(storageKey, Json.encodeToString(serializer,
            retained(now).filterNot { it.key == fingerprint(parts) }))
    }

    /** Forget all derived attention metadata, including checkpoints referencing erased ancestors. */
    suspend fun clear() = mutex.withLock { store.remove(storageKey) }
}
