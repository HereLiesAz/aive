package com.hereliesaz.geministrator.memory

import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Semantic association is embedding inference, not generative reasoning. It may only connect
 * similar/related visible memory nodes; it cannot infer contradiction, truth, or resolution.
 */
class EmbeddingAssociationLinkerMicroAgent(
    override val model: MemoryMicroAgentModelSpec,
    private val runtime: MemoryEmbeddingInferenceRuntime,
    private val minimumSimilarity: Float = 0.82f,
    private val maxLinksPerItem: Int = 6,
    private val nowEpochMillis: () -> Long = ::associationNowEpochMillis,
) : MemoryMicroAgent {
    override val role: MemoryMicroAgentRole = MemoryMicroAgentRole.AssociationLinker

    init {
        require(model.requirements.workload == MemoryInferenceWorkload.Embedding)
        require(minimumSimilarity in 0f..1f)
        require(maxLinksPerItem > 0)
        require(model.deployment.artifactsFor(runtime.platform).isNotEmpty()) {
            "Model ${model.modelId} has no ${runtime.platform} deployment artifact"
        }
    }

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        require(packet.stage == MemoryConsolidationStage.Associations)
        val visible = (packet.items + packet.neighborhood).distinctBy(MemoryWorkItem::id)
        if (visible.size < 2) return MemoryMutationBatch()
        require(visible.all { it.kind.startsWith("node:") }) {
            "AssociationLinker only accepts visible memory nodes"
        }

        val artifact = selectArtifact()
        val result = runtime.embed(
            MemoryEmbeddingInferenceRequest(
                model = model,
                artifact = artifact,
                texts = visible.map(MemoryWorkItem::text),
            ),
        )
        require(result.vectors.size == visible.size) {
            "${model.modelId} returned ${result.vectors.size} embeddings for ${visible.size} inputs"
        }
        val dimension = result.vectors.firstOrNull()?.size ?: 0
        require(dimension > 0 && result.vectors.all { it.size == dimension }) {
            "${model.modelId} returned inconsistent embedding dimensions"
        }

        val indexById = visible.mapIndexed { index, item -> item.id to index }.toMap()
        val primaryIds = packet.items.mapTo(linkedSetOf(), MemoryWorkItem::id)
        val emittedPairs = linkedSetOf<String>()
        val edges = mutableListOf<MemoryEdge>()

        packet.items.forEach { source ->
            val sourceIndex = requireNotNull(indexById[source.id])
            val candidates = visible.asSequence()
                .filter { it.id != source.id }
                .map { target ->
                    val targetIndex = requireNotNull(indexById[target.id])
                    target to cosineSimilarity(result.vectors[sourceIndex], result.vectors[targetIndex])
                }
                .filter { (_, similarity) -> similarity >= minimumSimilarity }
                .sortedByDescending { (_, similarity) -> similarity }
                .take(maxLinksPerItem)
                .toList()

            candidates.forEach { (target, similarity) ->
                val pairKey = if (source.id < target.id) {
                    "${source.id}\u0000${target.id}"
                } else {
                    "${target.id}\u0000${source.id}"
                }
                if (!emittedPairs.add(pairKey)) return@forEach

                val sourceFirst = source.id <= target.id || target.id !in primaryIds
                val fromId = if (sourceFirst) source.id else target.id
                val toId = if (sourceFirst) target.id else source.id
                edges += MemoryEdge(
                    id = MemoryEdgeId(
                        "${packet.queueId.value}:${packet.packetKey}:association:${pairKey.hashCode().toUInt().toString(16)}",
                    ),
                    from = MemoryNodeId(fromId),
                    to = MemoryNodeId(toId),
                    relation = MemoryRelationKind.SimilarTo,
                    weight = similarity.coerceIn(0f, 1f),
                    createdAtEpochMillis = nowEpochMillis(),
                    metadata = mapOf(
                        "microAgentRole" to role.name,
                        "embeddingModel" to model.modelId,
                    ),
                )
            }
        }

        return MemoryMutationBatch(edgesToAdd = edges)
    }

    private suspend fun selectArtifact(): MemoryMicroAgentArtifact {
        for (candidate in model.deployment.artifactsFor(runtime.platform)) {
            if (runtime.isAvailable(model, candidate)) return candidate
        }
        error("No available ${runtime.platform} embedding artifact for ${model.modelId}")
    }

    private fun cosineSimilarity(left: List<Float>, right: List<Float>): Float {
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val l = left[index].toDouble()
            val r = right[index].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat().coerceIn(-1f, 1f)
    }
}

@OptIn(ExperimentalTime::class)
private fun associationNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
