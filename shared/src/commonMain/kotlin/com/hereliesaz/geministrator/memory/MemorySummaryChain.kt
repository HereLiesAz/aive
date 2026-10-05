package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.TimeSource

/*
 * The on-device summarizer chain used by the summary tree and the pair summaries.
 *
 * Input is a list of whole paragraphs in source order, a character limit (always from the size
 * budget, MemoryRewrite.kt) and each paragraph's existing salience. The first available engine wins:
 *
 *   a. model      — the local SummarySynthesizer clerk (abstractive), when one is installed and loads;
 *   b. centroid   — paragraphs ranked by cosine similarity to the segment centroid of their
 *                   embeddings (the AssociationLinker's MiniLM embedder), joined with salience;
 *   c. lexrank    — TF-IDF cosine graph over paragraphs, power iteration, plus cue, position and
 *                   heading-overlap signals from the salience features; pure Kotlin, deterministic;
 *   d. luhn       — significant-word clusters, for very short or noisy segments (and as the last
 *                   engine, so the chain always answers).
 *
 * Extractive engines output a selection of whole paragraphs in their original order. Only when not
 * even one paragraph fits does the existing last-resort rule apply (MemoryRewrite.compress: best
 * sentences, then clauses, then a cut at a word). The engine validates every output against the
 * limit and the retention rule (see [MemorySummarizerChain.validate]); an output that fails is
 * discarded and the next engine runs.
 */

/** Embeds texts with the on-device embedding model (the AssociationLinker's MiniLM). */
fun interface MemoryTextEmbedder {
    suspend fun embed(texts: List<String>): List<List<Float>>
}

/** An abstractive summary of [paragraphs] in at most [limit] characters (a model clerk). */
fun interface MemoryAbstractiveSummarizer {
    suspend fun summarize(paragraphs: List<String>, limit: Int, instruction: String): String
}

/**
 * Runs a model-backed SummarySynthesizer clerk on a one-off Summaries packet whose items are the
 * paragraphs, and returns the text of the Summary it proposes.
 */
class ClerkAbstractiveSummarizer(private val agent: MemoryMicroAgent) : MemoryAbstractiveSummarizer {
    init {
        require(agent.role == MemoryMicroAgentRole.SummarySynthesizer) { "Only a SummarySynthesizer clerk summarizes" }
    }

    override suspend fun summarize(paragraphs: List<String>, limit: Int, instruction: String): String =
        agent.process(memorySummaryChainPacket(paragraphs, limit, instruction)).nodesToAdd.firstOrNull { it.kind == MemoryNodeKind.Summary }?.text
            ?: error("${agent.model.modelId} proposed no summary")
}

/**
 * The one-off Summaries packet the chain sends a SummarySynthesizer clerk: the paragraphs as items
 * `p0`, `p1`, … and the instruction with the character limit, which the clerk must see to meet it.
 * `MemoryDatasetGenerator` builds the clerk's summary-chain rows from the same function.
 */
internal fun memorySummaryChainPacket(paragraphs: List<String>, limit: Int, instruction: String): MemoryWorkPacket {
    val episode = MemoryEpisodeId("summary-chain")
    return MemoryWorkPacket(
        queueId = MemoryQueueId("summary-chain"),
        episodeId = episode,
        stage = MemoryConsolidationStage.Summaries,
        packetKey = "summary-${paragraphs.hashCode().toUInt().toString(16)}",
        items = paragraphs.mapIndexed { i, text ->
            MemoryWorkItem("p$i", "node:${MemoryNodeKind.Phrase.name}", text, mapOf("sourceEpisodeIds" to episode.value))
        },
        instruction = "$instruction Character limit: $limit.",
    )
}

/** Summarizer cost, counted per engine over the layer's life (model calls and time included). */
class MemorySummaryMetrics {
    var modelCalls: Int = 0
        internal set
    var modelFailures: Int = 0
        internal set
    var embeddingCalls: Int = 0
        internal set
    var embeddingFailures: Int = 0
        internal set
    var summaries: Int = 0
        internal set
    var millis: Long = 0
        internal set
    /** Pair summaries written. */
    var pairSummaries: Int = 0
        internal set
    /** Links recalled before their pair summary existed (generated synchronously at recall). */
    var pairSummaryViolations: Int = 0
        internal set
    /** Summary-tree nodes written. */
    var treeNodes: Int = 0
        internal set
    private val engines = linkedMapOf<String, Int>()
    val byEngine: Map<String, Int> get() = engines.toMap()

    internal fun used(engine: String) {
        engines[engine] = (engines[engine] ?: 0) + 1
    }
}

/** One request: whole paragraphs in source order, a limit, and each paragraph's existing salience. */
internal data class MemorySummaryRequest(
    val paragraphs: List<String>,
    val limit: Int,
    val salience: List<Double>,
    /** Heading terms of the segment (Edmundson's title signal). */
    val titleTerms: Set<String> = emptySet(),
    val separator: String = PARAGRAPH_SEPARATOR,
    /** Words a model output may not add (a divergence pair summary never judges). */
    val forbidden: Regex? = null,
    val instruction: String = DEFAULT_SUMMARY_INSTRUCTION,
) {
    init {
        require(paragraphs.isNotEmpty() && paragraphs.all(String::isNotBlank))
        require(salience.size == paragraphs.size)
        require(limit > 0)
    }
}

/** A summary and how it was made; [selected] are the kept paragraph indices (extractive only). */
data class MemorySummary(
    val text: String,
    val engine: String,
    val selected: List<Int>,
    val lastResort: Boolean,
    val modelCalls: Int,
    val embeddingCalls: Int,
    val millis: Long,
)

class MemorySummarizerChain(
    private val model: MemoryAbstractiveSummarizer? = null,
    private val embedder: MemoryTextEmbedder? = null,
    val metrics: MemorySummaryMetrics = MemorySummaryMetrics(),
) {
    /** Sees every summary an engine other than the model produced (the corpus generator records them). */
    internal var observer: ((MemorySummaryRequest, MemorySummary) -> Unit)? = null

    private val embeddingCache = linkedMapOf<String, List<Float>>()
    private var embedderDown = false

    /** Embeddings of [texts] (cached), or null when no embedder is available or it fails. */
    internal suspend fun embeddings(texts: List<String>): List<List<Float>>? {
        val embedder = embedder ?: return null
        if (embedderDown) return null
        val missing = texts.filter { it !in embeddingCache }.distinct()
        if (missing.isNotEmpty()) {
            metrics.embeddingCalls += 1
            val vectors = try {
                embedder.embed(missing)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (vectors == null || vectors.size != missing.size || vectors.any { it.isEmpty() } || vectors.map { it.size }.distinct().size != 1) {
                metrics.embeddingFailures += 1
                embedderDown = true
                return null
            }
            missing.zip(vectors).forEach { (text, vector) -> embeddingCache[text] = vector }
            while (embeddingCache.size > MAX_CACHED_EMBEDDINGS) embeddingCache.remove(embeddingCache.keys.first())
        }
        return texts.map { embeddingCache.getValue(it) }
    }

    internal suspend fun summarize(request: MemorySummaryRequest): MemorySummary {
        val mark = TimeSource.Monotonic.markNow()
        var modelCalls = 0
        var embeddingCalls = 0
        fun done(text: String, engine: String, selected: List<Int>, lastResort: Boolean = false): MemorySummary {
            val millis = mark.elapsedNow().inWholeMilliseconds
            metrics.summaries += 1
            metrics.millis += millis
            metrics.used(engine)
            return MemorySummary(text, engine, selected, lastResort, modelCalls, embeddingCalls, millis)
                .also { summary -> if (engine != ENGINE_MODEL) observer?.invoke(request, summary) }
        }
        val whole = request.paragraphs.joinToString(request.separator)
        if (whole.length <= request.limit) return done(whole, ENGINE_VERBATIM, request.paragraphs.indices.toList())

        // a. Model clerk (abstractive).
        model?.let { clerk ->
            modelCalls += 1
            metrics.modelCalls += 1
            val text = try {
                clerk.summarize(request.paragraphs, request.limit, request.instruction).trim()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (text != null && validate(text, request, abstractive = true)) return done(text, ENGINE_MODEL, emptyList())
            metrics.modelFailures += 1
        }

        // b. Embedding centroid.
        if (embedder != null && !embedderDown) {
            val before = metrics.embeddingCalls
            val vectors = embeddings(request.paragraphs)
            embeddingCalls += metrics.embeddingCalls - before
            if (vectors != null) {
                val weights = centroidWeights(vectors).mapIndexed { i, w -> memoryJoin(w, request.salience[i]) }
                extract(request, weights)?.let { (text, selected, lastResort) -> return done(text, ENGINE_CENTROID, selected, lastResort) }
            }
        }

        // c. LexRank with cue/position/heading signals, unless the segment is very short or noisy.
        if (!shortOrNoisy(request.paragraphs)) {
            val weights = lexRankEdmundsonWeights(request.paragraphs, request.titleTerms).mapIndexed { i, w -> memoryJoin(w, request.salience[i]) }
            extract(request, weights)?.let { (text, selected, lastResort) -> return done(text, ENGINE_LEXRANK, selected, lastResort) }
        }

        // d. Luhn significant-word clusters.
        val weights = luhnWeights(request.paragraphs).mapIndexed { i, w -> memoryJoin(w, request.salience[i]) }
        val (text, selected, lastResort) = extract(request, weights) ?: Triple(cutToLimit(whole, request.limit), emptyList(), true)
        return done(text, ENGINE_LUHN, selected, lastResort)
    }

    /**
     * The retention rule every output must pass: non-blank, within the limit, and keeping the
     * anchor — the highest-salience paragraph that fits on its own. An extractive output keeps the
     * anchor whole; an abstractive one must restate the anchor's values (numbers, quoted values) and
     * its negation, must not state a value no paragraph states, and must not add a [forbidden] word.
     */
    internal fun validate(text: String, request: MemorySummaryRequest, abstractive: Boolean): Boolean {
        if (text.isBlank() || text.length > request.limit) return false
        val anchors = anchorCandidates(request)
        if (anchors.isEmpty()) return true
        if (!abstractive) return anchors.any { request.paragraphs[it] in text }
        val outClaims = memoryClaimSignature(text)
        val sourceValues = request.paragraphs.flatMapTo(hashSetOf()) { memoryClaimSignature(it).values }
        val keepsAnchor = anchors.any { anchor ->
            val claims = memoryClaimSignature(request.paragraphs[anchor])
            outClaims.values.containsAll(claims.values) && (!claims.negated || outClaims.negated)
        }
        if (!keepsAnchor) return false
        if (!sourceValues.containsAll(outClaims.values)) return false
        val forbidden = request.forbidden
        if (forbidden != null) {
            val sources = request.paragraphs.joinToString("\n").lowercase()
            if (forbidden.findAll(text.lowercase()).any { it.value !in sources }) return false
        }
        return true
    }

    /** Whole paragraphs by weight within the limit, the anchor first, kept in source order. */
    private fun extract(request: MemorySummaryRequest, weights: List<Double>): Triple<String, List<Int>, Boolean>? {
        val selected = memorySelectParagraphs(request.paragraphs, weights, request.limit, request.separator, anchorOf(request, weights))
        if (selected.isNotEmpty()) {
            val text = selected.joinToString(request.separator) { request.paragraphs[it] }
            return if (validate(text, request, abstractive = false)) Triple(text, selected, false) else null
        }
        // Last resort (no paragraph fits): the existing rewrite rule on the best paragraph.
        val best = request.paragraphs.indices.maxWith(compareBy<Int> { weights[it] }.thenByDescending { it })
        val idf = MemorySalienceFeatures.idf(memorySentences(request.paragraphs[best]))
        val compressed = MemoryRewrite.compress(request.paragraphs[best], request.limit) { sentence ->
            MemorySalienceFeatures.score(sentence, null, emptySet(), idf, duplicates = 0).score.toDouble()
        }
        val text = cutToLimit(compressed.text, request.limit)
        return if (text.isNotBlank()) Triple(text, listOf(best), true) else null
    }

    /** The paragraphs that fit on their own and have the highest salience among those that do. */
    private fun anchorCandidates(request: MemorySummaryRequest): List<Int> {
        val fitting = request.paragraphs.indices.filter { request.paragraphs[it].length <= request.limit }
        val best = fitting.maxOfOrNull { request.salience[it] } ?: return emptyList()
        return fitting.filter { request.salience[it] == best }
    }

    /** The anchor: among the candidates, the engine's highest weight (ties: earlier). */
    private fun anchorOf(request: MemorySummaryRequest, weights: List<Double>): Int? =
        anchorCandidates(request).maxWithOrNull(compareBy<Int> { weights[it] }.thenByDescending { it })

    companion object {
        const val ENGINE_VERBATIM = "verbatim"
        const val ENGINE_MODEL = "model"
        const val ENGINE_CENTROID = "centroid"
        const val ENGINE_LEXRANK = "lexrank"
        const val ENGINE_LUHN = "luhn"
        private const val MAX_CACHED_EMBEDDINGS = 4_096
    }
}

internal const val PARAGRAPH_SEPARATOR = "\n\n"

internal const val DEFAULT_SUMMARY_INSTRUCTION =
    "Summarize these paragraphs as exactly one Summary node within the character limit. Keep the numbers, quoted values " +
        "and negation of what you keep; add no facts; do not judge which paragraph is right."

/**
 * Greedy selection: the [anchor] first, then the rest by weight (ties: earlier first) while they fit
 * [limit] with [separator]; returned in source order. Empty when nothing fits.
 */
internal fun memorySelectParagraphs(paragraphs: List<String>, weights: List<Double>, limit: Int, separator: String, anchor: Int?): List<Int> {
    val order = paragraphs.indices.sortedWith(compareByDescending<Int> { weights[it] }.thenBy { it })
    val chosen = hashSetOf<Int>()
    var length = 0
    (listOfNotNull(anchor) + order).forEach { i ->
        if (i in chosen) return@forEach
        val extra = paragraphs[i].length + if (chosen.isEmpty()) 0 else separator.length
        if (length + extra <= limit) {
            chosen += i
            length += extra
        }
    }
    return chosen.sorted()
}

/** Cosine of each vector to the centroid, clamped to 0..1. */
internal fun centroidWeights(vectors: List<List<Float>>): List<Double> {
    val dimension = vectors.first().size
    val centroid = DoubleArray(dimension)
    vectors.forEach { v -> for (d in 0 until dimension) centroid[d] += v[d].toDouble() / vectors.size }
    return vectors.map { v ->
        var dot = 0.0
        var nv = 0.0
        var nc = 0.0
        for (d in 0 until dimension) {
            dot += v[d] * centroid[d]
            nv += v[d].toDouble() * v[d]
            nc += centroid[d] * centroid[d]
        }
        if (nv == 0.0 || nc == 0.0) 0.0 else (dot / (sqrt(nv) * sqrt(nc))).coerceIn(0.0, 1.0)
    }
}

/** TF-IDF vectors (term counts × the salience features' smoothed IDF over [texts]). */
internal fun memoryTfIdf(texts: List<String>): List<Map<String, Double>> {
    val idf = MemorySalienceFeatures.idf(texts)
    return texts.map { text ->
        MemorySalienceFeatures.terms(text).groupingBy { it }.eachCount().mapValues { (term, count) -> count * (idf[term] ?: 0.0) }
    }
}

internal fun sparseCosine(left: Map<String, Double>, right: Map<String, Double>): Double {
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val (small, large) = if (left.size <= right.size) left to right else right to left
    val dot = small.entries.sumOf { (term, value) -> value * (large[term] ?: 0.0) }
    val nl = sqrt(left.values.sumOf { it * it })
    val nr = sqrt(right.values.sumOf { it * it })
    return if (nl == 0.0 || nr == 0.0) 0.0 else dot / (nl * nr)
}

/**
 * LexRank (continuous): cosine TF-IDF similarity graph with edges at or above [LEXRANK_THRESHOLD],
 * row-normalized, damped by [LEXRANK_DAMPING], power iteration from uniform until the L1 change is
 * under [LEXRANK_EPSILON] (at most [LEXRANK_MAX_ITERATIONS]). Deterministic: no randomness, fixed
 * iteration order. Returns centrality normalized to 0..1 (max = 1).
 */
internal fun lexRank(paragraphs: List<String>): List<Double> {
    val n = paragraphs.size
    if (n == 1) return listOf(1.0)
    val vectors = memoryTfIdf(paragraphs)
    val weights = Array(n) { DoubleArray(n) }
    for (i in 0 until n) for (j in i + 1 until n) {
        val c = sparseCosine(vectors[i], vectors[j])
        if (c >= LEXRANK_THRESHOLD) {
            weights[i][j] = c
            weights[j][i] = c
        }
    }
    val rowSums = DoubleArray(n) { i -> weights[i].sum() }
    var p = DoubleArray(n) { 1.0 / n }
    repeat(LEXRANK_MAX_ITERATIONS) {
        val next = DoubleArray(n) { LEXRANK_DAMPING / n }
        for (i in 0 until n) {
            if (rowSums[i] == 0.0) {
                // A paragraph with no neighbour spreads its share uniformly.
                for (j in 0 until n) next[j] += (1 - LEXRANK_DAMPING) * p[i] / n
            } else {
                for (j in 0 until n) if (weights[i][j] != 0.0) next[j] += (1 - LEXRANK_DAMPING) * p[i] * weights[i][j] / rowSums[i]
            }
        }
        val change = (0 until n).sumOf { abs(next[it] - p[it]) }
        p = next
        if (change < LEXRANK_EPSILON) return normalizeMax(p.toList())
    }
    return normalizeMax(p.toList())
}

/**
 * LexRank centrality joined with Edmundson-style signals taken from the existing salience features:
 * cue (the score's decision / error / action-item parts), location (first or last paragraph) and
 * title (share of the segment's heading terms the paragraph contains):
 * `w = 0.55·lexrank + 0.15·cue + 0.15·location + 0.15·title`.
 */
internal fun lexRankEdmundsonWeights(paragraphs: List<String>, titleTerms: Set<String>): List<Double> {
    val central = lexRank(paragraphs)
    return paragraphs.mapIndexed { i, text ->
        val parts = MemorySalienceFeatures.score(text, null, emptySet(), emptyMap(), duplicates = 0).parts.map { it.first }.toSet()
        val cue = CUE_PARTS.count { it in parts }.toDouble() / CUE_PARTS.size
        val location = if (i == 0 || i == paragraphs.lastIndex) 1.0 else 0.0
        val title = if (titleTerms.isEmpty()) 0.0 else MemorySalienceFeatures.terms(text).toSet().count { it in titleTerms }.toDouble() / titleTerms.size
        LEXRANK_WEIGHT * central[i] + EDMUNDSON_WEIGHT * (cue + location + title)
    }
}

/**
 * Luhn: significant words are the segment's terms occurring at least twice (all its terms when none
 * does); a cluster is a run of words bracketed by significant words with at most [LUHN_GAP]
 * insignificant words between consecutive significant ones; a paragraph scores its best cluster's
 * (significant count)² / span. Normalized to 0..1.
 */
internal fun luhnWeights(paragraphs: List<String>): List<Double> {
    val words = paragraphs.map { LUHN_WORD.findAll(it.lowercase()).map { m -> m.value }.toList() }
    val counts = paragraphs.flatMap(MemorySalienceFeatures::terms).groupingBy { it }.eachCount()
    val significant = counts.filterValues { it >= 2 }.keys.ifEmpty { counts.keys }
    val scores = words.map { tokens ->
        var best = 0.0
        var start = -1
        var last = -1
        var count = 0
        fun close() {
            if (count > 0) best = maxOf(best, count.toDouble() * count / (last - start + 1))
        }
        tokens.forEachIndexed { i, token ->
            if (token !in significant) return@forEachIndexed
            if (start >= 0 && i - last - 1 <= LUHN_GAP) {
                last = i
                count += 1
            } else {
                close()
                start = i
                last = i
                count = 1
            }
        }
        close()
        best
    }
    return normalizeMax(scores)
}

/** Very short or noisy: under three paragraphs, few terms per paragraph, or mostly tool noise. */
internal fun shortOrNoisy(paragraphs: List<String>): Boolean {
    if (paragraphs.size < 3) return true
    val terms = paragraphs.sumOf { MemorySalienceFeatures.terms(it).size }.toDouble() / paragraphs.size
    if (terms < LUHN_MIN_TERMS) return true
    return paragraphs.count { MemorySalienceFeatures.isToolNoise(it) } * 2 >= paragraphs.size
}

private fun normalizeMax(values: List<Double>): List<Double> {
    val max = values.maxOrNull() ?: return values
    return if (max <= 0.0) values.map { 0.0 } else values.map { it / max }
}

private fun cutToLimit(text: String, limit: Int): String =
    if (text.length <= limit) text else text.take(limit).substringBeforeLast(' ').ifBlank { text.take(limit) }.trim()

internal const val LEXRANK_THRESHOLD = 0.1
internal const val LEXRANK_DAMPING = 0.15
internal const val LEXRANK_EPSILON = 1e-9
internal const val LEXRANK_MAX_ITERATIONS = 200
private const val LEXRANK_WEIGHT = 0.55
private const val EDMUNDSON_WEIGHT = 0.15
private const val LUHN_GAP = 4
private const val LUHN_MIN_TERMS = 6.0
private val CUE_PARTS = listOf("decision", "error", "actionItem")
private val LUHN_WORD = Regex("[a-z][a-z0-9_]+")
