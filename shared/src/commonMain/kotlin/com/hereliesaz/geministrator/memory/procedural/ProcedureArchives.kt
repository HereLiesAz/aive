package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A single workflow's explicit, inspection-only procedure export. */
@Serializable
data class ProcedureArchive(
    val formatVersion: Int = 1,
    val workflowId: String,
    val snapshot: ProcedureSnapshot,
)

/**
 * Import is an explicit user operation, never an MCP side effect.
 * Referential, cryptographic and lineage provenance checks happen before any storage mutation.
 */
object ProcedureArchives {
    private const val MAX_ARCHIVE_CHARS = 4_000_000
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun export(workflowId: String, snapshot: ProcedureSnapshot): String {
        require(snapshot.definitions.all { it.ref.workflowId == workflowId })
        require(snapshot.traces.all { it.workflowId == workflowId })
        require(snapshot.artifacts.all { it.workflowId == workflowId })
        return json.encodeToString(ProcedureArchive.serializer(), ProcedureArchive(workflowId = workflowId, snapshot = snapshot))
    }

    suspend fun decode(
        serialized: String,
        destinationWorkflowId: String,
        availableSourceEpisodes: Set<MemoryEpisodeId>,
    ): ProcedureSnapshot {
        require(serialized.length <= MAX_ARCHIVE_CHARS) { "Procedure archive exceeds size limit" }
        val archive = json.decodeFromString(ProcedureArchive.serializer(), serialized)
        require(archive.formatVersion == 1) { "Unsupported procedural memory archive" }
        require(archive.workflowId == destinationWorkflowId) { "Cross-workflow procedure import is not permitted" }
        val imported = archive.snapshot
        // Constructor validates duplicate identities, referential integrity and workflow isolation.
        InMemoryProcedureStore(destinationWorkflowId, imported)
        imported.artifacts.forEach { it.verify() }
        require(imported.definitions.all { definition ->
            definition.sourceEpisodeIds.all { it in availableSourceEpisodes }
        }) { "Imported procedure cites a missing or non-lineage episode" }
        require(imported.evidence.all { it.sourceEpisodeId in availableSourceEpisodes }) {
            "Imported procedure evidence cites a missing episode"
        }
        val availableDigests = imported.artifacts.mapTo(hashSetOf()) { it.digest }
        require(imported.definitions.all { it.contentDigest in availableDigests }) {
            "Every imported procedure requires its original content-addressed source"
        }
        return imported
    }
}
