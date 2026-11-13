package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.persistence.refuseIfUnreadable
import com.hereliesaz.geministrator.persistence.readQuarantining
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class NodeCharacterAssetRecord(
    val roleId: RoleDefinitionId,
    val roleLabel: String,
    val sourceWorkflowId: WorkflowDefinitionId,
    val sourceCharacterArtifactId: ArtifactId,
    val rigSheetArtifactId: ArtifactId,
    val generationPromptArtifactId: ArtifactId?,
    val verificationArtifactId: ArtifactId,
    val registeredAtEpochMillis: Long,
)

interface NodeCharacterAssetStore {
    suspend fun get(roleId: RoleDefinitionId): NodeCharacterAssetRecord?
    suspend fun all(): List<NodeCharacterAssetRecord>
    suspend fun put(record: NodeCharacterAssetRecord)
    suspend fun remove(roleId: RoleDefinitionId)
}

class SettingsNodeCharacterAssetStore(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : NodeCharacterAssetStore {
    private val mutex = Mutex()

    override suspend fun get(roleId: RoleDefinitionId): NodeCharacterAssetRecord? = mutex.withLock {
        readUnlocked().firstOrNull { it.roleId == roleId }
    }

    override suspend fun all(): List<NodeCharacterAssetRecord> = mutex.withLock {
        readUnlocked()
    }

    override suspend fun put(record: NodeCharacterAssetRecord) {
        mutex.withLock {
            val next = readUnlocked()
                .filterNot { it.roleId == record.roleId }
                .plus(record)
                .sortedBy { it.roleId.value }
            writeUnlocked(next)
        }
    }

    override suspend fun remove(roleId: RoleDefinitionId) {
        mutex.withLock {
            writeUnlocked(readUnlocked().filterNot { it.roleId == roleId })
        }
    }

    private fun readUnlocked(): List<NodeCharacterAssetRecord> {
        return settings.readQuarantining(storageKey) {
            json.decodeFromString(ListSerializer(NodeCharacterAssetRecord.serializer()), it)
        } ?: emptyList()
    }

    private fun writeUnlocked(records: List<NodeCharacterAssetRecord>) {
        settings.refuseIfUnreadable(storageKey) {
            json.decodeFromString(ListSerializer(NodeCharacterAssetRecord.serializer()), it)
        }
        if (records.isEmpty()) {
            settings.remove(storageKey)
        } else {
            settings.putString(
                storageKey,
                json.encodeToString(ListSerializer(NodeCharacterAssetRecord.serializer()), records),
            )
        }
    }

    companion object {
        const val DEFAULT_STORAGE_KEY: String = "aive.node-character.assets.v1"
    }
}

/**
 * Where the pictures of a node creature are kept (the source character and its rig sheet). They are megabytes of PNG: held
 * in an artifact's `uri` as a base64 data URI they were copied into every saved run, every cycle, and shown as text in the
 * Inbox. A store keeps the bytes in a file and the artifact keeps only the reference [save] returns.
 */
interface NodeCharacterImageStore {
    /** Keeps [png] under [key] (an artifact id; the implementation must not trust it as a file name) and returns its uri. */
    suspend fun save(key: String, png: ByteArray): String
}

/** A store that keeps nothing but hands back a short uri: for tests. */
class InMemoryNodeCharacterImageStore : NodeCharacterImageStore {
    val images = mutableMapOf<String, ByteArray>()

    override suspend fun save(key: String, png: ByteArray): String {
        images[key] = png
        return "memory:node-character/$key"
    }
}
