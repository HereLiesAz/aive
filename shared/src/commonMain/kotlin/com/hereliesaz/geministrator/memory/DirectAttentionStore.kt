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
internal class DirectAttentionStore(settings: Settings) {
    private val store = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()

    @Serializable
    private data class Entry(val key: String, val updatedAt: Long, val checkpoint: AttentionCheckpoint)

    private companion object {
        const val KEY = "aive.memory.direct-attention.v1"
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
        val key = Sha256.hex(Json.encodeToString(ListSerializer(String.serializer()),
            listOf(conversationId, participantId, roleId) + workflowIds.distinct().sorted()).encodeToByteArray())
        val entries = store.getStringOrNull(KEY)?.let { Json.decodeFromString(serializer, it) }.orEmpty()
            .filter { it.updatedAt in 0..now && now - it.updatedAt < TTL_MILLIS }
        val gate = AttentionGatedRecall(policy, MemoryAttentionState(baselineLevel = level),
            entries.lastOrNull { it.key == key }?.checkpoint)
        val result = block(gate)
        store.putString(KEY, Json.encodeToString(serializer,
            (entries.filterNot { it.key == key } + Entry(key, now, gate.checkpoint())).takeLast(LIMIT)))
        result
    }

    /** Forget all derived attention metadata, including checkpoints referencing erased ancestors. */
    suspend fun clear() = mutex.withLock { store.remove(KEY) }
}
