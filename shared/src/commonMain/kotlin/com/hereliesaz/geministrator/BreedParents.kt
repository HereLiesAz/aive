package com.hereliesaz.geministrator

import kotlin.math.sqrt

/**
 * Who parents a hatch (docs/Node-Creature-Genome.md, Breeds).
 *
 * A hatch has up to [MAX_PARENTS] parents, one for each part locus a creature has (body, tendrils, tail).
 * They are chosen from the creatures that did the run's work, not simply the two that did the most of it:
 * the sets of parents are ranked the way "The Alien Space of Science" (arXiv:2603.01092) ranks idea
 * combinations, by how well they cohere (a pair that fits) and how seldom this stable has bred them
 * together (a combination nobody here has made). Both are z-scored across the sets on offer and weighted
 * [COHERENCE_WEIGHT] to [UNAVAILABILITY_WEIGHT]. Nothing here is random, and nothing here is shown to the
 * keeper: pairing stays hidden.
 */
internal const val MAX_PARENTS = 3

/** Only the creatures that did the most work are considered, so the sets to rank stay few (C(6,3) = 20). */
private const val POOL = 6

private const val COHERENCE_WEIGHT = 0.3
private const val UNAVAILABILITY_WEIGHT = 0.7

/** A set of parents whose mean pair coherence is below this is chosen only when no other set is above it. */
private const val COHERENCE_FLOOR = 0.3

/** A pair at or above this counts as a good pair for the next generation's pace. */
internal const val PAIRED = 0.6

/** A creature offered as a parent: its key and the breed each of its parts comes from. */
internal data class Parent(val key: String, val body: String, val tendrils: String, val tail: String)

/**
 * How well two breeds fit: the same breed 1.0, the same genus 0.8, one lists the other in `pairsWith` 0.6,
 * any other pair (or a breed the catalog does not know) 0.1.
 */
internal fun pairCoherence(a: String, b: String, catalog: BreedCatalog): Double {
    val x = catalog[a] ?: return 0.1
    val y = catalog[b] ?: return 0.1
    return when {
        a == b -> 1.0
        x.genus == y.genus -> 0.8
        b in x.pairsWith || a in y.pairsWith -> 0.6
        else -> 0.1
    }
}

/** How many creatures this stable has hatched from each pair of breeds. */
internal fun Stable.pairings(): Map<Set<String>, Int> = bred
    .flatMap { child -> child.parents.distinct().pairs().map { (x, y) -> setOf(x, y) } }
    .groupingBy { it }
    .eachCount()

/**
 * The parents of a hatch, from [candidates] (most work first): up to [MAX_PARENTS], in the order they were
 * offered. One candidate is its own parent.
 */
internal fun Stable.chooseParents(candidates: List<Parent>, catalog: BreedCatalog): List<Parent> {
    val pool = candidates.take(POOL)
    val size = minOf(MAX_PARENTS, pool.size)
    if (size <= 1) return pool
    val seen = pairings()
    val sets = pool.combinations(size).map { set ->
        val pairs = set.pairs()
        Ranked(
            set,
            coherence = pairs.map { (x, y) -> pairCoherence(x.body, y.body, catalog) }.average(),
            unavailability = -pairs.map { (x, y) -> seen[setOf(x.body, y.body)] ?: 0 }.average(),
        )
    }
    val viable = sets.filter { it.coherence >= COHERENCE_FLOOR }.ifEmpty { sets }
    val coherence = viable.map { it.coherence }.zScores()
    val unavailability = viable.map { it.unavailability }.zScores()
    // Ties go to the earliest set, which holds the creatures that did the most work.
    val best = viable.indices.maxByOrNull { COHERENCE_WEIGHT * coherence[it] + UNAVAILABILITY_WEIGHT * unavailability[it] } ?: 0
    return viable[best].set
}

private class Ranked(val set: List<Parent>, val coherence: Double, val unavailability: Double)

private fun List<Double>.zScores(): List<Double> {
    val mean = average()
    val deviation = sqrt(sumOf { (it - mean) * (it - mean) } / size)
    return if (deviation == 0.0) map { 0.0 } else map { (it - mean) / deviation }
}

internal fun <T> List<T>.pairs(): List<Pair<T, T>> =
    indices.flatMap { i -> (i + 1 until size).map { j -> this[i] to this[j] } }

/** Every [size]-long selection, each in the original order, earliest first. */
private fun <T> List<T>.combinations(size: Int): List<List<T>> = when {
    size == 0 -> listOf(emptyList())
    size > this.size -> emptyList()
    else -> indices.flatMap { i -> drop(i + 1).combinations(size - 1).map { listOf(this[i]) + it } }
}

/** The list turned [by] places, so each part locus is offered first to a different parent. */
internal fun <T> List<T>.rotated(by: Int): List<T> = if (isEmpty()) this else (by % size).let { drop(it) + take(it) }
