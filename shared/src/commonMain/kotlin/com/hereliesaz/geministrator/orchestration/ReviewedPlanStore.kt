package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * A reviewed plan waiting for the keeper survives a restart (docs/Keepers-Room.md, the plan reviewed). It comes
 * back only while the inputs it was made from are the same ([planInputsFingerprint]): the keeper is resuming the
 * decision they were looking at, not reusing it. Launching again, even the same objective, is a new run and is
 * planned, reviewed and decided afresh; nothing here makes a later launch skip any of that.
 *
 * Like the progressive-delegation store, this is deliberately outside workflow persistence, the setup file and
 * every export, so an imported `.ive` file or a remote interface (THREAT_MODEL.md, invariant 8) can neither
 * write nor read it. It holds no approval: the keeper's Start is still pressed in the app, every time. Run-time
 * gates (HumanApproval tasks, taint, escalations, repository changes) are never recorded here.
 */

/** One review round, as kept: the plan that was read and whether the reviewer approved it, with its reasons. */
@Serializable
data class StoredPlanRound(val plan: OrchestrationPlan, val approved: Boolean, val reasons: String)

/** A reviewed plan the keeper has not yet decided on, with everything needed to show and start it again. */
@Serializable
data class PendingPlanLaunch(
    val fingerprint: String,
    val objective: String,
    val projectName: String,
    val existingProjectId: String? = null,
    val repository: RepositoryRef? = null,
    val parentRunIds: List<String> = emptyList(),
    val packet: OrchestrationPacket,
    val plan: OrchestrationPlan,
    val rounds: List<StoredPlanRound> = emptyList(),
    val problem: String? = null,
    val reviewerRoleId: String,
    val reviewerName: String,
    val createdAtEpochMillis: Long,
)

/** Settings-backed store for the one [PendingPlanLaunch]. Unreadable state is no state: the keeper is simply asked afresh. */
class ReviewedPlanStore(settings: Settings) {
    // A reviewed plan, three rounds of reasons and the packet far exceed a JVM preference's 8,192 characters.
    private val settings: Settings = ChunkedStringSettings(settings, setOf(PENDING_KEY))

    fun pending(nowEpochMillis: Long): PendingPlanLaunch? =
        read(PENDING_KEY, PendingPlanLaunch.serializer())?.takeIf { nowEpochMillis - it.createdAtEpochMillis in 0..PENDING_TTL_MILLIS }

    /** Whether anything is kept at all (it may yet be stale); a cheap check before the screen is touched. */
    fun hasPending(): Boolean = runCatching { settings.hasKey(PENDING_KEY) }.getOrDefault(false)

    fun setPending(pending: PendingPlanLaunch) {
        runCatching { settings.putString(PENDING_KEY, json.encodeToString(PendingPlanLaunch.serializer(), pending)) }
    }

    fun clearPending() {
        runCatching { settings.remove(PENDING_KEY) }
    }

    private fun <T> read(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { settings.getStringOrNull(key)?.let { json.decodeFromString(serializer, it) } }.getOrNull()

    companion object {
        const val PENDING_KEY = "aive.plan-review.pending.v1"

        /** A decision left waiting is offered again for a week; after that the plan is too old to start unseen. */
        const val PENDING_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * Bumped when planning itself changes (the planner's instruction, the reviewer's contract), so an approval
 * given under the old way is not reused under the new one.
 */
private const val PLAN_FINGERPRINT_VERSION = "plan-review-v1"

/**
 * A SHA-256 over everything a plan is made from: the objective, the repository, the runs it continues,
 * every role the planner may use (all of each role's fields) and the reviewer's. If any of it differs, the
 * fingerprint does, and a remembered decision no longer applies. Sets and maps are written in a fixed order,
 * so the same inputs always give the same fingerprint.
 */
fun planInputsFingerprint(
    objective: String,
    repository: RepositoryRef?,
    parentRunIds: List<String>,
    plannerRoles: List<RoleDefinition>,
    reviewer: RoleDefinition,
): String {
    val element = buildJsonObject {
        put("version", PLAN_FINGERPRINT_VERSION)
        put("objective", objective.trim())
        put("repository", repository?.let { canonical(fingerprintJson.encodeToJsonElement(RepositoryRef.serializer(), it)) } ?: JsonPrimitive(null as String?))
        put("parents", canonical(JsonArray(parentRunIds.sorted().map(::JsonPrimitive))))
        put(
            "planner",
            JsonArray(
                plannerRoles.sortedBy { it.id.value }.map { canonical(fingerprintJson.encodeToJsonElement(RoleDefinition.serializer(), it)) },
            ),
        )
        put("reviewer", canonical(fingerprintJson.encodeToJsonElement(RoleDefinition.serializer(), reviewer)))
    }
    return Sha256.hex(element.toString().encodeToByteArray())
}

private val fingerprintJson = Json { encodeDefaults = true }

/** [element] with object keys and array members in a fixed order, so equal data always writes the same bytes. */
private fun canonical(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (key, value) -> key to canonical(value) })
    is JsonArray -> JsonArray(element.map(::canonical).sortedBy { it.toString() })
    else -> element
}
