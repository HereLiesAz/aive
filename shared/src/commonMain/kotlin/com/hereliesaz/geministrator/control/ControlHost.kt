package com.hereliesaz.geministrator.control

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the control API can ask of the running app (docs/MCP.md). Deliberately small: no member spends a
 * cap or touches a key store. The one member that decides anything is [approveCreature], and it answers
 * only where the owner switched remote approval on ([remoteApprovalAllowed], off unless set; THREAT_MODEL
 * invariant 8): it approves a creature that waits for a person, nothing else, and rejecting, releasing a
 * gate or lifting a spend stop stay out of reach. Failures are [ControlException]s with a typed code.
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

    /** Approvals waiting on a person: read here, decided in the app or, where the owner allows it ([remoteApprovalAllowed]), by [approveCreature]. */
    suspend fun listApprovals(): JsonElement

    /** Whether the owner switched remote approval on for this host. Off unless set (the headless engine: `AIVE_ALLOW_REMOTE_APPROVAL=1`). */
    val remoteApprovalAllowed: Boolean get() = false

    /**
     * Approves the creature [creatureId] of run [runId] that is waiting for a person (a plan to approve, a step that needs a go-ahead).
     * Refused unless [remoteApprovalAllowed]; it never rejects, never releases a gate and never lifts a spend stop.
     */
    suspend fun approveCreature(runId: String, creatureId: String): JsonObject =
        controlError(ControlErrorCode.PolicyDenied, "Remote approval is off on this computer. The owner turns it on; until then approvals are decided in the app.")
    suspend fun listProviders(): JsonObject
    suspend fun settings(): JsonObject

    /** The Sources (name, whether it needs no key, whether it is switched on). Read-only: no key, and no way to change one, is here. */
    suspend fun listSources(): JsonObject = JsonObject(emptyMap())

    /** The vendor command-line agents on this computer (installed, switched on, version). Read-only: installing or enabling one is never offered here. */
    suspend fun listAgents(): JsonObject = JsonObject(emptyMap())

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
