package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.CreatureRigMapping
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ROLE_COLLECTION_MARKER_ID
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import com.hereliesaz.geministrator.net.ResponseTooLargeException
import com.hereliesaz.geministrator.net.boundedText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** Why a drifter could not be read, in the words the hive's edge shows on it. */
enum class DrifterTrouble {
    /** Not an https:// address (or http:// to this computer or the local network). */
    BadAddress,

    /** Nothing answered: offline, refused, timed out, or (on the web) not allowed from this page. */
    Unreachable,

    /** Something answered, but there is no Agent Card at that address (404, 410). */
    NoCard,

    /** What answered is not an Agent Card. */
    NotACard,

    /** A real card, but it speaks only bindings The Aive does not (gRPC, HTTP+JSON). */
    NoJsonRpc,
}

/**
 * A remote A2A Agent Card validation result. Connections uses [A2aDrifters.read] to inspect a
 * service before saving it as a provider. [trouble] explains a missing, unreachable or incompatible
 * card; content read from the card remains untrusted and is never followed as instructions.
 */
data class A2aDrifter(
    /** The normalized card URL, or what the keeper typed when it is not an address at all. */
    val cardUrl: String,
    val card: A2aAgentCard? = null,
    val trouble: DrifterTrouble? = null,
    /** A short technical detail for [trouble] (an HTTP status), never the remote body. */
    val detail: String? = null,
    val looking: Boolean = false,
    /** From the seed list rather than brought in by the keeper. */
    val seeded: Boolean = false,
    /** A seed's own name and line about it, shown until its card is read ([A2aDrifterSeed]). */
    val label: String? = null,
    val blurb: String? = null,
) {
    /** Its card's name, else its seed's, else its host. */
    val name: String get() = card?.let { A2aDrifters.nameOf(it, cardUrl) } ?: label ?: A2aDrifters.hostOf(cardUrl)

    /** A seed nobody has read yet: "Let it in" reads its card first. */
    val isUnreadSeed: Boolean get() = seeded && card == null && trouble == null && !looking

    /** The creature it is drawn as: the same one every time for the same card URL. */
    val creature: String get() = A2aDrifters.creatureFor(cardUrl)

    /** Read, and speaking the one binding The Aive speaks: it can be let in. */
    val canBeLetIn: Boolean get() = card != null && trouble == null && !looking
}

/**
 * Validates remote Agent Cards before Providers connects them. The older waiting-drifter cache and
 * role-conversion helpers are retained for compatibility with existing callers/tests; the hive-edge
 * discovery UI no longer exists, and no role is created implicitly when adding a provider.
 */
object A2aDrifters {
    /** The most drifters that wait at the edge at once; the oldest leaves first. */
    const val MAX_WAITING: Int = 12

    /** A card bigger than this is not read. */
    const val MAX_CARD_BYTES: Int = 256 * 1024

    private const val NAME_LIMIT = 60
    private const val DESCRIPTION_LIMIT = 240
    private const val SKILL_LIMIT = 8

    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<List<A2aDrifter>>(emptyList())

    /** Drifters at the edge, newest first. */
    val waiting: StateFlow<List<A2aDrifter>> get() = state

    /** Reads the card at [rawUrl] and puts the drifter at the edge, its trouble and all. */
    suspend fun bringIn(rawUrl: String, client: HttpClient = cardClient, seeded: Boolean = false): A2aDrifter {
        val key = runCatching { A2aAgentConnections.normalizeCardUrl(rawUrl) }.getOrNull() ?: rawUrl.trim()
        val seed = if (seeded) A2aDrifterSeeds.seedFor(key) else null
        put(A2aDrifter(cardUrl = key, looking = true, seeded = seeded, label = seed?.name, blurb = seed?.about))
        val read = read(rawUrl, client).copy(seeded = seeded, label = seed?.name, blurb = seed?.about)
        // Sent away while it was being read: it stays away.
        if (state.value.any { it.cardUrl == key }) put(read)
        return read
    }

    /**
     * Puts the seeds still pending ([A2aDrifterSeeds.pending]) at the edge, behind any drifter
     * already there, unread: nothing is fetched until the keeper lets one in.
     */
    fun offerSeeds(pending: List<A2aDrifterSeed> = A2aDrifterSeeds.pending()) {
        state.update { current ->
            val here = current.mapTo(hashSetOf()) { it.cardUrl }
            val seeds = pending.filter { it.cardUrl !in here }.map { seed ->
                A2aDrifter(cardUrl = seed.cardUrl, seeded = true, label = seed.name, blurb = seed.about)
            }
            (current + seeds).take(MAX_WAITING)
        }
    }

    /** Sends the drifter at [cardUrl] away from the edge. */
    fun sendAway(cardUrl: String) {
        state.update { current -> current.filterNot { it.cardUrl == cardUrl } }
    }

    /** Clears the edge; for tests. */
    fun clear() {
        state.value = emptyList()
    }

    private fun put(drifter: A2aDrifter) {
        state.update { current ->
            (listOf(drifter) + current.filterNot { it.cardUrl == drifter.cardUrl }).take(MAX_WAITING)
        }
    }

    /**
     * Reads the Agent Card at [rawUrl] without a token and without following redirects (the card
     * fetch the provider makes is the same). Never throws: what went wrong is the drifter's [A2aDrifter.trouble].
     */
    suspend fun read(rawUrl: String, client: HttpClient = cardClient): A2aDrifter {
        val cardUrl = try {
            A2aAgentConnections.normalizeCardUrl(rawUrl)
        } catch (_: IllegalArgumentException) {
            return A2aDrifter(cardUrl = rawUrl.trim(), trouble = DrifterTrouble.BadAddress)
        }
        val body = try {
            val response = client.get(cardUrl) { header(HttpHeaders.Accept, "application/json") }
            val status = response.status.value
            when {
                status == 404 || status == 410 ->
                    return A2aDrifter(cardUrl, trouble = DrifterTrouble.NoCard, detail = "HTTP $status")
                status !in 200..299 ->
                    return A2aDrifter(cardUrl, trouble = DrifterTrouble.Unreachable, detail = "HTTP $status")
            }
            response.boundedText(MAX_CARD_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseTooLargeException) {
            return A2aDrifter(cardUrl, trouble = DrifterTrouble.NotACard, detail = "too large")
        } catch (failure: Exception) {
            return A2aDrifter(cardUrl, trouble = DrifterTrouble.Unreachable, detail = failure::class.simpleName)
        }
        val card = runCatching { A2aCodec.decodeCard(json.parseToJsonElement(body)) }.getOrNull()
            ?.takeIf { it.name.isNotBlank() || it.interfaces.isNotEmpty() }
            ?: return A2aDrifter(cardUrl, trouble = DrifterTrouble.NotACard)
        if (card.jsonRpcEndpoint() == null) return A2aDrifter(cardUrl, card = card, trouble = DrifterTrouble.NoJsonRpc)
        return A2aDrifter(cardUrl, card = card)
    }

    /** One line of remote text, without control characters, at most [limit] characters. */
    fun cleanCardText(text: String, limit: Int): String =
        text.map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(limit)
            .trim()

    fun nameOf(card: A2aAgentCard?, cardUrl: String): String =
        card?.name?.let { cleanCardText(it, NAME_LIMIT) }?.ifBlank { null } ?: hostOf(cardUrl)

    /** What the card says it is, bounded; empty when it says nothing. */
    fun descriptionOf(card: A2aAgentCard?): String = card?.description?.let { cleanCardText(it, DESCRIPTION_LIMIT) }.orEmpty()

    /** The skills the card offers, by name, bounded. */
    fun skillsOf(card: A2aAgentCard?): List<String> = card?.skills.orEmpty()
        .map { cleanCardText(it.name.ifBlank { it.id }, NAME_LIMIT) }
        .filter(String::isNotBlank)
        .distinct()
        .take(SKILL_LIMIT)

    /**
     * The creature a drifter is drawn as: one of the role creatures, chosen by a stable hash of its
     * card URL, so the same agent always arrives as the same creature on every device.
     */
    fun creatureFor(cardUrl: String): String {
        val pool = CreatureRigMapping.bySlugKind.values.distinct()
        return pool[stableHash(cardUrl.trim().lowercase()).mod(pool.size)]
    }

    /** The swarm role of the drifter whose provider is [providerId]. */
    fun roleIdFor(providerId: String): RoleDefinitionId =
        RoleDefinitionId(
            "drifter-" + providerId.removePrefix(A2aAgentConnection.PROVIDER_ID_PREFIX)
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "agent" },
        )

    /**
     * The role a drifter takes in the swarm once let in. It prefers the drifter's own provider (so
     * the router never picks the remote agent for anyone else), needs only [AgentCapability.Research]
     * (its output is external content and it cannot change the repository), and holds no
     * authority: it can never approve a plan, a gate or a release (THREAT_MODEL invariant 4).
     * The card's words are quoted, bounded and on one line; the keeper read them before letting it in.
     */
    fun roleFor(connection: A2aAgentConnection, card: A2aAgentCard): RoleDefinition {
        val host = hostOf(connection.cardUrl)
        val about = descriptionOf(card)
        val skills = skillsOf(card)
        return RoleDefinition(
            id = roleIdFor(connection.providerId),
            name = nameOf(card, connection.cardUrl),
            description = buildString {
                append("A drifter: a remote agent at ").append(host).append(", spoken to over A2A.")
                if (about.isNotEmpty()) append(" Its card says: \"").append(about.replace("\"", "'")).append("\"")
                if (skills.isNotEmpty()) append(" Skills: ").append(skills.joinToString(", ")).append('.')
            },
            instructions = "Do the task you are sent and answer with what you found or made, as text. " +
                "Say plainly what you could not do.",
            enabled = true,
            preferredProviderId = AgentProviderId(connection.providerId),
            capabilitiesRequired = setOf(AgentCapability.Research),
        )
    }

    /** [roles] (the active swarm) with [role] in it, replacing an earlier role of the same id. */
    fun swarmWith(roles: Collection<RoleDefinition>, role: RoleDefinition): List<RoleDefinition> =
        activeSwarm(roles).filterNot { it.id == role.id } + role

    /** [roles] (the active swarm) without the drifter whose provider is [providerId]. */
    fun swarmWithout(roles: Collection<RoleDefinition>, providerId: String): List<RoleDefinition> {
        val id = roleIdFor(providerId)
        return activeSwarm(roles).filterNot { it.id == id }
    }

    private fun activeSwarm(roles: Collection<RoleDefinition>): List<RoleDefinition> =
        roles.filter { it.enabled && it.id.value != ROLE_COLLECTION_MARKER_ID }

    internal fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').lowercase().ifBlank { url.trim() }

    private fun stableHash(value: String): Int {
        var hash = 0x811C9DC5.toInt()
        value.forEach { char -> hash = (hash xor char.code) * 0x01000193 }
        return hash
    }

    private val cardClient: HttpClient by lazy {
        HttpClient {
            expectSuccess = false
            // The provider does not follow redirects either: a card is read where it was named.
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000L
                connectTimeoutMillis = 10_000L
            }
        }
    }
}
