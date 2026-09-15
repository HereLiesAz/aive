package com.hereliesaz.geministrator.memory

import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Semantic association is embedding inference, not generative reasoning. It may only connect
 * similar/related visible memory nodes; it cannot infer contradiction, truth, or resolution.
 *
 * Embeddings are cached by model/artifact/text so a node that reappears in bounded neighborhoods
 * does not repeatedly invoke Specialist 08. Lexical/programmatic association remains an earlier,
 * separate pass; this model handles semantic residue that deterministic evidence cannot explain.
 */
class EmbeddingAssociationLinkerMicroAgent(
    override val model: MemoryMicroAgentModelSpec,
    private val runtime: MemoryEmbeddingInferenceRuntime,
    private val minimumSimilarity: Float = 0.82f,
    private val maxLinksPerItem: Int = 6,
    private val maxCachedEmbeddings: Int = 4_096,
    private val nowEpochMillis: () -> Long = ::associationNowEpochMillis,
) : MemoryMicroAgent {
    override val role: MemoryMicroAgentRole = MemoryMicroAgentRole.AssociationLinker
    private val embeddingCache = linkedMapOf<EmbeddingCacheKey, List<Float>>()

    init {
        require(model.requirements.workload == MemoryInferenceWorkload.Embedding)
        require(minimumSimilarity in 0f..1f)
        require(maxLinksPerItem > 0)
        require(maxCachedEmbeddings > 0)
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
        val cacheKeys = visible.map { item -> EmbeddingCacheKey(model.modelId, artifact.artifactId, item.text) }
        val missing = cacheKeys.mapIndexedNotNull { index, key -> if (key !in embeddingCache) index else null }
        if (missing.isNotEmpty()) {
            val result = runtime.embed(
                MemoryEmbeddingInferenceRequest(
                    model = model,
                    artifact = artifact,
                    texts = missing.map { visible[it].text },
                ),
            )
            require(result.vectors.size == missing.size) {
                "${model.modelId} returned ${result.vectors.size} embeddings for ${missing.size} uncached inputs"
            }
            result.vectors.forEachIndexed { resultIndex, vector ->
                require(vector.isNotEmpty()) { "${model.modelId} returned an empty embedding" }
                putCached(cacheKeys[missing[resultIndex]], vector)
            }
        }

        val vectors = cacheKeys.map { key -> requireNotNull(embeddingCache[key]) }
        val dimension = vectors.firstOrNull()?.size ?: 0
        require(dimension > 0 && vectors.all { it.size == dimension }) {
            "${model.modelId} returned inconsistent embedding dimensions"
        }

        val indexById = visible.mapIndexed { index, item -> item.id to index }.toMap()
        val primaryIds = packet.items.mapTo(linkedSetOf(), MemoryWorkItem::id)
        val emittedPairs = linkedSetOf<String>()
        val edges = mutableListOf<MemoryEdge>()
        val cacheHitCount = visible.size - missing.size

        packet.items.forEach { source ->
            val sourceIndex = requireNotNull(indexById[source.id])
            val candidates = visible.asSequence()
                .filter { it.id != source.id }
                .map { target ->
                    val targetIndex = requireNotNull(indexById[target.id])
                    target to cosineSimilarity(vectors[sourceIndex], vectors[targetIndex])
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
                        "embeddingCacheHits" to cacheHitCount.toString(),
                        "embeddingCacheMisses" to missing.size.toString(),
                    ),
                )
            }
        }

        return MemoryMutationBatch(edgesToAdd = edges)
    }

    private fun putCached(key: EmbeddingCacheKey, vector: List<Float>) {
        embeddingCache.remove(key)
        embeddingCache[key] = vector.toList()
        while (embeddingCache.size > maxCachedEmbeddings) {
            val oldest = embeddingCache.keys.firstOrNull() ?: break
            embeddingCache.remove(oldest)
        }
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

    private data class EmbeddingCacheKey(
        val modelId: String,
        val artifactId: String,
        val text: String,
    )
}

@OptIn(ExperimentalTime::class)
private fun associationNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
