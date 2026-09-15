package com.hereliesaz.geministrator.memory

/**
 * Strongest shared lexical/structural signal between two texts.
 *
 * This is used to keep Specialist 08 focused on semantic residue. A pair already explained by a
 * sufficiently strong *decisive* lexical feature does not need an embedding comparison merely to
 * rediscover the same relationship. Generic shared verbs/actions and short acronym-like code hints
 * remain useful association evidence, but deliberately do not suppress embeddings by themselves.
 */
data class MemoryLexicalPairEvidence(
    val kind: MemoryLexicalFeatureKind,
    val value: String,
    val weight: Float,
)

internal fun strongestMemoryLexicalPairEvidence(
    left: String,
    right: String,
    lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
): MemoryLexicalPairEvidence? {
    val leftFeatures = memoryLexicalFeatures(left, lexicon).features
    val rightByKey = memoryLexicalFeatures(right, lexicon).features.associateBy { it.kind to it.value }

    return leftFeatures.asSequence()
        .filter(MemoryLexicalFeature::canSuppressEmbedding)
        .mapNotNull { leftFeature ->
            val rightFeature = rightByKey[leftFeature.kind to leftFeature.value] ?: return@mapNotNull null
            val base = leftFeature.kind.semanticAssociationBaseWeight()
            val confidence = minOf(leftFeature.confidence, rightFeature.confidence)
            val weight = (base * confidence).coerceIn(0f, 1f)
            if (weight < MIN_STRONG_LEXICAL_PAIR_WEIGHT) null else MemoryLexicalPairEvidence(
                kind = leftFeature.kind,
                value = leftFeature.value,
                weight = weight,
            )
        }
        .maxByOrNull(MemoryLexicalPairEvidence::weight)
}

internal fun MemoryLexicalFeatureKind.semanticAssociationBaseWeight(): Float = when (this) {
    MemoryLexicalFeatureKind.CodeEntity -> 0.94f
    MemoryLexicalFeatureKind.CodeAction -> 0.90f
    MemoryLexicalFeatureKind.NounSense -> 0.90f
    MemoryLexicalFeatureKind.VerbSense -> 0.92f
    MemoryLexicalFeatureKind.SubjectVerbObject -> 0.86f
    MemoryLexicalFeatureKind.VerbObject -> 0.80f
    MemoryLexicalFeatureKind.SubjectVerb -> 0.74f
    MemoryLexicalFeatureKind.VerbClass -> 0.72f
    MemoryLexicalFeatureKind.VerbLemma -> 0.70f
    MemoryLexicalFeatureKind.NounLemma -> 0.58f
}

private fun MemoryLexicalFeature.canSuppressEmbedding(): Boolean = when (kind) {
    MemoryLexicalFeatureKind.CodeEntity -> value.isDecisiveCodeEntity()
    MemoryLexicalFeatureKind.NounSense,
    MemoryLexicalFeatureKind.VerbSense,
    MemoryLexicalFeatureKind.SubjectVerbObject,
    MemoryLexicalFeatureKind.VerbObject,
    -> true
    else -> false
}

private fun String.isDecisiveCodeEntity(): Boolean =
    length >= 6 || any { it == '.' || it == '/' || it == '_' || it == ':' || it == '#' || it == '(' }

internal const val MIN_STRONG_LEXICAL_PAIR_WEIGHT: Float = 0.72f
