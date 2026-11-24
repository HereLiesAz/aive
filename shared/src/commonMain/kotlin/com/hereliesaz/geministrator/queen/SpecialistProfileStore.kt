package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Identity of one configured advisory specialist, distinct from its role or task. A model-route
 * or role revision changes the identity; neither identity nor past activity confers authority.
 * Hash the serialized role and ordered possible model route, never persist its instructions.
 */
internal fun specialistInstanceId(role: RoleDefinition, uses: List<ModelUse>, namespace: String = "advisory-v1"): String {
    val revision = role.copy(
        capabilitiesRequired = role.capabilitiesRequired.sortedBy { it.name }.toSet(),
        authorities = role.authorities.sortedBy { it.name }.toSet(),
    )
    val route = uses.map { listOf(it.sourceId, it.modelId.orEmpty(), it.billsPerToken.toString()) }
    val roleHash = Sha256.hex(Json.encodeToString(RoleDefinition.serializer(), revision).encodeToByteArray())
    val routeHash = Sha256.hex(Json.encodeToString(
        ListSerializer(ListSerializer(String.serializer())), route).encodeToByteArray())
    return Sha256.hex("$namespace:$roleHash:$routeHash".encodeToByteArray())
}

/** Observed completed calls, never inferred accuracy, agreement or epistemic reliability. */
@Serializable
internal data class SpecialistProfile(
    val id: String,
    val roleId: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val answered: Long = 0,
    val empty: Long = 0,
    val failed: Long = 0,
    val timedOut: Long = 0,
    val managedSessions: Long = 0,
)

/**
 * Local bounded metadata ledger. No prompt, reply, private memory, or permission is stored.
 * Profiles outlive a conversation; current roster validation remains mandatory for each call.
 */
internal class SpecialistProfileStore(settings: Settings, private val now: () -> Long) {
    private val store = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()

    @Serializable
    private data class Binding(val session: String, val specialist: String)

    @Serializable
    private data class Book(
        val profiles: List<SpecialistProfile> = emptyList(),
        val bindings: List<Binding> = emptyList(),
    )

    /**
     * Bind a real provider session once. Only hashes and metadata survive restart; an existing
     * binding is never reinterpreted against a later role revision. No authority is conveyed.
     */
    suspend fun bindManaged(session: String, role: RoleDefinition, provider: String): String = mutex.withLock {
        val book = read()
        book.bindings.singleOrNull { it.session == session }?.let { return@withLock it.specialist }
        val id = specialistInstanceId(role, listOf(ModelUse(provider, null, false)), "managed-v1")
        val time = now()
        val old = book.profiles.singleOrNull { it.id == id } ?: SpecialistProfile(id, role.id.value.take(128), time, time)
        val next = old.copy(lastSeen = time, managedSessions = if (old.managedSessions == Long.MAX_VALUE)
            Long.MAX_VALUE else old.managedSessions + 1)
        store.putString(KEY, Json.encodeToString(Book.serializer(), Book(
            (listOf(next) + book.profiles.filterNot { it.id == id }).take(LIMIT),
            (listOf(Binding(session, id)) + book.bindings).take(BINDING_LIMIT),
        )))
        id
    }

    /** Unknown or evicted sessions remain unknown; do not invent historical identities. */
    suspend fun managedIdentity(session: String): String? = mutex.withLock {
        read().bindings.singleOrNull { it.session == session }?.specialist
    }

    private fun read(): Book = store.getStringOrNull(KEY)?.let {
        Json.decodeFromString(Book.serializer(), it)
    } ?: Book()

    suspend fun profiles(): List<SpecialistProfile> = mutex.withLock { read().profiles }

    /** Record only a returned invocation outcome, with saturating counters. */
    suspend fun observe(id: String, roleId: String, outcome: SpecialistCallOutcome) = mutex.withLock {
        require(id.matches(Regex("[a-f0-9]{64}")) && roleId.isNotBlank())
        val book = read()
        val time = now()
        val old = book.profiles.singleOrNull { it.id == id }
            ?: SpecialistProfile(id, roleId.take(128), time, time)
        require(old.roleId == roleId.take(128))
        fun inc(value: Long) = if (value == Long.MAX_VALUE) value else value + 1
        val next = old.copy(
            lastSeen = time,
            answered = if (outcome == SpecialistCallOutcome.Answered) inc(old.answered) else old.answered,
            empty = if (outcome == SpecialistCallOutcome.Empty) inc(old.empty) else old.empty,
            failed = if (outcome == SpecialistCallOutcome.Failed) inc(old.failed) else old.failed,
            timedOut = if (outcome == SpecialistCallOutcome.TimedOut) inc(old.timedOut) else old.timedOut,
        )
        store.putString(KEY, Json.encodeToString(Book.serializer(),
            book.copy(profiles = (listOf(next) + book.profiles.filterNot { it.id == id }).take(LIMIT))))
    }

    suspend fun clear() = mutex.withLock { store.remove(KEY) }

    private companion object {
        const val KEY = "aive.collective.specialists.v1"
        const val LIMIT = 128
        const val BINDING_LIMIT = 512
    }
}
