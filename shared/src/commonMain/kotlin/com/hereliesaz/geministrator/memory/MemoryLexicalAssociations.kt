package com.hereliesaz.geministrator.memory

/**
 * Adds reproducible lexical/structural association evidence without invoking an inference model.
 *
 * This is intentionally separate from [MemoryProgrammaticAssociator]. The latter records facts
 * that can be proven from bookkeeping alone; this class records deterministic *heuristics* such as
 * a shared normalized action, code entity, or weak S/V/O signature. Metadata preserves that
 * distinction so downstream scoring and diagnostics never mistake reproducibility for certainty.
 */
class MemoryLexicalAssociator(
    private val store: MemoryStore,
    private val lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
    private val maxEdgesPerRefresh: Int = 256,
) {
    init {
        require(maxEdgesPerRefresh > 0)
    }

    suspend fun refresh(nowEpochMillis: Long): Int {
        while (true) {
            val snapshot = store.read()
            val edges = snapshot.lexicalAssociationCandidates(
                nowEpochMillis = nowEpochMillis,
                lexicon = lexicon,
                limit = maxEdgesPerRefresh,
            )
            if (edges.isEmpty()) return 0
            if (store.commit(snapshot.revision, MemoryStoreMutation(edgesToAdd = edges))) return edges.size
        }
    }
}

internal fun MemorySnapshot.lexicalAssociationCandidates(
    nowEpochMillis: Long,
    lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
    limit: Int,
): List<MemoryEdge> {
    if (limit <= 0 || nodes.size < 2) return emptyList()

    val superseded = edges
        .asSequence()
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
    val active = nodes.filter { it.id !in superseded }
    if (active.size < 2) return emptyList()

    val existingIds = edges.mapTo(hashSetOf(), MemoryEdge::id)
    val analyses = active.associate { node -> node.id to memoryLexicalFeatures(node.text, lexicon) }
    val candidates = linkedMapOf<MemoryEdgeId, MemoryEdge>()

    data class Signal(
        val basis: String,
        val feature: MemoryLexicalFeature,
        val weight: Float,
    )

    fun signals(node: MemoryNode): Sequence<Signal> = analyses.getValue(node.id).features.asSequence().mapNotNull { feature ->
        val weight = (feature.kind.semanticAssociationBaseWeight() * feature.confidence).coerceIn(0f, 1f)
        if (weight < MIN_LEXICAL_EDGE_WEIGHT) null else Signal(
            basis = "lexical:${feature.kind.name}",
            feature = feature,
            weight = weight,
        )
    }

    val groups = linkedMapOf<String, MutableList<Pair<MemoryNode, Signal>>>()
    active.forEach { node ->
        signals(node).forEach { signal ->
            val key = "${signal.feature.kind.name}:${signal.feature.value}"
            groups.getOrPut(key) { mutableListOf() } += node to signal
        }
    }

    groups.entries
        .asSequence()
        .filter { it.value.size > 1 }
        .sortedWith(
            compareByDescending<Map.Entry<String, MutableList<Pair<MemoryNode, Signal>>>> { entry ->
                entry.value.maxOf { it.second.weight }
            }.thenBy { it.key },
        )
        .forEach { (_, group) ->
            if (candidates.size >= limit) return@forEach
            val ordered = group
                .distinctBy { it.first.id }
                .sortedWith(compareBy<Pair<MemoryNode, Signal>> { it.first.createdAtEpochMillis }.thenBy { it.first.id.value })
            ordered.zipWithNext().forEach { (leftPair, rightPair) ->
                if (candidates.size >= limit) return@forEach
                val (left, leftSignal) = leftPair
                val (right, rightSignal) = rightPair
                if (left.id == right.id) return@forEach

                val firstIsLeft = left.id.value <= right.id.value
                val first = if (firstIsLeft) left else right
                val second = if (firstIsLeft) right else left
                val feature = leftSignal.feature
                val evidenceKey = "${feature.kind.name}:${feature.value}".memoryLexicalIdPart()
                val edgeId = MemoryEdgeId("lexical:$evidenceKey:${first.id.value}|${second.id.value}")
                if (edgeId in existingIds || edgeId in candidates) return@forEach

                candidates[edgeId] = MemoryEdge(
                    id = edgeId,
                    from = first.id,
                    to = second.id,
                    relation = MemoryRelationKind.AssociatedWith,
                    weight = minOf(leftSignal.weight, rightSignal.weight).coerceIn(0f, 1f),
                    createdAtEpochMillis = nowEpochMillis,
                    metadata = mapOf(
                        "deterministic" to "true",
                        "heuristic" to "true",
                        "evidenceClass" to "lexical-structural",
                        "basis" to leftSignal.basis,
                        "featureKind" to feature.kind.name,
                        "featureValue" to feature.value.take(256),
                    ),
                )
            }
        }

    return candidates.values.take(limit)
}

private fun String.memoryLexicalIdPart(): String = buildString {
    this@memoryLexicalIdPart.forEach { char ->
        when {
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            char == '-' || char == '_' || char == ':' -> append(char)
            else -> append('-')
        }
    }
}.trim('-').take(96).ifBlank { "feature" }

private const val MIN_LEXICAL_EDGE_WEIGHT = 0.50f
