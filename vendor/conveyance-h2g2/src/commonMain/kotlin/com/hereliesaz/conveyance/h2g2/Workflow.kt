package com.hereliesaz.conveyance.h2g2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2.contrastingText
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

/** Presentation-only state for a subject in an h2g2 workflow map. */
enum class H2g2WorkflowState {
    Pending,
    Ready,
    Active,
    Gate,
    Blocked,
    Complete,
    Failed,
}

/** Stable motion personalities hosts may assign explicitly to workflow subjects. */
enum class H2g2WorkflowMotion {
    Nod,
    Pendulum,
    Hover,
    Shimmy,
    Breathe,
    Orbit,
    Tilt,
    Scoot,
    Sway,
    Bob,
    Pulse,
    Skitter,
    Float,
    Wag,
}

/**
 * A generic subject in a process/mind-map. The host owns semantics; this library only renders the
 * route. [hueSeed] is identity, never state or rank. [motion] may be assigned explicitly when a
 * role or subject has a deliberate personality; otherwise [motionSeed] chooses one
 * deterministically so the same identity still moves the same way every time.
 *
 * [progress] is optional, normalized 0f..1f work progress. When present the node fills from the
 * bottom with its own identity colour instead of growing a detached progress indicator.
 */
data class H2g2WorkflowNode(
    val id: String,
    val label: String,
    val subtitle: String? = null,
    val hueSeed: String = id,
    val motionSeed: String = id,
    val motion: H2g2WorkflowMotion? = null,
    val state: H2g2WorkflowState = H2g2WorkflowState.Pending,
    val progress: Float? = null,
    val injected: Boolean = false,
    val detail: String? = null,
)

data class H2g2WorkflowEdge(
    val from: String,
    val to: String,
    val label: String? = null,
)

/** A topological rank. Multiple nodes in one band are rendered as a visible fork. */
data class H2g2WorkflowBand(
    val nodes: List<H2g2WorkflowNode>,
)

private val WorkflowEase = CubicBezierEasing(0f, .9f, .1f, 1f)
private val BandHeight = 126.dp
private val NodeInset = 10.dp
private const val SuperiorMotionShare = .22f
private const val MaxInheritedGenerations = 6

private enum class BandMotion {
    Lift,
    Cant,
    Stretch,
    Drift,
    Rock,
    Bounce,
}

private data class InheritedMotionSample(
    val motion: H2g2WorkflowMotion,
    val primary: Float,
    val secondary: Float,
    val strength: Float,
)

private fun motionOf(seed: String): H2g2WorkflowMotion =
    H2g2WorkflowMotion.entries[seed.hashCode().mod(H2g2WorkflowMotion.entries.size)]

private fun bandMotionOf(index: Int): BandMotion = BandMotion.entries[index.mod(BandMotion.entries.size)]

private fun H2g2WorkflowBand.isBeingSetUp(): Boolean = nodes.any {
    it.state == H2g2WorkflowState.Pending ||
        it.state == H2g2WorkflowState.Ready ||
        it.state == H2g2WorkflowState.Blocked ||
        it.state == H2g2WorkflowState.Gate
}

private fun inheritedStrength(depth: Int): Float {
    var strength = SuperiorMotionShare
    repeat(depth) { strength *= SuperiorMotionShare }
    return strength
}

private fun GraphicsLayerScope.applyWorkflowMotion(
    motion: H2g2WorkflowMotion,
    primary: Float,
    secondary: Float,
    strength: Float = 1f,
) {
    when (motion) {
        H2g2WorkflowMotion.Nod -> {
            rotationZ += primary * 2.4f * strength
            translationY += secondary * 3f * strength
        }
        H2g2WorkflowMotion.Pendulum -> {
            rotationZ += primary * 3.2f * strength
            if (strength == 1f) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 0f)
        }
        H2g2WorkflowMotion.Hover -> {
            translationY += primary * 6f * strength
            translationX += secondary * 2f * strength
        }
        H2g2WorkflowMotion.Shimmy -> {
            translationX += primary * 5f * strength
            rotationZ += secondary * 1.2f * strength
        }
        H2g2WorkflowMotion.Breathe -> {
            scaleX *= 1f + primary * .025f * strength
            scaleY *= 1f + primary * .025f * strength
        }
        H2g2WorkflowMotion.Orbit -> {
            translationX += primary * 5f * strength
            translationY += secondary * 5f * strength
            rotationZ += primary * .8f * strength
        }
        H2g2WorkflowMotion.Tilt -> {
            rotationZ += primary * 2.2f * strength
            scaleY *= 1f + secondary * .015f * strength
        }
        H2g2WorkflowMotion.Scoot -> {
            translationX += primary * 7f * strength
            scaleX *= 1f + secondary * .018f * strength
        }
        H2g2WorkflowMotion.Sway -> {
            translationX += primary * 4f * strength
            rotationZ += primary * 1.6f * strength
        }
        H2g2WorkflowMotion.Bob -> {
            translationY += primary * 7f * strength
            scaleY *= 1f - secondary.absoluteValue * .012f * strength
        }
        H2g2WorkflowMotion.Pulse -> {
            val pulse = primary.absoluteValue
            scaleX *= 1f + pulse * .035f * strength
            scaleY *= 1f + pulse * .035f * strength
        }
        H2g2WorkflowMotion.Skitter -> {
            translationX += (primary * 4f + secondary * 2f) * strength
            translationY += secondary * 2f * strength
            rotationZ += primary * .9f * strength
        }
        H2g2WorkflowMotion.Float -> {
            translationY += primary * 5f * strength
            rotationZ += secondary * 1.1f * strength
            scaleX *= 1f + secondary * .012f * strength
        }
        H2g2WorkflowMotion.Wag -> {
            rotationZ += primary * 2.8f * strength
            translationX += secondary * 2.5f * strength
            if (strength == 1f) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f)
        }
    }
}

private fun cubicPoint(
    t: Float,
    start: Offset,
    control1: Offset,
    control2: Offset,
    end: Offset,
): Offset {
    val u = 1f - t
    val uu = u * u
    val tt = t * t
    val uuu = uu * u
    val ttt = tt * t
    return Offset(
        x = uuu * start.x + 3f * uu * t * control1.x + 3f * u * tt * control2.x + ttt * end.x,
        y = uuu * start.y + 3f * uu * t * control1.y + 3f * u * tt * control2.y + ttt * end.y,
    )
}

/**
 * A flat vector workflow/mind-map for h2g2 applications.
 *
 * The whole map is alive. At rest it rocks very slowly as one object. Each topological band has a
 * distinct looping setup motion while unresolved, every subject owns a stable motion personality,
 * and small packets travel through the routes so relationships carry visible motion as well as
 * geometry. Those motions are repetitive enough to become recognizable but use different
 * periods/amplitudes so the composition does not lock into one mechanical beat.
 *
 * Subjects inherit motion through their workflow lineage. The first incoming edge identifies the
 * immediate superior. That superior contributes 22% of its personality, the grandparent contributes
 * 22% of that again, and so on. The effect fades rapidly while keeping a branch visibly related.
 * A subject's own personality always remains at full strength.
 *
 * Engagement changes the choreography instead of merely adding another highlight: selecting a
 * subject damps the large ambient rock and drift while leaving the subjects and route traffic alive.
 * The map therefore settles around the person's focus without becoming inert.
 *
 * The default view is intentionally not a stack of record tiles. Subjects float as identity-hued
 * vector lozenges on the Ground and are connected by thick cubic routes. Forks, joins, gates and
 * interruptions are spatial. Selecting a subject transforms that same subject in place and
 * reveals its detail beneath it; no replacement inspector is required.
 */
@Composable
fun H2g2WorkflowMap(
    bands: List<H2g2WorkflowBand>,
    edges: List<H2g2WorkflowEdge>,
    selectedId: String? = null,
    onNodeSelected: (H2g2WorkflowNode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (bands.isEmpty()) return

    val routeProgress = remember(bands, edges) { Animatable(0f) }
    LaunchedEffect(bands, edges) {
        routeProgress.snapTo(0f)
        routeProgress.animateTo(1f, tween(520, easing = WorkflowEase))
    }

    val ambient = rememberInfiniteTransition(label = "h2g2-workflow-ambient")
    val wholeRock by ambient.animateFloat(
        initialValue = -0.8f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-whole-rock",
    )
    val wholeDrift by ambient.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(11200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-whole-drift",
    )
    val routeBreath by ambient.animateFloat(
        initialValue = .92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(5400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-route-breath",
    )
    val routeTraffic by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "h2g2-route-traffic",
    )
    val ambientStrength by animateFloatAsState(
        targetValue = if (selectedId == null) 1f else .16f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "h2g2-workflow-engagement-damping",
    )

    val nodeLocations = remember(bands) {
        buildMap {
            bands.forEachIndexed { bandIndex, band ->
                band.nodes.forEachIndexed { nodeIndex, node ->
                    put(node.id, Triple(bandIndex, nodeIndex, band.nodes.size))
                }
            }
        }
    }
    val nodesById = remember(bands) {
        bands.flatMap { it.nodes }.associateBy { it.id }
    }
    val superiorIdByNode = remember(edges) {
        edges.groupBy { it.to }.mapValues { (_, incoming) -> incoming.first().from }
    }
    val lineageByNode = remember(bands, edges) {
        nodesById.keys.associateWith { nodeId ->
            buildList {
                val visited = mutableSetOf(nodeId)
                var cursor = superiorIdByNode[nodeId]
                while (cursor != null && size < MaxInheritedGenerations && visited.add(cursor)) {
                    nodesById[cursor]?.let(::add)
                    cursor = superiorIdByNode[cursor]
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(BandHeight * bands.size)
            .graphicsLayer {
                rotationZ = wholeRock * ambientStrength
                translationY = wholeDrift * ambientStrength
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val bandHeightPx = with(density) { BandHeight.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            edges.forEachIndexed { edgeIndex, edge ->
                val from = nodeLocations[edge.from] ?: return@forEachIndexed
                val to = nodeLocations[edge.to] ?: return@forEachIndexed
                val fromX = widthPx * ((from.second + 0.5f) / from.third)
                val toX = widthPx * ((to.second + 0.5f) / to.third)
                val fromY = bandHeightPx * (from.first + 0.58f)
                val toY = bandHeightPx * (to.first + 0.34f)
                val midY = (fromY + toY) / 2f
                val start = Offset(fromX, fromY)
                val control1 = Offset(fromX, midY)
                val control2 = Offset(toX, midY)
                val end = Offset(toX, toY)
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
                }
                val source = bands[from.first].nodes[from.second]
                val routeColor = H2g2.caps[H2g2.indexOf(source.hueSeed)]
                val stagger = (edgeIndex * 0.06f).coerceAtMost(.42f)
                val p = ((routeProgress.value - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                val settledBreath = 1f + (routeBreath - 1f) * ambientStrength
                val focusedRoute = selectedId != null && (edge.from == selectedId || edge.to == selectedId)
                if (p > 0f) {
                    drawPath(
                        path = path,
                        color = routeColor,
                        style = Stroke(width = 7.dp.toPx() * p * settledBreath, cap = StrokeCap.Round),
                    )
                    drawCircle(
                        color = routeColor,
                        radius = 4.dp.toPx() * p * settledBreath,
                        center = end,
                    )

                    repeat(3) { packetIndex ->
                        val t = (routeTraffic + edgeIndex * .137f + packetIndex * .31f) % 1f
                        val position = cubicPoint(t, start, control1, control2, end)
                        val packetAlpha = p * if (focusedRoute) .95f else .62f
                        val packetRadius = if (focusedRoute) 5.4.dp.toPx() else 4.2.dp.toPx()
                        drawCircle(
                            color = routeColor.copy(alpha = packetAlpha),
                            radius = packetRadius,
                            center = position,
                        )
                        drawCircle(
                            color = H2g2.white.copy(alpha = packetAlpha * .72f),
                            radius = packetRadius * .38f,
                            center = position,
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            bands.forEachIndexed { bandIndex, band ->
                WorkflowBand(
                    band = band,
                    bandIndex = bandIndex,
                    lineages = lineageByNode,
                    selectedId = selectedId,
                    onNodeSelected = onNodeSelected,
                )
            }
        }
    }
}

@Composable
private fun WorkflowBand(
    band: H2g2WorkflowBand,
    bandIndex: Int,
    lineages: Map<String, List<H2g2WorkflowNode>>,
    selectedId: String?,
    onNodeSelected: (H2g2WorkflowNode) -> Unit,
) {
    val motion = bandMotionOf(bandIndex)
    val transition = rememberInfiniteTransition(label = "h2g2-band-$bandIndex")
    val loop by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700 + bandIndex * 173, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-band-loop-$bandIndex",
    )
    val setup = band.isBeingSetUp()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BandHeight)
            .graphicsLayer {
                if (setup) {
                    when (motion) {
                        BandMotion.Lift -> translationY = loop * 5f
                        BandMotion.Cant -> rotationZ = loop * 1.15f
                        BandMotion.Stretch -> scaleX = 1f + loop.absoluteValue * .025f
                        BandMotion.Drift -> translationX = loop * 8f
                        BandMotion.Rock -> {
                            rotationZ = loop * .8f
                            translationX = loop * 3f
                        }
                        BandMotion.Bounce -> {
                            translationY = -loop.absoluteValue * 6f
                            scaleY = 1f + loop.absoluteValue * .018f
                        }
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        band.nodes.forEachIndexed { nodeIndex, node ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                H2g2WorkflowSubject(
                    node = node,
                    lineage = lineages[node.id].orEmpty(),
                    selected = node.id == selectedId,
                    arrivalDelayMs = 70L * bandIndex + 34L * nodeIndex,
                    onClick = { onNodeSelected(node) },
                    modifier = Modifier.padding(horizontal = NodeInset),
                )
            }
        }
    }
}

@Composable
private fun H2g2WorkflowSubject(
    node: H2g2WorkflowNode,
    lineage: List<H2g2WorkflowNode>,
    selected: Boolean,
    arrivalDelayMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hueIndex = H2g2.indexOf(node.hueSeed)
    val hue = H2g2.hues[hueIndex]
    val cap = H2g2.caps[hueIndex]
    val text = hue.contrastingText()
    val type = h2g2Type()
    val arrival = remember(node.id) { Animatable(0f) }
    val activeScale by animateFloatAsState(
        targetValue = when {
            selected -> 1.12f
            node.state == H2g2WorkflowState.Active -> 1.06f
            else -> 1f
        },
        animationSpec = tween(240, easing = WorkflowEase),
        label = "h2g2-workflow-subject-scale",
    )
    val progress by animateFloatAsState(
        targetValue = node.progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(360, easing = WorkflowEase),
        label = "h2g2-workflow-progress-${node.id}",
    )

    val personality = node.motion ?: motionOf(node.motionSeed)
    val hash = node.motionSeed.hashCode().absoluteValue
    val personalityTransition = rememberInfiniteTransition(label = "h2g2-node-${node.id}")
    val primary by personalityTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1450 + (hash % 1900), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-node-primary-${node.id}",
    )
    val secondary by personalityTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2100 + (hash % 2300), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-node-secondary-${node.id}",
    )
    val inherited = lineage.mapIndexed { depth, ancestor ->
        val ancestorHash = ancestor.motionSeed.hashCode().absoluteValue
        val ancestorPrimary = personalityTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1450 + (ancestorHash % 1900), easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "h2g2-node-ancestor-primary-${node.id}-$depth",
        ).value
        val ancestorSecondary = personalityTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2100 + (ancestorHash % 2300), easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "h2g2-node-ancestor-secondary-${node.id}-$depth",
        ).value
        InheritedMotionSample(
            motion = ancestor.motion ?: motionOf(ancestor.motionSeed),
            primary = ancestorPrimary,
            secondary = ancestorSecondary,
            strength = inheritedStrength(depth),
        )
    }
    val jello by personalityTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(560 + (hash % 260), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-node-jello-${node.id}",
    )

    LaunchedEffect(node.id) {
        delay(arrivalDelayMs)
        arrival.animateTo(1f, tween(320, easing = WorkflowEase))
    }

    Column(
        modifier = modifier
            .widthIn(min = 96.dp, max = 240.dp)
            .graphicsLayer {
                alpha = arrival.value
                translationY += (1f - arrival.value) * 42f
                rotationZ += (1f - arrival.value) * if (hueIndex % 2 == 0) -4f else 4f

                inherited.asReversed().forEach { inheritedMotion ->
                    applyWorkflowMotion(
                        motion = inheritedMotion.motion,
                        primary = inheritedMotion.primary,
                        secondary = inheritedMotion.secondary,
                        strength = inheritedMotion.strength,
                    )
                }
                applyWorkflowMotion(personality, primary, secondary)

                if (node.state == H2g2WorkflowState.Active) {
                    val wobble = jello * .028f
                    scaleX *= 1f + wobble
                    scaleY *= 1f - wobble * .72f
                    rotationZ += jello * .55f
                }

                scaleX *= activeScale
                scaleY *= activeScale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val selectedBackground = if (selected) H2g2.ink else hue
        val selectedText = if (selected) H2g2.hues[4] else text
        val radius = when (node.state) {
            H2g2WorkflowState.Gate -> 8.dp
            H2g2WorkflowState.Failed -> 16.dp
            else -> 999.dp
        }
        val progressOverlay = if (selected) H2g2.hues[4] else cap

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(radius))
                .background(selectedBackground)
                .drawWithContent {
                    if (node.progress != null) {
                        val top = size.height * (1f - progress)
                        drawRect(
                            color = progressOverlay.copy(alpha = if (selected) .24f else .42f),
                            topLeft = Offset(0f, top),
                            size = androidx.compose.ui.geometry.Size(size.width, size.height - top),
                        )
                    }
                    drawContent()
                }
                .clickable(onClick = onClick),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    text = node.label.uppercase(),
                    style = type.capsule.copy(color = selectedText, textAlign = TextAlign.Center),
                )
                if (node.subtitle != null) {
                    BasicText(
                        text = node.subtitle,
                        style = type.endCap.copy(color = selectedText, textAlign = TextAlign.Center),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(H2g2Surface.capsule)
                            .background(if (selected) H2g2.hues[4] else cap)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        BasicText(
                            text = node.state.name.uppercase(),
                            style = type.endCap.copy(color = if (selected) H2g2.ink else cap.contrastingText()),
                        )
                    }
                    if (node.injected) {
                        BasicText(
                            text = "AUTO",
                            style = type.endCap.copy(color = selectedText),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selected && node.detail != null,
            enter = expandVertically(tween(220, easing = WorkflowEase)) + fadeIn(tween(120)),
            exit = shrinkVertically(tween(190, easing = WorkflowEase)) + fadeOut(tween(90)),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .clip(H2g2Surface.note)
                    .background(H2g2.ink)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                BasicText(
                    text = node.detail.orEmpty(),
                    style = type.body.copy(color = H2g2.white, textAlign = TextAlign.Center),
                )
            }
        }
    }
}
