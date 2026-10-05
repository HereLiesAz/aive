package com.hereliesaz.geministrator.memory

/** Deliberation metadata: the memory it judged correct. */
const val DELIBERATION_CHOSEN: String = "chosen"

/** Current-version metadata: the deliberation it absorbed. */
const val RESOLVES_DELIBERATION: String = "resolvesDeliberation"

/** Current-version metadata: the cited memories not chosen (now history), comma-joined. */
const val NOT_CHOSEN: String = "notChosen"

/**
 * Absorbs resolved contrasts: the only way contrasting memories are ever consolidated.
 *
 * Clerks never pick a side. When a model has deliberated over a divergence and named the memory it
 * judged correct ([MemoryDeliberationRequest.chosen]), this deterministic step writes a new current
 * version: the chosen memory's text, citing the deliberation (`CondensedFrom` to the chosen memory and
 * to the deliberation, which names the evidence). Everything the deliberation cited, and their current
 * versions, become history (`Supersedes`, basis `deliberation`): never deleted, faded from default
 * recall, reachable through [historyOf]. A deliberation with no choice ("unresolved") absorbs
 * nothing. A later deliberation over the same memories reopens it: its absorption supersedes the
 * earlier current version in turn.
 *
 * Borrowed, not ours: the shape follows reconsolidation (a retrieved trace becomes labile and is
 * re-stored with the update) and the trace-transformation view of systems consolidation.
 */
internal object MemoryDeliberationAbsorber {
    /** The absorption of the oldest deliberation not yet absorbed, or an empty mutation. */
    fun mutationFor(snapshot: MemorySnapshot, nowEpochMillis: Long): MemoryStoreMutation {
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val supersededBy = snapshot.edges.filter { it.relation == MemoryRelationKind.Supersedes }.groupBy({ it.to }, { it.from })
        val deliberation = snapshot.nodes
            .filter { it.kind == MemoryNodeKind.Deliberation && !it.metadata[DELIBERATION_CHOSEN].isNullOrBlank() }
            .sortedWith(compareBy<MemoryNode> { it.createdAtEpochMillis }.thenBy { it.id.value })
            .firstOrNull { versionId(it) !in nodesById }
            ?: return MemoryStoreMutation()
        val chosen = nodesById[MemoryNodeId(deliberation.metadata.getValue(DELIBERATION_CHOSEN))] ?: return MemoryStoreMutation()
        val cited = snapshot.edges
            .filter { it.relation == MemoryRelationKind.Deliberates && it.from == deliberation.id }
            .mapNotNull { nodesById[it.to] }
            .filter { it.kind != MemoryNodeKind.Deliberation }
        // Only a resolved contrast is absorbed: something other than the chosen memory must be cited.
        if (cited.none { it.id != chosen.id }) return MemoryStoreMutation()

        fun currentVersions(id: MemoryNodeId): Set<MemoryNodeId> {
            val result = linkedSetOf<MemoryNodeId>()
            val queue = ArrayDeque(listOf(id))
            val seen = hashSetOf(id)
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                val newer = supersededBy[next].orEmpty()
                if (newer.isEmpty()) result += next
                newer.filter(seen::add).forEach(queue::addLast)
            }
            return result
        }

        val versionId = versionId(deliberation)
        val notChosen = cited.filter { it.id != chosen.id }
        val historical = (cited.map { it.id } + chosen.id).flatMap { listOf(it) + currentVersions(it) }.toSet() - versionId
        // A memory never grows: the new version is fitted to its predecessors by weight; dropped detail
        // becomes a separate linked memory.
        val predecessors = historical
        val fitted = MemoryRewrite.fit(snapshot, chosen.text, predecessors)
        val version = MemoryNode(
            id = versionId,
            kind = chosen.kind,
            text = fitted.text,
            sourceEpisodeIds = chosen.sourceEpisodeIds + deliberation.sourceEpisodeIds,
            createdAtEpochMillis = nowEpochMillis,
            metadata = mapOf(
                RESOLVES_DELIBERATION to deliberation.id.value,
                DELIBERATION_CHOSEN to chosen.id.value,
                NOT_CHOSEN to notChosen.joinToString(",") { it.id.value },
            ) + MemoryRewrite.versionMetadata(snapshot, predecessors),
        )
        val edges = buildList {
            listOf(chosen.id, deliberation.id).forEach { source ->
                add(MemoryEdge(MemoryEdgeId("${versionId.value}:condensedfrom:${source.value}"), versionId, source, MemoryRelationKind.CondensedFrom, createdAtEpochMillis = nowEpochMillis))
            }
            historical.sortedBy { it.value }.forEach { old ->
                add(
                    MemoryEdge(
                        MemoryEdgeId("${versionId.value}:supersedes:${old.value}"), versionId, old, MemoryRelationKind.Supersedes,
                        createdAtEpochMillis = nowEpochMillis,
                        metadata = mapOf("basis" to "deliberation", "deliberation" to deliberation.id.value),
                    ),
                )
            }
        }
        val spill = MemoryRewrite.spillFor(version, fitted.dropped)
        return MemoryStoreMutation(
            nodesToAdd = listOfNotNull(version, spill?.first),
            edgesToAdd = edges + listOfNotNull(spill?.second),
        )
    }

    private fun versionId(deliberation: MemoryNode) = MemoryNodeId("resolved:${deliberation.id.value}")
}

/**
 * The explicit history of [nodeId]: every version it replaced and every memory it was condensed from
 * (following `Supersedes` and `CondensedFrom`, transitively), and the deliberations involved, oldest
 * first. History never surfaces in default recall; this is how to reach it.
 */
fun MemorySnapshot.historyOf(nodeId: MemoryNodeId): List<MemoryNode> {
    val nodesById = nodes.associateBy(MemoryNode::id)
    val older = edges.filter { it.relation == MemoryRelationKind.Supersedes || it.relation == MemoryRelationKind.CondensedFrom }
        .groupBy({ it.from }, { it.to })
    val seen = linkedSetOf<MemoryNodeId>()
    val queue = ArrayDeque(listOf(nodeId))
    while (queue.isNotEmpty()) {
        older[queue.removeFirst()].orEmpty().filter { it != nodeId && seen.add(it) }.forEach(queue::addLast)
    }
    return seen.mapNotNull(nodesById::get).sortedWith(compareBy<MemoryNode> { it.createdAtEpochMillis }.thenBy { it.id.value })
}
