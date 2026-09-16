package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.persistence.PersistenceCorruptionException
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val settingsInferenceGenealogyMutex = Mutex()

/**
 * Durable structural ancestry store for inference invocations.
 *
 * The persisted graph contains derivation coordinates only. It deliberately does not persist truth,
 * correctness, confidence, contradictions, preferences, or model conclusions, preserving the
 * memory/reconsolidation authority boundary documented for compound inference.
 */
class SettingsInferenceGenealogyGraph(
    private val settings: Settings,
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = defaultJson,
) : InferenceGenealogyGraph {

    override suspend fun register(node: InferenceGenealogyNode) {
        settingsInferenceGenealogyMutex.withLock {
            val snapshot = readUnlocked()
            val existing = snapshot.nodes
                .firstOrNull { it.invocationId == node.invocationId }
                ?.toDomain()
            require(existing == null || existing == node) {
                "Inference genealogy node ${node.invocationId} is already registered with different ancestry"
            }
            if (existing == null) {
                writeUnlocked(
                    snapshot.copy(nodes = snapshot.nodes + PersistedInferenceGenealogyNode.fromDomain(node)),
                )
            }
        }
    }

    override suspend fun get(invocationId: String): InferenceGenealogyNode? =
        settingsInferenceGenealogyMutex.withLock {
            readUnlocked().nodes.firstOrNull { it.invocationId == invocationId }?.toDomain()
        }

    override suspend fun all(): List<InferenceGenealogyNode> =
        settingsInferenceGenealogyMutex.withLock {
            readUnlocked().nodes.map(PersistedInferenceGenealogyNode::toDomain)
        }

    private fun readUnlocked(): InferenceGenealogySnapshot {
        val encoded = settings.getStringOrNull(storageKey) ?: return InferenceGenealogySnapshot()
        val snapshot = try {
            json.decodeFromString(InferenceGenealogySnapshot.serializer(), encoded)
        } catch (failure: Exception) {
            throw PersistenceCorruptionException(
                "Inference genealogy persistence is unreadable and must be recovered.",
                failure,
            )
        }
        require(snapshot.version <= CURRENT_SCHEMA_VERSION) {
            "Unsupported inference genealogy schema ${snapshot.version}; maximum supported is $CURRENT_SCHEMA_VERSION"
        }
        return snapshot
    }

    private fun writeUnlocked(snapshot: InferenceGenealogySnapshot) {
        settings.putString(
            storageKey,
            json.encodeToString(InferenceGenealogySnapshot.serializer(), snapshot),
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_STORAGE_KEY: String = "haive.inference.genealogy.v1"

        val defaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun createDefault(): SettingsInferenceGenealogyGraph = SettingsInferenceGenealogyGraph(Settings())
    }
}

@Serializable
private data class InferenceGenealogySnapshot(
    val version: Int = SettingsInferenceGenealogyGraph.CURRENT_SCHEMA_VERSION,
    val nodes: List<PersistedInferenceGenealogyNode> = emptyList(),
)

@Serializable
private data class PersistedInferenceGenealogyNode(
    val invocationId: String,
    val upstreamInvocationIds: List<String> = emptyList(),
    val upstreamTaskRunIds: List<String> = emptyList(),
    val upstreamArtifactIds: List<String> = emptyList(),
    val memoryAddresses: List<String> = emptyList(),
    val toolEvidenceIds: List<String> = emptyList(),
    val promptFingerprint: String? = null,
    val configurationFingerprint: String? = null,
) {
    fun toDomain(): InferenceGenealogyNode = InferenceGenealogyNode(
        invocationId = invocationId,
        upstreamInvocationIds = upstreamInvocationIds.toSet(),
        upstreamTaskRunIds = upstreamTaskRunIds.mapTo(linkedSetOf(), ::TaskRunId),
        upstreamArtifactIds = upstreamArtifactIds.mapTo(linkedSetOf(), ::ArtifactId),
        memoryAddresses = memoryAddresses.toSet(),
        toolEvidenceIds = toolEvidenceIds.toSet(),
        promptFingerprint = promptFingerprint,
        configurationFingerprint = configurationFingerprint,
    )

    companion object {
        fun fromDomain(node: InferenceGenealogyNode): PersistedInferenceGenealogyNode =
            PersistedInferenceGenealogyNode(
                invocationId = node.invocationId,
                upstreamInvocationIds = node.upstreamInvocationIds.sorted(),
                upstreamTaskRunIds = node.upstreamTaskRunIds.map { it.value }.sorted(),
                upstreamArtifactIds = node.upstreamArtifactIds.map { it.value }.sorted(),
                memoryAddresses = node.memoryAddresses.sorted(),
                toolEvidenceIds = node.toolEvidenceIds.sorted(),
                promptFingerprint = node.promptFingerprint,
                configurationFingerprint = node.configurationFingerprint,
            )
    }
}
