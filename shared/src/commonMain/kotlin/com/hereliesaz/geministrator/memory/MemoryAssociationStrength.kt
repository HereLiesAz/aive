package com.hereliesaz.geministrator.memory

/**
 * Accumulates independent associative evidence with diminishing returns.
 *
 * For evidence weights w1..wn the combined strength is:
 *
 *     1 - Π(1 - wi)
 *
 * Equal repeated evidence therefore follows the saturating exponential curve
 * `1 - (1 - w)^n`: it strengthens indefinitely toward 1.0, but each additional supporting
 * association contributes less than the previous one. This is deliberately not linear addition.
 */
internal fun accumulateAssociationStrength(weights: Iterable<Float>): Float {
    var complement = 1.0
    var sawEvidence = false
    for (weight in weights) {
        require(weight in 0f..1f) { "Association evidence weight must be normalized" }
        if (weight <= 0f) continue
        sawEvidence = true
        complement *= 1.0 - weight.toDouble()
    }
    if (!sawEvidence) return 0f
    return (1.0 - complement).toFloat().coerceIn(0f, 1f)
}

internal fun MemoryRelationKind.isAssociativeEvidence(): Boolean =
    this == MemoryRelationKind.SimilarTo || this == MemoryRelationKind.AssociatedWith

internal fun weightedTraversalScore(edgeStrength: Float, depth: Int): Float {
    require(edgeStrength in 0f..1f)
    require(depth >= 0)
    val hopAttenuation = 1f / (1f + depth * 0.35f)
    return (edgeStrength * hopAttenuation).coerceIn(0f, 1f)
}
