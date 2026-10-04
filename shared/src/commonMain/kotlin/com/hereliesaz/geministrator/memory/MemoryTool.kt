package com.hereliesaz.geministrator.memory

import kotlin.math.max

/**
 * Agent-facing memory surface. Agents bank experience and query this service; they do not read
 * persistence directly.
 *
 * GRIP means Global Regular IMpression Print: Haive's memory-specific direct-recall operation.
 * Normal associative recall is cue-first. An agent that already carries semantic tags in its CoTR
 * can pass those tags directly to [grip] and request a deeper resolution only when the tags
 * themselves are not enough to recollect what it needs. Semantic cue tags include noun/entity,
 * verb/action, and category/subject tags.
 */
interface MemoryTool {
    /**
     * Deliberately bank a note, small plan, checkpoint, procedure, resource, or other context now.
     * The deposit enters the same clerical pipeline as lifecycle banking but jumps to next in line
     * for consolidation. Once consolidated, it has no special reminder retrieval semantics.
     */
    suspend fun bank(request: MemoryBankRequest): MemoryQueueEntry

    /** Free-text GRIP. Defaults to tag-level cues. */
    suspend fun grip(query: MemoryQuery): MemoryRecallBundle

    /** Tag-addressed GRIP for semantic tags already present in an agent's CoTR. */
    suspend fun grip(query: MemoryTagQuery): MemoryRecallBundle

    /**
     * How many active memories contain [term], out of how many. Recall triggers use it to keep words
     * that appear everywhere ("build", "test") from firing on their own.
     */
    suspend fun termFrequency(term: String): MemoryTermFrequency = MemoryTermFrequency(0, 0)

    /** Explicitly descend or ascend from a known memory node when tag cues are insufficient. */
    suspend fun expand(
        nodeId: MemoryNodeId,
        resolution: MemoryResolution,
        maxResults: Int = 12,
        includeConflicts: Boolean = false,
    ): List<MemoryRecallHit>
}

/** [memories] of [total] active memories contain a term. */
data class MemoryTermFrequency(val memories: Int, val total: Int)

class GraphMemoryTool(
    private val store: MemoryStore,
    private val queue: MemoryConsolidationQueue = MemoryConsolidationQueue(store),
) : MemoryTool {
    /** Rebuilt only when the store hands out a different snapshot; recall between commits reuses it. */
    private var cachedIndex: MemoryRecallIndex? = null

    private suspend fun index(): MemoryRecallIndex {
        val snapshot = store.read()
        cachedIndex?.takeIf { it.snapshot === snapshot }?.let { return it }
        return MemoryRecallIndex(snapshot).also { cachedIndex = it }
    }

    override suspend fun bank(request: MemoryBankRequest): MemoryQueueEntry = queue.enqueueBank(request)

    override suspend fun termFrequency(term: String): MemoryTermFrequency = index().bm25.frequency(term.lowercase())

    override suspend fun grip(query: MemoryQuery): MemoryRecallBundle {
        val index = index()
        val active = index.activeNodes(query)
        if (active.isEmpty()) return MemoryRecallBundle(query, emptyList())

        val queryLower = query.text.trim().lowercase()
        val weights = queryTermWeights(query)
        // Only nodes sharing a term (BM25F postings), plus exact-phrase matches, are scored.
        val candidates = index.bm25.candidates(weights.keys, index.activeIds)
        val scoredSeeds = active
            .asSequence()
            .filter { it.id in candidates || queryLower.length > 2 && index.lowerText(it).contains(queryLower) }
            .mapNotNull { node ->
                // Relevance only; scope, salience, confidence and recency order hits by rank later.
                val lexical = lexicalScore(weights, queryLower, node, index)
                if (lexical <= 0f) null else node to lexical
            }
            .toList()
            .sortedWith(memorySeedComparator())
            .take(max(query.maxResults * 4, 24))

        return index.recallFromSeeds(query, scoredSeeds, active)
    }

    override suspend fun grip(query: MemoryTagQuery): MemoryRecallBundle {
        val normalizedQuery = query.asMemoryQuery()
        val index = index()
        val active = index.activeNodes(normalizedQuery)
        if (active.isEmpty()) return MemoryRecallBundle(normalizedQuery, emptyList())

        val requestedPhrases = query.tags.map { it.trim().lowercase() }.filter(String::isNotEmpty)
        val requestedTerms = query.tags.flatMap(String::memoryTerms).toSet()
        val scoredSeeds = active
            .asSequence()
            .filter { it.kind in semanticCueKinds }
            .mapNotNull { node ->
                val match = tagAddressScore(requestedPhrases, requestedTerms, node, index)
                if (match <= 0f) null else node to match
            }
            .sortedWith(memorySeedComparator())
            .take(max(query.maxResults * 4, 24))
            .toList()

        return index.recallFromSeeds(normalizedQuery, scoredSeeds, active)
    }

    override suspend fun expand(
        nodeId: MemoryNodeId,
        resolution: MemoryResolution,
        maxResults: Int,
        includeConflicts: Boolean,
    ): List<MemoryRecallHit> {
        require(maxResults > 0)
        val index = index()
        val nodesById = index.nodesById
        require(nodeId in nodesById) { "Memory node ${nodeId.value} was not found" }
        val paths = projectToKinds(
            start = nodeId,
            targetKinds = resolution.nodeKinds(),
            nodesById = nodesById,
            graph = index.graph,
            activeIds = index.activeIds,
            maxDepth = 6,
        )
        return paths.entries
            .sortedWith(
                compareByDescending<Map.Entry<MemoryNodeId, TraversalPath>> { it.value.score }
                    .thenBy { it.value.depth }
                    .thenBy { it.key.value },
            )
            .take(maxResults)
            .mapNotNull { (id, path) ->
                nodesById[id]?.let { node ->
                    MemoryRecallHit(
                        node = node,
                        score = path.score,
                        conflicts = if (includeConflicts) index.conflictsFor(id) else emptyList(),
                    )
                }
            }
    }
}

private val semanticCueKinds = setOf(
    MemoryNodeKind.NounTag,
    MemoryNodeKind.VerbTag,
    MemoryNodeKind.Category,
)

/**
 * Everything GRIP derives from one snapshot: lookups, the superseded set, traversal adjacency, and
 * each node's lexical terms. Node text is immutable, so none of it changes until the next commit.
 */
private class MemoryRecallIndex(val snapshot: MemorySnapshot) {
    val nodesById: Map<MemoryNodeId, MemoryNode> = snapshot.nodes.associateBy(MemoryNode::id)
    val episodesById: Map<MemoryEpisodeId, MemoryEpisode> = snapshot.episodes.associateBy(MemoryEpisode::id)
    private val superseded: Set<MemoryNodeId> = snapshot.edges
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
    val active: List<MemoryNode> = snapshot.nodes.filter { it.id !in superseded }
    val activeIds: Set<MemoryNodeId> = active.mapTo(hashSetOf(), MemoryNode::id)
    val graph: MemoryRecallGraph by lazy { snapshot.recallGraph() }
    val bm25: MemoryBm25Index by lazy { MemoryBm25Index(active) }
    private val terms = hashMapOf<MemoryNodeId, Set<String>>()
    private val lowerTexts = hashMapOf<MemoryNodeId, String>()
    private val conflicts: Map<MemoryNodeId, List<MemoryNodeId>> by lazy {
        buildMap<MemoryNodeId, MutableList<MemoryNodeId>> {
            snapshot.edges.filter { it.relation == MemoryRelationKind.ConflictsWith }.forEach { edge ->
                getOrPut(edge.from) { mutableListOf() } += edge.to
                getOrPut(edge.to) { mutableListOf() } += edge.from
            }
        }
    }

    fun terms(node: MemoryNode): Set<String> = terms.getOrPut(node.id) {
        (node.text.memoryTerms() + node.metadata.values.flatMap(String::memoryTerms)).toSet()
    }

    fun lowerText(node: MemoryNode): String = lowerTexts.getOrPut(node.id) { node.text.trim().lowercase() }

    fun activeNodes(query: MemoryQuery): List<MemoryNode> {
        if (!query.hasHardScopeConstraints()) return active
        return active.filter { node ->
            node.sourceEpisodeIds.isNotEmpty() &&
                node.sourceEpisodeIds.any { episodeId -> episodesById[episodeId]?.let(query::matchesHardScope) == true }
        }
    }

    fun conflictsFor(nodeId: MemoryNodeId, allowedNodeIds: Set<MemoryNodeId>? = null): List<MemoryNode> =
        conflicts[nodeId].orEmpty()
            .asSequence()
            .filter { allowedNodeIds == null || it in allowedNodeIds }
            .mapNotNull(nodesById::get)
            .distinctBy(MemoryNode::id)
            .toList()
}

private fun MemoryRecallIndex.recallFromSeeds(
    query: MemoryQuery,
    scoredSeeds: List<Pair<MemoryNode, Float>>,
    scopedActive: List<MemoryNode>,
): MemoryRecallBundle {
    if (scoredSeeds.isEmpty()) return MemoryRecallBundle(query, emptyList())

    val targetKinds = query.resolution.nodeKinds()
    val activeIds = if (scopedActive === active) this.activeIds else scopedActive.mapTo(hashSetOf()) { it.id }
    val projected = linkedMapOf<MemoryNodeId, Float>()

    scoredSeeds.forEach { (seed, score) ->
        if (seed.kind in targetKinds) {
            projected[seed.id] = max(projected[seed.id] ?: 0f, score)
        }
    }

    // One walk from every seed at once. A path's score is linear in its edge strength, so carrying
    // seedScore x strength per node and keeping the max per hop equals the best over separate walks.
    val bestPath = linkedMapOf<MemoryNodeId, Float>()
    var frontier = linkedMapOf<MemoryNodeId, Float>()
    scoredSeeds.forEach { (seed, score) -> frontier[seed.id] = max(frontier[seed.id] ?: 0f, score) }
    for (depth in 0..MAX_RECALL_DEPTH) {
        frontier.forEach { (id, value) ->
            val node = nodesById[id]
            if (id in activeIds && node?.kind in targetKinds) {
                val candidate = value * weightedTraversalScore(1f, depth)
                if (candidate > (bestPath[id] ?: 0f)) bestPath[id] = candidate
            }
        }
        if (depth == MAX_RECALL_DEPTH) break
        val next = linkedMapOf<MemoryNodeId, Float>()
        frontier.forEach { (id, value) ->
            val fan = fanDamping(graph.links(id))
            graph.neighbors(id).forEach { neighbor ->
                val carried = value * neighbor.weight * fan
                if (carried > 0f && carried > (next[neighbor.id] ?: 0f)) next[neighbor.id] = carried
            }
        }
        frontier = next
    }
    bestPath.forEach { (nodeId, pathScore) ->
        projected[nodeId] = max(projected[nodeId] ?: 0f, pathScore)
    }

    val hits = fusedOrder(query, projected.entries.mapNotNull { (nodeId, score) -> nodesById[nodeId]?.let { it to score } })
        .take(query.maxResults)
        .map { (node, score) ->
            MemoryRecallHit(
                node = node,
                score = score,
                conflicts = if (query.includeConflicts) {
                    conflictsFor(node.id, activeIds)
                } else {
                    emptyList()
                },
            )
        }

    return MemoryRecallBundle(query, hits)
}

/**
 * Orders recall candidates by weighted reciprocal rank fusion (Cormack et al.) instead of adding
 * signals and clamping: relevance (weight 2), scope affinity (0.4, when the query is scoped),
 * salience (0.15), confidence (0.1) and recency (0.15), each as Σ w / (k + rank) with k = 10. The
 * returned score stays the relevance itself (lexical match carried along the graph), so the
 * attention gate's threshold keeps its meaning: how strongly the memory matched, never boosted.
 */
private fun MemoryRecallIndex.fusedOrder(query: MemoryQuery, candidates: List<Pair<MemoryNode, Float>>): List<Pair<MemoryNode, Float>> {
    if (candidates.size <= 1) return candidates
    val signals = buildList<Pair<Double, (Pair<MemoryNode, Float>) -> Double>> {
        add(FUSION_RELEVANCE to { it.second.toDouble() })
        if (query.hasAffinityConstraints()) add(FUSION_SCOPE to { scopeAffinity(query, it.first, episodesById).toDouble() })
        add(FUSION_SALIENCE to { it.first.salience.toDouble() })
        add(FUSION_CONFIDENCE to { it.first.confidence.toDouble() })
        add(FUSION_RECENCY to { it.first.createdAtEpochMillis.toDouble() })
    }
    val fused = DoubleArray(candidates.size)
    signals.forEach { (weight, value) ->
        val values = candidates.map(value)
        // Competition ranking: equal values share the better rank.
        val rankOf = HashMap<Double, Int>()
        values.sortedDescending().forEachIndexed { rank, v -> rankOf.getOrPut(v) { rank } }
        values.forEachIndexed { i, v -> fused[i] += weight / (FUSION_K + 1 + rankOf.getValue(v)) }
    }
    return candidates.indices
        .sortedWith(
            compareByDescending<Int> { fused[it] }
                .thenByDescending { candidates[it].second }
                .thenBy { candidates[it].first.id.value },
        )
        .map(candidates::get)
}

private const val FUSION_K = 10.0
private const val FUSION_RELEVANCE = 2.0
private const val FUSION_SCOPE = 0.4
private const val FUSION_SALIENCE = 0.15
private const val FUSION_CONFIDENCE = 0.1
private const val FUSION_RECENCY = 0.15

private const val MAX_RECALL_DEPTH = 6

private fun memorySeedComparator(): Comparator<Pair<MemoryNode, Float>> =
    compareByDescending<Pair<MemoryNode, Float>> { it.second }
        .thenByDescending { it.first.salience }
        .thenByDescending { it.first.confidence }

private fun tagAddressScore(
    requestedPhrases: List<String>,
    requestedTerms: Set<String>,
    node: MemoryNode,
    index: MemoryRecallIndex,
): Float {
    val nodeText = index.lowerText(node)
    if (requestedPhrases.any { it == nodeText }) return 1f

    if (requestedTerms.isEmpty()) return 0f
    val nodeTerms = index.terms(node)
    if (nodeTerms.isEmpty()) return 0f

    val overlap = requestedTerms.count { it in nodeTerms }.toFloat() / requestedTerms.size
    val phraseBonus = if (requestedPhrases.any { phrase -> phrase in nodeText || nodeText in phrase }) 0.25f else 0f
    return (overlap * 0.75f + phraseBonus).coerceIn(0f, 1f)
}

private fun MemoryTagQuery.asMemoryQuery(): MemoryQuery = MemoryQuery(
    text = tags.joinToString(" "),
    resolution = resolution,
    maxResults = maxResults,
    includeConflicts = includeConflicts,
    projectId = scope.projectId,
    workflowRunId = scope.workflowRunId,
    workflowDefinitionId = scope.workflowDefinitionId,
    taskRunId = scope.taskRunId,
    taskDefinitionId = scope.taskDefinitionId,
    roleId = scope.roleId,
)

private fun MemoryResolution.nodeKinds(): Set<MemoryNodeKind> = when (this) {
    MemoryResolution.Category -> setOf(MemoryNodeKind.Category)
    MemoryResolution.Summary -> setOf(MemoryNodeKind.Summary)
    MemoryResolution.Phrase -> setOf(MemoryNodeKind.Phrase)
    MemoryResolution.Tag -> semanticCueKinds
    MemoryResolution.Context -> setOf(MemoryNodeKind.Context)
}

private data class Neighbor(
    val id: MemoryNodeId,
    val weight: Float,
)

/** A group of nodes sharing one feature (a session, a time bucket, a tag), joined through a hub. */
private class MemoryHub(val members: List<MemoryNodeId>, val weight: Float, val family: String?) {
    val positions: Map<MemoryNodeId, Int> = members.withIndex().associate { (i, id) -> id to i }
}

/**
 * Traversal adjacency for GRIP. Pairwise edges are accumulated per pair as before. Edges that
 * record a feature `group` are read as membership of that group instead of as a chain: every member
 * reaches the others through one hub (the nearest [HUB_WINDOW] on each side, in time order), damped
 * by the hub's size, so a shared session or time bucket links its members in one step rather than
 * along a chain that grows with the group. A hub counts as one link for the walker's own fan.
 */
private class MemoryRecallGraph(
    private val direct: Map<MemoryNodeId, List<Neighbor>>,
    private val hubsOf: Map<MemoryNodeId, List<MemoryHub>>,
) {
    fun links(id: MemoryNodeId): Int = direct[id].orEmpty().size + hubsOf[id].orEmpty().size

    /** Computed per call (no shared cache), so concurrent recalls never race on it. */
    fun neighbors(id: MemoryNodeId): List<Neighbor> {
        val hubs = hubsOf[id].orEmpty()
        if (hubs.isEmpty()) return direct[id].orEmpty()
        val independent = LinkedHashMap<MemoryNodeId, MutableList<Float>>()
        val families = LinkedHashMap<MemoryNodeId, LinkedHashMap<String, Float>>()
        direct[id].orEmpty().forEach { independent.getOrPut(it.id) { mutableListOf() } += it.weight }
        hubs.forEach { hub ->
            val at = hub.positions.getValue(id)
            val weight = hub.weight * fanDamping(hub.members.size - 1)
            for (i in maxOf(0, at - HUB_WINDOW)..minOf(hub.members.lastIndex, at + HUB_WINDOW)) {
                val member = hub.members[i]
                if (member == id) continue
                val family = hub.family
                if (family == null) {
                    independent.getOrPut(member) { mutableListOf() } += weight
                } else {
                    val byFamily = families.getOrPut(member) { linkedMapOf() }
                    byFamily[family] = maxOf(byFamily[family] ?: 0f, weight)
                }
            }
        }
        return (independent.keys + families.keys).distinct().map { target ->
            Neighbor(target, accumulateAssociationStrength(independent[target].orEmpty() + families[target]?.values.orEmpty()))
        }
    }
}

private const val HUB_WINDOW = 32

/**
 * Parallel graph facts between the same pair are accumulated only when they are independent
 * evidence. Correlated re-representations such as temporal rebucketing contribute once through the
 * canonical evidence-family policy before GRIP starts path traversal. Grouped edges become hubs.
 */
private fun MemorySnapshot.recallGraph(): MemoryRecallGraph {
    val evidenceBySource = linkedMapOf<MemoryNodeId, LinkedHashMap<MemoryNodeId, MutableList<MemoryEdge>>>()
    val groups = linkedMapOf<String, MutableList<MemoryEdge>>()

    fun add(from: MemoryNodeId, to: MemoryNodeId, edge: MemoryEdge) {
        val byTarget = evidenceBySource.getOrPut(from) { linkedMapOf() }
        byTarget.getOrPut(to) { mutableListOf() } += edge
    }

    edges.forEach { edge ->
        if (!edge.relation.isRecallTraversable()) return@forEach
        val group = edge.metadata[EDGE_GROUP]?.takeIf(String::isNotBlank)
        if (group != null) {
            groups.getOrPut("${edge.metadata["basis"].orEmpty()}|$group") { mutableListOf() } += edge
            return@forEach
        }
        add(edge.from, edge.to, edge)
        add(edge.to, edge.from, edge)
    }

    val direct = evidenceBySource.mapValues { (_, byTarget) ->
        byTarget.entries.mapNotNull { (target, evidence) ->
            val strength = accumulateAssociationEvidence(evidence)
            if (strength <= 0f) null else Neighbor(target, strength)
        }
    }
    val created = nodes.associate { it.id to it.createdAtEpochMillis }
    val hubsOf = HashMap<MemoryNodeId, MutableList<MemoryHub>>()
    groups.values.forEach { groupEdges ->
        val members = groupEdges.flatMap { listOf(it.from, it.to) }.distinct()
            .sortedWith(compareBy<MemoryNodeId> { created[it] ?: 0L }.thenBy { it.value })
        if (members.size < 2) return@forEach
        // The newest edge's weight stands for the group (rebucketing rewrites weights).
        val newest = groupEdges.maxWith(compareBy<MemoryEdge> { it.createdAtEpochMillis }.thenBy { it.id.value })
        val hub = MemoryHub(members, newest.weight.coerceIn(0f, 1f), hubFamily(newest))
        members.forEach { hubsOf.getOrPut(it) { mutableListOf() } += hub }
    }
    return MemoryRecallGraph(direct, hubsOf)
}

/** Correlated hubs (nested scopes; time buckets) count once per pair, like their chain edges did. */
private fun hubFamily(edge: MemoryEdge): String? {
    val basis = edge.metadata["basis"].orEmpty()
    return when {
        basis.startsWith("scope:") -> "scope"
        basis.startsWith("temporal:") -> "temporal-co-bucket"
        else -> null
    }
}

/** Edge metadata naming the shared feature whose members the edge links (read as a hub). */
internal const val EDGE_GROUP = "group"

private fun MemoryQuery.hasHardScopeConstraints(): Boolean = projectId != null

private fun MemoryQuery.matchesHardScope(episode: MemoryEpisode): Boolean =
    projectId == null || projectId == episode.projectId

private fun MemoryQuery.hasAffinityConstraints(): Boolean =
    projectId != null ||
        workflowRunId != null ||
        workflowDefinitionId != null ||
        taskRunId != null ||
        taskDefinitionId != null ||
        roleId != null

private fun scopeAffinity(
    query: MemoryQuery,
    node: MemoryNode,
    episodesById: Map<MemoryEpisodeId, MemoryEpisode>,
): Float {
    if (!query.hasAffinityConstraints()) return 0f

    return node.sourceEpisodeIds
        .asSequence()
        .mapNotNull(episodesById::get)
        .filter(query::matchesHardScope)
        .maxOfOrNull { episode ->
            var score = 0f
            if (query.projectId != null && query.projectId == episode.projectId) score += 0.05f
            if (query.roleId != null && query.roleId == episode.roleId) score += 0.08f
            if (
                query.workflowDefinitionId != null &&
                query.workflowDefinitionId == episode.workflowDefinitionId
            ) {
                score += 0.12f
            }
            if (query.workflowRunId != null && query.workflowRunId == episode.workflowRunId) score += 0.16f
            if (
                query.taskDefinitionId != null &&
                query.taskDefinitionId == episode.taskDefinitionId
            ) {
                score += 0.18f
            }
            if (query.taskRunId != null && query.taskRunId == episode.taskRunId) score += 0.25f
            score
        }
        ?.coerceAtMost(0.45f)
        ?: 0f
}

private data class TraversalPath(
    val depth: Int,
    val cumulativeEdgeStrength: Float,
) {
    val score: Float
        get() = weightedTraversalScore(cumulativeEdgeStrength, depth)
}

/**
 * Finds the strongest weighted path to each requested node kind up to [maxDepth]. Because depth is
 * strictly bounded, we can retain the strongest cumulative edge strength for every node at each
 * depth without a global visited set; this avoids discarding a stronger-but-longer path too early.
 */
private fun projectToKinds(
    start: MemoryNodeId,
    targetKinds: Set<MemoryNodeKind>,
    nodesById: Map<MemoryNodeId, MemoryNode>,
    graph: MemoryRecallGraph,
    activeIds: Set<MemoryNodeId>,
    maxDepth: Int,
): Map<MemoryNodeId, TraversalPath> {
    val result = linkedMapOf<MemoryNodeId, TraversalPath>()
    var frontier = linkedMapOf(start to 1f)
    var depth = 0

    while (frontier.isNotEmpty() && depth <= maxDepth) {
        frontier.forEach { (id, edgeStrength) ->
            val node = nodesById[id]
            if (id in activeIds && node?.kind in targetKinds) {
                val candidate = TraversalPath(depth, edgeStrength)
                val existing = result[id]
                if (existing == null || candidate.score > existing.score) {
                    result[id] = candidate
                }
            }
        }

        if (depth == maxDepth) break

        val next = linkedMapOf<MemoryNodeId, Float>()
        frontier.forEach { (id, edgeStrength) ->
            val fan = fanDamping(graph.links(id))
            graph.neighbors(id).forEach { neighbor ->
                val cumulative = (edgeStrength * neighbor.weight * fan).coerceIn(0f, 1f)
                if (cumulative <= 0f) return@forEach
                val existing = next[neighbor.id]
                if (existing == null || cumulative > existing) {
                    next[neighbor.id] = cumulative
                }
            }
        }
        frontier = next
        depth += 1
    }

    return result
}

private fun MemoryRelationKind.isRecallTraversable(): Boolean = when (this) {
    MemoryRelationKind.Indexes,
    MemoryRelationKind.Composes,
    MemoryRelationKind.Summarizes,
    MemoryRelationKind.Categorizes,
    MemoryRelationKind.SimilarTo,
    MemoryRelationKind.AssociatedWith,
    MemoryRelationKind.ConflictsWith,
    MemoryRelationKind.ResolvesConflict,
    MemoryRelationKind.CondensedFrom,
    -> true

    MemoryRelationKind.Supersedes -> false
}

/**
 * Lexical relevance in 0..1: BM25F normalized by the query's best possible score (0.8) and an
 * exact-phrase match (0.2). Salience, confidence, scope and recency do not add to it; they order
 * hits by rank ([fusedOrder]).
 */
private fun lexicalScore(
    weights: Map<String, Double>,
    queryLower: String,
    node: MemoryNode,
    index: MemoryRecallIndex,
): Float {
    val bm25 = index.bm25.score(node.id, weights)
    val exactBonus = if (queryLower.isNotEmpty() && index.lowerText(node).contains(queryLower)) 0.2f else 0f
    if (bm25 <= 0f && exactBonus <= 0f) return 0f
    return bm25 * 0.8f + exactBonus
}

/** The caller's terms at weight 1 (stopwords dropped unless nothing else is left); expansions at 0.4. */
private fun queryTermWeights(query: MemoryQuery): Map<String, Double> {
    val own = query.text.memoryTerms()
    val content = own.filterNot { it in RECALL_STOPWORDS }.ifEmpty { own }
    val weights = LinkedHashMap<String, Double>()
    content.forEach { weights[it] = 1.0 }
    query.expansionTerms.flatMap(String::memoryTerms).forEach { if (it !in weights && it !in RECALL_STOPWORDS) weights[it] = EXPANSION_WEIGHT }
    return weights
}

private const val EXPANSION_WEIGHT = 0.4

/**
 * ACT-R's fan effect, softened: spreading from a node with many links is damped by
 * ln(e + 8) / ln(e + links), so hubs stop flooding recall; nodes with eight links or fewer are untouched.
 */
private fun fanDamping(links: Int): Float =
    if (links <= FAN_FREE_LINKS) 1f else (kotlin.math.ln(kotlin.math.E + FAN_FREE_LINKS) / kotlin.math.ln(kotlin.math.E + links)).toFloat()

private const val FAN_FREE_LINKS = 8

/**
 * BM25F over active nodes (Robertson & Zaragoza): fields text (weight 1, b 0.75), tag aliases
 * (0.8, b 0.3) and remaining metadata (0.3, b 0.5); k1 1.2. Scores are normalized by the query's best
 * possible score, so 1.0 means every query term saturated.
 */
internal class MemoryBm25Index(nodes: List<MemoryNode>) {
    private class Fields(val text: Map<String, Int>, val aliases: Map<String, Int>, val meta: Map<String, Int>)

    private val fields = HashMap<MemoryNodeId, Fields>(nodes.size * 2)
    private val postings = HashMap<String, MutableList<MemoryNodeId>>()
    private val documents = nodes.size.coerceAtLeast(1)
    private val averageText: Double
    private val averageAliases: Double
    private val averageMeta: Double

    init {
        var textTotal = 0L
        var aliasTotal = 0L
        var metaTotal = 0L
        nodes.forEach { node ->
            val text = node.text.memoryTermCounts()
            val aliases = node.metadata[TAG_ALIASES].orEmpty().memoryTermCounts()
            val meta = node.metadata.filterKeys { it !in UNINDEXED_METADATA }.values.joinToString(" ").memoryTermCounts()
            fields[node.id] = Fields(text, aliases, meta)
            textTotal += text.values.sum()
            aliasTotal += aliases.values.sum()
            metaTotal += meta.values.sum()
            (text.keys + aliases.keys + meta.keys).forEach { term -> postings.getOrPut(term) { mutableListOf() } += node.id }
        }
        averageText = (textTotal.toDouble() / documents).coerceAtLeast(1.0)
        averageAliases = (aliasTotal.toDouble() / documents).coerceAtLeast(1.0)
        averageMeta = (metaTotal.toDouble() / documents).coerceAtLeast(1.0)
    }

    fun candidates(terms: Collection<String>, allowed: Set<MemoryNodeId>): Set<MemoryNodeId> =
        terms.flatMapTo(HashSet()) { postings[it].orEmpty() }.filterTo(HashSet()) { it in allowed }

    fun frequency(term: String): MemoryTermFrequency = MemoryTermFrequency(postings[term]?.size ?: 0, fields.size)

    fun idf(term: String): Double {
        val df = postings[term]?.size ?: 0
        return kotlin.math.ln(1.0 + (documents - df + 0.5) / (df + 0.5))
    }

    fun score(id: MemoryNodeId, weights: Map<String, Double>): Float {
        val node = fields[id] ?: return 0f
        var score = 0.0
        var best = 0.0
        val textLength = node.text.values.sum().toDouble()
        val aliasLength = node.aliases.values.sum().toDouble()
        val metaLength = node.meta.values.sum().toDouble()
        weights.forEach { (term, weight) ->
            val idf = idf(term)
            best += weight * idf * (K1 + 1)
            val tf = 1.0 * (node.text[term] ?: 0) / (1 - 0.75 + 0.75 * textLength / averageText) +
                0.8 * (node.aliases[term] ?: 0) / (1 - 0.3 + 0.3 * aliasLength / averageAliases) +
                0.3 * (node.meta[term] ?: 0) / (1 - 0.5 + 0.5 * metaLength / averageMeta)
            if (tf > 0) score += weight * idf * tf * (K1 + 1) / (K1 + tf)
        }
        return if (best <= 0.0) 0f else (score / best).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val K1 = 1.2

        /** Bookkeeping metadata whose values are ids, provenance or scores, not content. */
        val UNINDEXED_METADATA = setOf(
            "microAgentRole", "semanticSource", "salienceFeatures", TAG_KEY, TAG_SENSE, TAG_IMPLIED_BY, TAG_OBJECTS,
            TAG_ALIASES, "sourceEpisodeIds", "sourceSectionIds", "representative", "appendedFrom", "salience",
            "confidence", "collapsedRepeatedLines", "nearDuplicatesDropped", "summaryMethod", "taxonomy",
        )
    }
}

private val RECALL_STOPWORDS = setOf(
    "the", "a", "an", "to", "of", "in", "on", "at", "for", "with", "and", "or", "is", "are", "was", "were", "be", "it",
    "its", "this", "that", "by", "as", "from", "into", "do", "does", "did", "we", "you", "i", "how", "what", "why", "when",
)

private fun String.memoryTermCounts(): Map<String, Int> {
    val counts = HashMap<String, Int>()
    val token = StringBuilder()
    fun flush() {
        if (token.length > 1) counts[token.toString()] = (counts[token.toString()] ?: 0) + 1
        token.clear()
    }
    lowercase().forEach { char -> if (char.isLetterOrDigit() || char == '_' || char == '-') token.append(char) else flush() }
    flush()
    return counts
}

private fun String.memoryTerms(): List<String> {
    val terms = mutableListOf<String>()
    val token = StringBuilder()
    lowercase().forEach { char ->
        if (char.isLetterOrDigit() || char == '_' || char == '-') {
            token.append(char)
        } else if (token.isNotEmpty()) {
            if (token.length > 1) terms += token.toString()
            token.clear()
        }
    }
    if (token.length > 1) terms += token.toString()
    return terms.distinct()
}
