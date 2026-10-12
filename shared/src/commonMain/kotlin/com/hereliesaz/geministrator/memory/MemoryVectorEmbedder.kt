package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.vector.BuiltInTextEmbedder
import com.hereliesaz.geministrator.vector.TextEmbedder

/**
 * The memory layer's built-in embedder: [BuiltInTextEmbedder] with the shipped WordNet as its
 * concept source, so a word and its synonyms share features. WordNet is loaded before the first
 * vector is made and every vector in this space includes concepts; if the resource cannot be read
 * the embedder falls back to the plain space, under its own id so the two never mix.
 */
class MemoryVectorEmbedder(override val dimension: Int = 384) : TextEmbedder {
    private var delegate: BuiltInTextEmbedder? = null

    override val id: String get() = delegate?.id ?: "builtin-hash-wordnet"
    override val version: Int get() = delegate?.version ?: BUILT_IN_VERSION

    override suspend fun embed(texts: List<String>): List<FloatArray> = resolve().embed(texts)

    private suspend fun resolve(): BuiltInTextEmbedder = delegate ?: run {
        val wordNet = runCatching { MemoryLanguageResources.get().wordNet }.getOrNull()
        val concepts: ((String) -> IntArray)? = wordNet?.let { lexicon ->
            { word ->
                listOf(WordNetLexicon.Pos.Noun, WordNetLexicon.Pos.Verb)
                    .flatMap { pos -> lexicon.baseForms(word, pos).flatMap { lexicon.senses(it, pos).take(1) } }
                    .distinct()
                    .toIntArray()
            }
        }
        BuiltInTextEmbedder(dimension, concepts).also { delegate = it }
    }

    private companion object {
        val BUILT_IN_VERSION = BuiltInTextEmbedder().version
    }
}
