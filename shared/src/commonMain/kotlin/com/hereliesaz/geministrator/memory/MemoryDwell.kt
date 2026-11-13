package com.hereliesaz.geministrator.memory

/**
 * How deep an injection goes down the condensation, shallowest first: the keyword cloud and a
 * gist, then key phrases, then finer summaries, then the detail the memories were condensed from.
 */
internal enum class MemoryDepth { Gist, Phrases, Summaries, Detail }

/**
 * One agent's dwell on topics, held in the session only: never persisted, never written to a memory.
 * Each hit on a topic (a cue surfaced for it, or the agent following it) raises its dwell by one;
 * dwell falls by one for every [tokensPerStep] tokens that pass, so a topic left alone returns to the
 * top of the ladder. The dwell sets the injection [MemoryDepth]: one hit is [MemoryDepth.Gist], each
 * further hit goes one level deeper, up to [MemoryDepth.Detail].
 */
internal class MemoryDwell(private val tokensPerStep: Int = DEFAULT_TOKENS_PER_STEP) {
    private class Entry(val count: Int, val atTokens: Long)

    private val topics = LinkedHashMap<String, Entry>()
    private var tokens = 0L

    init {
        require(tokensPerStep > 0)
    }

    /** Tokens of the agent's own thinking and chat that have passed. */
    fun consumeTokens(count: Int) {
        if (count > 0) tokens += count
    }

    /** The topic's dwell now: its hits less one per [tokensPerStep] tokens since the last. */
    fun dwell(topic: String): Int {
        val entry = topics[MemoryCueWatcher.normalizeTag(topic)] ?: return 0
        return (entry.count - ((tokens - entry.atTokens) / tokensPerStep).toInt()).coerceAtLeast(0)
    }

    /** The depth the topic's current dwell calls for. */
    fun depth(topic: String): MemoryDepth = depthOf(dwell(topic))

    /** Records one hit on [topic] and returns the depth it now calls for. */
    fun hit(topic: String): MemoryDepth {
        val key = MemoryCueWatcher.normalizeTag(topic)
        if (key.isEmpty()) return MemoryDepth.Gist
        val raised = dwell(key) + 1
        topics.remove(key)
        topics[key] = Entry(raised, tokens)
        while (topics.size > MAX_TOPICS) topics.remove(topics.keys.first())
        return depthOf(raised)
    }

    companion object {
        const val DEFAULT_TOKENS_PER_STEP = 300
        private const val MAX_TOPICS = 64

        fun depthOf(dwell: Int): MemoryDepth = MemoryDepth.entries[(dwell - 1).coerceIn(0, MemoryDepth.entries.lastIndex)]
    }
}
