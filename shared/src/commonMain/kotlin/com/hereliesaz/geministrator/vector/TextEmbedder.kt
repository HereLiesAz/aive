package com.hereliesaz.geministrator.vector

import kotlin.math.ln

/**
 * Turns text into vectors for a [VectorIndex]. Each embedder is its own vector space: [id] and
 * [version] go with every stored vector, and vectors from different spaces are never compared.
 */
interface TextEmbedder {
    val id: String
    val version: Int
    val dimension: Int

    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * The always-available embedder: deterministic, pure Kotlin, no model download. Text becomes a
 * bag of weighted features, hashed into [dimension] signed buckets (the hashing trick):
 *
 * - words (camelCase, snake_case and paths split), with sublinear term frequency, and their
 *   light suffix-stripped stems;
 * - adjacent word pairs, for a little word order;
 * - character trigrams of each word, so spelling variants and inflections overlap;
 * - concepts, when [conceptsOf] is given: WordNet synsets (and their hypernyms) for each word,
 *   so "database" and "datastore" or "repair" and "fix" share features.
 *
 * Closer to concept-aware keyword matching than to a neural sentence model; the optional MiniLM
 * embedder is the semantic upgrade. Stop words are dropped.
 */
class BuiltInTextEmbedder(
    override val dimension: Int = 384,
    private val conceptsOf: ((String) -> IntArray)? = null,
) : TextEmbedder {
    override val id: String = if (conceptsOf == null) "builtin-hash" else "builtin-hash-wordnet"
    /** 2: stem features added. Bump whenever a change moves existing vectors. */
    override val version: Int = 2

    init {
        require(dimension >= 32) { "dimension must be at least 32" }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::embedOne)

    fun embedOne(text: String): FloatArray {
        val words = tokens(text)
        val vector = FloatArray(dimension)
        if (words.isEmpty()) return vector

        val counts = HashMap<String, Int>()
        for (word in words) counts[word] = (counts[word] ?: 0) + 1
        for ((word, count) in counts) {
            val weight = 1f + ln(count.toFloat())
            add(vector, "w:$word", weight)
            // The stem, so "retrying"/"retry" and "builds"/"build" share a feature.
            add(vector, "t:${stem(word)}", STEM_WEIGHT * weight)
            val padded = "^$word$"
            val trigrams = (0..padded.length - 3).map { padded.substring(it, it + 3) }
            val trigramWeight = TRIGRAM_WEIGHT * weight / trigrams.size.coerceAtLeast(1)
            for (trigram in trigrams) add(vector, "c:$trigram", trigramWeight)
            conceptsOf?.invoke(word)?.let { concepts ->
                // The first senses dominate; a word's most frequent sense is usually the one meant.
                concepts.take(MAX_SENSES).forEachIndexed { rank, concept ->
                    add(vector, "s:$concept", CONCEPT_WEIGHT * weight / (rank + 1))
                }
            }
        }
        for (i in 0 until words.size - 1) add(vector, "b:${words[i]} ${words[i + 1]}", BIGRAM_WEIGHT)
        return normalized(vector)
    }

    private fun add(vector: FloatArray, feature: String, weight: Float) {
        val hash = fnv1a64(feature)
        val bucket = ((hash ushr 1) % dimension).toInt()
        vector[bucket] += if (hash and 1L == 0L) weight else -weight
    }

    internal companion object {
        const val TRIGRAM_WEIGHT = 0.6f
        const val CONCEPT_WEIGHT = 0.8f
        const val BIGRAM_WEIGHT = 0.4f
        const val STEM_WEIGHT = 0.8f
        const val MAX_SENSES = 2

        private val STOP_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "has", "have", "if",
            "in", "into", "is", "it", "its", "of", "on", "or", "so", "that", "the", "their", "then",
            "there", "these", "this", "to", "was", "were", "will", "with",
        )

        /** Lowercase words, splitting camelCase, snake_case, kebab-case and paths. */
        fun tokens(text: String): List<String> {
            val spaced = text
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            return Regex("[\\p{L}\\p{N}]+").findAll(spaced)
                .map { it.value.lowercase() }
                .filter { it.length > 1 && it !in STOP_WORDS }
                .toList()
        }

        /**
         * Strips common English inflections, keeping at least three letters: not a full stemmer.
         * Every stem, inflected or not, then drops a final silent "e" and un-doubles a final
         * doubled consonant, so run/running, make/making and case/cases meet at one stem.
         */
        fun stem(word: String): String {
            fun cut(suffix: String, replacement: String = ""): String? =
                if (word.endsWith(suffix) && word.length - suffix.length >= 3) word.dropLast(suffix.length) + replacement else null
            val stripped = cut("ies", "y") ?: cut("ing") ?: cut("ed") ?: cut("es")?.takeIf { word.endsWith("ses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes") }
                ?: (if (word.endsWith("ss")) null else cut("s")) ?: word
            var stem = stripped
            if (stem.length > 3 && stem.endsWith('e')) stem = stem.dropLast(1)
            if (stem.length > 3 && stem[stem.length - 1] == stem[stem.length - 2] && stem.last() !in "aeiouls") stem = stem.dropLast(1)
            return stem
        }

        fun fnv1a64(text: String): Long {
            var hash = -3750763034362895579L
            for (char in text) {
                hash = hash xor char.code.toLong()
                hash *= 1099511628211L
            }
            return hash
        }
    }
}
