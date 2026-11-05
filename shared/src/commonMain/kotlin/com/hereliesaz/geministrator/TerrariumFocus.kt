package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition

/*
 * The semantic focus stack (docs/Keepers-Room.md, "Zoom levels"): instead of a free zoom, the camera
 * steps through views of the hive that mean something, from the selected creature outwards. Pure
 * logic: the graph helpers, the level list, how the stack is walked, and the camera fit. The
 * terrarium only applies the result.
 *
 * A strand runs from a feeder to the creature it feeds; "up" follows strands backwards (toward the
 * orchestrator), "down" follows them forwards (toward an end goal, a creature nothing else waits on).
 */

/** The views, most zoomed-in first; [Overview] is always the last. */
internal enum class FocusLevel { Creature, Neighbours, PathToOrchestrator, PathToEnd, PathToActive, PathToBlocks, WholeChain, Overview }

internal data class FocusStep(val level: FocusLevel, val nodes: Set<String>)

/** The hive as feeders and fed: [edges] are (feeder, fed) pairs between the ids in [nodes]. */
internal class FocusGraph(val nodes: Set<String>, edges: Collection<Pair<String, String>>) {
    private val ups: Map<String, Set<String>>
    private val downs: Map<String, Set<String>>

    init {
        val valid = edges.filter { (a, b) -> a in nodes && b in nodes && a != b }
        ups = valid.groupBy({ it.second }, { it.first }).mapValues { it.value.toSet() }
        downs = valid.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    fun feeders(id: String): Set<String> = ups[id].orEmpty()
    fun fed(id: String): Set<String> = downs[id].orEmpty()

    /** The orchestrator: the first of [orchestrators] in the hive, else the entry creature (nothing feeds it). */
    fun orchestrator(orchestrators: Collection<String>): String? =
        orchestrators.filter { it in nodes }.minOrNull()
            // An entry that feeds something beats a creature with no strands at all.
            ?: nodes.filter { ups[it].isNullOrEmpty() }.sortedWith(compareBy({ downs[it].isNullOrEmpty() }, { it })).firstOrNull()

    /** The shortest path, [from] first, following strands back (or forward) to the nearest of [goals]; null if none. */
    fun directedPath(from: String, goals: Set<String>, forward: Boolean): List<String>? =
        bfs(from, goals) { id -> (if (forward) downs[id] else ups[id]).orEmpty() }

    /** The shortest path over strands in either direction to the nearest of [goals]; null if none. */
    fun undirectedPath(from: String, goals: Set<String>): List<String>? =
        bfs(from, goals) { id -> ups[id].orEmpty() + downs[id].orEmpty() }

    fun ancestors(id: String): Set<String> = reach(id) { ups[it].orEmpty() }
    fun descendants(id: String): Set<String> = reach(id) { downs[it].orEmpty() }

    private fun bfs(from: String, goals: Set<String>, next: (String) -> Set<String>): List<String>? {
        if (from !in nodes) return null
        val parent = mutableMapOf<String, String?>(from to null)
        val queue = ArrayDeque<String>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at != from && at in goals) {
                return generateSequence(at) { parent[it] }.toList().reversed()
            }
            next(at).sorted().forEach { n -> if (n !in parent) { parent[n] = at; queue.add(n) } }
        }
        return null
    }

    private fun reach(id: String, next: (String) -> Set<String>): Set<String> {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        while (stack.isNotEmpty()) {
            next(stack.removeLast()).forEach { if (it != id && seen.add(it)) stack.add(it) }
        }
        return seen
    }
}

/**
 * The ordered views for [selected] (one creature, or a cluster treated as one), skipping any that is
 * empty or shows the same creatures as the one before it. [orchestrators] are the ids wearing the
 * orchestrator role; [active] are creatures running now, [blocked] those waiting on a person or failed.
 * With nothing selected there is only the Overview.
 */
internal fun focusLevels(
    graph: FocusGraph,
    selected: Set<String>,
    orchestrators: Collection<String>,
    active: Set<String>,
    blocked: Set<String>,
): List<FocusStep> {
    val overview = FocusStep(FocusLevel.Overview, graph.nodes)
    val picked = selected.filter { it in graph.nodes }.toSet()
    if (picked.isEmpty()) return listOf(overview)

    fun union(per: (String) -> Collection<String>?): Set<String> = picked.flatMapTo(mutableSetOf()) { per(it).orEmpty() }
    val root = graph.orchestrator(orchestrators)

    val toOrchestrator = if (root == null) emptySet() else union { graph.directedPath(it, setOf(root), forward = false) }
    val terminals = graph.nodes.filter { graph.fed(it).isEmpty() }.toSet()
    val toEnd = union { graph.directedPath(it, terminals, forward = true) }
    // The shorter of the two goes first; a tie favours the orchestrator.
    val upFirst = toOrchestrator.isNotEmpty() && (toEnd.isEmpty() || toOrchestrator.size <= toEnd.size)
    val (firstPath, secondPath) = if (upFirst) {
        (FocusLevel.PathToOrchestrator to toOrchestrator) to (FocusLevel.PathToEnd to toEnd)
    } else {
        (FocusLevel.PathToEnd to toEnd) to (FocusLevel.PathToOrchestrator to toOrchestrator)
    }

    val raw = listOf(
        FocusStep(FocusLevel.Creature, picked),
        FocusStep(FocusLevel.Neighbours, picked + union { graph.feeders(it) + graph.fed(it) }),
        FocusStep(firstPath.first, firstPath.second),
        FocusStep(secondPath.first, secondPath.second),
        FocusStep(FocusLevel.PathToActive, union { graph.undirectedPath(it, active - picked) }),
        FocusStep(FocusLevel.PathToBlocks, union { graph.undirectedPath(it, blocked - picked) }),
        FocusStep(FocusLevel.WholeChain, picked + union { graph.ancestors(it) + graph.descendants(it) }),
        overview,
    )
    val kept = mutableListOf<FocusStep>()
    raw.forEach { step ->
        if (step.nodes.isEmpty()) return@forEach
        if (kept.lastOrNull()?.nodes == step.nodes) {
            // The Overview is the one that must stay.
            if (step.level == FocusLevel.Overview) kept[kept.lastIndex] = step
            return@forEach
        }
        kept += step
    }
    return kept
}

/** Where the camera is in a list of [steps]; index 0 is the most zoomed-in. */
internal data class FocusStack(val steps: List<FocusStep> = listOf(FocusStep(FocusLevel.Overview, emptySet())), val index: Int = 0) {
    val current: FocusStep get() = steps[index.coerceIn(steps.indices)]
    val canStepOut: Boolean get() = index < steps.lastIndex
    val canStepIn: Boolean get() = index > 0

    /** One level toward the whole hive; unchanged at the Overview. */
    fun stepOut(): FocusStack = if (canStepOut) copy(index = index + 1) else this

    /** One level back in, through the levels already passed; unchanged at the first. */
    fun stepIn(): FocusStack = if (canStepIn) copy(index = index - 1) else this

    /** A new selection starts at its first level. */
    fun selected(steps: List<FocusStep>): FocusStack = FocusStack(steps, 0)

    /** The same selection with its levels recomputed (the run moved on): stay on the same kind of view if it still exists. */
    fun refreshed(steps: List<FocusStep>): FocusStack {
        val same = steps.indexOfFirst { it.level == current.level }
        return FocusStack(steps, if (same >= 0) same else index.coerceIn(steps.indices))
    }
}

/** Where to put the camera so [positions] fit: zoom, and the point to centre on (fractions of the field). */
internal data class CameraFit(val zoom: Float, val focus: Offset)

/**
 * Fits the box around [positions] with [margin] (about half a creature) and [padding] (a fraction of
 * the box) on every side. A lone creature is shown with at least [minExtent] of context. Zoom stays
 * within [minZoom]..[maxZoom], and never below [floorZoom], the Overview's, so no view is wider than it.
 */
internal fun fitCamera(
    positions: Collection<H2g2TerrariumPosition>,
    floorZoom: Float = TerrariumCamera.MIN_ZOOM,
    margin: Float = 0.12f,
    padding: Float = 0.15f,
    minExtent: Float = 0.32f,
    minZoom: Float = TerrariumCamera.MIN_ZOOM,
    maxZoom: Float = TerrariumCamera.MAX_ZOOM,
): CameraFit {
    if (positions.isEmpty()) return CameraFit(1f, Offset(0.5f, 0.5f))
    val minX = positions.minOf { it.x }
    val maxX = positions.maxOf { it.x }
    val minY = positions.minOf { it.y }
    val maxY = positions.maxOf { it.y }
    val extent = maxOf(maxX - minX, maxY - minY, minExtent)
    val visible = extent * (1f + 2f * padding) + 2f * margin
    val zoom = (1f / visible).coerceIn(maxOf(minZoom, floorZoom), maxZoom)
    return CameraFit(zoom, Offset((minX + maxX) / 2f, (minY + maxY) / 2f))
}

/** The Overview's fit: the whole hive, never closer than 1.3x or farther than the old widest view. */
internal fun overviewFit(positions: Collection<H2g2TerrariumPosition>): CameraFit =
    fitCamera(positions, maxZoom = 1.3f)

/** The selection that survives removing creatures: only ids still in the hive, so no focus is left on a removed creature. */
internal fun selectionAfterRemoval(selection: Set<String>, remaining: Set<String>): Set<String> = selection.filterTo(linkedSetOf()) { it in remaining }
