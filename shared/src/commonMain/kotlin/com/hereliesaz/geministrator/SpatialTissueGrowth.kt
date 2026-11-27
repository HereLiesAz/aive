package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Artist-tuned morphological strategies, not measured biological species or execution semantics. */
@Serializable
internal enum class SpatialGrowthStyle { Neural, Exploratory, Reticulate, Cord }

/** Persistent reference to a prior centerline sample or an actual organism's site. */
@Serializable
internal data class SpatialTissueAnchor(
    val point: SpatialPoint,
    val organismId: String? = null,
    val branchId: Long? = null,
    val sample: Int? = null,
)

/** The recorded centerline is authoritative; it must not be reconstructed from the visual seed. */
@Serializable
internal data class SpatialTissueBranch(
    val id: Long,
    val from: SpatialTissueAnchor,
    val to: SpatialTissueAnchor,
    val style: SpatialGrowthStyle,
    val points: List<SpatialPoint>,
)

@Serializable
internal data class SpatialGrowthEvent(val visitId: String, val branches: List<SpatialTissueBranch>)

/** Per-run morphological history. Appending an event never changes previously recorded geometry. */
@Serializable
internal data class SpatialTissueJournal(val runId: String, val events: List<SpatialGrowthEvent> = emptyList()) {
    val branches: List<SpatialTissueBranch> get() = events.flatMap { it.branches }
}

private fun spatialDot(a: SpatialPoint, b: SpatialPoint): Float = a.x * b.x + a.y * b.y + a.z * b.z

/** Exact distance between a centerline segment and an obstacle center. */
internal fun spatialSegmentClearance(a: SpatialPoint, b: SpatialPoint, point: SpatialPoint): Float {
    val ab = b - a
    val lengthSquared = spatialDot(ab, ab)
    if (lengthSquared <= .0000001f) return (point - a).length()
    val t = (spatialDot(point - a, ab) / lengthSquared).coerceIn(0f, 1f)
    return (point - (a + ab * t)).length()
}

private fun Random.direction(): SpatialPoint {
    val y = nextFloat() * 2f - 1f
    val angle = nextFloat() * (2f * PI.toFloat())
    val radius = sqrt((1f - y * y).coerceAtLeast(0f))
    return SpatialPoint(cos(angle) * radius, y, sin(angle) * radius)
}

/**
 * Directional growth cone adapted from the recovered prototype. Persistent tangent and correlated
 * exploration produce different strategies; route samples are checked against non-endpoint somas.
 * Failure is represented by no route rather than a fictitious strand through an obstacle.
 */
internal fun spatialGrowthPath(
    from: SpatialPoint, to: SpatialPoint, style: SpatialGrowthStyle, seed: Int,
    obstacles: List<SpatialPoint> = emptyList(),
): List<SpatialPoint>? {
    val span = (to - from).length()
    if (!span.isFinite() || span < .001f) return null
    val (attraction, persistence, exploration) = when (style) {
        SpatialGrowthStyle.Neural -> Triple(1.1f, .80f, .37f)
        SpatialGrowthStyle.Exploratory -> Triple(.85f, .95f, .70f)
        SpatialGrowthStyle.Reticulate -> Triple(1f, .55f, .52f)
        SpatialGrowthStyle.Cord -> Triple(1.35f, 1.1f, .20f)
    }
    val blockers = obstacles.filter { (it - from).length() > .55f && (it - to).length() > .55f }
    val step = maxOf(.18f, span / 28f)
    for (attempt in 0 until 12) {
        val rng = Random(seed xor (attempt * 7919))
        val points = mutableListOf(from)
        var p = from
        var tangent = (to - from).unit()
        var bias = rng.direction()
        var nextBias = rng.direction()
        for (i in 0 until 96) {
            val remaining = (to - p).length()
            if (remaining <= step * 1.15f) break
            if (i > 0 && i % 5 == 0) { bias = nextBias; nextBias = rng.direction() }
            val blend = (i % 5) / 5f
            val toward = (to - p).unit()
            var force = toward * attraction + tangent * persistence +
                (bias * (1f - blend) + nextBias * blend) * exploration
            for (obstacle in blockers) {
                val away = p - obstacle
                val d = away.length()
                if (d in .0001f..1.15f) force += away.unit() * (1.3f * (1f - d / 1.15f))
            }
            tangent = force.unit()
            val progress = spatialDot(tangent, toward)
            if (progress < .48f) tangent = (tangent + toward * (1.48f - progress)).unit()
            p += tangent * minOf(step, remaining * .65f)
            points += p
        }
        points += to
        if (points.all(SpatialPoint::finite) && points.zipWithNext().all { (a, b) ->
                blockers.all { spatialSegmentClearance(a, b, it) >= .48f }
            }) return points
    }
    return null
}

/**
 * Add one recorded visit, idempotent in [visitId]. Reach new organism sites first, using the
 * nearest available root or prior tissue sample. Later visits attempt a new lateral fusion.
 * This geometry is decorative and does not assert communication, dependency, recall or work.
 */
internal fun growSpatialTissue(
    journal: SpatialTissueJournal,
    visitId: String,
    scene: SpatialHiveScene,
): SpatialTissueJournal {
    require(journal.runId == scene.runId && visitId.isNotBlank())
    if (journal.events.any { it.visitId == visitId }) return journal
    val previous = journal.branches
    val added = mutableListOf<SpatialTissueBranch>()
    val anchors = mutableListOf(SpatialTissueAnchor(scene.nucleus.position, organismId = scene.nucleus.id))
    fun register(branch: SpatialTissueBranch) {
        branch.points.forEachIndexed { index, point ->
            if (index > 0) anchors += SpatialTissueAnchor(point, branchId = branch.id, sample = index)
        }
    }
    previous.forEach(::register)
    val sites = scene.creatures.filter { it.form != SpatialForm.WitheredEgg }
        .map { SpatialTissueAnchor(it.position, organismId = it.id) }
    val obstacles = listOf(scene.nucleus.position) + sites.map { it.point }
    val occupied = previous.mapNotNullTo(mutableSetOf()) { it.to.organismId }
    var nextId = (previous.maxOfOrNull { it.id } ?: 0L) + 1L
    val rng = Random(spatialSeed(journal.runId + "\u0000" + visitId))
    fun append(from: SpatialTissueAnchor, to: SpatialTissueAnchor, style: SpatialGrowthStyle): Boolean {
        repeat(4) {
            val path = spatialGrowthPath(from.point, to.point, style, rng.nextInt(), obstacles) ?: return@repeat
            if ((previous + added).none { it.points == path || it.points == path.asReversed() }) {
                val branch = SpatialTissueBranch(nextId++, from, to, style, path)
                added += branch
                register(branch)
                return true
            }
        }
        return false
    }
    val pending = sites.filter { it.organismId !in occupied }.toMutableList()
    while (pending.isNotEmpty()) {
        val candidate = pending.minBy { site -> anchors.minOf { (it.point - site.point).length() } }
        pending.remove(candidate)
        val style = SpatialGrowthStyle.entries[(previous.size + added.size) % SpatialGrowthStyle.entries.size]
        for (root in anchors.sortedBy { (it.point - candidate.point).length() }.take(12)) {
            if (append(root, candidate, style)) break
        }
    }
    if (added.isEmpty() && anchors.size > 1) {
        val origins = anchors.shuffled(rng).take(32)
        for (origin in origins) {
            val ends = anchors.filter { end ->
                end != origin && (end.point - origin.point).length() in .65f..3.5f
            }.sortedBy { if (it.branchId != origin.branchId) 0 else 1 }
            if (ends.take(12).any { append(origin, it, SpatialGrowthStyle.Reticulate) }) break
        }
    }
    return journal.copy(events = journal.events + SpatialGrowthEvent(visitId, added.toList()))
}

/** Run-scoped, generation-chunked presentation storage. No writes to workflow or memory stores. */
internal class SpatialTissueStore(settings: Settings) {
    private val storage = ChunkedStringSettings.unbounded(settings)
    private fun key(runId: String) = "aive.st.v1." + Sha256.hex(runId.encodeToByteArray()).take(24)

    fun load(runId: String): SpatialTissueJournal {
        val journal = storage.getStringOrNull(key(runId))?.let { Json.decodeFromString<SpatialTissueJournal>(it) }
            ?: SpatialTissueJournal(runId)
        require(journal.runId == runId) { "Spatial tissue key collision" }
        return journal
    }

    /** The owner of a scene session supplies a distinct visit id once per actual opening. */
    fun advance(scene: SpatialHiveScene, visitId: String): SpatialTissueJournal {
        val previous = load(scene.runId)
        val next = growSpatialTissue(previous, visitId, scene)
        if (next != previous) storage.putString(key(scene.runId), Json.encodeToString(next))
        return next
    }
}
