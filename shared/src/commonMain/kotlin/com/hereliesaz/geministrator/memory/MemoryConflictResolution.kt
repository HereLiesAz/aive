package com.hereliesaz.geministrator.memory

sealed interface MemoryConflictResolutionResult {
    data object Idle : MemoryConflictResolutionResult

    data class Resolved(
        val conflictEdgeId: MemoryEdgeId,
        val resolutionNodeId: MemoryNodeId,
    ) : MemoryConflictResolutionResult

    data class Deferred(
        val conflictEdgeId: MemoryEdgeId,
    ) : MemoryConflictResolutionResult
}

/**
 * Resolves at most one graph conflict per invocation. This deliberately lives outside the episodic
 * consolidation cursor: a contradiction may remain unresolved until later memories provide enough
 * evidence, at which point the same associative conflict can be revisited.
 */
class MemoryConflictResolutionProcessor(
    private val store: MemoryStore,
    private val router: MemoryMicroAgentRouter,
) {
    suspend fun resolveNext(nowEpochMillis: Long): MemoryConflictResolutionResult {
        @Suppress("UNUSED_VARIABLE")
        val invocationTime = nowEpochMillis

        while (true) {
            val snapshot = store.read()
            val conflict = snapshot.unresolvedConflicts().firstOrNull()
                ?: return MemoryConflictResolutionResult.Idle
            val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
            val left = nodesById[conflict.from] ?: return MemoryConflictResolutionResult.Deferred(conflict.id)
            val right = nodesById[conflict.to] ?: return MemoryConflictResolutionResult.Deferred(conflict.id)
            val sourceEpisode = (left.sourceEpisodeIds + right.sourceEpisodeIds)
                .mapNotNull { id -> snapshot.episodes.firstOrNull { it.id == id } }
                .maxByOrNull(MemoryEpisode::createdAtEpochMillis)
                ?.id
                ?: left.sourceEpisodeIds.firstOrNull()
                ?: right.sourceEpisodeIds.firstOrNull()
                ?: return MemoryConflictResolutionResult.Deferred(conflict.id)

            val packet = MemoryWorkPacket(
                queueId = MemoryQueueId("conflict-${conflict.id.value}"),
                episodeId = sourceEpisode,
                stage = MemoryConsolidationStage.Associations,
                packetKey = "resolve-${conflict.id.value}",
                items = listOf(left.asConflictWorkItem(), right.asConflictWorkItem()),
                instruction = "Resolve only this contradictory pair if the supplied memories justify a resolution. " +
                    "If they do not, return no mutations and leave the conflict unresolved. If they do, create one " +
                    "Summary resolution memory derived from both inputs and link that resolution to both inputs with " +
                    "ResolvesConflict. Preserve the contradictory source memories as audit evidence.",
            )

            val batch = router.resolveConflict(packet)
            if (batch.size == 0) return MemoryConflictResolutionResult.Deferred(conflict.id)
            validateResolution(packet, conflict, batch)

            val latest = store.read()
            if (latest.isConflictResolved(conflict)) continue
            if (
                store.commit(
                    expectedRevision = latest.revision,
                    mutation = MemoryStoreMutation(
                        nodesToAdd = batch.nodesToAdd,
                        edgesToAdd = batch.edgesToAdd,
                    ),
                )
            ) {
                return MemoryConflictResolutionResult.Resolved(
                    conflictEdgeId = conflict.id,
                    resolutionNodeId = batch.nodesToAdd.single().id,
                )
            }
        }
    }

    private fun validateResolution(
        packet: MemoryWorkPacket,
        conflict: MemoryEdge,
        batch: MemoryMutationBatch,
    ) {
        require(batch.sectionsToAdd.isEmpty())
        require(batch.nodesToAdd.size == 1) { "A conflict resolution must create exactly one resolution memory" }
        val resolution = batch.nodesToAdd.single()
        require(resolution.kind == MemoryNodeKind.Summary)
        require(packet.episodeId in resolution.sourceEpisodeIds)
        val endpoints = setOf(conflict.from, conflict.to)
        require(endpoints.all { endpoint ->
            batch.edgesToAdd.any { edge ->
                edge.from == resolution.id &&
                    edge.to == endpoint &&
                    edge.relation == MemoryRelationKind.ResolvesConflict
            }
        }) { "A conflict resolution must explicitly resolve both contradictory memories" }
        require(batch.edgesToAdd.all { edge ->
            edge.relation == MemoryRelationKind.ResolvesConflict ||
                edge.relation == MemoryRelationKind.AssociatedWith
        })
    }
}

private fun MemorySnapshot.unresolvedConflicts(): List<MemoryEdge> = edges
    .asSequence()
    .filter { it.relation == MemoryRelationKind.ConflictsWith }
    .filterNot(::isConflictResolved)
    .sortedWith(compareBy<MemoryEdge> { it.createdAtEpochMillis }.thenBy { it.id.value })
    .toList()

private fun MemorySnapshot.isConflictResolved(conflict: MemoryEdge): Boolean {
    val targetsByResolution = edges
        .asSequence()
        .filter { it.relation == MemoryRelationKind.ResolvesConflict }
        .groupBy(MemoryEdge::from, MemoryEdge::to)
    return targetsByResolution.values.any { targets ->
        conflict.from in targets && conflict.to in targets
    }
}

private fun MemoryNode.asConflictWorkItem(): MemoryWorkItem = MemoryWorkItem(
    id = id.value,
    kind = "node:${kind.name}",
    text = text,
    metadata = metadata + mapOf(
        "salience" to salience.toString(),
        "confidence" to confidence.toString(),
        "createdAtEpochMillis" to createdAtEpochMillis.toString(),
        "sourceEpisodeIds" to sourceEpisodeIds.joinToString(",") { it.value },
        "sourceSectionIds" to sourceSectionIds.joinToString(",") { it.value },
    ),
)
