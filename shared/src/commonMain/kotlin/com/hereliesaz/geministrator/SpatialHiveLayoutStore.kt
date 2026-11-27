package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists world-space sites separately from workflow definitions and from run-specific tissue.
 * Retains absent sites so a removed or temporarily invisible organism can return to its place.
 * The caller owns the store on the UI thread. Invalid data is reported rather than overwritten.
 */
internal class SpatialHiveLayoutStore(private val settings: Settings) {
    private fun key(definitionId: WorkflowDefinitionId): String =
        "aive.spatial.layout.v1." + Sha256.hex(definitionId.value.encodeToByteArray())

    /** Resolve the current workflow's sites and save new allocations, never execution state. */
    fun resolve(definitionId: WorkflowDefinitionId, ids: List<String>, queen: String): Map<String, SpatialPoint> {
        val key = key(definitionId)
        val encoded = settings.getStringOrNull(key)
        val previous = encoded?.let { Json.decodeFromString<Map<String, SpatialPoint>>(it) } ?: emptyMap()
        require(previous.values.all(SpatialPoint::finite)) { "Invalid spatial layout coordinates" }
        val updated = spatialCreaturePositions(definitionId.value, ids, queen, previous)
        if (updated != previous) settings.putString(key, Json.encodeToString(updated))
        val visible = ids.toSet() + queen
        return updated.filterKeys { it in visible }
    }
}
