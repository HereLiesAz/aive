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
    Role,
    PromptContext,
    Plan,
    Message,
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
    val taskRunId: String? = null,
    val userPrompt: String,
    val chunks: List<MemorySourceChunk>,
    val createdAtEpochMillis: Long,
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
 * The semantic ladder intentionally keeps noun and verb indexes distinct. A memory can be
 * traversed in either direction: Context <-> tags <-> phrases <-> summaries <-> categories.
 */
@Serializable
enum class MemoryNodeKind {
    Context,
    NounTag,
    VerbTag,
    Phrase,
    Summary,
    Category,
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

    /** Category -> summaries. */
    Categorizes,

    SimilarTo,
    AssociatedWith,
    ConflictsWith,
    ResolvesConflict,
    Supersedes,
    CondensedFrom,
}

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

@Serializable
data class MemoryQueueEntry(
    val id: MemoryQueueId,
    val sequence: Long,
    val episodeId: MemoryEpisodeId,
    val stage: MemoryConsolidationStage = MemoryConsolidationStage.Sectioning,
    val cursor: Int = 0,
    val status: MemoryQueueStatus = MemoryQueueStatus.Pending,
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
)

@Serializable
data class MemoryStoreMutation(
    val episodesToAdd: List<MemoryEpisode> = emptyList(),
    val sectionsToAdd: List<MemorySection> = emptyList(),
    val nodesToAdd: List<MemoryNode> = emptyList(),
    val edgesToAdd: List<MemoryEdge> = emptyList(),
    val queueUpserts: List<MemoryQueueEntry> = emptyList(),
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
    val taskRunId: String? = null,
    val userPrompt: String,
    val parts: List<MemorySessionPart>,
    val closedAtEpochMillis: Long,
)

@Serializable
data class MemoryConsolidationPolicy(
    val maxPacketItems: Int = 24,
    val maxPacketChars: Int = 12_000,
    val maxMutationsPerPacket: Int = 96,
    val minimumSimilarityWeight: Float = 0.82f,
    val condensationBatchSize: Int = 3,
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

@Serializable
data class MemoryQuery(
    val text: String,
    val resolution: MemoryResolution = MemoryResolution.Summary,
    val maxResults: Int = 12,
    val includeConflicts: Boolean = true,
    val projectId: String? = null,
) {
    init {
        require(text.isNotBlank()) { "Memory query must not be blank" }
        require(maxResults > 0) { "Memory query result limit must be positive" }
    }
}

@Serializable
data class MemoryRecallHit(
    val node: MemoryNode,
    val score: Float,
    val conflicts: List<MemoryNode> = emptyList(),
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
