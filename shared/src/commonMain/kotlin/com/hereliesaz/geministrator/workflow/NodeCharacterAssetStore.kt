package com.hereliesaz.geministrator.workflow

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
        val encoded = settings.getStringOrNull(storageKey) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(NodeCharacterAssetRecord.serializer()), encoded)
        }.getOrElse { emptyList() }
    }

    private fun writeUnlocked(records: List<NodeCharacterAssetRecord>) {
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
