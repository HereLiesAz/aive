package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.resources.Res
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * A breed of node creature (docs/Node-Creature-Genome.md, Breeds). Its body is the rig named [id];
 * which agent wears it is the keeper's choice, never its role's.
 *
 * Only [name], [rarity], [source] and [price] are ever shown. [mutation] and [pairsWith] are hidden:
 * the keeper learns them only by breeding.
 */
@Serializable
internal data class Breed(
    val id: String,
    val name: String,
    val genus: String,
    val rarity: String,
    /** `store` (bought only), `breed` (hatched only) or `both`. */
    val source: String = "both",
    /** `rare`, `very-rare` or `mythic` when a hatch can draw it by lottery. */
    val lottery: String? = null,
    /** Offered as a first creature. */
    val starter: Boolean = false,
    val price: Double = 0.0,
    /** How far a hatch from this breed swings the next generation's workflow count. Positive. */
    val mutation: Int = 1,
    /** Breeds it pairs well with; its own kind always does. */
    val pairsWith: List<String> = emptyList(),
) {
    val bred: Boolean get() = source != "store"
    val sold: Boolean get() = source != "breed"
}

@Serializable
internal data class BreedCatalog(val breeds: List<Breed> = emptyList()) {
    private val byId by lazy { breeds.associateBy(Breed::id) }
    operator fun get(id: String): Breed? = byId[id]
    val starters: List<Breed> get() = breeds.filter(Breed::starter)

    companion object {
        const val FILE = "files/breeds.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): BreedCatalog = json.decodeFromString(serializer(), text)
        suspend fun load(): BreedCatalog = parse(Res.readBytes(FILE).decodeToString())
    }
}

/** A hatched creature: one of a kind, its body, tendrils and tail each taken whole from a parent breed. */
@Serializable
internal data class BredCreature(
    val id: String,
    val body: String,
    val tendrils: String,
    val tail: String,
    val parents: List<String>,
    val generation: Int,
)

/** What a hatch produced, kept until the keeper has seen it. */
@Serializable
internal data class Hatching(
    val generation: Int,
    val parents: List<String>,
    /** A breed id (a pure child joins its breed's count) or a [BredCreature] id; null if nothing hatched. */
    val child: String?,
    /** A breed won by lottery, if any. */
    val stranger: String? = null,
)

/**
 * The keeper's stable: the starter, unlimited, and every other creature one at a time.
 * Creature keys are breed ids (counted in [owned]) or [BredCreature] ids (one each).
 */
@Serializable
internal data class Stable(
    val starter: String? = null,
    val owned: Map<String, Int> = emptyMap(),
    val bred: List<BredCreature> = emptyList(),
    /** Agent (role id) to the creature it wears; agents not listed wear the starter. */
    val assignments: Map<String, String> = emptyMap(),
    val generation: Int = 1,
    /** Workflows cleared toward the next generation, and how many it needs. */
    val progress: Int = 0,
    val need: Int = 1,
    /** Runs already counted, so a run is counted once. */
    val counted: List<String> = emptyList(),
    val lastHatching: Hatching? = null,
) {
    fun creatureOf(role: RoleDefinitionId): String? = assignments[role.value] ?: starter

    /** How many of [key] are free to give an agent; null means without limit (the starter). */
    fun available(key: String, except: RoleDefinitionId? = null): Int? {
        if (key == starter) return null
        val total = owned[key] ?: if (bred.any { it.id == key }) 1 else 0
        val inUse = assignments.count { (role, creature) -> creature == key && role != except?.value }
        return (total - inUse).coerceAtLeast(0)
    }

    /** Every creature key the keeper holds, starter first. */
    val keys: List<String>
        get() = listOfNotNull(starter) + owned.filterValues { it > 0 }.keys.filter { it != starter }.sorted() + bred.map { it.id }

    fun assign(role: RoleDefinitionId, key: String?): Stable = when {
        key == null || key == starter -> copy(assignments = assignments - role.value)
        (available(key, except = role) ?: 1) > 0 -> copy(assignments = assignments + (role.value to key))
        else -> this
    }

    fun add(breed: String, count: Int = 1): Stable = copy(owned = owned + (breed to ((owned[breed] ?: 0) + count)))
}

/** The rig a creature key is drawn with. A bred creature shows its body breed's rig. */
internal fun Stable.rigOf(key: String): String = bred.firstOrNull { it.id == key }?.body ?: key

/** 1, 1, 2, 3, 5, 8… */
internal fun fibonacci(n: Int): Int {
    var a = 1
    var b = 1
    repeat((n - 1).coerceAtLeast(0)) { val next = a + b; a = b; b = next }
    return a
}

/** Odds of the lottery, rarest first; one draw a hatch, at most one win. */
internal val LotteryOdds: List<Pair<String, Double>> = listOf("mythic" to 1.0 / 1000, "very-rare" to 1.0 / 100, "rare" to 1.0 / 20)

/** A pair that fits makes the swing go the keeper's way this often; a poor pair, the reverse. */
private const val FAVOURABLE_IF_PAIRED = 0.75

/**
 * Counts a cleared run toward the next generation and, when enough have cleared, hatches it
 * (docs/Node-Creature-Genome.md, Breeds). [contributors] are the creature keys that worked the run.
 */
internal fun Stable.onRunCleared(
    run: WorkflowRun,
    contributors: List<String>,
    catalog: BreedCatalog,
    random: Random = Random.Default,
): Stable {
    if (run.status != WorkflowRunStatus.Completed || run.id.value in counted || starter == null) return this
    val counted = (counted + run.id.value).takeLast(200)
    val progress = progress + 1
    if (progress < need) return copy(counted = counted, progress = progress)

    val parts = contributors.ifEmpty { listOf(starter) }.map { key ->
        bred.firstOrNull { it.id == key }?.let { Triple(it.body, it.tendrils, it.tail) } ?: Triple(key, key, key)
    }
    val a = parts.first()
    val b = parts.getOrElse(1) { a }
    // Store-only breeds never pass themselves on: their part comes from the other parent.
    fun pick(x: String, y: String): String? = listOf(x, y).firstOrNull { catalog[it]?.bred != false }
    val body = pick(a.first, b.first)
    val tendrils = pick(b.second, a.second)
    val tail = pick(if (random.nextBoolean()) a.third else b.third, if (random.nextBoolean()) b.third else a.third)

    var stable = copy(counted = counted)
    val child = if (body == null || tendrils == null || tail == null) {
        null
    } else if (body == tendrils && tendrils == tail) {
        stable = stable.add(body)
        body
    } else {
        val id = "bred-${run.id.value}"
        stable = stable.copy(bred = stable.bred + BredCreature(id, body, tendrils, tail, listOf(a.first, b.first), generation))
        id
    }

    // The next generation's pace: Fibonacci, swung by the parents' mutation, steered by how well they pair.
    val breedA = catalog[a.first]
    val breedB = catalog[b.first]
    val swing = maxOf(breedA?.mutation ?: 1, breedB?.mutation ?: 1)
    val paired = breedA != null && breedB != null &&
        (breedA.id == breedB.id || breedA.genus == breedB.genus || breedB.id in breedA.pairsWith || breedA.id in breedB.pairsWith)
    val favourable = random.nextDouble() < if (paired) FAVOURABLE_IF_PAIRED else 1 - FAVOURABLE_IF_PAIRED
    val next = generation + 1
    val need = (fibonacci(next) + if (favourable) -swing else swing).coerceAtLeast(1)

    // One lottery draw.
    val roll = random.nextDouble()
    var threshold = 0.0
    val tier = LotteryOdds.firstOrNull { (_, odds) -> threshold += odds; roll < threshold }?.first
    val stranger = tier?.let { t -> catalog.breeds.filter { it.lottery == t }.takeIf { it.isNotEmpty() }?.random(random)?.id }
    if (stranger != null) stable = stable.add(stranger)

    return stable.copy(
        generation = next,
        progress = 0,
        need = need,
        lastHatching = Hatching(generation, listOf(a.first, b.first).distinct(), child, stranger),
    )
}

/** The creature keys of the agents that did [run]'s work, most work first. */
internal fun Stable.contributorsOf(run: WorkflowRun): List<String> = run.taskRuns.values
    .filter { it.status == TaskRunStatus.Completed }
    .mapNotNull { it.assignedRoleId }
    .groupingBy { it }
    .eachCount()
    .entries
    .sortedWith(compareByDescending<Map.Entry<RoleDefinitionId, Int>> { it.value }.thenBy { it.key.value })
    .mapNotNull { creatureOf(it.key) }
