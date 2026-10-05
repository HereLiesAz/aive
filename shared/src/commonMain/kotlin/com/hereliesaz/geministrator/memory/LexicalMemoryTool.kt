package com.hereliesaz.geministrator.memory

/**
 * Query-side deterministic normalization for GRIP.
 *
 * The graph tool remains responsible for scoring and traversal. This decorator adds the same
 * normalized lexical cues used during non-model association as [MemoryQuery.expansionTerms] (scored
 * at reduced weight, never diluting the caller's words), so inflection and technical action
 * normalization can seed the graph without a model call.
 */
class LexicalMemoryTool(
    private val delegate: MemoryTool,
    private val lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
) : MemoryTool {
    override suspend fun bank(request: MemoryBankRequest): MemoryQueueEntry = delegate.bank(request)

    override suspend fun termFrequency(term: String): MemoryTermFrequency = delegate.termFrequency(term)

    override suspend fun deliberate(request: MemoryDeliberationRequest): MemoryNode = delegate.deliberate(request)

    override suspend fun grip(query: MemoryQuery): MemoryRecallBundle {
        val expanded = query.copy(expansionTerms = (query.expansionTerms + query.text.lexicalCues()).distinct())
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

    /** Normalized cues not already among the query's own words. */
    private fun String.lexicalCues(): List<String> {
        val own = lowercase().split(Regex("[^a-z0-9_-]+")).toSet()
        return memoryLexicalTerms(lexicon).filterNot { cue -> cue.equals(trim(), ignoreCase = true) || cue in own }
    }
}
