package com.hereliesaz.geministrator.memory

import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * The top-down summary tree of one banked episode.
 *
 * The units are the episode's paragraphs: the Sectioner's sections in order, each split into its
 * paragraph-level typed blocks (MemorySectioning.kt). The root summarizes the whole episode; a range
 * of units is split at its largest natural boundaries and each part is summarized and split again,
 * down to leaves of one paragraph (one claim or action), which keep the paragraph verbatim.
 *
 * Boundaries, largest first (the first level that has a boundary inside the range is used):
 *  1. task phases: a change of source part (the session part a section's chunks came from);
 *  2. section groups: section boundaries where the topic shifts — the similarity between the
 *     adjacent sections drops below mean − sd/2 of the range's boundary similarities — or, when no
 *     boundary stands out, every section boundary;
 *  3. paragraphs within one section, by the same topic-shift rule.
 * Similarity is the cosine of the units' embeddings (the on-device MiniLM embedder) averaged per
 * side, or of TF-IDF vectors when no embedder is available. Every split yields at least two parts,
 * so the recursion ends.
 *
 * Every node is an [MemoryNodeKind.Outline] memory node with `Outlines` edges parent → child, its
 * level (root 0), path, cues (top TF-IDF terms) and `Indexes` edges from the episode's existing noun
 * and verb tags it mentions; the producing-workflow tag is stamped by the lineage store as on every
 * memory. An interior node is a summary of its children under the existing size rules: its children
 * are the contributors of a combined rewrite (MemoryRewrite.kt), so its limit is
 * `memoryRewriteLimit(children)` and it records the folded original size and life position. No
 * tighter budget is imposed toward the root; the combining rule alone makes upper levels shorter
 * and further along the curve. Built once, at the start of the episode's Condensation stage.
 */
internal object MemorySummaryTree {
    fun rootId(episodeId: MemoryEpisodeId) = MemoryNodeId("outline:${episodeId.value}:0")

    private data class Para(val text: String, val section: MemorySectionId?, val part: String, val heading: Boolean)

    private class Built(val node: MemoryNode, val state: MemorySizeState)

    suspend fun mutationFor(snapshot: MemorySnapshot, episodeId: MemoryEpisodeId, nowEpochMillis: Long, chain: MemorySummarizerChain): MemoryStoreMutation {
        val rootId = rootId(episodeId)
        if (snapshot.nodes.any { it.id == rootId }) return MemoryStoreMutation()
        val episode = snapshot.episodes.firstOrNull { it.id == episodeId } ?: return MemoryStoreMutation()
        val units = unitsOf(snapshot, episode)
        if (units.isEmpty()) return MemoryStoreMutation()
        val mark = TimeSource.Monotonic.markNow()
        val modelBefore = chain.metrics.modelCalls
        val embedBefore = chain.metrics.embeddingCalls

        val texts = units.map(Para::text)
        val idf = MemorySalienceFeatures.idf(texts)
        val promptTerms = MemorySalienceFeatures.terms(episode.userPrompt).toSet()
        val salience = units.mapIndexed { i, unit ->
            MemorySalienceFeatures.score(unit.text, null, promptTerms, idf, duplicates = 0, position = if (units.size > 1) i.toFloat() / (units.size - 1) else 0f).score.toDouble()
        }
        val dense = chain.embeddings(texts)?.map { v -> v.withIndex().associate { (d, x) -> d.toString() to x.toDouble() } }
        val vectors = dense ?: memoryTfIdf(texts)
        val tags = snapshot.nodes.filter { episodeId in it.sourceEpisodeIds && (it.kind == MemoryNodeKind.NounTag || it.kind == MemoryNodeKind.VerbTag) }

        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()

        fun cues(text: String): String = MemorySalienceFeatures.terms(text).groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value * (idf[it.key] ?: 0.0) }.thenBy { it.key })
            .take(CUES_PER_NODE).joinToString(",") { it.key }

        suspend fun build(range: IntRange, path: String, level: Int): Built {
            val id = MemoryNodeId("outline:${episodeId.value}:$path")
            val sections = range.mapNotNullTo(linkedSetOf()) { units[it].section }
            val base = mapOf(
                OUTLINE_EPISODE to episodeId.value,
                OUTLINE_LEVEL to level.toString(),
                OUTLINE_PATH to path,
                OUTLINE_CUES to cues(range.joinToString(" ") { units[it].text }),
            )
            if (range.first == range.last) {
                val text = units[range.first].text
                val node = MemoryNode(
                    id = id, kind = MemoryNodeKind.Outline, text = text, sourceEpisodeIds = setOf(episodeId), sourceSectionIds = sections,
                    salience = salience[range.first].toFloat(), createdAtEpochMillis = nowEpochMillis,
                    metadata = base + mapOf(OUTLINE_LEAF to "true", MEMORY_ORIGINAL_SIZE to text.length.toString(), MEMORY_REWRITE_PASS to "0.0"),
                )
                nodes += node
                return Built(node, MemorySizeState(text.length, text.length, 0.0, memoryContributorWeight(node.salience.toDouble(), 1.0, 0.0)))
            }
            val parts = segment(range, units, vectors)
            val children = parts.mapIndexed { i, part -> build(part, "$path.$i", level + 1) }
            val states = children.map(Built::state)
            val limit = memoryRewriteLimit(states)
            val (original, pass) = memoryRewriteState(states)
            val titleTerms = range.filter { units[it].heading }.flatMapTo(hashSetOf()) { MemorySalienceFeatures.terms(units[it].text.lineSequence().first()) }
            val summary = chain.summarize(
                MemorySummaryRequest(
                    paragraphs = range.map { units[it].text },
                    limit = limit,
                    salience = range.map { salience[it] },
                    titleTerms = titleTerms,
                ),
            )
            val nodeSalience = MemorySalienceFeatures.score(summary.text, null, promptTerms, idf, duplicates = 0).score
            val node = MemoryNode(
                id = id, kind = MemoryNodeKind.Outline, text = summary.text, sourceEpisodeIds = setOf(episodeId), sourceSectionIds = sections,
                salience = nodeSalience, createdAtEpochMillis = nowEpochMillis,
                metadata = base + mapOf(
                    OUTLINE_CHILDREN to children.size.toString(),
                    SUMMARIZER to summary.engine,
                    SUMMARY_MILLIS to summary.millis.toString(),
                    MEMORY_ORIGINAL_SIZE to original.toString(),
                    MEMORY_REWRITE_PASS to pass.toString(),
                ) + (if (summary.lastResort) mapOf(SUMMARY_LAST_RESORT to "true") else emptyMap()),
            )
            nodes += node
            children.forEach { child ->
                edges += MemoryEdge(
                    MemoryEdgeId("outlines:${id.value}|${child.node.id.value}"), id, child.node.id, MemoryRelationKind.Outlines,
                    createdAtEpochMillis = nowEpochMillis,
                )
            }
            return Built(node, MemorySizeState(summary.text.length, original, pass, memoryContributorWeight(nodeSalience.toDouble(), 1.0, 0.0)))
        }

        build(units.indices, "0", 0)
        // Cues at every level: the episode's existing noun/verb tags each node mentions.
        nodes.forEach { node ->
            val lower = node.text.lowercase()
            tags.filter { tag -> tag.text.isNotBlank() && Regex("\\b${Regex.escape(tag.text.lowercase())}\\b").containsMatchIn(lower) }.forEach { tag ->
                edges += MemoryEdge(
                    MemoryEdgeId("outline-cue:${tag.id.value}|${node.id.value}"), tag.id, node.id, MemoryRelationKind.Indexes,
                    createdAtEpochMillis = nowEpochMillis, metadata = mapOf("basis" to "outline-cue", "deterministic" to "true"),
                )
            }
        }
        chain.metrics.treeNodes += nodes.size
        val cost = mapOf(
            TREE_NODES to nodes.size.toString(),
            TREE_MODEL_CALLS to (chain.metrics.modelCalls - modelBefore).toString(),
            TREE_EMBEDDING_CALLS to (chain.metrics.embeddingCalls - embedBefore).toString(),
            TREE_MILLIS to mark.elapsedNow().inWholeMilliseconds.toString(),
        )
        val withCost = nodes.map { if (it.id == rootId) it.copy(metadata = it.metadata + cost) else it }
        return MemoryStoreMutation(nodesToAdd = withCost, edgesToAdd = edges)
    }

    /** The episode's paragraphs in order: each section's paragraph-level blocks (chunks when unsectioned). */
    private fun unitsOf(snapshot: MemorySnapshot, episode: MemoryEpisode): List<Para> {
        val sections = snapshot.sections.filter { it.episodeId == episode.id }.sortedBy(MemorySection::ordinal)
        if (sections.isNotEmpty()) {
            return sections.flatMap { section ->
                val part = section.sourceChunkIds.minOfOrNull { partOf(it.value) } ?: ""
                blocks(section.text).map { (text, heading) -> Para(text, section.id, part, heading) }
            }
        }
        return episode.chunks.sortedBy(MemorySourceChunk::ordinal).flatMap { chunk ->
            blocks(chunk.text).map { (text, heading) -> Para(text, null, partOf(chunk.id.value), heading) }
        }
    }

    private fun blocks(text: String): List<Pair<String, Boolean>> =
        text.typedBlocks().map { it.text.trim() to (it.type == MemoryBlockType.Heading || it.text.trimStart().startsWith("#")) }
            .filter { it.first.isNotBlank() }
            .ifEmpty { listOfNotNull(text.trim().takeIf(String::isNotBlank)?.let { it to false }) }

    /** `<episode>:chunk:<part>:<piece>` → the zero-padded part, so parts order as numbers. */
    private fun partOf(chunkId: String): String =
        chunkId.substringAfter(":chunk:", "").substringBefore(':').padStart(6, '0')

    /** Splits [range] (two or more units) at its largest natural boundaries. */
    private fun segment(range: IntRange, units: List<Para>, vectors: List<Map<String, Double>>): List<IntRange> {
        val inner = (range.first + 1)..range.last
        val phases = inner.filter { units[it].part != units[it - 1].part }
        if (phases.isNotEmpty()) return cut(range, phases)
        val sectionStarts = inner.filter { units[it].section != units[it - 1].section }
        if (sectionStarts.isNotEmpty()) {
            val groups = cut(range, sectionStarts)
            return cut(range, topicShifts(groups, vectors).ifEmpty { sectionStarts })
        }
        val singles = range.map { it..it }
        return cut(range, topicShifts(singles, vectors).ifEmpty { inner.toList() })
    }

    /**
     * Boundaries between consecutive [groups] where the similarity of the two sides (mean vectors)
     * drops below mean − sd/2 of all the boundaries' similarities. Empty when none stands out.
     */
    private fun topicShifts(groups: List<IntRange>, vectors: List<Map<String, Double>>): List<Int> {
        if (groups.size < 3) return emptyList()
        val sims = (1 until groups.size).map { k -> sparseCosine(mean(groups[k - 1], vectors), mean(groups[k], vectors)) }
        val m = sims.average()
        val sd = sqrt(sims.sumOf { (it - m) * (it - m) } / sims.size)
        if (sd == 0.0) return emptyList()
        return (1 until groups.size).filter { k -> sims[k - 1] < m - sd / 2 }.map { groups[it].first }
    }

    private fun mean(range: IntRange, vectors: List<Map<String, Double>>): Map<String, Double> {
        val sum = HashMap<String, Double>()
        range.forEach { i -> vectors[i].forEach { (k, v) -> sum[k] = (sum[k] ?: 0.0) + v / (range.last - range.first + 1) } }
        return sum
    }

    private fun cut(range: IntRange, starts: List<Int>): List<IntRange> {
        val bounds = (listOf(range.first) + starts.filter { it > range.first && it <= range.last }.distinct().sorted() + (range.last + 1))
        return bounds.zipWithNext { a, b -> a until b }
    }
}

/**
 * Pair summaries: every link between two memories gets a summary of the two together, written by
 * the engine during consolidation, in the sequential queue, so it exists before the link is first
 * recalled. Links covered: semantic and mechanical associations (`SimilarTo`, `AssociatedWith`,
 * co-recall links included), divergence (`Diverges`, legacy `ConflictsWith`) and `CondensedFrom`.
 * Hierarchy and bookkeeping edges (tree, ladder, supersession, register, deliberation, access) are
 * not links between two memories' content and get none.
 *
 * The summary always represents both memories: each side is summarized by the chain in half the
 * room and the two are joined as `A: …\nB: …` (`A` the edge's `from`, `B` its `to`), or `… / …`
 * when labels cost too much; [validatePair] rejects a summary missing either side. Its size budget is the existing one-memory rewrite rule
 * (MemoryRewrite.kt) applied to the pair's joint text: size and original size are the two memories'
 * sums, the life position their weighted mean (contributor weights), so the limit is
 * `memoryRewriteLimit([joint])`. A divergence summary reads "same question, different answers": the
 * shared frame and each side's filler as the contrast step reads them (MemoryContrast) when that
 * fits, else each side summarized by the chain in half the room. It never names a chosen side, and
 * a model's wording that adds a judging word is rejected.
 *
 * At most [PAIR_SUMMARIES_PER_PASS] are written per consolidation pass; the rest carry over to the
 * next pass, oldest link first. A link recalled before its summary exists gets one synchronously at
 * recall, counted as a violation ([MemorySummaryMetrics.pairSummaryViolations]).
 */
internal object MemoryPairSummaries {
    val RELATIONS: Set<MemoryRelationKind> = setOf(
        MemoryRelationKind.SimilarTo,
        MemoryRelationKind.AssociatedWith,
        MemoryRelationKind.Diverges,
        MemoryRelationKind.ConflictsWith,
        MemoryRelationKind.CondensedFrom,
    )

    fun idFor(edgeId: MemoryEdgeId) = MemoryNodeId("pair:${edgeId.value}")

    private val VIEW_KINDS = setOf(MemoryNodeKind.Outline, MemoryNodeKind.PairSummary)

    /** True when [edge] is a link between two memories that should carry a pair summary. */
    fun covers(edge: MemoryEdge, nodesById: Map<MemoryNodeId, MemoryNode>): Boolean {
        if (edge.relation !in RELATIONS) return false
        val from = nodesById[edge.from] ?: return false
        val to = nodesById[edge.to] ?: return false
        return from.kind !in VIEW_KINDS && to.kind !in VIEW_KINDS
    }

    /** Covered links without a summary, oldest first. */
    fun pending(snapshot: MemorySnapshot): List<MemoryEdge> {
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        return snapshot.edges
            .filter { covers(it, nodesById) && idFor(it.id) !in nodesById }
            .sortedWith(compareBy<MemoryEdge> { it.createdAtEpochMillis }.thenBy { it.id.value })
    }

    suspend fun summaryFor(snapshot: MemorySnapshot, edge: MemoryEdge, nowEpochMillis: Long, chain: MemorySummarizerChain): MemoryNode {
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val a = nodesById.getValue(edge.from)
        val b = nodesById.getValue(edge.to)
        // The pair's joint text (both memories side by side) is the memory being rewritten: one
        // memory continuing down its curve, at the weighted life position of the two (weights as for
        // any contributor). A fold of one into the other would leave room for only one side.
        val states = snapshot.contributorStates(listOf(a.id, b.id))
        val jointPass = states.sumOf { it.weight * it.pass } / states.sumOf { it.weight }
        val joint = MemorySizeState(states.sumOf { it.size }, states.sumOf { it.original }, jointPass)
        val limit = memoryRewriteLimit(listOf(joint)).coerceAtLeast(1)
        val (original, pass) = memoryRewriteState(listOf(joint))
        val divergence = edge.relation == MemoryRelationKind.Diverges || edge.relation == MemoryRelationKind.ConflictsWith
        fun paragraphs(node: MemoryNode) = node.text.typedBlocks().map { it.text.trim() }.filter(String::isNotBlank).ifEmpty { listOf(node.text.trim()) }
        val engines = mutableListOf<String>()
        var millis = 0L
        val contrast = if (divergence) MemoryContrast.between(a.text, b.text) else null
        val contrastText = contrast?.let { c ->
            val sides = "${c.frameText}\n$SIDE_A${c.leftFillerText}\n$SIDE_B${c.rightFillerText}"
            listOf("$DIVERGENCE_HEADER $sides", sides).firstOrNull { validatePair(it, limit) }
        }
        val tiny = a.text.length + b.text.length <= 2 * MIN_SIDE
        val text = if (tiny) {
            // Two very short memories (tags, names) are their own gist: both kept whole, no labels.
            engines += ENGINE_VERBATIM_PAIR
            // On one line: the separator must be on the last line, and a chatter memory ("ok\n\nthanks!")
            // would otherwise put a line break after it.
            "${a.text.oneLine()}$UNLABELLED_SEPARATOR${b.text.oneLine()}"
        } else if (contrastText != null) {
            // The frame and each side's filler, as the contrast step reads them: exact, no engine needed.
            engines += ENGINE_CONTRAST
            contrastText
        } else {
            // Two-part form: each side summarized by the chain in half the room, never one side alone.
            val header = if (divergence) DIVERGENCE_HEADER.takeIf { limit - it.length - 1 >= 2 * MIN_SIDE + SIDE_A.length + SIDE_B.length + 1 } else null
            val headerCost = header?.length?.plus(1) ?: 0
            val labelled = (limit - headerCost - SIDE_A.length - SIDE_B.length - 1) / 2 >= MIN_SIDE
            val joinCost = if (labelled) SIDE_A.length + SIDE_B.length + 1 else UNLABELLED_SEPARATOR.length
            val side = ((limit - headerCost - joinCost) / 2).coerceAtLeast(1)
            val sides = listOf(a, b).map { node ->
                val ps = paragraphs(node)
                chain.summarize(
                    MemorySummaryRequest(
                        ps, side, ps.map { node.salience.toDouble() }, separator = " ",
                        forbidden = if (divergence) JUDGING else null,
                        instruction = if (divergence) DIVERGENCE_INSTRUCTION else PAIR_SIDE_INSTRUCTION,
                    ),
                ).also { engines += it.engine; millis += it.millis }.text.replace('\n', ' ').trim()
            }
            val body = if (labelled) "$SIDE_A${sides[0]}\n$SIDE_B${sides[1]}" else sides.joinToString(UNLABELLED_SEPARATOR)
            listOfNotNull(header, body).joinToString("\n")
        }
        require(validatePair(text, if (tiny) text.length else limit)) { "Pair summary of ${edge.id.value} does not represent both memories within $limit characters" }
        chain.metrics.pairSummaries += 1
        return MemoryNode(
            id = idFor(edge.id),
            kind = MemoryNodeKind.PairSummary,
            text = text,
            sourceEpisodeIds = a.sourceEpisodeIds + b.sourceEpisodeIds,
            sourceSectionIds = a.sourceSectionIds + b.sourceSectionIds,
            salience = memoryJoin(a.salience.toDouble(), b.salience.toDouble()).toFloat(),
            confidence = minOf(a.confidence, b.confidence),
            createdAtEpochMillis = nowEpochMillis,
            metadata = mapOf(
                PAIR_OF to edge.id.value,
                PAIR_RELATION to edge.relation.name,
                PAIR_FROM to a.id.value,
                PAIR_TO to b.id.value,
                PAIR_KIND to if (divergence) "divergence" else "link",
                SUMMARIZER to engines.distinct().joinToString(","),
                SUMMARY_MILLIS to millis.toString(),
                MEMORY_ORIGINAL_SIZE to original.toString(),
                MEMORY_REWRITE_PASS to pass.toString(),
            ),
        )
    }

    /** Writes up to [limit] pending pair summaries; returns how many were written. */
    suspend fun step(store: MemoryStore, nowEpochMillis: Long, chain: MemorySummarizerChain, limit: Int = PAIR_SUMMARIES_PER_PASS): Int =
        write(store, nowEpochMillis, chain) { pending(it).take(limit) }

    /** Writes the summaries [edges] lack, now (the recall-time safety net). */
    suspend fun ensure(store: MemoryStore, edges: Collection<MemoryEdge>, nowEpochMillis: Long, chain: MemorySummarizerChain): Map<MemoryEdgeId, MemoryNode> {
        val ids = edges.mapTo(hashSetOf()) { it.id }
        val written = mutableMapOf<MemoryEdgeId, MemoryNode>()
        write(store, nowEpochMillis, chain, written) { snapshot -> pending(snapshot).filter { it.id in ids } }
        return written
    }

    private suspend fun write(
        store: MemoryStore,
        nowEpochMillis: Long,
        chain: MemorySummarizerChain,
        written: MutableMap<MemoryEdgeId, MemoryNode> = mutableMapOf(),
        select: (MemorySnapshot) -> List<MemoryEdge>,
    ): Int {
        repeat(COMMIT_ATTEMPTS) {
            val snapshot = store.read()
            val edges = select(snapshot)
            if (edges.isEmpty()) return 0
            val nodes = edges.map { summaryFor(snapshot, it, nowEpochMillis, chain) }
            if (store.commit(snapshot.revision, MemoryStoreMutation(nodesToAdd = nodes))) {
                edges.zip(nodes).forEach { (edge, node) -> written[edge.id] = node }
                return nodes.size
            }
        }
        return 0
    }

    private fun String.oneLine(): String = trim().replace(Regex("\\s*\n\\s*"), " ")

    private const val COMMIT_ATTEMPTS = 3
    private const val MIN_SIDE = 16
    const val ENGINE_CONTRAST = "contrast"
    const val ENGINE_VERBATIM_PAIR = "verbatim-pair"
    const val SIDE_A = "A: "
    const val SIDE_B = "B: "
    const val DIVERGENCE_HEADER = "Same question, different answers:"
    private const val PAIR_SIDE_INSTRUCTION =
        "Summarize this memory, one side of a linked pair, within the character limit. Add no facts."
    private const val UNLABELLED_SEPARATOR = " / "

    /** A pair summary is within its limit and has a non-blank part for each side (labelled or separated). */
    fun validatePair(text: String, limit: Int): Boolean {
        if (text.length > limit) return false
        val lines = text.lines()
        val a = lines.firstOrNull { it.startsWith(SIDE_A) }?.removePrefix(SIDE_A)
        val b = lines.firstOrNull { it.startsWith(SIDE_B) }?.removePrefix(SIDE_B)
        if (a != null || b != null) return !a.isNullOrBlank() && !b.isNullOrBlank()
        val last = lines.last()
        return last.contains(UNLABELLED_SEPARATOR) && last.split(UNLABELLED_SEPARATOR, limit = 2).all(String::isNotBlank)
    }
    private const val DIVERGENCE_INSTRUCTION =
        "Summarize this side of a divergence within the character limit. State what it says; do not judge whether it is right."

    /** Words a divergence pair summary's model wording may not add: it describes, never judges. */
    val JUDGING = Regex("\\b(correct|incorrect|wrong|right|true|false|better|worse|outdated|obsolete|mistaken|wins?|chosen|prefer\\w*|should)\\b")
}

/** Pair summaries written per consolidation pass; the remainder carries over to the next pass. */
const val PAIR_SUMMARIES_PER_PASS: Int = 32

/** Summary-tree nodes' cue terms (top TF-IDF terms of the node's span). */
private const val CUES_PER_NODE = 6

const val OUTLINE_EPISODE: String = "outlineEpisode"
const val OUTLINE_LEVEL: String = "outlineLevel"
const val OUTLINE_PATH: String = "outlinePath"
const val OUTLINE_LEAF: String = "outlineLeaf"
const val OUTLINE_CHILDREN: String = "outlineChildren"
const val OUTLINE_CUES: String = "cues"
const val SUMMARIZER: String = "summarizer"
const val SUMMARY_MILLIS: String = "summaryMillis"
const val SUMMARY_LAST_RESORT: String = "summaryLastResort"
const val TREE_NODES: String = "treeNodes"
const val TREE_MODEL_CALLS: String = "treeModelCalls"
const val TREE_EMBEDDING_CALLS: String = "treeEmbeddingCalls"
const val TREE_MILLIS: String = "treeMillis"
const val PAIR_OF: String = "pairOf"
const val PAIR_RELATION: String = "pairRelation"
const val PAIR_FROM: String = "pairFrom"
const val PAIR_TO: String = "pairTo"
const val PAIR_KIND: String = "pairKind"
