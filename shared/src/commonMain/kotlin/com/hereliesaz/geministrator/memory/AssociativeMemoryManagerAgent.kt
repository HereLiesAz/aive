package com.hereliesaz.geministrator.memory

/**
 * Safety boundary for every memory curator, including legacy/cloud implementations.
 * Memory maintenance may organize, summarize, associate, and condense material, but it does not
 * perform conscious belief comparison. Explicit contradiction/reconciliation is work for an
 * ordinary orchestrated agent and later returns as ordinary session context.
 */
class AssociativeMemoryManagerAgent(
    private val delegate: MemoryManagerAgent,
) : MemoryManagerAgent {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val batch = delegate.process(packet)
        require(batch.edgesToAdd.none { edge ->
            edge.relation == MemoryRelationKind.ConflictsWith ||
                edge.relation == MemoryRelationKind.ResolvesConflict
        }) {
            "Memory curators may not classify or resolve contradictory beliefs"
        }
        return batch
    }
}
