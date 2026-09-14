package com.hereliesaz.geministrator.memory

/**
 * Safety boundary for every memory curator, including legacy/cloud implementations.
 *
 * Memory maintenance may organize, summarize, associate, and condense supplied material, but it
 * does not perform conscious belief comparison. Contradiction-related concepts are allowed only
 * when they are explicitly present in the bounded source packet; a curator may never introduce
 * them merely because two memories appear inconsistent. Conscious reconciliation belongs to an
 * ordinary orchestrated agent and later returns as ordinary session context.
 */
class AssociativeMemoryManagerAgent(
    private val delegate: MemoryManagerAgent,
) : MemoryManagerAgent {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val safePacket = if (packet.stage == MemoryConsolidationStage.Associations) {
            packet.copy(
                instruction = "Associate memories only by shared topics, concepts, entities, actions, or other semantic proximity. " +
                    "Do not infer contradiction, inconsistency, disagreement, truth, falsity, conflict resolution, or which memory supersedes another.",
            )
        } else {
            packet
        }

        val batch = delegate.process(safePacket)
        require(batch.edgesToAdd.none { edge ->
            edge.relation == MemoryRelationKind.ConflictsWith ||
                edge.relation == MemoryRelationKind.ResolvesConflict
        }) {
            "Memory curators may not classify or resolve contradictory beliefs"
        }

        validateNoInferredContradictionLabels(safePacket, batch)
        return batch
    }
}

private fun validateNoInferredContradictionLabels(
    packet: MemoryWorkPacket,
    batch: MemoryMutationBatch,
) {
    val sourceText = buildString {
        packet.items.forEach { append(' ').append(it.text) }
        packet.neighborhood.forEach { append(' ').append(it.text) }
    }.lowercase()

    batch.nodesToAdd.forEach { node ->
        val generated = buildString {
            append(node.text)
            node.metadata.forEach { (key, value) ->
                append(' ').append(key).append(' ').append(value)
            }
        }.lowercase()

        val introduced = CONTRADICTION_TERMS.filter { term ->
            generated.contains(term) && !sourceText.contains(term)
        }
        require(introduced.isEmpty()) {
            "Memory curator introduced contradiction semantics not present in its source packet: ${introduced.joinToString()}"
        }
    }
}

private val CONTRADICTION_TERMS = setOf(
    "contradiction",
    "contradictory",
    "contradicts",
    "inconsistency",
    "inconsistent",
    "conflicting",
    "disagreement",
)
