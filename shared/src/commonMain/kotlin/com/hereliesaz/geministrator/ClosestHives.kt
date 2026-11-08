package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowDefinition

/** A saved hive that resembles what the keeper described, and the words that made it so. */
internal data class ClosestHive(
    val definition: WorkflowDefinition,
    /** The share of the description's own words the hive speaks of, 0 to 1. */
    val score: Double,
    /** Those words, in the order the description has them. */
    val matched: List<String>,
)

/**
 * The saved hives closest to a description (docs/Keepers-Room.md, A new hive): the literal option
 * shown beside what the planner would draft. It reads each hive's name, description, and its
 * creatures' names and objectives, and ranks by how much of the description they cover. Pure and
 * deterministic: no model, nothing stored, nothing changed.
 */
internal object ClosestHives {
    private val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "onto", "then", "than", "them", "they", "their",
        "have", "has", "had", "are", "was", "were", "will", "would", "should", "could", "can", "all", "any", "each",
        "our", "your", "you", "its", "but", "not", "out", "about", "over", "make", "want", "need", "like", "get",
        "when", "what", "which", "who", "how", "use", "using", "also", "just", "some", "more", "most", "one", "two",
    )

    /** At most [limit] hives from [candidates], best first, each covering at least [minScore] of the description. */
    fun of(
        description: String,
        candidates: Collection<WorkflowDefinition>,
        limit: Int = 3,
        minScore: Double = 0.3,
    ): List<ClosestHive> {
        val wanted = words(description)
        if (wanted.isEmpty()) return emptyList()
        // At least two shared words, unless the description is a single word.
        val floor = if (wanted.size == 1) 1 else 2
        return candidates.asSequence()
            .filter { it.tasks.isNotEmpty() }
            .map { definition ->
                val have = words(
                    buildString {
                        append(definition.name).append(' ').append(definition.description.orEmpty())
                        definition.tasks.forEach { append(' ').append(it.name).append(' ').append(it.objective) }
                    },
                ).toSet()
                val matched = wanted.filter { it in have }
                ClosestHive(definition, matched.size.toDouble() / wanted.size, matched)
            }
            .filter { it.matched.size >= floor && it.score >= minScore }
            .sortedWith(
                compareByDescending<ClosestHive> { it.score }
                    .thenByDescending { it.matched.size }
                    .thenBy { it.definition.name.lowercase() }
                    .thenBy { it.definition.id.value },
            )
            .take(limit)
            .toList()
    }

    /** The distinct, significant words of [text], lowercased, in order, with plurals and -ing/-ed folded. */
    internal fun words(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                val word = fold(current.toString())
                if (word.length >= 3 && word !in stopWords) seen += word
                current.clear()
            }
        }
        for (ch in text.lowercase()) if (ch.isLetterOrDigit()) current.append(ch) else flush()
        flush()
        return seen.toList()
    }

    private fun fold(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }
}
