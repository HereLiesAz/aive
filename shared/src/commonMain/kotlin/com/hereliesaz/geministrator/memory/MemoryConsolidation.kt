package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.CancellationException

/**
 * The manager is intentionally narrow: one bounded packet in, declarative graph mutations out.
 * It never receives the complete memory tree and never writes persistence directly.
 */
interface MemoryManagerAgent {
    suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch
}

class MemoryConsolidationQueue(
    private val store: MemoryStore,
    private val maxChunkChars: Int = 6_000,
) {
    init {
        require(maxChunkChars > 0)
    }

    suspend fun enqueueSession(envelope: MemorySessionEnvelope): MemoryQueueEntry {
        require(envelope.sourceSessionId.isNotBlank())
        require(envelope.userPrompt.isNotBlank())

        while (true) {
            val snapshot = store.read()
            val episodeId = envelope.episodeId()
            snapshot.queue.firstOrNull { it.episodeId == episodeId }?.let { return it }

            val episode = envelope.toEpisode(episodeId, maxChunkChars)
            val sequence = (snapshot.queue.maxOfOrNull(MemoryQueueEntry::sequence) ?: 0L) + 1L
            val entry = MemoryQueueEntry(
                id = MemoryQueueId("queue-$sequence-${episodeId.value}"),
                sequence = sequence,
                episodeId = episodeId,
                createdAtEpochMillis = envelope.closedAtEpochMillis,
            )
            val committed = store.commit(
                expectedRevision = snapshot.revision,
                mutation = MemoryStoreMutation(
                    episodesToAdd = listOf(episode),
                    queueUpserts = listOf(entry),
                ),
            )
            if (committed) return entry
        }
    }
}

sealed interface MemoryConsolidationResult {
    data object Idle : MemoryConsolidationResult
    data class Applied(
        val queueId: MemoryQueueId,
        val stage: MemoryConsolidationStage,
        val itemCount: Int,
    ) : MemoryConsolidationResult
    data class Completed(val queueId: MemoryQueueId) : MemoryConsolidationResult
    data class Failed(
        val queueId: MemoryQueueId,
        val stage: MemoryConsolidationStage,
        val reason: String,
    ) : MemoryConsolidationResult
}

class MemoryConsolidator(
    private val store: MemoryStore,
    private val manager: MemoryManagerAgent,
    private val policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
) {
    /**
     * Processes at most one bounded packet. The earliest unfinished episode owns the consolidator
     * until it reaches Complete, which makes cross-session integration deterministic.
     */
    suspend fun processNext(nowEpochMillis: Long): MemoryConsolidationResult {
        while (true) {
            val snapshot = store.read()
            val entry = snapshot.queue
                .asSequence()
                .filter { it.status != MemoryQueueStatus.Complete }
                .minByOrNull(MemoryQueueEntry::sequence)
                ?: return MemoryConsolidationResult.Idle

            if (entry.stage == MemoryConsolidationStage.Complete) {
                val completed = entry.copy(status = MemoryQueueStatus.Complete, lastError = null)
                if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(completed)))) {
                    return MemoryConsolidationResult.Completed(entry.id)
                }
                continue
            }

            val packetPlan = buildPacket(snapshot, entry)
            if (packetPlan == null) {
                val advanced = if (entry.stage == MemoryConsolidationStage.Condensation) {
                    entry.copy(
                        stage = MemoryConsolidationStage.Complete,
                        cursor = 0,
                        status = MemoryQueueStatus.Complete,
                        lastError = null,
                    )
                } else {
                    entry.copy(
                        stage = entry.stage.next(),
                        cursor = 0,
                        status = MemoryQueueStatus.Pending,
                        lastError = null,
                    )
                }
                if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(advanced)))) {
                    if (advanced.status == MemoryQueueStatus.Complete) {
                        return MemoryConsolidationResult.Completed(entry.id)
                    }
                }
                continue
            }

            val processing = entry.copy(status = MemoryQueueStatus.Processing, lastError = null)
            if (!store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(processing)))) {
                continue
            }

            val batch = try {
                manager.process(packetPlan.packet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                markFailed(processing, failure.message ?: failure::class.simpleName.orEmpty())
                return MemoryConsolidationResult.Failed(
                    queueId = entry.id,
                    stage = entry.stage,
                    reason = failure.message ?: "Memory manager failed",
                )
            }

            try {
                validateBatch(entry.stage, packetPlan, batch)
            } catch (failure: Throwable) {
                markFailed(processing, failure.message ?: "Invalid memory mutation batch")
                return MemoryConsolidationResult.Failed(
                    queueId = entry.id,
                    stage = entry.stage,
                    reason = failure.message ?: "Invalid memory mutation batch",
                )
            }

            while (true) {
                val latest = store.read()
                val current = latest.queue.firstOrNull { it.id == entry.id }
                    ?: error("Memory queue entry ${entry.id.value} disappeared")
                if (current.stage != entry.stage) break

                val nextEntry = when {
                    entry.stage == MemoryConsolidationStage.Condensation -> current.copy(
                        cursor = 0,
                        status = MemoryQueueStatus.Pending,
                        attempt = 0,
                        lastError = null,
                    )
                    packetPlan.hasMore -> current.copy(
                        cursor = current.cursor + packetPlan.consumed,
                        status = MemoryQueueStatus.Pending,
                        attempt = 0,
                        lastError = null,
                    )
                    else -> current.copy(
                        stage = current.stage.next(),
                        cursor = 0,
                        status = MemoryQueueStatus.Pending,
                        attempt = 0,
                        lastError = null,
                    )
                }
                val committed = store.commit(
                    expectedRevision = latest.revision,
                    mutation = MemoryStoreMutation(
                        sectionsToAdd = batch.sectionsToAdd,
                        nodesToAdd = batch.nodesToAdd,
                        edgesToAdd = batch.edgesToAdd,
                        queueUpserts = listOf(nextEntry),
                    ),
                )
                if (committed) {
                    return MemoryConsolidationResult.Applied(
                        queueId = entry.id,
                        stage = entry.stage,
                        itemCount = packetPlan.packet.items.size,
                    )
                }
            }
        }
    }

    private suspend fun markFailed(entry: MemoryQueueEntry, reason: String) {
        while (true) {
            val snapshot = store.read()
            val current = snapshot.queue.firstOrNull { it.id == entry.id } ?: return
            if (current.stage != entry.stage) return
            val failed = current.copy(
                status = MemoryQueueStatus.Failed,
                attempt = current.attempt + 1,
                lastError = reason.take(1_000),
            )
            if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(failed)))) return
        }
    }

    private fun buildPacket(snapshot: MemorySnapshot, entry: MemoryQueueEntry): PacketPlan? {
        val episode = snapshot.episodes.firstOrNull { it.id == entry.episodeId }
            ?: error("Memory episode ${entry.episodeId.value} is missing")

        if (entry.stage == MemoryConsolidationStage.Condensation) {
            val cluster = snapshot.findCondensationCluster(policy) ?: return null
            val items = cluster.map(MemoryNode::asWorkItem)
            return PacketPlan(
                packet = MemoryWorkPacket(
                    queueId = entry.id,
                    episodeId = entry.episodeId,
                    stage = entry.stage,
                    items = items,
                    neighborhood = emptyList(),
                    instruction = instructionFor(entry.stage),
                ),
                consumed = items.size,
                hasMore = true,
                condensationKind = cluster.first().kind,
            )
        }

        val allItems = when (entry.stage) {
            MemoryConsolidationStage.Sectioning -> episode.chunks.map(MemorySourceChunk::asWorkItem)
            MemoryConsolidationStage.Salience -> snapshot.sections
                .filter { it.episodeId == entry.episodeId }
                .sortedBy(MemorySection::ordinal)
                .map(MemorySection::asWorkItem)
            MemoryConsolidationStage.Tags -> snapshot.episodeNodes(entry.episodeId, setOf(MemoryNodeKind.Context))
                .map(MemoryNode::asWorkItem)
            MemoryConsolidationStage.Phrases -> snapshot.episodeNodes(
                entry.episodeId,
                setOf(MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag),
            ).map(MemoryNode::asWorkItem)
            MemoryConsolidationStage.Summaries -> snapshot.episodeNodes(entry.episodeId, setOf(MemoryNodeKind.Phrase))
                .map(MemoryNode::asWorkItem)
            MemoryConsolidationStage.Categories -> snapshot.episodeNodes(entry.episodeId, setOf(MemoryNodeKind.Summary))
                .map(MemoryNode::asWorkItem)
            MemoryConsolidationStage.Associations -> snapshot.episodeNodes(
                entry.episodeId,
                setOf(
                    MemoryNodeKind.Context,
                    MemoryNodeKind.NounTag,
                    MemoryNodeKind.VerbTag,
                    MemoryNodeKind.Phrase,
                    MemoryNodeKind.Summary,
                    MemoryNodeKind.Category,
                ),
            ).map(MemoryNode::asWorkItem)
            MemoryConsolidationStage.Condensation,
            MemoryConsolidationStage.Complete,
            -> emptyList()
        }

        if (entry.cursor >= allItems.size) return null
        val selected = allItems.boundedSlice(entry.cursor, policy.maxPacketItems, policy.maxPacketChars)
        if (selected.isEmpty()) return null
        val remainingChars = (policy.maxPacketChars - selected.sumOf { it.text.length }).coerceAtLeast(0)
        val neighborhood = if (entry.stage == MemoryConsolidationStage.Associations && remainingChars > 0) {
            snapshot.relatedNeighborhood(
                episodeId = entry.episodeId,
                needles = selected,
                maxItems = policy.maxPacketItems,
                maxChars = remainingChars,
            )
        } else {
            emptyList()
        }

        return PacketPlan(
            packet = MemoryWorkPacket(
                queueId = entry.id,
                episodeId = entry.episodeId,
                stage = entry.stage,
                items = selected,
                neighborhood = neighborhood,
                instruction = instructionFor(entry.stage),
            ),
            consumed = selected.size,
            hasMore = entry.cursor + selected.size < allItems.size,
        )
    }

    private fun validateBatch(
        stage: MemoryConsolidationStage,
        plan: PacketPlan,
        batch: MemoryMutationBatch,
    ) {
        require(batch.size <= policy.maxMutationsPerPacket) {
            "Memory manager returned ${batch.size} mutations; limit is ${policy.maxMutationsPerPacket}"
        }
        require(plan.packet.items.size <= policy.maxPacketItems)
        require(
            plan.packet.items.sumOf { it.text.length } + plan.packet.neighborhood.sumOf { it.text.length } <=
                policy.maxPacketChars,
        ) { "Memory work packet exceeded its character budget" }

        when (stage) {
            MemoryConsolidationStage.Sectioning -> {
                require(batch.nodesToAdd.isEmpty() && batch.edgesToAdd.isEmpty())
                require(batch.sectionsToAdd.all { it.episodeId == plan.packet.episodeId })
            }
            MemoryConsolidationStage.Salience -> {
                require(batch.sectionsToAdd.isEmpty() && batch.edgesToAdd.isEmpty())
                require(batch.nodesToAdd.all { it.kind == MemoryNodeKind.Context })
            }
            MemoryConsolidationStage.Tags -> validateSemanticStage(
                batch,
                setOf(MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag),
                setOf(MemoryRelationKind.Indexes),
            )
            MemoryConsolidationStage.Phrases -> validateSemanticStage(
                batch,
                setOf(MemoryNodeKind.Phrase),
                setOf(MemoryRelationKind.Composes),
            )
            MemoryConsolidationStage.Summaries -> validateSemanticStage(
                batch,
                setOf(MemoryNodeKind.Summary),
                setOf(MemoryRelationKind.Summarizes),
            )
            MemoryConsolidationStage.Categories -> validateSemanticStage(
                batch,
                setOf(MemoryNodeKind.Category),
                setOf(MemoryRelationKind.Categorizes),
            )
            MemoryConsolidationStage.Associations -> {
                require(batch.sectionsToAdd.isEmpty() && batch.nodesToAdd.isEmpty())
                require(batch.edgesToAdd.all {
                    it.relation == MemoryRelationKind.SimilarTo ||
                        it.relation == MemoryRelationKind.AssociatedWith ||
                        it.relation == MemoryRelationKind.ConflictsWith ||
                        it.relation == MemoryRelationKind.ResolvesConflict
                })
            }
            MemoryConsolidationStage.Condensation -> {
                require(batch.sectionsToAdd.isEmpty())
                require(batch.nodesToAdd.isNotEmpty()) { "Condensation must create a generalized memory" }
                require(batch.nodesToAdd.all { it.kind == plan.condensationKind })
                require(batch.edgesToAdd.all {
                    it.relation == MemoryRelationKind.CondensedFrom ||
                        it.relation == MemoryRelationKind.Supersedes ||
                        it.relation == MemoryRelationKind.AssociatedWith
                })
            }
            MemoryConsolidationStage.Complete -> error("Complete memory jobs cannot be processed")
        }
    }

    private fun validateSemanticStage(
        batch: MemoryMutationBatch,
        allowedKinds: Set<MemoryNodeKind>,
        allowedRelations: Set<MemoryRelationKind>,
    ) {
        require(batch.sectionsToAdd.isEmpty())
        require(batch.nodesToAdd.all { it.kind in allowedKinds })
        require(batch.edgesToAdd.all { it.relation in allowedRelations })
    }

    private data class PacketPlan(
        val packet: MemoryWorkPacket,
        val consumed: Int,
        val hasMore: Boolean,
        val condensationKind: MemoryNodeKind? = null,
    )
}

private fun MemoryConsolidationStage.next(): MemoryConsolidationStage = when (this) {
    MemoryConsolidationStage.Sectioning -> MemoryConsolidationStage.Salience
    MemoryConsolidationStage.Salience -> MemoryConsolidationStage.Tags
    MemoryConsolidationStage.Tags -> MemoryConsolidationStage.Phrases
    MemoryConsolidationStage.Phrases -> MemoryConsolidationStage.Summaries
    MemoryConsolidationStage.Summaries -> MemoryConsolidationStage.Categories
    MemoryConsolidationStage.Categories -> MemoryConsolidationStage.Associations
    MemoryConsolidationStage.Associations -> MemoryConsolidationStage.Condensation
    MemoryConsolidationStage.Condensation -> MemoryConsolidationStage.Complete
    MemoryConsolidationStage.Complete -> MemoryConsolidationStage.Complete
}

private fun MemorySessionEnvelope.episodeId(): MemoryEpisodeId = MemoryEpisodeId(
    "episode-${closedAtEpochMillis}-${sourceSessionId.hashCode().toString(16)}",
)

private fun MemorySessionEnvelope.toEpisode(
    episodeId: MemoryEpisodeId,
    maxChunkChars: Int,
): MemoryEpisode {
    val source = buildList {
        add(MemorySessionPart(MemorySourceKind.UserPrompt, "User prompt", userPrompt))
        addAll(parts)
    }
    var ordinal = 0
    val chunks = buildList {
        source.forEachIndexed { partIndex, part ->
            part.text.trim().takeIf(String::isNotEmpty)?.chunkedText(maxChunkChars)?.forEachIndexed { pieceIndex, piece ->
                add(
                    MemorySourceChunk(
                        id = MemoryChunkId("${episodeId.value}:chunk:$partIndex:$pieceIndex"),
                        episodeId = episodeId,
                        ordinal = ordinal++,
                        kind = part.kind,
                        label = part.label,
                        text = piece,
                    ),
                )
            }
        }
    }
    return MemoryEpisode(
        id = episodeId,
        sourceSessionId = sourceSessionId,
        projectId = projectId,
        workflowRunId = workflowRunId,
        taskRunId = taskRunId,
        userPrompt = userPrompt,
        chunks = chunks,
        createdAtEpochMillis = closedAtEpochMillis,
    )
}

private fun String.chunkedText(maxChars: Int): List<String> {
    if (length <= maxChars) return listOf(this)
    val result = mutableListOf<String>()
    var start = 0
    while (start < length) {
        val hardEnd = minOf(start + maxChars, length)
        var end = hardEnd
        if (hardEnd < length) {
            val soft = lastIndexOfAny(charArrayOf('\n', '.', '!', '?', ' '), startIndex = hardEnd - 1)
            if (soft > start + maxChars / 2) end = soft + 1
        }
        result += substring(start, end).trim()
        start = end
    }
    return result.filter(String::isNotEmpty)
}

private fun List<MemoryWorkItem>.boundedSlice(
    start: Int,
    maxItems: Int,
    maxChars: Int,
): List<MemoryWorkItem> {
    val result = mutableListOf<MemoryWorkItem>()
    var chars = 0
    var index = start
    while (index < size && result.size < maxItems) {
        val item = this[index]
        if (result.isNotEmpty() && chars + item.text.length > maxChars) break
        val bounded = if (result.isEmpty() && item.text.length > maxChars) {
            item.copy(text = item.text.take(maxChars))
        } else {
            item
        }
        result += bounded
        chars += bounded.text.length
        index += 1
    }
    return result
}

private fun MemorySourceChunk.asWorkItem() = MemoryWorkItem(
    id = id.value,
    kind = "source:${kind.name}",
    text = text,
    metadata = mapOf("label" to label, "ordinal" to ordinal.toString()),
)

private fun MemorySection.asWorkItem() = MemoryWorkItem(
    id = id.value,
    kind = "section",
    text = text,
    metadata = metadata + ("ordinal" to ordinal.toString()),
)

private fun MemoryNode.asWorkItem() = MemoryWorkItem(
    id = id.value,
    kind = "node:${kind.name}",
    text = text,
    metadata = metadata + mapOf(
        "salience" to salience.toString(),
        "confidence" to confidence.toString(),
    ),
)

private fun MemorySnapshot.episodeNodes(
    episodeId: MemoryEpisodeId,
    kinds: Set<MemoryNodeKind>,
): List<MemoryNode> {
    val superseded = edges.filter { it.relation == MemoryRelationKind.Supersedes }.mapTo(hashSetOf()) { it.to }
    return nodes
        .filter { episodeId in it.sourceEpisodeIds && it.kind in kinds && it.id !in superseded }
        .sortedWith(compareBy<MemoryNode> { it.kind.ordinal }.thenBy { it.createdAtEpochMillis }.thenBy { it.id.value })
}

private fun MemorySnapshot.relatedNeighborhood(
    episodeId: MemoryEpisodeId,
    needles: List<MemoryWorkItem>,
    maxItems: Int,
    maxChars: Int,
): List<MemoryWorkItem> {
    val terms = needles.flatMap { it.text.memoryTerms() }.toSet()
    if (terms.isEmpty()) return emptyList()
    var chars = 0
    val candidates = nodes
        .asSequence()
        .filter { episodeId !in it.sourceEpisodeIds }
        .map { node ->
            val candidateTerms = node.text.memoryTerms().toSet()
            val overlap = terms.count { it in candidateTerms }
            node to overlap
        }
        .filter { (_, overlap) -> overlap > 0 }
        .sortedByDescending { (_, overlap) -> overlap }
        .map { (node, _) -> node.asWorkItem() }
        .toList()

    return buildList {
        for (item in candidates) {
            if (size >= maxItems) break
            if (isNotEmpty() && chars + item.text.length > maxChars) break
            val bounded = if (isEmpty() && item.text.length > maxChars) item.copy(text = item.text.take(maxChars)) else item
            add(bounded)
            chars += bounded.text.length
        }
    }
}

private fun MemorySnapshot.findCondensationCluster(policy: MemoryConsolidationPolicy): List<MemoryNode>? {
    val superseded = edges.filter { it.relation == MemoryRelationKind.Supersedes }.mapTo(hashSetOf()) { it.to }
    val activeById = nodes.filter { it.id !in superseded }.associateBy(MemoryNode::id)
    val similarEdges = edges.filter {
        it.relation == MemoryRelationKind.SimilarTo &&
            it.weight >= policy.minimumSimilarityWeight &&
            it.from in activeById &&
            it.to in activeById &&
            activeById[it.from]?.kind == activeById[it.to]?.kind
    }
    if (similarEdges.isEmpty()) return null

    val adjacency = mutableMapOf<MemoryNodeId, MutableSet<MemoryNodeId>>()
    similarEdges.forEach { edge ->
        adjacency.getOrPut(edge.from) { mutableSetOf() } += edge.to
        adjacency.getOrPut(edge.to) { mutableSetOf() } += edge.from
    }
    val visited = mutableSetOf<MemoryNodeId>()
    val qualifying = mutableListOf<List<MemoryNode>>()
    adjacency.keys.forEach { start ->
        if (!visited.add(start)) return@forEach
        val component = mutableListOf<MemoryNodeId>()
        val frontier = ArrayDeque<MemoryNodeId>()
        frontier.addLast(start)
        while (frontier.isNotEmpty()) {
            val id = frontier.removeFirst()
            component += id
            adjacency[id].orEmpty().forEach { next ->
                if (visited.add(next)) frontier.addLast(next)
            }
        }
        val componentNodes = component.mapNotNull(activeById::get)
        val kind = componentNodes.firstOrNull()?.kind ?: return@forEach
        val threshold = policy.maxSimilarPerKind[kind] ?: Int.MAX_VALUE
        if (componentNodes.size > threshold) qualifying += componentNodes
    }
    val chosen = qualifying.maxByOrNull(List<MemoryNode>::size) ?: return null
    val weights = similarEdges.flatMap { edge -> listOf(edge.from to edge.weight, edge.to to edge.weight) }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }
    return chosen
        .sortedByDescending { weights[it.id] ?: 0f }
        .take(policy.condensationBatchSize)
}

private fun instructionFor(stage: MemoryConsolidationStage): String = when (stage) {
    MemoryConsolidationStage.Sectioning ->
        "Split only these source chunks into granular, self-contained memory sections. Preserve source links."
    MemoryConsolidationStage.Salience ->
        "Keep only information worth remembering. Create Context nodes for retained sections; omit noise and transient chatter."
    MemoryConsolidationStage.Tags ->
        "Create as many useful noun and verb index tags as the retained context supports, then link each tag to its evidence."
    MemoryConsolidationStage.Phrases ->
        "Combine noun and verb indexes into short, precise phrases that express what happened, what changed, or what is intended."
    MemoryConsolidationStage.Summaries ->
        "Generalize related phrases into compact paragraphs that preserve their ideas and purposes without inventing facts."
    MemoryConsolidationStage.Categories ->
        "Assign reusable conceptual categories to the summaries. Prefer categories that will help later recall."
    MemoryConsolidationStage.Associations ->
        "Compare this bounded memory neighborhood. Link similarities, useful associations, and contradictions. Preserve conflicts rather than resolving them by deletion."
    MemoryConsolidationStage.Condensation ->
        "Restate these highly similar same-level memories as one memory that truthfully expresses all of them. Link the new memory to every source and supersede only the redundant representations."
    MemoryConsolidationStage.Complete -> "No work."
}

private fun String.memoryTerms(): List<String> {
    val result = mutableListOf<String>()
    val token = StringBuilder()
    lowercase().forEach { char ->
        if (char.isLetterOrDigit() || char == '_' || char == '-') {
            token.append(char)
        } else if (token.isNotEmpty()) {
            if (token.length > 1) result += token.toString()
            token.clear()
        }
    }
    if (token.length > 1) result += token.toString()
    return result.distinct()
}
