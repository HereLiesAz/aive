package com.hereliesaz.geministrator.memory

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/**
 * A memory never grows, and it fades along an S-curve.
 *
 * Each current version records its memory's original size (characters of text) and its life
 * position: how many rewrites (condensation, absorption of a deliberation, any clerk's rewrite) it
 * has gone through, possibly fractional after a combination. The size budget at life position n is
 * a logistic curve from the original size down to a floor:
 *
 *     f(n)      = (σ((n − m)/s) − σ(−m/s)) / (1 − σ(−m/s))      so f(0) = 0
 *     floor     = φ × original
 *     budget(n) = floor + (original − floor) × (1 − f(n))
 *
 * with σ the logistic function, L = [MEMORY_LIFESPAN_PASSES], m = [MEMORY_MIDPOINT_FRACTION] × L,
 * s = [MEMORY_STEEPNESS_PASSES] and φ = [MEMORY_FLOOR_FRACTION]. Little is lost early and near the
 * floor; the steepest loss is just past the middle of the memory's life.
 *
 * **One memory rewritten** continues down its own curve: the new version is at most
 * budget(n + 1), strictly smaller than the previous version (at least one character) until the floor,
 * and never larger than it.
 *
 * **Memories combined** (a condensation of several, a deliberation absorbed, new information folded
 * in) re-base the curve. Contributors are folded in pairwise in order of arrival (record time): the
 * first is the current summary, each next one the new memory meeting it. With weights w (below):
 *
 *     original' = (w_summary × size_summary + w_new × size_new) / (w_summary + w_new)
 *     n'        = (w_summary × n_summary + w_new × n_new) / (w_summary + w_new)
 *     w'        = w_summary + w_new          (carried to the next fold, with size original')
 *     floor'    = φ × original'
 *     limit     = max(floor', min(budget'(n' + 1), size_previous − 1))   (size_previous ≤ floor': size_previous)
 *
 * where size_previous is the largest contributor's size (it stands for the previous current version),
 * so a rewrite is never as big as what it replaces, even when the weighted ceiling is above it. One
 * exception: folding exact repeats (every contributor has the version's text) rewrites nothing and
 * keeps the text.
 *
 * A contributor's weight w combines existing measures only: `w = confidence × (1 − (1 − salience) ×
 * (1 − strength))`, where `strength` is its associative strength (accumulateAssociationEvidence over
 * its SimilarTo/AssociatedWith edges, Hebbian co-recall links included; TEMPORAL_MEMORY_AND_
 * PROGRAMMATIC_ASSOCIATIONS.md), `salience` its stored SalienceFilter score and `confidence` its
 * derivation fidelity (Memory-layer.md); salience joins strength by the same complementary rule.
 *
 * Worked example: an old summary of 600 characters (w = 0.75, life position 4) meets a new memory of
 * 200 (w = 0.25, position 0): original' = (0.75·600 + 0.25·200)/1 = 500, floor' = 100, n' = 3, and
 * the rewrite may be at most min(budget'(4), 599) characters.
 *
 * [MemorySnapshot.applyMutation] refuses any rewrite over its limit, on every store, whichever clerk
 * wrote it. Fitting drops detail by weight ([MemoryRewrite.compress]); the predecessors stay as
 * history, and the engine's rewrites put dropped sentences in a separate new memory linked to the
 * version ([MemoryRewrite.spillFor]).
 */
const val MEMORY_LIFESPAN_PASSES: Int = 10

/** Where the steepest loss falls, as a fraction of the lifespan (beyond the midpoint). */
const val MEMORY_MIDPOINT_FRACTION: Double = 0.6

/** Logistic scale, in passes: smaller is a steeper drop. */
const val MEMORY_STEEPNESS_PASSES: Double = 1.0

/** The floor (recognisable gist) as a fraction of the original size. */
const val MEMORY_FLOOR_FRACTION: Double = 0.2

/** Version metadata: the memory's (effective) original size in characters. */
const val MEMORY_ORIGINAL_SIZE: String = "originalSize"

/** Version metadata: the version's life position (0: never rewritten). */
const val MEMORY_REWRITE_PASS: String = "rewritePass"

/** A memory's place on its curve: current size, original size, life position. */
data class MemorySizeState(val size: Int, val original: Int, val pass: Double, val weight: Double = 1.0)

fun memoryFloor(original: Int): Int = ceil(MEMORY_FLOOR_FRACTION * original).toInt()

/** The size budget at life position [pass] for a memory of [original] characters. */
fun memorySizeBudget(original: Int, pass: Double): Int {
    val floorSize = memoryFloor(original)
    val m = MEMORY_MIDPOINT_FRACTION * MEMORY_LIFESPAN_PASSES
    fun sigma(x: Double) = 1.0 / (1.0 + exp(-x))
    val s0 = sigma(-m / MEMORY_STEEPNESS_PASSES)
    val f = ((sigma((pass - m) / MEMORY_STEEPNESS_PASSES) - s0) / (1 - s0)).coerceIn(0.0, 1.0)
    return floorSize + floor((original - floorSize) * (1 - f)).toInt()
}

/** The curve state of a rewrite of [contributors]: its (effective) original size and life position. */
fun memoryRewriteState(contributors: Collection<MemorySizeState>): Pair<Int, Double> {
    require(contributors.isNotEmpty())
    if (contributors.size == 1) return contributors.single().let { it.original to it.pass + 1 }
    var size = contributors.first().size.toDouble()
    var pass = contributors.first().pass
    var weight = contributors.first().weight
    contributors.drop(1).forEach { next ->
        val total = weight + next.weight
        size = (weight * size + next.weight * next.size) / total
        pass = (weight * pass + next.weight * next.pass) / total
        weight = total
    }
    return floor(size).toInt() to pass + 1
}

/**
 * The longest a rewrite of [contributors] (in order of arrival; the first is the current summary)
 * may be (see [MEMORY_LIFESPAN_PASSES]).
 */
fun memoryRewriteLimit(contributors: List<MemorySizeState>): Int {
    if (contributors.isEmpty()) return Int.MAX_VALUE
    val (original, pass) = memoryRewriteState(contributors)
    val floorSize = memoryFloor(original)
    val previous = contributors.maxOf { it.size }
    if (previous <= floorSize) return previous
    return maxOf(floorSize, minOf(memorySizeBudget(original, pass), previous - 1))
}

/** Keeps a contributor with zero salience, strength or confidence from vanishing from the average. */
private const val MIN_CONTRIBUTOR_WEIGHT = 1e-3

internal fun MemoryNode.sizeState(weight: Double = 1.0): MemorySizeState = MemorySizeState(
    text.length,
    metadata[MEMORY_ORIGINAL_SIZE]?.toIntOrNull() ?: text.length,
    metadata[MEMORY_REWRITE_PASS]?.toDoubleOrNull() ?: 0.0,
    weight,
)

/** The contributors' curve states in order of arrival, each with its memory-level weight. */
internal fun MemorySnapshot.contributorStates(ids: Collection<MemoryNodeId>): List<MemorySizeState> {
    val nodesById = nodes.associateBy(MemoryNode::id)
    val contributors = ids.distinct().mapNotNull(nodesById::get).sortedWith(compareBy<MemoryNode> { it.createdAtEpochMillis }.thenBy { it.id.value })
    if (contributors.isEmpty()) return emptyList()
    val evidence = edges.filter { it.relation.isAssociativeEvidence() }
    return contributors.map { node ->
        // Existing measures only: associative strength (accumulateAssociationEvidence, which
        // includes Hebbian co-recall links), combined with the stored salience by the same
        // complementary rule, scaled by confidence (derivation fidelity).
        val strength = accumulateAssociationEvidence(evidence.filter { it.from == node.id || it.to == node.id }).toDouble()
        val weight = node.confidence * (1 - (1 - node.salience) * (1 - strength))
        node.sizeState(weight.coerceAtLeast(MIN_CONTRIBUTOR_WEIGHT))
    }
}

/** A rewrite fitted to its limit: what was kept and the sentences dropped (in original order). */
data class MemoryCompression(val text: String, val dropped: List<String>)

internal object MemoryRewrite {
    /**
     * Retention weight of each sentence of a rewrite of [predecessors]: the existing SalienceFilter
     * score ([MemorySalienceFeatures.score]; MEMORY_DETERMINISTIC_SEMANTICS.md, "Programmatic
     * sectioning and salience") applied to the sentence, with the deliberations citing the
     * predecessors as the prompt terms, IDF over the predecessors' sentences, repeats counted across
     * the predecessors, and the sentence's position in the predecessor that holds it. Nothing new is
     * defined here.
     */
    fun weigher(snapshot: MemorySnapshot, predecessors: Collection<MemoryNodeId>): (String) -> Double {
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val preds = predecessors.mapNotNull(nodesById::get)
        val promptTerms = snapshot.edges
            .filter { it.relation == MemoryRelationKind.Deliberates && it.to in predecessors }
            .mapNotNull { nodesById[it.from] }
            .flatMapTo(hashSetOf()) { MemorySalienceFeatures.terms(it.text) }
        val predSentences = preds.map { node -> memorySentences(node.text).map(::normalize) }
        val idf = MemorySalienceFeatures.idf(predSentences.flatten())
        return { sentence ->
            val norm = normalize(sentence)
            val holders = predSentences.filter { norm in it }
            val position = holders.firstOrNull()?.let { list -> if (list.size > 1) list.indexOf(norm).toFloat() / (list.size - 1) else 0f } ?: 1f
            MemorySalienceFeatures.score(sentence, null, promptTerms, idf, duplicates = (holders.size - 1).coerceAtLeast(0), position = position).score.toDouble()
        }
    }

    /**
     * Fits [text] into [limit] characters: keeps the highest-weight sentences that fit (ties: earlier
     * first), in their original order; if not even one sentence fits, keeps the best sentence's
     * highest-weight clauses, and at last cuts at a word boundary.
     */
    fun compress(text: String, limit: Int, weight: (String) -> Double): MemoryCompression {
        if (text.length <= limit) return MemoryCompression(text, emptyList())
        val sentences = memorySentences(text).map(String::trim).filter(String::isNotEmpty)
        val kept = select(sentences, limit, " ", weight)
        if (kept.isNotEmpty()) {
            return MemoryCompression(kept.joinToString(" ") { sentences[it] }, sentences.filterIndexed { i, _ -> i !in kept })
        }
        val bestIndex = sentences.indices.sortedWith(compareByDescending<Int> { weight(sentences[it]) }.thenBy { it }).firstOrNull()
            ?: return MemoryCompression(cut(text, limit), listOf(text))
        val best = sentences[bestIndex]
        val clauses = best.split(CLAUSE).map(String::trim).filter(String::isNotEmpty)
        val keptClauses = select(clauses, limit, "; ", weight)
        val gist = if (keptClauses.isNotEmpty()) keptClauses.joinToString("; ") { clauses[it] } else cut(best, limit)
        // The best sentence itself stays whole in history; only the other sentences spill.
        return MemoryCompression(gist, sentences.filterIndexed { i, _ -> i != bestIndex })
    }

    /** Last resort: drop function words, then cut at a word boundary. */
    private fun cut(text: String, limit: Int): String {
        val terse = text.replace(FUNCTION_WORDS, "").replace(Regex("\\s+"), " ").trim()
        if (terse.length <= limit) return terse
        return terse.take(limit).substringBeforeLast(' ').ifEmpty { terse.take(limit) }
    }

    private fun select(parts: List<String>, limit: Int, separator: String, weight: (String) -> Double): Set<Int> {
        val order = parts.indices.sortedWith(compareByDescending<Int> { weight(parts[it]) }.thenBy { it })
        val chosen = hashSetOf<Int>()
        var length = 0
        order.forEach { i ->
            val extra = parts[i].length + if (chosen.isEmpty()) 0 else separator.length
            if (length + extra <= limit) {
                chosen += i
                length += extra
            }
        }
        return chosen.sorted().toCollection(linkedSetOf())
    }

    /** A rewrite of [predecessors] fitted to its limit, with the detail it drops. */
    fun fit(snapshot: MemorySnapshot, text: String, predecessors: Collection<MemoryNodeId>): MemoryCompression {
        if (isExactRepeat(snapshot, text, predecessors)) return MemoryCompression(text, emptyList())
        val limit = memoryRewriteLimit(snapshot.contributorStates(predecessors))
        return compress(text, limit, weigher(snapshot, predecessors))
    }

    /** The version's curve state (original size, life position) and time range, from its predecessors. */
    fun versionMetadata(snapshot: MemorySnapshot, predecessors: Collection<MemoryNodeId>): Map<String, String> {
        val states = snapshot.contributorStates(predecessors)
        if (states.isEmpty()) return emptyMap()
        val (original, pass) = memoryRewriteState(states)
        // A consolidated version carries the time range of everything it was made from.
        return mapOf(MEMORY_ORIGINAL_SIZE to original.toString(), MEMORY_REWRITE_PASS to pass.toString()) +
            MemoryTimeRange.metadataFor(snapshot, predecessors) +
            (MEMORY_OCCURRENCES to MemoryTimeRange.occurrences(snapshot, predecessors).toString())
    }

    /**
     * The dropped detail of a rewrite [version] as a separate new memory linked to it (not growth),
     * or null when nothing was dropped.
     */
    fun spillFor(version: MemoryNode, dropped: List<String>): Pair<MemoryNode, MemoryEdge>? {
        if (dropped.isEmpty()) return null
        val spill = MemoryNode(
            id = MemoryNodeId("detail:${version.id.value}"),
            kind = version.kind,
            text = dropped.joinToString(" "),
            sourceEpisodeIds = version.sourceEpisodeIds,
            createdAtEpochMillis = version.createdAtEpochMillis,
            metadata = mapOf("detailOf" to version.id.value),
        )
        return spill to MemoryEdge(
            MemoryEdgeId("${spill.id.value}:associated:${version.id.value}"), spill.id, version.id, MemoryRelationKind.AssociatedWith,
            createdAtEpochMillis = version.createdAtEpochMillis,
            metadata = mapOf("basis" to "detail-spill"),
        )
    }

    fun hasDetail(text: String): Boolean = DETAIL.containsMatchIn(text)

    /** True when every contributor states exactly [text]: folding repeats rewrites nothing. */
    fun isExactRepeat(snapshot: MemorySnapshot, text: String, contributors: Collection<MemoryNodeId>): Boolean {
        val ids = contributors.toSet()
        val texts = snapshot.nodes.filter { it.id in ids }.map { normalize(it.text) }
        return texts.isNotEmpty() && texts.all { it == normalize(text) }
    }

    private fun contentWords(text: String) = WORD.findAll(text.lowercase()).map { it.value }.filter { it.length > 3 }.toSet()

    private fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')

    private val WORD = Regex("[a-z0-9_]+")
    private val DETAIL = Regex("\\d|\"[^\"]+\"|`[^`]+`|\\b[A-Za-z]+[_.][A-Za-z0-9_.]+\\b|\\b(not|never|no)\\b", RegexOption.IGNORE_CASE)
    private val CLAUSE = Regex("[,;:]\\s+")
    private val FUNCTION_WORDS = Regex("\\b(the|a|an|of|that|which|very|just|really)\\s+", RegexOption.IGNORE_CASE)
}

/** Refuses a rewrite longer than [memoryRewriteLimit] allows (see [MEMORY_LIFESPAN_PASSES]). */
internal fun MemorySnapshot.requireRewritesShrink(mutation: MemoryStoreMutation) {
    val supersedes = mutation.edgesToAdd.filter { it.relation == MemoryRelationKind.Supersedes }
    if (supersedes.isEmpty()) return
    val added = mutation.nodesToAdd.associateBy(MemoryNode::id)
    supersedes.groupBy { it.from }.forEach { (versionId, edges) ->
        val version = added[versionId] ?: return@forEach
        if (MemoryRewrite.isExactRepeat(this, version.text, edges.map { it.to })) return@forEach
        val states = contributorStates(edges.map { it.to })
        if (states.isEmpty()) return@forEach
        val limit = memoryRewriteLimit(states)
        require(version.text.length <= limit) {
            "Rewrite ${versionId.value} is ${version.text.length} characters; its size budget is $limit " +
                "(S-curve from the original size to its floor; see MEMORY_LIFESPAN_PASSES): a memory never grows"
        }
    }
}
