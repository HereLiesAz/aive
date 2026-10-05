package com.hereliesaz.geministrator.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryEpisodeId(val value: String)

@Serializable
data class MemoryChunkId(val value: String)

@Serializable
data class MemorySectionId(val value: String)

@Serializable
data class MemoryNodeId(val value: String)

@Serializable
data class MemoryEdgeId(val value: String)

@Serializable
data class MemoryQueueId(val value: String)

@Serializable
enum class MemorySourceKind {
    UserPrompt,
    Objective,
    /** An agent's deliberation about a divergence ([MemoryTool.deliberate]). */
    Deliberation,
    Role,
    PromptContext,
    Plan,
    Message,
    AgentNote,
    Artifact,
    Failure,
    Other,
}

@Serializable
data class MemorySourceChunk(
    val id: MemoryChunkId,
    val episodeId: MemoryEpisodeId,
    val ordinal: Int,
    val kind: MemorySourceKind,
    val label: String,
    val text: String,
)

@Serializable
data class MemoryEpisode(
    val id: MemoryEpisodeId,
    val sourceSessionId: String,
    val projectId: String? = null,
    val workflowRunId: String? = null,
    val workflowDefinitionId: String? = null,
    val taskRunId: String? = null,
    val taskDefinitionId: String? = null,
    val roleId: String? = null,
    val userPrompt: String,
    val chunks: List<MemorySourceChunk>,
    val createdAtEpochMillis: Long,
    /**
     * Tombstone: set when the user's raw-retention setting purged this episode's raw context (its
     * chunks and prompt). The episode record stays so provenance resolves; memories made from it are
     * untouched.
     */
    val purged: MemoryRawPurge? = null,
)

/** The audit record of one raw purge: what, when, why, and by whose setting. */
@Serializable
data class MemoryRawPurge(
    val purgedAtEpochMillis: Long,
    val chunks: Int,
    val characters: Long,
    val reason: String,
    /** `global` or `project:<id>`: whose retention setting purged it. */
    val setting: String,
)

@Serializable
data class MemorySection(
    val id: MemorySectionId,
    val episodeId: MemoryEpisodeId,
    val sourceChunkIds: Set<MemoryChunkId>,
    val ordinal: Int,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * The semantic ladder intentionally keeps noun and verb indexes distinct. Category nodes serve as
 * broader category/subject tags. Memory can be traversed in either direction:
 * Context <-> noun/verb tags <-> phrases <-> summaries <-> category/subject tags.
 */
@Serializable
enum class MemoryNodeKind {
    Context,
    NounTag,
    VerbTag,
    Phrase,
    Summary,
    Category,

    /**
     * Variant register: one frame (what a set of contrasting memories is about). Written only by the
     * engine's deterministic contrast step; never a recall result, never condensed.
     */
    Frame,

    /**
     * Variant register: one filler encountered for a frame. Add-only; its occurrences are the
     * [MemoryRelationKind.Attests] edges into it. No variant is ranked as correct.
     */
    Variant,

    /**
     * An agent's conscious conclusion about a divergence, citing the memories and evidence it
     * considered ([MemoryRelationKind.Deliberates]). Written only through [MemoryTool.deliberate];
     * never condensed, never supersedes anything.
     */
    Deliberation,
    ;

    /** The six kinds the clerk pipeline produces; the others are engine-owned records. */
    val isClerkMemory: Boolean get() = this in CLERK_MEMORY_KINDS

    companion object {
        val CLERK_MEMORY_KINDS: Set<MemoryNodeKind> = setOf(Context, NounTag, VerbTag, Phrase, Summary, Category)
    }
}

@Serializable
data class MemoryNode(
    val id: MemoryNodeId,
    val kind: MemoryNodeKind,
    val text: String,
    val sourceEpisodeIds: Set<MemoryEpisodeId> = emptySet(),
    val sourceSectionIds: Set<MemorySectionId> = emptySet(),
    val salience: Float = 0.5f,
    val confidence: Float = 1f,
    val createdAtEpochMillis: Long,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(text.isNotBlank()) { "Memory node text must not be blank" }
        require(salience in 0f..1f) { "Memory node salience must be normalized" }
        require(confidence in 0f..1f) { "Memory node confidence must be normalized" }
    }
}

@Serializable
enum class MemoryRelationKind {
    /** Noun/verb index -> retained context. */
    Indexes,

    /** Phrase -> noun/verb tags. */
    Composes,

    /** Summary -> phrases. */
    Summarizes,

    /** Category/subject tag -> summaries. */
    Categorizes,

    SimilarTo,
    AssociatedWith,

    /** Legacy: kept readable for stored graphs and surfaced like [Diverges]. No clerk may write it. */
    ConflictsWith,

    /** Legacy: kept readable for stored graphs. No clerk may write it; nothing writes it. */
    ResolvesConflict,

    /**
     * Derived -> source: the derived memory contains every sentence of the source (genuine coverage,
     * including identical repeats), so the source is hidden from ranking and stays in the store.
     * Written only by the consolidator's deterministic coverage check, never by a clerk.
     */
    Supersedes,

    /** Generalized memory -> each memory it was condensed from (provenance; sources stay active unless covered). */
    CondensedFrom,

    /**
     * Divergence marker: two memories share a frame and differ in filler (a contrast). Structural and
     * advisory: it says the memories differ, never which is right. Recall
     * returns marked partners together. Written only by the deterministic contrast step.
     */
    Diverges,

    /** Variant register: a memory node -> the variant (filler) it attests. Metadata carries age and context. */
    Attests,

    /** Variant register: variant -> its frame. */
    VariantOf,

    /** Deliberation -> each memory (or other deliberation) it cites. */
    Deliberates,

    /**
     * Access event, add-only: a recall delivered this (resolved) memory once. Resolving deliberation ->
     * memory, written to the reading workflow's bank; counting them gives the memory's access count.
     */
    Recalled,
}

/**
 * Relations a model-backed clerk may propose. `Supersedes`, `ResolvesConflict` and `ConflictsWith`
 * are not offered to models and are rejected if a model emits them; nor are the engine-owned
 * register, divergence and deliberation relations.
 */
val MODEL_WRITABLE_RELATIONS: Set<MemoryRelationKind> = setOf(
    MemoryRelationKind.Indexes,
    MemoryRelationKind.Composes,
    MemoryRelationKind.Summarizes,
    MemoryRelationKind.Categorizes,
    MemoryRelationKind.SimilarTo,
    MemoryRelationKind.AssociatedWith,
    MemoryRelationKind.CondensedFrom,
)

@Serializable
data class MemoryEdge(
    val id: MemoryEdgeId,
    val from: MemoryNodeId,
    val to: MemoryNodeId,
    val relation: MemoryRelationKind,
    val weight: Float = 1f,
    val createdAtEpochMillis: Long,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(from != to) { "Memory edges must connect distinct nodes" }
        require(weight in 0f..1f) { "Memory edge weight must be normalized" }
    }
}

@Serializable
enum class MemoryConsolidationStage {
    Sectioning,
    Salience,
    Tags,
    Phrases,
    Summaries,
    Categories,
    Associations,
    Condensation,
    Complete,
}

@Serializable
enum class MemoryQueueStatus {
    Pending,
    Processing,
    Failed,
    Complete,
}

/**
 * Deliberate in-session banks are processed before ordinary lifecycle backlog. Priority affects
 * consolidation order only; it does not survive as special retrieval or reminder semantics.
 */
@Serializable
enum class MemoryQueuePriority {
    Normal,
    Next,
}

@Serializable
data class MemoryQueueEntry(
    val id: MemoryQueueId,
    val sequence: Long,
    val episodeId: MemoryEpisodeId,
    val stage: MemoryConsolidationStage = MemoryConsolidationStage.Sectioning,
    val cursor: Int = 0,
    val status: MemoryQueueStatus = MemoryQueueStatus.Pending,
    val priority: MemoryQueuePriority = MemoryQueuePriority.Normal,
    val attempt: Int = 0,
    val lastError: String? = null,
    val createdAtEpochMillis: Long,
)

@Serializable
data class MemorySnapshot(
    val revision: Long = 0,
    val episodes: List<MemoryEpisode> = emptyList(),
    val sections: List<MemorySection> = emptyList(),
    val nodes: List<MemoryNode> = emptyList(),
    val edges: List<MemoryEdge> = emptyList(),
    val queue: List<MemoryQueueEntry> = emptyList(),
    /**
     * Condensation clusters that were declined (e.g. `DO_NOT_CONDENSE`) or kept failing, keyed by
     * their sorted member IDs. The same cluster is not offered again; a cluster that gains or loses
     * members has a new key and is.
     */
    val declinedCondensations: List<String> = emptyList(),
)

@Serializable
data class MemoryStoreMutation(
    val episodesToAdd: List<MemoryEpisode> = emptyList(),
    val sectionsToAdd: List<MemorySection> = emptyList(),
    val nodesToAdd: List<MemoryNode> = emptyList(),
    val edgesToAdd: List<MemoryEdge> = emptyList(),
    val queueUpserts: List<MemoryQueueEntry> = emptyList(),
    val condensationDeclinesToAdd: List<String> = emptyList(),
)

@Serializable
data class MemorySessionPart(
    val kind: MemorySourceKind,
    val label: String,
    val text: String,
)

@Serializable
data class MemorySessionEnvelope(
    val sourceSessionId: String,
    val projectId: String? = null,
    val workflowRunId: String? = null,
    val workflowDefinitionId: String? = null,
    val taskRunId: String? = null,
    val taskDefinitionId: String? = null,
    val roleId: String? = null,
    val userPrompt: String,
    val parts: List<MemorySessionPart>,
    val closedAtEpochMillis: Long,
)

/**
 * Scope is chosen by the active agent when it deliberately banks a note, plan, checkpoint, or
 * other small piece of context. Null fields intentionally broaden eligibility.
 */
@Serializable
data class MemoryBankScope(
    val projectId: String? = null,
    val workflowRunId: String? = null,
    val workflowDefinitionId: String? = null,
    val taskRunId: String? = null,
    val taskDefinitionId: String? = null,
    val roleId: String? = null,
)

/**
 * An early intentional deposit into the same memory pipeline used by lifecycle banking. It gains
 * queue priority so it is consolidated next, but receives no special retrieval behavior later.
 */
@Serializable
data class MemoryBankRequest(
    val sourceSessionId: String,
    val text: String,
    val label: String = "Agent note",
    val sourceKind: MemorySourceKind = MemorySourceKind.AgentNote,
    val scope: MemoryBankScope = MemoryBankScope(),
    val bankedAtEpochMillis: Long,
) {
    init {
        require(sourceSessionId.isNotBlank()) { "Memory bank source session must not be blank" }
        require(text.isNotBlank()) { "Memory bank text must not be blank" }
        require(label.isNotBlank()) { "Memory bank label must not be blank" }
    }
}

@Serializable
data class MemoryConsolidationPolicy(
    val maxPacketItems: Int = 24,
    val maxPacketChars: Int = 12_000,
    val maxMutationsPerPacket: Int = 96,
    val minimumSimilarityWeight: Float = 0.82f,
    val condensationBatchSize: Int = 3,
    /**
     * Failed attempts before a queue entry is parked (left `Failed`, skipped by consolidation, error
     * kept in `lastError`). A condensation cluster that exhausts its attempts is declined instead,
     * so the entry can finish.
     */
    val maxAttempts: Int = 3,
    val maxSimilarPerKind: Map<MemoryNodeKind, Int> = mapOf(
        MemoryNodeKind.Context to 8,
        MemoryNodeKind.NounTag to 12,
        MemoryNodeKind.VerbTag to 12,
        MemoryNodeKind.Phrase to 8,
        MemoryNodeKind.Summary to 6,
        MemoryNodeKind.Category to 8,
    ),
) {
    init {
        require(maxPacketItems > 0)
        require(maxPacketChars > 0)
        require(maxMutationsPerPacket > 0)
        require(minimumSimilarityWeight in 0f..1f)
        require(condensationBatchSize >= 2)
        require(maxAttempts > 0)
        require(maxSimilarPerKind.values.all { it >= 2 })
    }
}

@Serializable
enum class MemoryResolution {
    Category,
    Summary,
    Phrase,
    Tag,
    Context,
}

/**
 * Free-text associative lookup. Its default resolution is Tag because semantic noun/entity,
 * verb/action, and category/subject tags are Haive's normal ambient memory cues; deeper content is
 * requested deliberately.
 */
@Serializable
data class MemoryQuery(
    val text: String,
    val resolution: MemoryResolution = MemoryResolution.Tag,
    val maxResults: Int = 12,
    /** Divergent partners (and their deliberations) come back with each hit. On by default. */
    val includeConflicts: Boolean = true,
    val projectId: String? = null,
    val workflowRunId: String? = null,
    val workflowDefinitionId: String? = null,
    val taskRunId: String? = null,
    val taskDefinitionId: String? = null,
    val roleId: String? = null,
    /**
     * Normalized variants of the query's words (lemmas, synonyms) added by query expansion. They
     * count at reduced weight and never dilute the caller's own terms.
     */
    val expansionTerms: List<String> = emptyList(),
) {
    init {
        require(text.isNotBlank()) { "Memory query must not be blank" }
        require(maxResults > 0) { "Memory query result limit must be positive" }
    }
}

/**
 * Tag-addressed recall for agents that already carry semantic tags in their CoTR. Tags may address
 * noun/entity nodes, verb/action nodes, or broader category/subject nodes. No prose query synthesis
 * is required: the tags themselves are sufficient addresses into the memory graph.
 */
@Serializable
data class MemoryTagQuery(
    val tags: List<String>,
    val resolution: MemoryResolution = MemoryResolution.Tag,
    val maxResults: Int = 12,
    val includeConflicts: Boolean = true,
    val scope: MemoryBankScope = MemoryBankScope(),
) {
    init {
        require(tags.isNotEmpty()) { "Memory tag query must contain at least one tag" }
        require(tags.all { it.isNotBlank() }) { "Memory tag query tags must not be blank" }
        require(maxResults > 0) { "Memory tag query result limit must be positive" }
    }
}

/**
 * One recall unit. [conflicts] are the hit's divergent partners (memories sharing its frame with a
 * different filler, via `Diverges` or legacy `ConflictsWith`), returned whether or not they matched,
 * ranked, or are otherwise hidden; [deliberations] are agents' recorded conclusions about them. The
 * unit is ranked, budgeted and gated as one item, so nothing downstream can show one side alone.
 * Partners and deliberations always come from the bank the hit was read from.
 */
@Serializable
data class MemoryRecallHit(
    val node: MemoryNode,
    val score: Float,
    val conflicts: List<MemoryNode> = emptyList(),
    val deliberations: List<MemoryNode> = emptyList(),
    /** Where the memory came from and how it reaches the reading workflow. Set by workflow-bank recall. */
    val provenance: MemoryHitProvenance? = null,
    /**
     * Set when the hit is the current version of a resolved contrast: the deliberation that resolved
     * it, the side not chosen (now history) and how often it has been recalled since. Recall returns
     * such a memory as a single memory; this history fades from rendering as access grows.
     */
    val resolution: MemoryResolvedHistory? = null,
)

@Serializable
data class MemoryResolvedHistory(
    val deliberation: MemoryNode,
    val notChosen: List<MemoryNode>,
    /** Recalls of this memory recorded so far (add-only access events). */
    val accessCount: Int,
)

/**
 * A recall hit's provenance. [producedByWorkflow] is the workflow whose bank the memory was written
 * to (for a derived memory, the workflow that derived it; its sources keep their own tags, reachable
 * through `CondensedFrom`). The lineage is derived from the workflow DAG at read time, never stored.
 */
@Serializable
data class MemoryHitProvenance(
    val producedByWorkflow: String?,
    val producedBySession: String?,
    val projectId: String?,
    /**
     * Producing workflow to reading workflow through parent links (merge points included), when the
     * memory is in the reader's own lineage; null for a read-only memory of another workflow.
     */
    val lineagePath: List<String>?,
    /** Set when the hit was read, read-only, from another workflow's bank in the same project. */
    val readOnlyFromWorkflow: String? = null,
)

@Serializable
data class MemoryRecallBundle(
    val query: MemoryQuery,
    val hits: List<MemoryRecallHit>,
)

@Serializable
data class MemoryWorkItem(
    val id: String,
    val kind: String,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class MemoryWorkPacket(
    val queueId: MemoryQueueId,
    val episodeId: MemoryEpisodeId,
    val stage: MemoryConsolidationStage,
    /** Stable within a specific bounded batch; prevents generated IDs colliding across packets. */
    val packetKey: String,
    val items: List<MemoryWorkItem>,
    val neighborhood: List<MemoryWorkItem> = emptyList(),
    val instruction: String,
)

@Serializable
data class MemoryMutationBatch(
    val sectionsToAdd: List<MemorySection> = emptyList(),
    val nodesToAdd: List<MemoryNode> = emptyList(),
    val edgesToAdd: List<MemoryEdge> = emptyList(),
) {
    val size: Int get() = sectionsToAdd.size + nodesToAdd.size + edgesToAdd.size
}

/**
 * An agent's conscious conclusion about a divergence. [citedNodeIds] are the memories it considered
 * (at least one); [evidence] is free text naming anything else it weighed. Stored as its own episode
 * and a [MemoryNodeKind.Deliberation] node; it is not queued for consolidation, so it is never
 * condensed, and it never supersedes: the divergence marker and both sides stay.
 */
@Serializable
data class MemoryDeliberationRequest(
    val sourceSessionId: String,
    val conclusion: String,
    val citedNodeIds: List<MemoryNodeId>,
    val evidence: List<String> = emptyList(),
    val scope: MemoryBankScope = MemoryBankScope(),
    val deliberatedAtEpochMillis: Long,
    /**
     * The cited memory the deliberation judged correct, or null for "unresolved". Only a deliberation
     * that names one lets consolidation absorb the contrast ([MemoryDeliberationAbsorber]).
     */
    val chosen: MemoryNodeId? = null,
) {
    init {
        require(sourceSessionId.isNotBlank()) { "Deliberation source session must not be blank" }
        require(conclusion.isNotBlank()) { "Deliberation conclusion must not be blank" }
        require(citedNodeIds.isNotEmpty()) { "A deliberation must cite at least one memory" }
        require(chosen == null || chosen in citedNodeIds) { "A deliberation can choose only a memory it cites" }
    }
}
