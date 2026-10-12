package com.hereliesaz.conveyance

import kotlin.math.hypot

/**
 * A behavioral role inferred from what an element actually does on the live surface.
 *
 * These are deliberately not component classes and not Employment categories. They are the middle
 * vocabulary between raw observations (`Job`, offered Act, Gate address, consequence target) and an
 * SDK construction. That keeps the recommendation engine from becoming a lookup table of visual
 * object names.
 */
enum class BehavioralRole {
    ActionSource,
    ProgressReporter,
    CompletionReporter,
    Interruptible,
    StatusReporter,
    IdentityCarrier,
    GateResolver,
    Destination,
    Locator,
    Navigator,
    GroupContainer,
}

/** Infer behavioral roles from facts already present in an [AuditFrame]. */
fun AuditElement.behavioralRoles(
    gateAddresses: Set<ElementId> = emptySet(),
    targets: Set<ElementId> = emptySet(),
): Set<BehavioralRole> = buildSet {
    if (act != null || Job.Invite in jobs) add(BehavioralRole.ActionSource)
    if (Job.Progress in jobs) add(BehavioralRole.ProgressReporter)
    if (Job.Confirm in jobs) add(BehavioralRole.CompletionReporter)
    if (Job.Interrupt in jobs) add(BehavioralRole.Interruptible)
    if (Job.Report in jobs) add(BehavioralRole.StatusReporter)
    if (Job.Identify in jobs) add(BehavioralRole.IdentityCarrier)
    if (id in gateAddresses) add(BehavioralRole.GateResolver)
    if (Job.Receive in jobs || id in targets) add(BehavioralRole.Destination)
    if (Job.Locate in jobs) add(BehavioralRole.Locator)
    if (Job.Navigate in jobs || verb == Verb.Enter || verb == Verb.Reveal) add(BehavioralRole.Navigator)
    if (Job.Group in jobs) add(BehavioralRole.GroupContainer)
}

/**
 * A composable-shaped replacement the linter knows how to recommend.
 *
 * Core knows only the behavior a shipped SDK primitive absorbs; it does not depend on Compose.
 * [absorbs] remains useful evidence and compatibility vocabulary, while [roles] is the semantic
 * matching layer used by the standard recipes. Relational constraints are explicit as well: some
 * constructions are not just a bag of behaviors, but behaviors that must belong to one identity.
 */
data class ComposableRecipe(
    val name: String,
    val absorbs: Set<Job>,
    val minFragments: Int = 2,
    val maxFragments: Int = 4,
    val docsAnchor: String,
    val roles: Set<BehavioralRole> = emptySet(),
    val maxOfferedActs: Int? = null,
    val requiresSharedLifecycle: Boolean = false,
) {
    /** Direct documentation for the SDK construction the recommendation names. */
    val docsUrl: String
        get() = "https://github.com/HereLiesAz/Conveyance/blob/main/conveyance-compose/README.md#$docsAnchor"
}

/** The standard replacement vocabulary shipped by the Conveyance SDK. */
object ConveyanceRecipes {
    val Offer = ComposableRecipe(
        name = "Offer",
        absorbs = setOf(Job.Invite, Job.Progress, Job.Confirm, Job.Interrupt),
        docsAnchor = "offer",
        roles = setOf(
            BehavioralRole.ActionSource,
            BehavioralRole.ProgressReporter,
            BehavioralRole.CompletionReporter,
            BehavioralRole.Interruptible,
        ),
        maxOfferedActs = 1,
        requiresSharedLifecycle = true,
    )

    val Form = ComposableRecipe(
        name = "Form",
        absorbs = setOf(Job.Group, Job.Report, Job.Progress, Job.Confirm),
        docsAnchor = "form",
        roles = setOf(
            BehavioralRole.GroupContainer,
            BehavioralRole.StatusReporter,
            BehavioralRole.ProgressReporter,
            BehavioralRole.CompletionReporter,
        ),
    )

    val Collection = ComposableRecipe(
        name = "Collection",
        absorbs = setOf(Job.Group, Job.Identify, Job.Locate, Job.Report),
        docsAnchor = "collection",
        roles = setOf(
            BehavioralRole.GroupContainer,
            BehavioralRole.IdentityCarrier,
            BehavioralRole.Locator,
            BehavioralRole.StatusReporter,
        ),
    )

    val Places = ComposableRecipe(
        name = "Places",
        absorbs = setOf(Job.Locate, Job.Navigate, Job.Group, Job.Identify),
        docsAnchor = "places",
        roles = setOf(
            BehavioralRole.Navigator,
            BehavioralRole.Locator,
            BehavioralRole.GroupContainer,
            BehavioralRole.IdentityCarrier,
        ),
    )

    val all: List<ComposableRecipe> = listOf(Offer, Form, Collection, Places)
}

/** A surface-level suggestion derived from several elements together. */
data class ConsolidationSuggestion(
    val elements: List<ElementId>,
    val combinedJobs: Set<Job>,
    val replacement: ComposableRecipe? = null,
    val combinedRoles: Set<BehavioralRole> = emptySet(),
) {
    fun message(): String = if (replacement != null) {
        val behavior = if (combinedRoles.isNotEmpty()) {
            combinedRoles.joinToString()
        } else {
            combinedJobs.joinToString()
        }
        "Replace ${elements.joinToString { it.value }} with a single Conveyance ${replacement.name}; " +
            "together they already behave as $behavior. ${replacement.docsUrl}"
    } else {
        "Combine ${elements.joinToString { it.value }} into one richer interface object; together they already do " +
            "${combinedJobs.size} jobs: ${combinedJobs.joinToString()}."
    }
}

/**
 * Looks across the whole surface instead of ticketing each under-employed element in isolation.
 *
 * The pipeline is intentionally three-stage:
 *
 * observed facts -> behavioral roles and relations -> SDK construction
 *
 * Candidates still need spatial relationship so unrelated controls are not combined merely because
 * their job sets happen to complement one another. A role-based recipe match wins over a raw-job
 * fallback. Relations already present in the graph constrain matches too: an `Offer` recommendation
 * requires its lifecycle fragments to be proven members of the same Act lifecycle.
 */
object ConsolidationAdvisor {
    private val lifecycleJobs = setOf(Job.Progress, Job.Confirm, Job.Report, Job.Interrupt)

    fun suggest(
        frame: AuditFrame,
        recipes: List<ComposableRecipe> = ConveyanceRecipes.all,
    ): List<ConsolidationSuggestion> {
        val candidates = frame.elements.filter { !it.ambient && it.jobs.isNotEmpty() && it.jobs.size < 4 }
        if (candidates.size < 2) return emptyList()

        val targets = frame.elements.mapNotNullTo(mutableSetOf()) { it.target }
        val groups = mutableListOf<List<AuditElement>>()
        for (size in 2..minOf(4, candidates.size)) {
            combinations(candidates, size).forEach { group ->
                val jobs = group.flatMapTo(mutableSetOf()) { it.jobs }
                if (jobs.size >= 4 && spatiallyRelated(group)) groups += group
            }
        }

        val suggestions = groups.map { group ->
            val jobs = group.flatMapTo(mutableSetOf()) { it.jobs }
            val roles = group.flatMapTo(mutableSetOf()) {
                it.behavioralRoles(frame.gateAddresses, targets)
            }
            val offeredActs = group.mapNotNullTo(mutableSetOf()) { it.act }
            val recipe = recipes
                .filter { group.size in it.minFragments..it.maxFragments }
                .filter { candidate ->
                    candidate.maxOfferedActs == null || offeredActs.size <= candidate.maxOfferedActs
                }
                .filter { candidate ->
                    !candidate.requiresSharedLifecycle || sharesOneLifecycle(group)
                }
                .filter { candidate ->
                    if (candidate.roles.isNotEmpty()) {
                        roles.containsAll(candidate.roles)
                    } else {
                        jobs.containsAll(candidate.absorbs)
                    }
                }
                .maxWithOrNull(
                    compareBy<ComposableRecipe> { it.roles.size }
                        .thenBy { it.absorbs.size },
                )
            ConsolidationSuggestion(
                elements = group.map { it.id },
                combinedJobs = jobs,
                replacement = recipe,
                combinedRoles = roles,
            )
        }.sortedWith(
            compareByDescending<ConsolidationSuggestion> { it.replacement != null }
                .thenBy { it.elements.size },
        )

        val used = mutableSetOf<ElementId>()
        return buildList {
            suggestions.forEach { suggestion ->
                if (suggestion.elements.none { it in used }) {
                    add(suggestion)
                    used += suggestion.elements
                }
            }
        }
    }

    private fun sharesOneLifecycle(elements: List<AuditElement>): Boolean {
        val offeredActs = elements.mapNotNullTo(mutableSetOf()) { it.act }
        if (offeredActs.size != 1) return false
        val lifecycle = offeredActs.single()

        return elements
            .filter { element -> element.jobs.any { it in lifecycleJobs } }
            .all { element -> element.act == lifecycle || element.lifecycleAct == lifecycle }
    }

    private fun spatiallyRelated(elements: List<AuditElement>): Boolean {
        val centers = elements.map { (it.left + it.width / 2f) to (it.top + it.height / 2f) }
        val scale = elements.map { maxOf(it.width, it.height, 1f) }.average().toFloat()
        val maxDistance = scale * 8f
        return centers.indices.all { a ->
            centers.indices.any { b ->
                a != b && hypot(
                    (centers[a].first - centers[b].first).toDouble(),
                    (centers[a].second - centers[b].second).toDouble(),
                ) <= maxDistance
            }
        }
    }

    private fun <T> combinations(items: List<T>, size: Int): List<List<T>> {
        val out = mutableListOf<List<T>>()
        fun walk(start: Int, current: MutableList<T>) {
            if (current.size == size) {
                out += current.toList()
                return
            }
            for (index in start until items.size) {
                current += items[index]
                walk(index + 1, current)
                current.removeAt(current.lastIndex)
            }
        }
        walk(0, mutableListOf())
        return out
    }
}
