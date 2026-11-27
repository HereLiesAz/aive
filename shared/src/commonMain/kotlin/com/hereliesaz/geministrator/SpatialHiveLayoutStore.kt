package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredSpatialLayout(val definitionId: String, val points: Map<String, SpatialPoint>)

/**
 * Persists world-space sites separately from workflow definitions and from run-specific tissue.
 * Retains absent sites so a removed or temporarily invisible organism can return to its place.
 * Uses the shared chunked persistence layer to respect desktop Preferences limits.
 * The caller owns the store on the UI thread. Invalid data is reported rather than overwritten.
 */
internal class SpatialHiveLayoutStore(settings: Settings) {
    private val storage = ChunkedStringSettings.unbounded(settings)

    private fun key(definitionId: WorkflowDefinitionId): String =
        "aive.sl.v1." + Sha256.hex(definitionId.value.encodeToByteArray()).take(24)

    /** Resolve the current workflow's sites and save new allocations, never execution state. */
    fun resolve(definitionId: WorkflowDefinitionId, ids: List<String>, queen: String): Map<String, SpatialPoint> {
        val key = key(definitionId)
        val record = storage.getStringOrNull(key)?.let { Json.decodeFromString<StoredSpatialLayout>(it) }
        require(record == null || record.definitionId == definitionId.value) { "Spatial layout key collision" }
        val previous = record?.points.orEmpty()
        require(previous.values.all(SpatialPoint::finite)) { "Invalid spatial layout coordinates" }
        val updated = spatialCreaturePositions(definitionId.value, ids, queen, previous)
        if (updated != previous) storage.putString(key, Json.encodeToString(StoredSpatialLayout(definitionId.value, updated)))
        val visible = ids.toSet() + queen
        return updated.filterKeys { it in visible }
    }
}
