package com.hereliesaz.geministrator.memory

import kotlin.math.max

/**
 * Agent-facing memory surface. Agents bank experience and query this service; they do not read
 * persistence directly.
 *
 * GRIP means Global Regular IMpression Print: Haive's memory-specific direct-recall operation.
 * Normal associative recall is tag-first. An agent that already carries semantic tags in its CoTR
 * can pass those tags directly to [grip] and request a deeper resolution only when the tags
 * themselves are not enough to recollect what it needs.
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
    override suspend fun bank(request: MemoryBankRequest): MemoryQueueEntry = queue.enqueueBank(request)

    override suspend fun grip(query: MemoryTagQuery): MemoryRecallBundle = grip(query.asMemoryQuery())

    override suspend fun grip(query: MemoryQuery): MemoryRecallBundle {
        val snapshot = store.read()
        val active = snapshot.activeNodes(query.projectId)
        if (active.isEmpty()) return MemoryRecallBundle(query, emptyList())

        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val episodesById = snapshot.episodes.associateBy(MemoryEpisode::id)
        val adjacency = snapshot.adjacency()
        val targetKinds = query.resolution.nodeKinds()
        val activeIds = active.mapTo(hashSetOf()) { it.id }
        val scoredSeeds = active
            .mapNotNull { node ->
                val lexical = lexicalScore(query.text, node)
                if (lexical <= 0f) return@mapNotNull null
                val score = (lexical + scopeAffinity(query, node, episodesById)).coerceIn(0f, 1f)
                node to score
            }
            .sortedWith(
                compareByDescending<Pair<MemoryNode, Float>> { it.second }
                    .thenByDescending { it.first.salience }
                    .thenByDescending { it.first.confidence },
            )
            .take(max(query.maxResults * 4, 24))

        val projected = linkedMapOf<MemoryNodeId, Float>()
        scoredSeeds.forEach { (seed, score) ->
            if (seed.kind in targetKinds) {
                projected[seed.id] = max(projected[seed.id] ?: 0f, score)
            }
            projectToKinds(
                start = seed.id,
                targetKinds = targetKinds,
                nodesById = nodesById,
                adjacency = adjacency,
                activeIds = activeIds,
                maxDepth = 5,
            ).forEach { (nodeId, distance) ->
                val targetNode = nodesById[nodeId] ?: return@forEach
                val hierarchyBoost = scopeAffinity(query, targetNode, episodesById)
                val projectedScore = (score * (1f / (1f + distance * 0.35f)) + hierarchyBoost * 0.5f)
                    .coerceIn(0f, 1f)
                projected[nodeId] = max(projected[nodeId] ?: 0f, projectedScore)
            }
        }

        val hits = projected.entries
            .mapNotNull { (nodeId, score) -> nodesById[nodeId]?.let { it to score } }
            .sortedWith(
                compareByDescending<Pair<MemoryNode, Float>> { it.second }
                    .thenByDescending { it.first.salience }
                    .thenByDescending { it.first.confidence },
            )
            .take(query.maxResults)
            .map { (node, score) ->
                MemoryRecallHit(
                    node = node,
                    score = score.coerceIn(0f, 1f),
                    conflicts = if (query.includeConflicts) {
                        snapshot.conflictsFor(node.id, nodesById)
                    } else {
                        emptyList()
                    },
                )
            }

        return MemoryRecallBundle(query, hits)
    }

    override suspend fun expand(
        nodeId: MemoryNodeId,
        resolution: MemoryResolution,
        maxResults: Int,
        includeConflicts: Boolean,
    ): List<MemoryRecallHit> {
        require(maxResults > 0)
        val snapshot = store.read()
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        require(nodeId in nodesById) { "Memory node ${nodeId.value} was not found" }
        val activeIds = snapshot.activeNodes(null).mapTo(hashSetOf()) { it.id }
        val distances = projectToKinds(
            start = nodeId,
            targetKinds = resolution.nodeKinds(),
            nodesById = nodesById,
            adjacency = snapshot.adjacency(),
            activeIds = activeIds,
            maxDepth = 6,
        )
        return distances.entries
            .sortedBy { it.value }
            .take(maxResults)
            .mapNotNull { (id, distance) ->
                nodesById[id]?.let { node ->
                    MemoryRecallHit(
                        node = node,
                        score = (1f / (1f + distance * 0.35f)).coerceIn(0f, 1f),
                        conflicts = if (includeConflicts) snapshot.conflictsFor(id, nodesById) else emptyList(),
                    )
                }
            }
    }
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
    MemoryResolution.Tag -> setOf(MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag)
    MemoryResolution.Context -> setOf(MemoryNodeKind.Context)
}

private data class Neighbor(
    val id: MemoryNodeId,
    val relation: MemoryRelationKind,
)

private fun MemorySnapshot.adjacency(): Map<MemoryNodeId, List<Neighbor>> = buildMap {
    edges.forEach { edge ->
        put(edge.from, getOrElse(edge.from) { emptyList() } + Neighbor(edge.to, edge.relation))
        put(edge.to, getOrElse(edge.to) { emptyList() } + Neighbor(edge.from, edge.relation))
    }
}

private fun MemorySnapshot.activeNodes(projectId: String?): List<MemoryNode> {
    val superseded = edges
        .asSequence()
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
    val projectEpisodes = if (projectId == null) {
        null
    } else {
        episodes.filter { it.projectId == projectId }.mapTo(hashSetOf()) { it.id }
    }
    return nodes.filter { node ->
        node.id !in superseded &&
            (projectEpisodes == null || node.sourceEpisodeIds.isEmpty() || node.sourceEpisodeIds.any { it in projectEpisodes })
    }
}

private fun scopeAffinity(
    query: MemoryQuery,
    node: MemoryNode,
    episodesById: Map<MemoryEpisodeId, MemoryEpisode>,
): Float {
    if (
        query.projectId == null &&
        query.workflowRunId == null &&
        query.workflowDefinitionId == null &&
        query.taskRunId == null &&
        query.taskDefinitionId == null &&
        query.roleId == null
    ) {
        return 0f
    }

    return node.sourceEpisodeIds
        .asSequence()
        .mapNotNull(episodesById::get)
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

private fun projectToKinds(
    start: MemoryNodeId,
    targetKinds: Set<MemoryNodeKind>,
    nodesById: Map<MemoryNodeId, MemoryNode>,
    adjacency: Map<MemoryNodeId, List<Neighbor>>,
    activeIds: Set<MemoryNodeId>,
    maxDepth: Int,
): Map<MemoryNodeId, Int> {
    val result = linkedMapOf<MemoryNodeId, Int>()
    val visited = mutableSetOf(start)
    var frontier = listOf(start)
    var depth = 0
    while (frontier.isNotEmpty() && depth <= maxDepth) {
        val next = mutableListOf<MemoryNodeId>()
        frontier.forEach { id ->
            val node = nodesById[id]
            if (id in activeIds && node?.kind in targetKinds && id !in result) {
                result[id] = depth
            }
            adjacency[id].orEmpty().forEach { neighbor ->
                if (neighbor.id !in visited && neighbor.relation.isRecallTraversable()) {
                    visited += neighbor.id
                    next += neighbor.id
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

private fun MemorySnapshot.conflictsFor(
    nodeId: MemoryNodeId,
    nodesById: Map<MemoryNodeId, MemoryNode>,
): List<MemoryNode> = edges
    .asSequence()
    .filter { edge ->
        edge.relation == MemoryRelationKind.ConflictsWith && (edge.from == nodeId || edge.to == nodeId)
    }
    .mapNotNull { edge ->
        nodesById[if (edge.from == nodeId) edge.to else edge.from]
    }
    .distinctBy(MemoryNode::id)
    .toList()

private fun lexicalScore(query: String, node: MemoryNode): Float {
    val queryTerms = query.memoryTerms()
    if (queryTerms.isEmpty()) return 0f
    val nodeTerms = node.text.memoryTerms() + node.metadata.values.flatMap(String::memoryTerms)
    if (nodeTerms.isEmpty()) return 0f

    val overlap = queryTerms.count { it in nodeTerms }.toFloat() / queryTerms.size
    val exactBonus = if (node.text.lowercase().contains(query.trim().lowercase())) 0.25f else 0f
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
