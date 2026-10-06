package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.CancellationException

/**
 * The manager is intentionally narrow: one bounded packet in, declarative graph mutations out.
 * It never receives the complete memory tree and never writes persistence directly.
 */
interface MemoryManagerAgent {
    suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch

    /**
     * Whether [packet]'s primary items fit the input budget of every clerk that will receive it,
     * measured the way the manager measures it (ids, kinds, metadata, routed instruction). The
     * consolidator sizes each packet with this before sending it, so a packet is never built that
     * its stage must reject. The neighborhood is excluded: the manager trims it to the remainder.
     */
    fun fitsInput(packet: MemoryWorkPacket): Boolean = true
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

    /** No queue work was due, but [count] pending pair summaries were written ([MemoryPairSummaries]). */
    data class PairSummaries(val count: Int) : MemoryConsolidationResult

    /** A condensation cluster was declined (or kept failing) and will not be offered again. */
    data class Declined(
        val queueId: MemoryQueueId,
        val reason: String,
    ) : MemoryConsolidationResult
}

class MemoryConsolidator(
    private val store: MemoryStore,
    private val manager: MemoryManagerAgent,
    private val policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
    /** The on-device summarizer chain for the episode summary tree ([MemorySummaryTree]). */
    private val summarizer: MemorySummarizerChain = MemorySummarizerChain(),
) {
    /** Node text is immutable, so each node is tokenized once for neighborhood search. */
    private val neighborhoodTerms = hashMapOf<MemoryNodeId, Set<String>>()

    /** Episodes whose contrast step has been committed by this instance (the step is idempotent anyway). */
    private val registeredEpisodes = hashSetOf<MemoryEpisodeId>()

    /** Episodes whose summary tree has been committed (or found) by this instance. */
    private val outlinedEpisodes = hashSetOf<MemoryEpisodeId>()

    /**
     * Processes at most one manager packet. Priority-next entries preempt ordinary backlog between
     * packets; entries at the same priority remain FIFO by sequence. An entry that has failed
     * [MemoryConsolidationPolicy.maxAttempts] times is parked and skipped, so one permanent failure
     * cannot hold the head of the queue forever.
     */
    suspend fun processNext(nowEpochMillis: Long): MemoryConsolidationResult {
        while (true) {
            val snapshot = store.read()
            val entry = snapshot.queue
                .asSequence()
                .filter { it.status != MemoryQueueStatus.Complete && !it.isParked() }
                .sortedWith(
                    compareByDescending<MemoryQueueEntry> { it.priority.ordinal }
                        .thenBy { it.sequence },
                )
                .firstOrNull()
                ?: return MemoryConsolidationResult.Idle

            // Deterministic contrast step, once the episode's memories are associated and before any
            // condensation: divergence markers and the variant register, written by code for every engine.
            if (entry.stage == MemoryConsolidationStage.Condensation && entry.episodeId !in registeredEpisodes) {
                val register = MemoryVariantRegister.mutationFor(snapshot, entry.episodeId, nowEpochMillis)
                if (register.nodesToAdd.isEmpty() && register.edgesToAdd.isEmpty()) {
                    registeredEpisodes += entry.episodeId
                } else if (store.commit(snapshot.revision, register)) {
                    registeredEpisodes += entry.episodeId
                }
                continue
            }

            // The episode's top-down summary tree, once, after the contrast step and before any
            // condensation rewrites its memories. Deterministic apart from an optional model clerk.
            if (entry.stage == MemoryConsolidationStage.Condensation && entry.episodeId !in outlinedEpisodes) {
                val tree = MemorySummaryTree.mutationFor(snapshot, entry.episodeId, nowEpochMillis, summarizer)
                if (tree.nodesToAdd.isEmpty() || store.commit(snapshot.revision, tree)) outlinedEpisodes += entry.episodeId
                continue
            }

            if (entry.stage == MemoryConsolidationStage.Complete) {
                val completed = entry.copy(status = MemoryQueueStatus.Complete, lastError = null)
                if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(completed)))) {
                    return MemoryConsolidationResult.Completed(entry.id)
                }
                continue
            }

            val plan = try {
                buildPacket(snapshot, entry)
            } catch (failure: IllegalArgumentException) {
                val reason = failure.message ?: "Oversized memory work item prevented packet construction"
                markFailed(entry, reason)
                return MemoryConsolidationResult.Failed(entry.id, entry.stage, reason)
            }
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
                        part = 0,
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

            val validated = try {
                manager.process(plan.packet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val reason = failure.message ?: failure::class.simpleName ?: "Memory manager failed"
                return failed(processing, plan, reason)
            }

            if (entry.stage == MemoryConsolidationStage.Condensation && validated.size == 0) {
                val reason = "Condensation declined"
                decline(processing, requireNotNull(plan.clusterKey), reason)
                return MemoryConsolidationResult.Declined(entry.id, reason)
            }

            try {
                validateBatch(entry.stage, plan, validated)
            } catch (failure: Throwable) {
                val reason = failure.message ?: "Invalid memory mutation batch"
                return failed(processing, plan, reason)
            }

            // Tag keywords are the engine's, not the clerk's: every new noun/verb tag gets its WordNet
            // trigger keywords once, here, whichever clerk engine named it.
            val batch = if (entry.stage == MemoryConsolidationStage.Tags) {
                val wordNet = runCatching { MemoryLanguageResources.get().wordNet }.getOrNull()
                validated.copy(nodesToAdd = MemoryTagKeywords.withKeywords(validated.nodesToAdd, wordNet))
            } else {
                validated
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
                        part = plan.nextPart,
                        status = MemoryQueueStatus.Pending,
                        attempt = 0,
                        lastError = null,
                    )
                    else -> current.copy(
                        stage = current.stage.next(),
                        cursor = 0,
                        part = 0,
                        status = MemoryQueueStatus.Pending,
                        attempt = 0,
                        lastError = null,
                    )
                }

                // A memory never grows: the condensed version is fitted to its sources by weight, whatever
                // clerk wrote it, and the detail it drops becomes a separate linked memory.
                val fitted = if (entry.stage == MemoryConsolidationStage.Condensation) fitCondensation(latest, plan, batch) else batch
                if (
                    store.commit(
                        expectedRevision = latest.revision,
                        mutation = MemoryStoreMutation(
                            sectionsToAdd = fitted.sectionsToAdd,
                            nodesToAdd = fitted.nodesToAdd,
                            edgesToAdd = fitted.edgesToAdd + condensationEngineEdges(latest, entry.stage, plan, batch),
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

    private fun MemoryQueueEntry.isParked(): Boolean =
        status == MemoryQueueStatus.Failed && attempt >= policy.maxAttempts

    /**
     * Records a failed attempt. A condensation cluster that has used up its attempts is declined
     * rather than parking the entry: the entry's own stages are done, only that cluster is stuck.
     */
    private suspend fun failed(
        entry: MemoryQueueEntry,
        plan: PacketPlan,
        reason: String,
    ): MemoryConsolidationResult {
        val clusterKey = plan.clusterKey
        if (clusterKey != null && entry.attempt + 1 >= policy.maxAttempts) {
            decline(entry, clusterKey, reason)
            return MemoryConsolidationResult.Declined(entry.id, reason)
        }
        markFailed(entry, reason)
        return MemoryConsolidationResult.Failed(entry.id, entry.stage, reason)
    }

    private suspend fun decline(entry: MemoryQueueEntry, clusterKey: String, reason: String) {
        while (true) {
            val snapshot = store.read()
            val current = snapshot.queue.firstOrNull { it.id == entry.id } ?: return
            if (current.stage != entry.stage) return
            val next = current.copy(status = MemoryQueueStatus.Pending, attempt = 0, lastError = reason.take(1_000))
            if (
                store.commit(
                    snapshot.revision,
                    MemoryStoreMutation(queueUpserts = listOf(next), condensationDeclinesToAdd = listOf(clusterKey)),
                )
            ) {
                return
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
            val declined = snapshot.declinedCondensations.toHashSet()
            val (cluster, items) = snapshot.condensationClusters(
                policy = policy,
                protectedEpisodeIds = protectedEpisodeIds,
                projectId = episode.projectId,
            )
                .map { cluster ->
                    cluster to fitPrefix(
                        entry,
                        cluster.map(MemoryNode::asWorkItem).boundedSlice(0, policy.maxPacketItems, policy.maxPacketChars),
                    )
                }
                // A cluster whose members assert different values or contrast (same frame, different
                // filler) is never offered: merging it would pick a claim, whichever engine runs the
                // clerk. Its members stay side by side.
                .firstOrNull { (_, items) ->
                    items.size >= 2 && items.clusterKey() !in declined &&
                        !condensationWouldAdjudicate(items.map(MemoryWorkItem::text))
                }
                ?: return null
            val clusterKey = items.clusterKey()
            val packetKey = "condense-${clusterKey.hashCode().toString(16)}"
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
                clusterKey = clusterKey,
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
                MemoryNodeKind.CLERK_MEMORY_KINDS,
            ).map(MemoryNode::asWorkItem)
            MemoryConsolidationStage.Condensation,
            MemoryConsolidationStage.Complete,
            -> emptyList()
        }

        if (entry.cursor >= allItems.size) return null
        val candidate = allItems.boundedSlice(entry.cursor, policy.maxPacketItems, policy.maxPacketChars)
        if (candidate.isEmpty()) return null
        // Sized to what the stage's clerks will actually accept, not only by item count and text
        // length: the longest fitting prefix goes now, the rest starts the next packet.
        var selected = fitPrefix(entry, candidate)
        var packetKey = "${entry.stage.name.lowercase()}-${entry.cursor}"
        var partCount = 1
        if (selected.isEmpty()) {
            // One item alone is over budget: send it in paragraph parts, one part per packet.
            val item = allItems[entry.cursor]
            val parts = splitToFit(entry, item)
            partCount = parts.size
            require(entry.part < partCount) {
                "Memory work item ${item.id} has ${parts.size} parts; queue cursor points at part ${entry.part}"
            }
            selected = listOf(parts[entry.part])
            packetKey += "-part${entry.part}"
        }
        val lastPart = entry.part >= partCount - 1
        val remainingChars = (policy.maxPacketChars - selected.sumOf { it.text.length }).coerceAtLeast(0)
        val remainingItems = (policy.maxPacketItems - selected.size).coerceAtLeast(0)
        val neighborhood = if (
            entry.stage == MemoryConsolidationStage.Associations &&
            remainingChars > 0 &&
            remainingItems > 0
        ) {
            if (neighborhoodTerms.size > MAX_CACHED_TERMS) neighborhoodTerms.clear()
            snapshot.relatedNeighborhood(
                episodeId = entry.episodeId,
                needles = selected,
                maxItems = remainingItems,
                maxChars = remainingChars,
                termsOf = { node -> neighborhoodTerms.getOrPut(node.id) { node.text.memoryTerms().toSet() } },
            )
        } else {
            emptyList()
        }

        return PacketPlan(
            packet = MemoryWorkPacket(
                queueId = entry.id,
                episodeId = entry.episodeId,
                stage = entry.stage,
                packetKey = packetKey,
                items = selected,
                neighborhood = neighborhood,
                instruction = instructionFor(entry.stage),
            ),
            consumed = if (partCount > 1) (if (lastPart) 1 else 0) else selected.size,
            hasMore = !lastPart || entry.cursor + selected.size < allItems.size,
            nextPart = if (lastPart) 0 else entry.part + 1,
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
                // The members agree (clashing clusters are never offered), so the generalized memory
                // must assert exactly their values: none dropped, none invented, negation kept.
                require(memoryClaimSignature(generalized.text) == memoryClaimSignature(plan.packet.items.first().text)) {
                    "Condensation must restate its sources' values unchanged"
                }
                val expectedSourceEpisodes = plan.packet.items
                    .flatMap { item ->
                        item.metadata["sourceEpisodeIds"].orEmpty()
                            .split(',')
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                    }
                    .mapTo(linkedSetOf(), ::MemoryEpisodeId)
                require(generalized.sourceEpisodeIds.containsAll(expectedSourceEpisodes)) {
                    "Condensation must preserve source-episode provenance from the complete cluster"
                }
                val sourceIds = plan.packet.items.mapTo(linkedSetOf()) { MemoryNodeId(it.id) }
                require(sourceIds.isNotEmpty())
                require(sourceIds.all { sourceId ->
                    batch.edgesToAdd.any {
                        it.from == generalized.id &&
                            it.to == sourceId &&
                            it.relation == MemoryRelationKind.CondensedFrom
                    }
                }) { "Condensation must link every source memory through CondensedFrom" }
                // Supersedes is the engine's to write (coverageSupersessions), never a clerk's.
                require(batch.edgesToAdd.all {
                    it.relation == MemoryRelationKind.CondensedFrom ||
                        it.relation == MemoryRelationKind.AssociatedWith
                }) { "A condensation clerk may emit only CondensedFrom and AssociatedWith links" }
            }
            MemoryConsolidationStage.Complete -> error("Complete memory jobs cannot be processed")
        }
    }

    private fun fitCondensation(snapshot: MemorySnapshot, plan: PacketPlan, batch: MemoryMutationBatch): MemoryMutationBatch {
        val generalized = batch.nodesToAdd.singleOrNull() ?: return batch
        val sources = plan.packet.items.map { MemoryNodeId(it.id) }
        val fitted = MemoryRewrite.fit(snapshot, generalized.text, sources)
        val version = generalized.copy(text = fitted.text, metadata = generalized.metadata + MemoryRewrite.versionMetadata(snapshot, sources))
        val spill = MemoryRewrite.spillFor(version, fitted.dropped)
        return batch.copy(
            nodesToAdd = listOfNotNull(version, spill?.first),
            edgesToAdd = batch.edgesToAdd + listOfNotNull(spill?.second),
        )
    }

    /**
     * Edges only the engine writes for a condensation:
     *
     * - `Supersedes` from the generalized memory to every source: consolidation rewrites. The members
     *   are the same memory (similar, never contrasting: a contrasting cluster is never offered), so the
     *   generalized memory is the new current version and the sources become history, kept and
     *   reachable through `CondensedFrom`, faded from default recall.
     * - The sources' divergence markers and variant attestations, carried over to the generalized
     *   memory (its sources agree, so it states what they state), so recalling it still brings the
     *   contrasting partners.
     */
    private fun condensationEngineEdges(
        snapshot: MemorySnapshot,
        stage: MemoryConsolidationStage,
        plan: PacketPlan,
        batch: MemoryMutationBatch,
    ): List<MemoryEdge> {
        if (stage != MemoryConsolidationStage.Condensation) return emptyList()
        val generalized = batch.nodesToAdd.singleOrNull() ?: return emptyList()
        val sourceIds = plan.packet.items.mapTo(hashSetOf()) { MemoryNodeId(it.id) }
        val existing = snapshot.edges.mapTo(hashSetOf()) { it.id }
        val inherited = linkedMapOf<MemoryEdgeId, MemoryEdge>()
        snapshot.edges.forEach { edge ->
            when (edge.relation) {
                MemoryRelationKind.Diverges -> {
                    val partner = when {
                        edge.from in sourceIds && edge.to !in sourceIds -> edge.to
                        edge.to in sourceIds && edge.from !in sourceIds -> edge.from
                        else -> return@forEach
                    }
                    val (from, to) = if (generalized.id.value <= partner.value) generalized.id to partner else partner to generalized.id
                    val id = MemoryEdgeId("diverges:${from.value}|${to.value}")
                    if (id !in existing) {
                        inherited[id] = edge.copy(id = id, from = from, to = to, createdAtEpochMillis = generalized.createdAtEpochMillis, metadata = edge.metadata + ("inheritedFrom" to edge.id.value))
                    }
                }
                MemoryRelationKind.Attests -> if (edge.from in sourceIds) {
                    val id = MemoryEdgeId("attests:${generalized.id.value}|${edge.to.value}")
                    if (id !in existing) {
                        inherited[id] = edge.copy(
                            id = id,
                            from = generalized.id,
                            createdAtEpochMillis = generalized.createdAtEpochMillis,
                            metadata = edge.metadata + mapOf(
                                MemoryVariantRegister.RECORDED_AT to generalized.createdAtEpochMillis.toString(),
                                "sourceEpisodeIds" to generalized.sourceEpisodeIds.joinToString(",") { it.value },
                                "inheritedFrom" to edge.from.value,
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }
        return inherited.values.toList() + plan.packet.items
            .map { item ->
                MemoryEdge(
                    id = MemoryEdgeId("${generalized.id.value}:supersedes:${item.id}"),
                    from = generalized.id,
                    to = MemoryNodeId(item.id),
                    relation = MemoryRelationKind.Supersedes,
                    createdAtEpochMillis = generalized.createdAtEpochMillis,
                    metadata = mapOf("basis" to "condensation"),
                )
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

    private fun probe(entry: MemoryQueueEntry, items: List<MemoryWorkItem>) = MemoryWorkPacket(
        queueId = entry.id,
        episodeId = entry.episodeId,
        stage = entry.stage,
        packetKey = "${entry.stage.name.lowercase()}-${entry.cursor}-part${entry.part}",
        items = items,
        instruction = instructionFor(entry.stage),
    )

    private fun fits(entry: MemoryQueueEntry, items: List<MemoryWorkItem>): Boolean =
        items.size <= policy.maxPacketItems &&
            items.sumOf { it.text.length } <= policy.maxPacketChars &&
            manager.fitsInput(probe(entry, items))

    /** The longest prefix of [items] the stage accepts; empty when even the first item alone does not fit. */
    private fun fitPrefix(entry: MemoryQueueEntry, items: List<MemoryWorkItem>): List<MemoryWorkItem> {
        var end = items.size
        while (end > 0 && !fits(entry, items.subList(0, end))) end -= 1
        return items.subList(0, end).toList()
    }

    /**
     * Splits one item that alone exceeds its stage's budget into ordered parts that each fit. Parts
     * keep the item's id and kind (so provenance and edges still name the real source) and carry
     * [MEMORY_PART] = "i/n". Paragraphs are kept whole while they fit; a paragraph that alone is too
     * long is cut at whitespace. Deterministic for a given item and budget, so a resumed entry sees
     * the same parts.
     */
    private fun splitToFit(entry: MemoryQueueEntry, item: MemoryWorkItem): List<MemoryWorkItem> {
        val paragraphs = item.text.split(PARAGRAPH_BREAK).map(String::trim).filter(String::isNotEmpty)
        var limit = item.text.length
        while (limit >= MIN_PART_CHARS) {
            val texts = packParagraphs(paragraphs, limit)
            val parts = texts.mapIndexed { index, text ->
                item.copy(text = text, metadata = item.metadata + (MEMORY_PART to "${index + 1}/${texts.size}"))
            }
            if (parts.size > 1 && parts.all { fits(entry, listOf(it)) }) return parts
            limit = limit * 3 / 4
        }
        throw IllegalArgumentException(
            "Memory work item ${item.id} cannot fit the ${entry.stage} input budget even split into parts; " +
                "its id, kind and metadata alone exceed it",
        )
    }

    private data class PacketPlan(
        val packet: MemoryWorkPacket,
        val consumed: Int,
        val hasMore: Boolean,
        val nextPart: Int = 0,
        val condensationKind: MemoryNodeKind? = null,
        val clusterKey: String? = null,
    )
}

private fun List<MemoryWorkItem>.clusterKey(): String = map { it.id }.sorted().joinToString("|")

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

/** Metadata key on a work item that is one part ("i/n") of a source item too large for one packet. */
const val MEMORY_PART = "memory.part"

private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
private const val MIN_PART_CHARS = 16

/** Greedily packs whole paragraphs into texts of at most [limit] chars; an overlong paragraph is cut at whitespace. */
private fun packParagraphs(paragraphs: List<String>, limit: Int): List<String> {
    val pieces = paragraphs.flatMap { paragraph -> cutAtWhitespace(paragraph, limit) }
    val out = mutableListOf<String>()
    val current = StringBuilder()
    for (piece in pieces) {
        if (current.isNotEmpty() && current.length + 2 + piece.length > limit) {
            out += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(piece)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

private fun cutAtWhitespace(text: String, limit: Int): List<String> {
    val out = mutableListOf<String>()
    var rest = text
    while (rest.length > limit) {
        val space = rest.lastIndexOf(' ', limit).takeIf { it > 0 } ?: limit
        out += rest.substring(0, space).trim()
        rest = rest.substring(space).trim()
    }
    if (rest.isNotEmpty()) out += rest
    return out
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
        // An item over the limit on its own is still returned (alone); the caller splits it into parts.
        if (result.isNotEmpty() && chars + item.text.length > maxChars) break
        result += item
        chars += item.text.length
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
    // Tag keywords serve recall only; they would crowd a clerk's packet budget.
    metadata = (metadata - TAG_KEYWORDS) + mapOf(
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

private const val MAX_CACHED_TERMS = 200_000

private fun MemorySnapshot.relatedNeighborhood(
    episodeId: MemoryEpisodeId,
    needles: List<MemoryWorkItem>,
    maxItems: Int,
    maxChars: Int,
    termsOf: (MemoryNode) -> Set<String> = { it.text.memoryTerms().toSet() },
): List<MemoryWorkItem> {
    val terms = needles.flatMap { it.text.memoryTerms() }.toSet()
    if (terms.isEmpty() || maxItems <= 0 || maxChars <= 0) return emptyList()
    // Same-kind memories come first: the programmatic linker links only those, and only they can
    // later condense together. Other kinds fill the remaining slots for linkers that relate across
    // kinds (embeddings). A superseded memory is already represented by its condensation.
    val kinds = needles.mapTo(hashSetOf()) { it.kind }
    val superseded = edges
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
    var chars = 0
    val candidates = nodes
        .asSequence()
        .filter { it.kind.isClerkMemory && episodeId !in it.sourceEpisodeIds && it.id !in superseded }
        .map { node ->
            val candidateTerms = termsOf(node)
            val overlap = terms.count { it in candidateTerms }
            Triple(node, "node:${node.kind.name}" in kinds, overlap)
        }
        .filter { (_, _, overlap) -> overlap > 0 }
        .sortedWith(compareByDescending<Triple<MemoryNode, Boolean, Int>> { it.second }.thenByDescending { it.third })
        .map { (node, _, _) -> node.asWorkItem() }
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

/**
 * Candidate clusters, largest first. Each is trimmed to its largest group of members asserting the
 * same values (`memoryClaimSignature`), strongest similarity first: members that differ stay apart,
 * and the rest can still condense.
 */
private fun MemorySnapshot.condensationClusters(
    policy: MemoryConsolidationPolicy,
    protectedEpisodeIds: Set<MemoryEpisodeId> = emptySet(),
    projectId: String? = null,
): Sequence<List<MemoryNode>> {
    val superseded = edges
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
    // A memory already folded into a generalized one is not offered again (it may stay active).
    val folded = edges
        .filter { it.relation == MemoryRelationKind.CondensedFrom }
        .mapTo(hashSetOf()) { it.to }
    val divergent = edges
        .filter { it.relation == MemoryRelationKind.Diverges || it.relation == MemoryRelationKind.ConflictsWith }
        .flatMap { listOf(it.from to it.to, it.to to it.from) }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, partners) -> partners.toHashSet() }
    val episodesById = episodes.associateBy(MemoryEpisode::id)
    val activeById = nodes
        .filter { node ->
            node.kind.isClerkMemory &&
                node.id !in superseded &&
                node.id !in folded &&
                node.sourceEpisodeIds.none { it in protectedEpisodeIds } &&
                node.sourceEpisodeIds.isNotEmpty() &&
                node.sourceEpisodeIds.all { sourceEpisodeId ->
                    episodesById[sourceEpisodeId]?.projectId == projectId
                }
        }
        .associateBy(MemoryNode::id)
    val similarEdges = edges.filter {
        it.relation == MemoryRelationKind.SimilarTo &&
            it.weight >= policy.minimumSimilarityWeight &&
            it.from in activeById &&
            it.to in activeById &&
            activeById[it.from]?.kind == activeById[it.to]?.kind
    }
    if (similarEdges.isEmpty()) return emptySequence()

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

    val weights = similarEdges
        .flatMap { edge -> listOf(edge.from to edge.weight, edge.to to edge.weight) }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }
    return qualifying
        .sortedByDescending { it.size }
        .asSequence()
        .map { component ->
            component
                .groupBy { memoryClaimSignature(it.text) }
                .values
                .maxWith(compareBy<List<MemoryNode>> { it.size }.thenBy { group -> group.sumOf { (weights[it.id] ?: 0f).toDouble() } })
                .sortedByDescending { weights[it.id] ?: 0f }
                // Greedy: keep a member only if it neither carries a divergence marker to nor
                // contrasts with (same frame, different filler) any member already kept.
                .fold(mutableListOf<MemoryNode>()) { kept, node ->
                    if (
                        kept.size < policy.condensationBatchSize &&
                        kept.none { other ->
                            divergent[node.id]?.contains(other.id) == true || MemoryContrast.contrasts(node.text, other.text)
                        }
                    ) {
                        kept += node
                    }
                    kept
                }
        }
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
        "Mechanically restate these highly similar same-level representations as exactly one compact representation that keeps every source's values. Link it to every supplied source with CondensedFrom. Do not judge which source is right; the sources stay in memory."
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
