package com.hereliesaz.geministrator.control

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the control API can ask of the running app (docs/MCP.md). Deliberately small and with no
 * member that decides an approval gate, spends a cap, or touches a key store: there is nothing
 * here for a route to call that could approve, reject or release anything. Failures are
 * [ControlException]s with a typed code.
 */
interface ControlHost {
    // Read.
    suspend fun status(): JsonObject
    suspend fun listHives(): JsonElement
    suspend fun getHive(id: String): JsonObject
    suspend fun listRoles(): JsonElement
    suspend fun getRole(id: String): JsonObject
    suspend fun listRuns(hiveId: String?, limit: Int): JsonElement
    suspend fun getRun(id: String): JsonObject
    suspend fun listEvents(runId: String, afterMillis: Long?, limit: Int): JsonElement

    /** Approvals waiting on a person. Read-only: they are decided in the app. */
    suspend fun listApprovals(): JsonElement
    suspend fun listProviders(): JsonObject
    suspend fun settings(): JsonObject

    // Author hives.
    suspend fun createHive(spec: JsonObject): JsonObject
    suspend fun updateHive(id: String, patch: JsonObject): JsonObject
    suspend fun deleteHive(id: String): JsonObject
    suspend fun addCreature(hiveId: String, spec: JsonObject): JsonObject
    suspend fun updateCreature(hiveId: String, creatureId: String, patch: JsonObject): JsonObject
    suspend fun removeCreature(hiveId: String, creatureId: String): JsonObject

    /** Asks the linked planner for a proposal. Saves nothing and launches nothing. */
    suspend fun draftHive(prompt: String): JsonObject
    suspend fun setRoleProvider(roleId: String, providerId: String?): JsonObject

    // Run control.
    suspend fun startRun(spec: JsonObject): JsonObject
    suspend fun stopRun(runId: String): JsonObject
}
