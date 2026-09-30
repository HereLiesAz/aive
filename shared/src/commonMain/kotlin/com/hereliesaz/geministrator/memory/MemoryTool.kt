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

    /** Explicitly descend or ascend from a known memory node when tag cues are insufficient. */
    suspend fun expand(
        nodeId: MemoryNodeId,
        resolution: MemoryResolution,
        maxResults: Int = 12,
        includeConflicts: Boolean = false,
    ): List<MemoryRecallHit>
}

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

    override suspend fun grip(query: MemoryQuery): MemoryRecallBundle {
        val index = index()
        val active = index.activeNodes(query)
        if (active.isEmpty()) return MemoryRecallBundle(query, emptyList())

        val episodesById = index.episodesById
        val queryTerms = query.text.memoryTerms()
        val queryLower = query.text.trim().lowercase()
        val scoredSeeds = active
            .mapNotNull { node ->
                val lexical = lexicalScore(queryTerms, queryLower, node, index)
                if (lexical <= 0f) return@mapNotNull null
                // Keep affinity as a ranking signal even when lexical relevance is already 1.0.
                // Returned recall scores are normalized later; this internal score may exceed 1.
                val score = lexical + scopeAffinity(query, node, episodesById)
                node to score
            }
            .sortedWith(memorySeedComparator())
            .take(max(query.maxResults * 4, 24))

        return index.recallFromSeeds(query, scoredSeeds, active)
    }

    override suspend fun grip(query: MemoryTagQuery): MemoryRecallBundle {
        val normalizedQuery = query.asMemoryQuery()
        val index = index()
        val active = index.activeNodes(normalizedQuery)
        if (active.isEmpty()) return MemoryRecallBundle(normalizedQuery, emptyList())

        val episodesById = index.episodesById
        val requestedPhrases = query.tags.map { it.trim().lowercase() }.filter(String::isNotEmpty)
        val requestedTerms = query.tags.flatMap(String::memoryTerms).toSet()
        val scoredSeeds = active
            .asSequence()
            .filter { it.kind in semanticCueKinds }
            .mapNotNull { node ->
                val match = tagAddressScore(requestedPhrases, requestedTerms, node, index)
                if (match <= 0f) return@mapNotNull null
                // Do not clamp before sorting or exact tag matches would erase scope affinity.
                val score = match + scopeAffinity(normalizedQuery, node, episodesById)
                node to score
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
            adjacency = index.adjacency,
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
    val adjacency: Map<MemoryNodeId, List<Neighbor>> by lazy { snapshot.adjacency() }
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
            adjacency[id].orEmpty().forEach { neighbor ->
                val carried = value * neighbor.weight
                if (carried > 0f && carried > (next[neighbor.id] ?: 0f)) next[neighbor.id] = carried
            }
        }
        frontier = next
    }
    bestPath.forEach { (nodeId, pathScore) ->
        val targetNode = nodesById[nodeId] ?: return@forEach
        val hierarchyBoost = scopeAffinity(query, targetNode, episodesById)
        val projectedScore = (pathScore + hierarchyBoost * 0.5f).coerceIn(0f, 1f)
        projected[nodeId] = max(projected[nodeId] ?: 0f, projectedScore)
    }

    val hits = projected.entries
        .mapNotNull { (nodeId, score) -> nodesById[nodeId]?.let { it to score } }
        .sortedWith(memorySeedComparator())
        .take(query.maxResults)
        .map { (node, score) ->
            MemoryRecallHit(
                node = node,
                score = score.coerceIn(0f, 1f),
                conflicts = if (query.includeConflicts) {
                    conflictsFor(node.id, activeIds)
                } else {
                    emptyList()
                },
            )
        }

    return MemoryRecallBundle(query, hits)
}

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

/**
 * Parallel graph facts between the same pair are accumulated only when they are independent
 * evidence. Correlated re-representations such as temporal rebucketing contribute once through the
 * canonical evidence-family policy before GRIP starts path traversal.
 */
private fun MemorySnapshot.adjacency(): Map<MemoryNodeId, List<Neighbor>> {
    val evidenceBySource = linkedMapOf<MemoryNodeId, LinkedHashMap<MemoryNodeId, MutableList<MemoryEdge>>>()

    fun add(from: MemoryNodeId, to: MemoryNodeId, edge: MemoryEdge) {
        val byTarget = evidenceBySource.getOrPut(from) { linkedMapOf() }
        byTarget.getOrPut(to) { mutableListOf() } += edge
    }

    edges.forEach { edge ->
        if (!edge.relation.isRecallTraversable()) return@forEach
        add(edge.from, edge.to, edge)
        add(edge.to, edge.from, edge)
    }

    return evidenceBySource.mapValues { (_, byTarget) ->
        byTarget.entries.mapNotNull { (target, evidence) ->
            val strength = accumulateAssociationEvidence(evidence)
            if (strength <= 0f) null else Neighbor(target, strength)
        }
    }
}

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
    adjacency: Map<MemoryNodeId, List<Neighbor>>,
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
            adjacency[id].orEmpty().forEach { neighbor ->
                val cumulative = (edgeStrength * neighbor.weight).coerceIn(0f, 1f)
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

private fun lexicalScore(
    queryTerms: List<String>,
    queryLower: String,
    node: MemoryNode,
    index: MemoryRecallIndex,
): Float {
    if (queryTerms.isEmpty()) return 0f
    val nodeTerms = index.terms(node)
    if (nodeTerms.isEmpty()) return 0f

    val overlap = queryTerms.count { it in nodeTerms }.toFloat() / queryTerms.size
    val exactBonus = if (node.text.lowercase().contains(queryLower)) 0.25f else 0f
    if (overlap <= 0f && exactBonus <= 0f) return 0f
    val semanticWeight = (node.salience * 0.15f) + (node.confidence * 0.10f)
    return (overlap * 0.5f + exactBonus + semanticWeight).coerceIn(0f, 1f)
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
