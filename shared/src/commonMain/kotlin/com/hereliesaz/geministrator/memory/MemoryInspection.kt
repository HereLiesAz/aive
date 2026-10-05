package com.hereliesaz.geministrator.memory

/** One level of an episode's summary tree as the Memory screen lists it (depth-first, root first). */
data class MemoryOutlineRow(val node: MemoryNode, val level: Int, val leaf: Boolean)

/** A link of a memory and the pair summary written for it (null until consolidation writes one). */
data class MemoryPairRow(val edge: MemoryEdge, val partner: MemoryNode?, val summary: MemoryNode?)

/** A copy of [MemorySummaryMetrics] taken when the Memory screen was last published. */
data class MemorySummaryMetricsView(
    val modelCalls: Int = 0,
    val modelFailures: Int = 0,
    val embeddingCalls: Int = 0,
    val embeddingFailures: Int = 0,
    val summaries: Int = 0,
    val millis: Long = 0,
    val pairSummaries: Int = 0,
    val pairSummaryViolations: Int = 0,
    val treeNodes: Int = 0,
    val byEngine: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun of(metrics: MemorySummaryMetrics) = MemorySummaryMetricsView(
            modelCalls = metrics.modelCalls,
            modelFailures = metrics.modelFailures,
            embeddingCalls = metrics.embeddingCalls,
            embeddingFailures = metrics.embeddingFailures,
            summaries = metrics.summaries,
            millis = metrics.millis,
            pairSummaries = metrics.pairSummaries,
            pairSummaryViolations = metrics.pairSummaryViolations,
            treeNodes = metrics.treeNodes,
            byEngine = metrics.byEngine,
        )
    }
}

/** Read-only views of the stored graph for the Memory screen. */
object MemoryInspection {
    /** [episodeId]'s summary tree, depth-first from the root, each node with its level. */
    fun outline(snapshot: MemorySnapshot, episodeId: MemoryEpisodeId): List<MemoryOutlineRow> {
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val children = snapshot.edges.filter { it.relation == MemoryRelationKind.Outlines }
            .groupBy({ it.from }, { it.to })
        val rows = mutableListOf<MemoryOutlineRow>()
        fun walk(id: MemoryNodeId, level: Int) {
            val node = nodesById[id] ?: return
            if (rows.size >= MAX_OUTLINE_ROWS || rows.any { it.node.id == id }) return
            rows += MemoryOutlineRow(node, node.metadata[OUTLINE_LEVEL]?.toIntOrNull() ?: level, node.metadata[OUTLINE_LEAF] == "true")
            children[id].orEmpty()
                .mapNotNull(nodesById::get)
                .sortedBy { it.metadata[OUTLINE_PATH].orEmpty().pathKey() }
                .forEach { walk(it.id, level + 1) }
        }
        walk(MemorySummaryTree.rootId(episodeId), 0)
        return rows
    }

    /** The summary-tree levels above [node] (root first), as recall shows them under a hit. */
    fun outlineAbove(snapshot: MemorySnapshot, node: MemoryNode): List<MemoryNode> = snapshot.outlineAbove(node)

    /** Every link touching [nodeId] with its pair summary. */
    fun pairs(snapshot: MemorySnapshot, nodeId: MemoryNodeId): List<MemoryPairRow> {
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val summaries = snapshot.nodes.filter { it.kind == MemoryNodeKind.PairSummary }
            .associateBy { it.metadata[PAIR_OF] }
        return snapshot.edges
            .filter { (it.from == nodeId || it.to == nodeId) && it.relation != MemoryRelationKind.Outlines }
            .filter { it.id.value in summaries || MemoryPairSummaries.covers(it, nodesById) }
            .map { edge ->
                val partner = nodesById[if (edge.from == nodeId) edge.to else edge.from]
                MemoryPairRow(edge, partner, summaries[edge.id.value])
            }
    }

    /** Memories a person can inspect: not tree levels, pair summaries or register entries. */
    fun inspectable(snapshot: MemorySnapshot, limit: Int = 24): List<MemoryNode> = snapshot.nodes
        .filter { it.kind !in NOT_INSPECTABLE }
        .sortedByDescending { it.createdAtEpochMillis }
        .take(limit)

    private fun String.pathKey(): String = split('.').joinToString(".") { it.padStart(6, '0') }

    private val NOT_INSPECTABLE = setOf(MemoryNodeKind.Outline, MemoryNodeKind.PairSummary, MemoryNodeKind.Frame, MemoryNodeKind.Variant)
    private const val MAX_OUTLINE_ROWS = 400
}
