package com.hereliesaz.geministrator.memory

/** `Supersedes` edge metadata: when the superseded memory stopped being valid (epoch millis). */
const val MEMORY_VALID_UNTIL: String = "validUntil"

/**
 * Valid time, kept apart from ingest time ([MemoryNode.createdAtEpochMillis]).
 *
 * A memory is valid over `[validFrom, validUntil)`. [MemoryNode.validFromEpochMillis] is when the
 * fact became true: the time its source episodes carry ([MemoryEpisode.occurredAtEpochMillis]) when
 * they carry one, otherwise its record time. A memory has no stored end: nodes are add-only, so the end
 * of its valid time is read from the deterministic `Supersedes` edges into it, at the superseding
 * version's valid-from (recorded on the edge as [MEMORY_VALID_UNTIL]).
 *
 * Only coverage supersession (condensation, and legacy edges without a basis) ends validity. A
 * deliberation's choice ([MemoryDeliberationAbsorber], basis `deliberation`) judges which version is
 * right, not when a fact stopped holding, so it ends nothing; nothing here decides truth.
 */
internal object MemoryValidTime {
    private const val DELIBERATION_BASIS = "deliberation"

    /** True for a `Supersedes` edge that ends its target's valid time. */
    fun endsValidity(edge: MemoryEdge): Boolean =
        edge.relation == MemoryRelationKind.Supersedes && edge.metadata["basis"] != DELIBERATION_BASIS

    /** True for a `Supersedes` edge written by absorbing a deliberation. */
    fun replacesByDeliberation(edge: MemoryEdge): Boolean =
        edge.relation == MemoryRelationKind.Supersedes && edge.metadata["basis"] == DELIBERATION_BASIS

    /** The latest time [node]'s source episodes carry, or null when none carries one. */
    fun sourceTime(episodesById: Map<MemoryEpisodeId, MemoryEpisode>, node: MemoryNode): Long? =
        node.sourceEpisodeIds.mapNotNull { episodesById[it]?.occurredAtEpochMillis }.maxOrNull()

    /** Extraction: each node takes its sources' time as its valid-from when they carry one. */
    fun stampExtracted(snapshot: MemorySnapshot, nodes: List<MemoryNode>): List<MemoryNode> {
        if (nodes.isEmpty()) return nodes
        val episodesById = snapshot.episodes.associateBy(MemoryEpisode::id)
        return nodes.map { node ->
            val time = sourceTime(episodesById, node)
            if (time == null || time == node.validFromEpochMillis) node else node.copy(validFromEpochMillis = time)
        }
    }

    /**
     * A version restating [sources] holds once every one of them does: the latest of their
     * valid-from times, or null when none of them is in [snapshot].
     */
    fun versionValidFrom(snapshot: MemorySnapshot, sources: Collection<MemoryNodeId>): Long? {
        val ids = sources.toSet()
        return snapshot.nodes.filter { it.id in ids }.maxOfOrNull { it.validFromEpochMillis }
    }

    /** The end of each superseded memory's valid time (the earliest, if superseded more than once). */
    fun validUntilByNode(snapshot: MemorySnapshot): Map<MemoryNodeId, Long> {
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val result = HashMap<MemoryNodeId, Long>()
        snapshot.edges.forEach { edge ->
            if (!endsValidity(edge)) return@forEach
            val target = nodesById[edge.to] ?: return@forEach
            val end = edge.metadata[MEMORY_VALID_UNTIL]?.toLongOrNull()
                ?: nodesById[edge.from]?.validFromEpochMillis
                ?: edge.createdAtEpochMillis
            // Never before it began: an interval is at worst empty.
            val clamped = maxOf(end, target.validFromEpochMillis)
            result[edge.to] = result[edge.to]?.let { minOf(it, clamped) } ?: clamped
        }
        return result
    }

    fun isValidAt(node: MemoryNode, validUntil: Long?, atEpochMillis: Long): Boolean =
        node.validFromEpochMillis <= atEpochMillis && (validUntil == null || atEpochMillis < validUntil)

    /**
     * The memories valid at [atEpochMillis], superseded ones included while they still held. Memories
     * replaced by an absorbed deliberation stay out, as in default recall: their current version
     * (valid from the chosen memory's valid-from) stands for them and carries their history.
     */
    fun validAt(snapshot: MemorySnapshot, atEpochMillis: Long): List<MemoryNode> {
        val until = validUntilByNode(snapshot)
        val replaced = snapshot.edges.filter(::replacesByDeliberation).mapTo(hashSetOf()) { it.to }
        return snapshot.nodes.filter { it.id !in replaced && isValidAt(it, until[it.id], atEpochMillis) }
    }
}

/** When [nodeId] stopped being valid (it was superseded by coverage), or null while it still holds. */
fun MemorySnapshot.validUntilOf(nodeId: MemoryNodeId): Long? = MemoryValidTime.validUntilByNode(this)[nodeId]
