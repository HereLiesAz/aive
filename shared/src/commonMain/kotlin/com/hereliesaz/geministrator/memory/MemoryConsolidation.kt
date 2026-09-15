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

    suspend fun enqueueSession(
        envelope: MemorySessionEnvelope,
        priority: MemoryQueuePriority = MemoryQueuePriority.Normal,
    ): MemoryQueueEntry {
        require(envelope.sourceSessionId.isNotBlank())
        require(envelope.userPrompt.isNotBlank())
        val episodeId = envelope.episodeId()
        return enqueueEpisode(envelope.toEpisode(episodeId, maxChunkChars), priority)
    }

    /**
     * Deliberate in-session banks jump ahead of ordinary lifecycle backlog. Once consolidated,
     * they are ordinary memory and retain no special reminder or retrieval semantics.
     *
     * Unlike lifecycle envelopes, every deliberate bank is an event. Its identity is allocated
     * from the CAS-protected queue sequence so two same-text, same-millisecond deposits cannot
     * collide or silently replace one another.
     */
    suspend fun enqueueBank(
        request: MemoryBankRequest,
        priority: MemoryQueuePriority = MemoryQueuePriority.Next,
    ): MemoryQueueEntry {
        while (true) {
            val snapshot = store.read()
            val sequence = snapshot.nextQueueSequence()
            val episodeId = request.episodeId(sequence)
            val episode = request.toEpisode(episodeId, maxChunkChars)
            val entry = MemoryQueueEntry(
                id = MemoryQueueId("queue-$sequence-${episode.id.value}"),
                sequence = sequence,
                episodeId = episode.id,
                priority = priority,
                createdAtEpochMillis = episode.createdAtEpochMillis,
            )
            if (
                store.commit(
                    expectedRevision = snapshot.revision,
                    mutation = MemoryStoreMutation(
                        episodesToAdd = listOf(episode),
                        queueUpserts = listOf(entry),
                    ),
                )
            ) {
                return entry
            }
        }
    }

    private suspend fun enqueueEpisode(
        episode: MemoryEpisode,
        priority: MemoryQueuePriority,
    ): MemoryQueueEntry {
        while (true) {
            val snapshot = store.read()
            val existing = snapshot.queue.firstOrNull { it.episodeId == episode.id }
            if (existing != null) {
                if (
                    existing.status != MemoryQueueStatus.Complete &&
                    priority.ordinal > existing.priority.ordinal
                ) {
                    val promoted = existing.copy(priority = priority)
                    if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(promoted)))) {
                        return promoted
                    }
                    continue
                }
                return existing
            }

            val sequence = snapshot.nextQueueSequence()
            val entry = MemoryQueueEntry(
                id = MemoryQueueId("queue-$sequence-${episode.id.value}"),
                sequence = sequence,
                episodeId = episode.id,
                priority = priority,
                createdAtEpochMillis = episode.createdAtEpochMillis,
            )
            if (
                store.commit(
                    expectedRevision = snapshot.revision,
                    mutation = MemoryStoreMutation(
                        episodesToAdd = listOf(episode),
                        queueUpserts = listOf(entry),
                    ),
                )
            ) {
                return entry
            }
        }
    }
}

private fun MemorySnapshot.nextQueueSequence(): Long =
    (queue.maxOfOrNull(MemoryQueueEntry::sequence) ?: 0L) + 1L

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
     * Processes at most one manager packet. Priority-next entries preempt ordinary backlog between
     * packets; entries at the same priority remain FIFO by sequence.
     */
    suspend fun processNext(nowEpochMillis: Long): MemoryConsolidationResult {
        @Suppress("UNUSED_VARIABLE")
        val invocationTime = nowEpochMillis

        while (true) {
            val snapshot = store.read()
            val entry = snapshot.queue
                .asSequence()
                .filter { it.status != MemoryQueueStatus.Complete }
                .sortedWith(
                    compareByDescending<MemoryQueueEntry> { it.priority.ordinal }
                        .thenBy { it.sequence },
                )
                .firstOrNull()
                ?: return MemoryConsolidationResult.Idle

            if (entry.stage == MemoryConsolidationStage.Complete) {
                val completed = entry.copy(status = MemoryQueueStatus.Complete, lastError = null)
                if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(completed)))) {
                    return MemoryConsolidationResult.Completed(entry.id)
                }
                continue
            }

            val plan = buildPacket(snapshot, entry)
            if (plan == null) {
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
                manager.process(plan.packet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val reason = failure.message ?: failure::class.simpleName ?: "Memory manager failed"
                markFailed(processing, reason)
                return MemoryConsolidationResult.Failed(entry.id, entry.stage, reason)
            }

            try {
                validateBatch(entry.stage, plan, batch)
            } catch (failure: Throwable) {
                val reason = failure.message ?: "Invalid memory mutation batch"
                markFailed(processing, reason)
                return MemoryConsolidationResult.Failed(entry.id, entry.stage, reason)
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
                    plan.hasMore -> current.copy(
                        cursor = current.cursor + plan.consumed,
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

                if (
                    store.commit(
                        expectedRevision = latest.revision,
                        mutation = MemoryStoreMutation(
                            sectionsToAdd = batch.sectionsToAdd,
                            nodesToAdd = batch.nodesToAdd,
                            edgesToAdd = batch.edgesToAdd,
                            queueUpserts = listOf(nextEntry),
                        ),
                    )
                ) {
                    return MemoryConsolidationResult.Applied(
                        queueId = entry.id,
                        stage = entry.stage,
                        itemCount = plan.packet.items.size,
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
            // Priority preemption must not let a newly banked episode condense/supersede nodes that
            // belong to another unfinished queue entry. Otherwise that paused entry's numeric cursor
            // can shift underneath it and skip unprocessed memories when it resumes.
            val protectedEpisodeIds = snapshot.queue
                .asSequence()
                .filter { it.status != MemoryQueueStatus.Complete && it.id != entry.id }
                .mapTo(linkedSetOf()) { it.episodeId }
            val cluster = snapshot.findCondensationCluster(policy, protectedEpisodeIds) ?: return null
            val items = cluster
                .map(MemoryNode::asWorkItem)
                .boundedSlice(0, policy.maxPacketItems, policy.maxPacketChars)
            if (items.size < 2) return null
            val packetKey = "condense-${items.map { it.id }.sorted().joinToString("|").hashCode().toString(16)}"
            return PacketPlan(
                packet = MemoryWorkPacket(
                    queueId = entry.id,
                    episodeId = entry.episodeId,
                    stage = entry.stage,
                    packetKey = packetKey,
                    items = items,
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
                MemoryNodeKind.entries.toSet(),
            ).map(MemoryNode::asWorkItem)
            MemoryConsolidationStage.Condensation,
            MemoryConsolidationStage.Complete,
            -> emptyList()
        }

        if (entry.cursor >= allItems.size) return null
        val selected = allItems.boundedSlice(entry.cursor, policy.maxPacketItems, policy.maxPacketChars)
        if (selected.isEmpty()) return null
        val remainingChars = (policy.maxPacketChars - selected.sumOf { it.text.length }).coerceAtLeast(0)
        val remainingItems = (policy.maxPacketItems - selected.size).coerceAtLeast(0)
        val neighborhood = if (
            entry.stage == MemoryConsolidationStage.Associations &&
            remainingChars > 0 &&
            remainingItems > 0
        ) {
            snapshot.relatedNeighborhood(
                episodeId = entry.episodeId,
                needles = selected,
                maxItems = remainingItems,
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
                packetKey = "${entry.stage.name.lowercase()}-${entry.cursor}",
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
        require(plan.packet.items.size + plan.packet.neighborhood.size <= policy.maxPacketItems) {
            "Memory work packet exceeded its item budget"
        }
        require(
            plan.packet.items.sumOf { it.text.length } + plan.packet.neighborhood.sumOf { it.text.length } <=
                policy.maxPacketChars,
        ) { "Memory work packet exceeded its character budget" }

        val visibleNodeIds = (plan.packet.items + plan.packet.neighborhood)
            .filter { it.kind.startsWith("node:") }
            .mapTo(hashSetOf()) { MemoryNodeId(it.id) }
        val proposedNodeIds = batch.nodesToAdd.mapTo(hashSetOf(), MemoryNode::id)
        val legalEdgeEndpoints = visibleNodeIds + proposedNodeIds
        require(batch.edgesToAdd.all { it.from in legalEdgeEndpoints && it.to in legalEdgeEndpoints }) {
            "Memory manager attempted to link a node outside its bounded packet"
        }

        if (stage != MemoryConsolidationStage.Condensation) {
            require(batch.nodesToAdd.all { plan.packet.episodeId in it.sourceEpisodeIds }) {
                "New memory nodes must preserve their source episode"
            }
        }

        when (stage) {
            MemoryConsolidationStage.Sectioning -> {
                require(batch.nodesToAdd.isEmpty() && batch.edgesToAdd.isEmpty())
                val visibleChunks = plan.packet.items.mapTo(hashSetOf()) { MemoryChunkId(it.id) }
                require(batch.sectionsToAdd.all { section ->
                    section.episodeId == plan.packet.episodeId &&
                        section.sourceChunkIds.isNotEmpty() &&
                        section.sourceChunkIds.all { it in visibleChunks }
                }) { "Memory sections must point only to chunks in their packet" }
            }
            MemoryConsolidationStage.Salience -> {
                require(batch.sectionsToAdd.isEmpty() && batch.edgesToAdd.isEmpty())
                val visibleSections = plan.packet.items.mapTo(hashSetOf()) { MemorySectionId(it.id) }
                require(batch.nodesToAdd.all {
                    it.kind == MemoryNodeKind.Context &&
                        it.sourceSectionIds.isNotEmpty() &&
                        it.sourceSectionIds.all { sectionId -> sectionId in visibleSections }
                }) { "Context memories must retain direct evidence-section provenance" }
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
                        it.relation == MemoryRelationKind.AssociatedWith
                }) { "Association clerks may create only neutral similarity/association edges" }
            }
            MemoryConsolidationStage.Condensation -> {
                require(batch.sectionsToAdd.isEmpty())
                require(batch.nodesToAdd.size == 1) {
                    "One condensation packet must create exactly one generalized memory"
                }
                val generalized = batch.nodesToAdd.single()
                require(generalized.kind == plan.condensationKind)
                val sourceIds = plan.packet.items.mapTo(linkedSetOf()) { MemoryNodeId(it.id) }
                require(sourceIds.isNotEmpty())
                require(sourceIds.all { sourceId ->
                    batch.edgesToAdd.any {
                        it.from == generalized.id &&
                            it.to == sourceId &&
                            it.relation == MemoryRelationKind.CondensedFrom
                    } &&
                        batch.edgesToAdd.any {
                            it.from == generalized.id &&
                                it.to == sourceId &&
                                it.relation == MemoryRelationKind.Supersedes
                        }
                }) { "Condensation must preserve and supersede every source memory explicitly" }
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

private fun MemoryBankRequest.episodeId(sequence: Long): MemoryEpisodeId = MemoryEpisodeId(
    "episode-bank-$bankedAtEpochMillis-$sequence",
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
        workflowDefinitionId = workflowDefinitionId,
        taskRunId = taskRunId,
        taskDefinitionId = taskDefinitionId,
        roleId = roleId,
        userPrompt = userPrompt,
        chunks = chunks,
        createdAtEpochMillis = closedAtEpochMillis,
    )
}

private fun MemoryBankRequest.toEpisode(
    episodeId: MemoryEpisodeId,
    maxChunkChars: Int,
): MemoryEpisode {
    val chunks = text.trim().chunkedText(maxChunkChars).mapIndexed { index, piece ->
        MemorySourceChunk(
            id = MemoryChunkId("${episodeId.value}:chunk:0:$index"),
            episodeId = episodeId,
            ordinal = index,
            kind = sourceKind,
            label = label,
            text = piece,
        )
    }
    return MemoryEpisode(
        id = episodeId,
        sourceSessionId = sourceSessionId,
        projectId = scope.projectId,
        workflowRunId = scope.workflowRunId,
        workflowDefinitionId = scope.workflowDefinitionId,
        taskRunId = scope.taskRunId,
        taskDefinitionId = scope.taskDefinitionId,
        roleId = scope.roleId,
        userPrompt = text,
        chunks = chunks,
        createdAtEpochMillis = bankedAtEpochMillis,
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
        "sourceEpisodeIds" to sourceEpisodeIds.joinToString(",") { it.value },
        "sourceSectionIds" to sourceSectionIds.joinToString(",") { it.value },
    ),
)

private fun MemorySnapshot.episodeNodes(
    episodeId: MemoryEpisodeId,
    kinds: Set<MemoryNodeKind>,
): List<MemoryNode> {
    val superseded = edges
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
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
    if (terms.isEmpty() || maxItems <= 0 || maxChars <= 0) return emptyList()
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

private fun MemorySnapshot.findCondensationCluster(
    policy: MemoryConsolidationPolicy,
    protectedEpisodeIds: Set<MemoryEpisodeId> = emptySet(),
): List<MemoryNode>? {
    val superseded = edges
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
    val activeById = nodes
        .filter { node ->
            node.id !in superseded &&
                node.sourceEpisodeIds.none { it in protectedEpisodeIds }
        }
        .associateBy(MemoryNode::id)
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

    val chosen = qualifying.maxByOrNull { it.size } ?: return null
    val weights = similarEdges
        .flatMap { edge -> listOf(edge.from to edge.weight, edge.to to edge.weight) }
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
        "Keep only information worth remembering under the retention rules. Create Context nodes for retained sections; omit only bookkeeping noise and transient chatter."
    MemoryConsolidationStage.Tags ->
        "Create as many useful semantic noun/entity and verb/action index tags as the retained context supports, then link each tag to its evidence. Code symbols and operations are first-class semantic candidates."
    MemoryConsolidationStage.Phrases ->
        "Combine noun/entity and verb/action indexes into short, precise phrases supported by the supplied context. Do not decide which interpretation is correct."
    MemoryConsolidationStage.Summaries ->
        "Generalize related phrases into compact paragraphs that preserve their ideas and purposes without inventing facts or adjudicating them."
    MemoryConsolidationStage.Categories ->
        "Assign reusable conceptual category/subject tags to the summaries. Categorize for recall only; do not judge truth or preference."
    MemoryConsolidationStage.Associations ->
        "Link only neutral semantic similarity or association supported by shared topics, concepts, entities, actions, or proximity. Do not infer contradiction, truth, falsity, or reconciliation."
    MemoryConsolidationStage.Condensation ->
        "Mechanically restate these highly similar same-level representations as exactly one compact representation. Preserve provenance to every supplied source; representational supersession is not a judgment that any source is wrong."
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
