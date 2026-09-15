package com.hereliesaz.geministrator.memory

/**
 * Query-side deterministic normalization for GRIP.
 *
 * The graph tool remains responsible for scoring and traversal. This decorator simply augments a
 * free-text/tag query with the same normalized lexical cues used during non-model association, so
 * inflection and technical action normalization can seed the existing graph without a model call.
 */
class LexicalMemoryTool(
    private val delegate: MemoryTool,
    private val lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
) : MemoryTool {
    override suspend fun bank(request: MemoryBankRequest): MemoryQueueEntry = delegate.bank(request)

    override suspend fun grip(query: MemoryQuery): MemoryRecallBundle {
        val expanded = query.copy(text = query.text.withLexicalCueExpansion())
        val result = delegate.grip(expanded)
        // Preserve the caller's original query in the public bundle; expansion is an implementation detail.
        return result.copy(query = query)
    }

    override suspend fun grip(query: MemoryTagQuery): MemoryRecallBundle {
        val expandedTags = linkedSetOf<String>()
        query.tags.forEach { tag ->
            expandedTags += tag
            expandedTags += tag.memoryLexicalTerms(lexicon)
        }
        return delegate.grip(query.copy(tags = expandedTags.toList()))
    }

    override suspend fun expand(
        nodeId: MemoryNodeId,
        resolution: MemoryResolution,
        maxResults: Int,
        includeConflicts: Boolean,
    ): List<MemoryRecallHit> = delegate.expand(nodeId, resolution, maxResults, includeConflicts)

    private fun String.withLexicalCueExpansion(): String {
        val normalized = memoryLexicalTerms(lexicon)
            .filterNot { cue -> cue.equals(trim(), ignoreCase = true) }
        if (normalized.isEmpty()) return this
        return buildString {
            append(this@withLexicalCueExpansion)
            append(' ')
            append(normalized.joinToString(" "))
        }
    }
}
