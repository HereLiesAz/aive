package com.hereliesaz.geministrator

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.snap
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2SwarmAdornment
import com.hereliesaz.conveyance.h2g2.H2g2SwarmAdornmentLayer
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationship
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumServiceLayer
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumServiceVisit
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumSubject
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowNode
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import com.hereliesaz.geministrator.puppet.PuppetPoseBuffer
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.semantics_capillary
import com.hereliesaz.geministrator.resources.semantics_move_node_down
import com.hereliesaz.geministrator.resources.semantics_move_node_left
import com.hereliesaz.geministrator.resources.semantics_move_node_right
import com.hereliesaz.geministrator.resources.semantics_move_node_up
import com.hereliesaz.geministrator.resources.focus_chain
import com.hereliesaz.geministrator.resources.focus_creature
import com.hereliesaz.geministrator.resources.focus_free
import com.hereliesaz.geministrator.resources.focus_neighbours
import com.hereliesaz.geministrator.resources.focus_overview
import com.hereliesaz.geministrator.resources.focus_path_active
import com.hereliesaz.geministrator.resources.focus_path_blocks
import com.hereliesaz.geministrator.resources.focus_path_end
import com.hereliesaz.geministrator.resources.focus_path_orchestrator
import com.hereliesaz.geministrator.resources.focus_recentre
import com.hereliesaz.geministrator.resources.terrarium_zoom_in
import com.hereliesaz.geministrator.resources.terrarium_zoom_out
import org.jetbrains.compose.resources.stringResource

/**
 * Production workflow-mascot host.
 *
 * The terrarium draws every node as a puppet-rig creature ([NodeMascotSurface]) directly in
 * Compose; Compose owns creature rendering, workflow placement, interaction, labels, animation
 * and relationship links.
 */
@Composable
internal fun PlatformNodeTerrarium(
    subjects: List<H2g2TerrariumSubject>,
    relationships: List<H2g2TerrariumRelationship>,
    adornments: Map<String, List<H2g2SwarmAdornment>>,
    serviceVisits: List<H2g2TerrariumServiceVisit>,
    selectedId: String?,
    onNodeSelected: (H2g2WorkflowNode) -> Unit,
    onNodeMoved: (String, H2g2TerrariumPosition) -> Unit,
    onNodeDroppedOn: ((String, String) -> Unit)?,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    compact: Boolean = false,
    tissue: TissueScene? = null,
    onTetherDrawn: ((String, String) -> Unit)? = null,
    onTetherSliced: ((String, String) -> Unit)? = null,
    onSurface: (() -> Unit)? = null,
    /** A tap on bare ground (no drag): the hive lets go of its selection. */
    onGroundTapped: (() -> Unit)? = null,
    /** Zoom and focus held outside, for a controls bar that drives them; its own zoom buttons hide. */
    camera: TerrariumCamera? = null,
) {
    MascotNodeTerrarium(
        subjects = subjects,
        relationships = relationships,
        adornments = adornments,
        serviceVisits = serviceVisits,
        selectedId = selectedId,
        onNodeSelected = onNodeSelected,
        onNodeMoved = onNodeMoved,
        onNodeDroppedOn = onNodeDroppedOn,
        modifier = modifier,
        editable = editable,
        compact = compact,
        tissue = tissue,
        onTetherDrawn = onTetherDrawn,
        onTetherSliced = onTetherSliced,
        onSurface = onSurface,
        onGroundTapped = onGroundTapped,
        camera = camera,
    )
}

@Composable
private fun MascotNodeTerrarium(
    subjects: List<H2g2TerrariumSubject>,
    relationships: List<H2g2TerrariumRelationship>,
    adornments: Map<String, List<H2g2SwarmAdornment>>,
    serviceVisits: List<H2g2TerrariumServiceVisit>,
    selectedId: String?,
    onNodeSelected: (H2g2WorkflowNode) -> Unit,
    onNodeMoved: (String, H2g2TerrariumPosition) -> Unit,
    onNodeDroppedOn: ((String, String) -> Unit)?,
    modifier: Modifier,
    editable: Boolean,
    compact: Boolean,
    tissue: TissueScene?,
    onTetherDrawn: ((String, String) -> Unit)?,
    onTetherSliced: ((String, String) -> Unit)?,
    onSurface: (() -> Unit)?,
    onGroundTapped: (() -> Unit)?,
    camera: TerrariumCamera?,
) {
    if (subjects.isEmpty()) return

    val subjectPositions = subjects.associate { it.node.id to it.position }
    var workingPositions by remember(subjectPositions) { mutableStateOf(subjectPositions) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    // A tether being drawn from a creature's tail, and a slice being swiped across the field (screen px).
    var tetherFromId by remember { mutableStateOf<String?>(null) }
    var tetherEnd by remember { mutableStateOf(Offset.Zero) }
    var sliceTrail by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // True while a finger pans the ground: the camera then follows it at once instead of easing.
    var panning by remember { mutableStateOf(false) }
    val ownCamera = remember { TerrariumCamera() }
    val view = camera ?: ownCamera
    var zoomTarget by view::zoom
    var focusTarget by view::focus

    LaunchedEffect(subjectPositions) {
        if (draggingId == null) workingPositions = subjectPositions
    }
    // The levelled view (TerrariumFocus.kt): creatures outside it are dimmed, its strands highlighted.
    val focusStep = view.stack.current
    val focusNodes = focusStep.nodes.takeIf { focusStep.level != FocusLevel.Overview }

    val zoom by animateFloatAsState(
        targetValue = zoomTarget,
        animationSpec = if (AzphaltMotion.reduceMotion) snap() else tween(260),
        label = "terrarium-zoom",
    )
    val focusX by animateFloatAsState(
        targetValue = focusTarget.x,
        animationSpec = if (panning || AzphaltMotion.reduceMotion) snap() else tween(320),
        label = "terrarium-focus-x",
    )
    val focusY by animateFloatAsState(
        targetValue = focusTarget.y,
        animationSpec = if (panning || AzphaltMotion.reduceMotion) snap() else tween(320),
        label = "terrarium-focus-y",
    )

    val clock = rememberInfiniteTransition(label = "node-mascot-clock")
    val animatedMotionPhase by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "node-mascot-motion",
    )
    val animatedLinkPulse by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "node-mascot-link-pulse",
    )
    // Reduced motion (system animator scale 0 / prefers-reduced-motion) freezes both clocks.
    val reduceMotion = AzphaltMotion.reduceMotion
    val moveLeftLabel = stringResource(Res.string.semantics_move_node_left)
    val moveRightLabel = stringResource(Res.string.semantics_move_node_right)
    val moveUpLabel = stringResource(Res.string.semantics_move_node_up)
    val moveDownLabel = stringResource(Res.string.semantics_move_node_down)
    fun motionPhase(): Float = if (reduceMotion) 0f else animatedMotionPhase
    fun linkPulse(): Float = if (reduceMotion) 0.5f else animatedLinkPulse

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        // Creatures scale to the field so a whole hive fits: roughly a grid of them with room between.
        val creatureSize = (minOf(maxWidth, maxHeight) / (kotlin.math.sqrt(subjects.size.toFloat()) + 1.4f))
            .coerceIn(72.dp, if (compact) 154.dp else 176.dp)
        val creatureSizePx = with(density) { creatureSize.toPx() }
        val halfCreaturePx = creatureSizePx / 2f
        // Tendril reach, measured at zoom 1: slack up to rest, and no farther than max.
        // The rigs' tendril sprites are short: linked creatures sit close enough for two tips to
        // clasp half way, stretched a little (CompiledPuppetRig maxReach); unlinked ones keep apart.
        // Joined creatures pack close, like cells; a tendril grows segments to span the gap
        // (CompiledPuppetRig maxSegments). Unjoined ones keep a body's width apart.
        val tendrilRestPx = creatureSizePx * 0.75f
        val tendrilMaxPx = creatureSizePx * 0.95f
        val bodyGapPx = creatureSizePx * 0.9f
        val linkedGapPx = creatureSizePx * 0.7f
        val tendrilLinks = remember(relationships) {
            // Only task-to-task links clasp; the orchestrator is the hive itself and joins nothing.
            relationships
                .filter { it.kind == H2g2TerrariumRelationshipKind.Dependency || it.kind == H2g2TerrariumRelationshipKind.Transfer }
                .map { it.from to it.to }
        }
        // Creatures already too far apart for their tendrils are drawn together once the field is measured.
        LaunchedEffect(subjectPositions, widthPx, heightPx, tendrilLinks) {
            if (draggingId == null) {
                workingPositions = constrainTendrils(workingPositions, tendrilLinks, widthPx, heightPx, tendrilMaxPx, minPx = bodyGapPx, linkedMinPx = linkedGapPx, marginPx = halfCreaturePx, iterations = 40)
            }
        }
        val subjectById = remember(subjects) { subjects.associateBy { it.node.id } }
        val onGround = azphaltOnSurface()
        val capillaryText = rememberTextMeasurer()

        fun screenCenter(position: H2g2TerrariumPosition): Offset = Offset(
            x = widthPx * 0.5f + (position.x - focusX) * widthPx * zoom,
            y = heightPx * 0.5f + (position.y - focusY) * heightPx * zoom,
        )

        // Connections start and end at the creatures' `arm-socket` attachments. Each node's pose is
        // evaluated once per frame into a reused buffer; nodes whose rig has not loaded (or has no
        // socket facing the partner) use the silhouette-edge anchor below.
        // The body each agent wears: the keeper's creature when there is a stable, else its role's.
        val keptRig = LocalCreatureRig.current
        val programmatic = LocalProgrammaticNodes.current
        val rigSlugs = subjects.associate { subject ->
            subject.node.id to (
                rigOverrideFor(subject.node.id, programmatic)
                    ?: keptRig(subject.node.label)?.substringBefore('+')
                    ?: CreatureRigMapping.slugFor(subject.node.label)
                )
        }
        LaunchedEffect(rigSlugs) {
            rigSlugs.values.toSet().forEach { slug -> launch { CreatureRigCache.get(slug) } }
        }
        val rigBuffers = remember { HashMap<String, PuppetPoseBuffer>() }
        val rigPoses = HashMap<String, Pair<LoadedCreatureRig, PuppetPoseBuffer>>()
        if (relationships.isNotEmpty()) {
            subjects.forEach { subject ->
                val loaded = rigSlugs[subject.node.id]?.let(CreatureRigCache::peek) ?: return@forEach
                val compiled = loaded.compiled
                val buffer = rigBuffers[subject.node.id]
                    ?.takeIf { it.alpha.size == compiled.partCount && it.attachX.size == compiled.attachmentCount }
                    ?: compiled.newBuffer().also { rigBuffers[subject.node.id] = it }
                compiled.evaluate(
                    compiled.stateIndex(subject.node.state.name),
                    creatureRigPhase(motionPhase(), subject.identitySeed),
                    buffer,
                )
                rigPoses[subject.node.id] = loaded to buffer
            }
        }

        fun rigSocket(nodeId: String, center: Offset, dirX: Float, dirY: Float): Offset? {
            val (loaded, pose) = rigPoses[nodeId] ?: return null
            val k = pickArmSocket(loaded.compiled, pose, dirX, dirY)
            if (k < 0) return null
            return creatureRigPointToScreen(pose.attachX[k], pose.attachY[k], loaded.rig, creatureSizePx, center, zoom)
        }

        fun linkEndpoints(from: String, to: String): Pair<Offset, Offset>? {
            val fromSubject = subjectById[from] ?: return null
            val toSubject = subjectById[to] ?: return null
            val fromPosition = workingPositions[from] ?: fromSubject.position
            val toPosition = workingPositions[to] ?: toSubject.position
            val fromCenter = screenCenter(fromPosition)
            val toCenter = screenCenter(toPosition)
            val direction = toCenter - fromCenter
            val distance = direction.getDistance()
            if (distance <= 0.001f) return null

            // Attach the connection at the creature's arm socket facing the partner, else at the
            // silhouette edge.
            val normalized = Offset(direction.x / distance, direction.y / distance)
            val anchorRadius = creatureSizePx * zoom * 0.29f
            val start = rigSocket(from, fromCenter, normalized.x, normalized.y)
                ?: (fromCenter + normalized * anchorRadius)
            val end = rigSocket(to, toCenter, -normalized.x, -normalized.y)
                ?: (toCenter - normalized * anchorRadius)
            return start to end
        }

        fun relationshipEndpoints(relationship: H2g2TerrariumRelationship): Pair<Offset, Offset>? =
            linkEndpoints(relationship.from, relationship.to)

        fun claspOf(from: String, to: String): Clasp {
            val up = subjectById[from]?.node?.state ?: return Clasp(ClaspOutcome.None, ClaspFlow.Still)
            val down = subjectById[to]?.node?.state ?: return Clasp(ClaspOutcome.None, ClaspFlow.Still)
            return clasp(up, down)
        }

        /** The pulse's colour is what the tether waits for: success green, failure red, either yellow. */
        fun knotColour(from: String, to: String): Color = when (tissue?.knots?.get(from to to) ?: TerrariumKnot.OnSuccess) {
            TerrariumKnot.OnSuccess -> ClaspGreen
            TerrariumKnot.OnFailure -> ClaspRed
            TerrariumKnot.OnAnyOutcome -> Azphalt.Yellow
        }

        /**
         * Where [id]'s tendrils reach, in its own box px: half way to each partner, where the two
         * tips clasp, and toward the finger while a tether is being drawn from it.
         */
        fun tendrilReach(id: String): List<TendrilReach> {
            val centre = screenCenter(workingPositions[id] ?: subjectById[id]?.position ?: return emptyList())
            val half = creatureSizePx / 2f
            fun local(p: Offset) = Offset((p.x - centre.x) / zoom + half, (p.y - centre.y) / zoom + half)
            val clasps = tendrilLinks.distinct().mapNotNull { (from, to) ->
                val other = when (id) {
                    from -> to
                    to -> from
                    else -> return@mapNotNull null
                }
                val there = screenCenter(workingPositions[other] ?: subjectById[other]?.position ?: return@mapNotNull null)
                val (upstreamPulse, downstreamPulse) = claspPulse(claspOf(from, to).flow, linkPulse())
                TendrilReach(
                    at = local((centre + there) / 2f),
                    pulse = if (id == from) upstreamPulse else downstreamPulse,
                    pulseColor = knotColour(from, to),
                )
            }
            return if (tetherFromId == id) clasps + TendrilReach(local(tetherEnd)) else clasps
        }

        fun creatureAt(point: Offset, except: String?): String? = subjects.asSequence()
            .filter { it.node.id != except }
            .map { it.node.id to (screenCenter(workingPositions[it.node.id] ?: it.position) - point).getDistance() }
            .filter { (_, distance) -> distance <= creatureSizePx * zoom * 0.45f }
            .minByOrNull { it.second }
            ?.first

        // Read afresh by the ground's gesture below, which must not restart as the camera moves under a finger.
        val strandTouchPx = with(density) { STRAND_TOUCH_DP.dp.toPx() }
        fun strandsOnScreen(): List<List<Offset>> = relationships
            .filter { it.kind == H2g2TerrariumRelationshipKind.Dependency || it.kind == H2g2TerrariumRelationshipKind.Transfer }
            .mapNotNull { relationshipEndpoints(it)?.let { (a, b) -> axonPolyline(a, b) } }
        val canvasDragIntentNow by rememberUpdatedState<(Offset) -> CanvasDragIntent> { point ->
            canvasDragIntent(
                startedOnStrand = onTetherSliced != null && touchesStrand(point, strandsOnScreen(), strandTouchPx),
                sliceAvailable = onTetherSliced != null,
            )
        }
        val panByNow by rememberUpdatedState<(Offset) -> Unit> { drag ->
            view.free = true
            focusTarget = pannedTerrariumFocus(focusTarget, drag, widthPx, heightPx, zoomTarget, workingPositions.values)
        }
        val cutAcrossNow by rememberUpdatedState<(List<Offset>) -> Unit> { trail ->
            // The cut follows each axon as drawn, bow and all.
            relationships
                .filter { it.kind == H2g2TerrariumRelationshipKind.Dependency || it.kind == H2g2TerrariumRelationshipKind.Transfer }
                .filter { relationship ->
                    val (a, b) = relationshipEndpoints(relationship) ?: return@filter false
                    val axon = axonPolyline(a, b)
                    trail.zipWithNext().any { (p, q) -> axon.zipWithNext().any { (u, v) -> segmentsCross(u, v, p, q) } }
                }
                .forEach { onTetherSliced?.invoke(it.from, it.to) }
        }
        val onSurfaceNow by rememberUpdatedState(onSurface)
        val onGroundTappedNow by rememberUpdatedState(onGroundTapped)
        val panningNow: (Boolean) -> Unit = { panning = it }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // One finger: on open ground it pans the camera; on a strand it cuts it (Build only).
                    // Two fingers: pinch zooms (pinching out past the widest view surfaces to the district)
                    // and the pair's drift pans. A strand is drawn from a creature's tail, not from here.
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val intent = canvasDragIntentNow(down.position)
                        var travelled = Offset.Zero
                        var mode: String? = null // null = not yet a drag, "pan", "slice", "pinch"
                        var surfaced = false
                        var event0Multi = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                event0Multi = true
                                if (mode == "slice") sliceTrail = emptyList()
                                mode = "pinch"
                                panningNow(true)
                                view.free = true
                                val a = pressed[0]
                                val b = pressed[1]
                                val spread = (a.position - b.position).getDistance()
                                val last = (a.previousPosition - b.previousPosition).getDistance()
                                if (last > 0f) {
                                    val next = zoomTarget * (spread / last)
                                    if (next < 0.6f && !surfaced && onSurfaceNow != null) {
                                        surfaced = true
                                        onSurfaceNow?.invoke()
                                    }
                                    zoomTarget = next.coerceIn(0.70f, 2.4f)
                                }
                                panByNow(((a.position - a.previousPosition) + (b.position - b.previousPosition)) / 2f)
                                pressed.forEach { it.consume() }
                                continue
                            }
                            val change = pressed[0]
                            val delta = change.position - change.previousPosition
                            if (mode == "pinch") mode = "pan" // one finger left: carry on panning with it
                            if (mode == null) {
                                travelled += delta
                                if (travelled.getDistance() <= slop) continue
                                mode = if (intent == CanvasDragIntent.Slice) "slice" else "pan"
                                if (mode == "slice") sliceTrail = listOf(down.position)
                                if (mode == "pan") {
                                    panningNow(true)
                                    panByNow(travelled)
                                    change.consume()
                                    continue
                                }
                            }
                            if (mode == "slice") sliceTrail = sliceTrail + change.position else panByNow(delta)
                            change.consume()
                        }
                        if (mode == null && !event0Multi) onGroundTappedNow?.invoke()
                        if (mode == "slice") {
                            val trail = sliceTrail
                            sliceTrail = emptyList()
                            cutAcrossNow(trail)
                        }
                        panningNow(false)
                    }
                },
        ) {
            if (tissue != null) {
                drawTissueGround(
                    tissue,
                    zoom = zoom,
                    creatureRadius = creatureSizePx * zoom * 0.42f,
                    phase = linkPulse(),
                    isFlowing = { id -> subjectById[id]?.node?.state == H2g2WorkflowState.Active },
                    textMeasurer = capillaryText,
                ) { id -> (workingPositions[id] ?: subjectById[id]?.position)?.let { screenCenter(it) } }
            } else {
                drawRect(Azphalt.Ink.copy(alpha = 0.025f))
                val grid = 48.dp.toPx() * zoom
                val worldOriginX = size.width * 0.5f - focusX * widthPx * zoom
                val worldOriginY = size.height * 0.5f - focusY * heightPx * zoom
                var x = ((worldOriginX % grid) + grid) % grid
                while (x <= size.width) {
                    drawLine(onGround.copy(alpha = 0.055f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += grid
                }
                var y = ((worldOriginY % grid) + grid) % grid
                while (y <= size.height) {
                    drawLine(onGround.copy(alpha = 0.055f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += grid
                }
            }

            // Synapses drawn as axons beneath the bodies (the Figma field): a dark membrane under a
            // core that reads the clasp. Creatures still reach for each other with their own tendrils
            // (NodeMascotSurface reach), which grip the axon from above.
            val axonScale = (creatureSizePx / 128.dp.toPx()).coerceIn(0.6f, 1.25f) * zoom
            tendrilLinks.distinct().forEach { (from, to) ->
                val (a, b) = linkEndpoints(from, to) ?: return@forEach
                val downstream = subjectById[to]?.node?.state ?: return@forEach
                drawAxon(
                    a = a,
                    b = b,
                    state = axonState(claspOf(from, to)),
                    gated = axonGated(downstream),
                    scale = axonScale,
                    travel = linkPulse(),
                    slowTravel = motionPhase(),
                    reduceMotion = reduceMotion,
                )
            }
            if (focusNodes != null && focusNodes.size > 1) {
                tendrilLinks.distinct().forEach { (from, to) ->
                    if (from !in focusNodes || to !in focusNodes) return@forEach
                    val (a, b) = linkEndpoints(from, to) ?: return@forEach
                    axonPolyline(a, b).zipWithNext().forEach { (p, q) ->
                        drawLine(Azphalt.Yellow.copy(alpha = 0.75f), p, q, strokeWidth = 3.dp.toPx() * zoom.coerceIn(0.7f, 1.4f), cap = StrokeCap.Round)
                    }
                }
            }
            sliceTrail.zipWithNext().forEach { (p, q) ->
                drawLine(Azphalt.Yellow.copy(alpha = 0.8f), p, q, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }

        subjects.forEach { subject ->
            val node = subject.node
            val position = workingPositions[node.id] ?: subject.position
            val center = screenCenter(position)
            val isSelected = selectedId == node.id
            val isDropTarget = dropTargetId == node.id
            val stateColor = node.state.mascotStateColor()
            val action = mascotActivityLabel(node.label, node.state)
            val taskLabel = node.subtitle?.trim().orEmpty()

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (center.x - halfCreaturePx).roundToInt(),
                            y = (center.y - halfCreaturePx).roundToInt(),
                        )
                    }
                    .size(creatureSize)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        alpha = if (focusNodes != null && node.id !in focusNodes) 0.4f else 1f
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append(node.label)
                            append(", ")
                            append(action.lowercase())
                            if (taskLabel.isNotEmpty()) append(", $taskLabel")
                        }
                        role = Role.Button
                        selected = isSelected
                        // Non-drag alternative to moving a node: nudge it with assistive actions.
                        if (editable) {
                            val step = 0.04f
                            fun nudge(dx: Float, dy: Float): Boolean {
                                val current = workingPositions[node.id] ?: subject.position
                                val next = H2g2TerrariumPosition(current.x + dx, current.y + dy).clamped()
                                workingPositions = workingPositions + (node.id to next)
                                onNodeMoved(node.id, next)
                                return true
                            }
                            customActions = listOf(
                                CustomAccessibilityAction(moveLeftLabel) { nudge(-step, 0f) },
                                CustomAccessibilityAction(moveRightLabel) { nudge(step, 0f) },
                                CustomAccessibilityAction(moveUpLabel) { nudge(0f, -step) },
                                CustomAccessibilityAction(moveDownLabel) { nudge(0f, step) },
                            )
                        }
                    }
                    .pointerInput(editable, node.id, widthPx, heightPx, subjects, zoom) {
                        if (!editable) return@pointerInput
                        detectDragGestures(
                            onDragStart = { start ->
                                // A drag that starts on the tail (the lower part of the body) draws a tether.
                                if (onTetherDrawn != null && start.y > size.height * 0.62f) {
                                    tetherFromId = node.id
                                    tetherEnd = screenCenter(workingPositions[node.id] ?: subject.position) +
                                        (start - Offset(size.width / 2f, size.height / 2f)) * zoom
                                } else {
                                    draggingId = node.id
                                }
                                dropTargetId = null
                            },
                            onDragCancel = {
                                draggingId = null
                                dropTargetId = null
                                tetherFromId = null
                            },
                            onDragEnd = {
                                tetherFromId?.let { from ->
                                    tetherFromId = null
                                    creatureAt(tetherEnd, except = from)?.let { to -> onTetherDrawn?.invoke(from, to) }
                                    return@detectDragGestures
                                }
                                // The dragged creature and every partner its tendrils pulled along keep their places.
                                workingPositions.forEach { (id, position) ->
                                    if (id == node.id || subjectPositions[id] != position) onNodeMoved(id, position)
                                }
                                val target = dropTargetId
                                if (target != null && target != node.id) onNodeDroppedOn?.invoke(node.id, target)
                                draggingId = null
                                dropTargetId = null
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            if (tetherFromId == node.id) {
                                tetherEnd += dragAmount * zoom
                                return@detectDragGestures
                            }
                            val current = workingPositions[node.id] ?: subject.position
                            val next = H2g2TerrariumPosition(
                                x = current.x + dragAmount.x / (widthPx * zoom),
                                y = current.y + dragAmount.y / (heightPx * zoom),
                            ).clamped()
                            workingPositions = constrainTendrils(
                                workingPositions + (node.id to next),
                                tendrilLinks,
                                widthPx,
                                heightPx,
                                tendrilMaxPx,
                                pinned = node.id,
                                minPx = bodyGapPx,
                                linkedMinPx = linkedGapPx,
                                marginPx = halfCreaturePx,
                            )
                            val nextPx = screenCenter(next)
                            // Without a drop handler, bodies only arrange: no creature is ever a drop target.
                            if (onNodeDroppedOn == null) return@detectDragGestures
                            dropTargetId = subjects.asSequence()
                                .filter { it.node.id != node.id }
                                .map { candidate ->
                                    val candidatePosition = workingPositions[candidate.node.id] ?: candidate.position
                                    val candidatePx = screenCenter(candidatePosition)
                                    candidate.node.id to hypot(
                                        (candidatePx.x - nextPx.x).toDouble(),
                                        (candidatePx.y - nextPx.y).toDouble(),
                                    ).toFloat()
                                }
                                .filter { (_, distance) -> distance <= creatureSizePx * zoom * 0.72f }
                                .minByOrNull { it.second }
                                ?.first
                        }
                    }
                    .clickable(role = Role.Button) { onNodeSelected(node) },
                contentAlignment = Alignment.Center,
            ) {
                when (tissue?.echo?.get(node.id)) {
                    // Insane: septic. A sick green halo it spreads to whatever reads its work.
                    EchoState.Insane -> Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color(0xFF7FA34A).copy(alpha = 0.35f), radius = min(size.width, size.height) * 0.48f)
                    }
                    // Fevered: a dashed warm ring; it has stopped listening.
                    EchoState.Fevered -> Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            Color(0xFFD9762A),
                            radius = min(size.width, size.height) * 0.42f,
                            style = Stroke(
                                width = 2.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                            ),
                        )
                    }
                    else -> Unit
                }
                if (tissue?.gathered?.contains(node.id) == true) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Azphalt.Yellow, radius = min(size.width, size.height) * 0.47f, style = Stroke(width = 3.dp.toPx()))
                    }
                }
                if (tissue?.held?.contains(node.id) == true) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Azphalt.White, radius = min(size.width, size.height) * 0.46f, style = Stroke(width = 2.dp.toPx()))
                    }
                }
                if (tissue?.atSurface?.contains(node.id) == true) {
                    // At the surface: it needs you. A warm glow behind it, like the gate in the mockups.
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(Azphalt.Yellow.copy(alpha = 0.6f), Azphalt.Yellow.copy(alpha = 0f)),
                                center = Offset(size.width * 0.5f, size.height * 0.5f),
                                radius = min(size.width, size.height) * 0.5f,
                            ),
                            radius = min(size.width, size.height) * 0.5f,
                            center = Offset(size.width * 0.5f, size.height * 0.5f),
                        )
                    }
                }
                if (isSelected || isDropTarget) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = if (isDropTarget) {
                                stateColor.copy(alpha = 0.28f)
                            } else {
                                Azphalt.White.copy(alpha = 0.18f)
                            },
                            radius = min(size.width, size.height) * 0.45f,
                            center = Offset(size.width * 0.5f, size.height * 0.5f),
                            style = Stroke(width = min(size.width, size.height) * 0.028f),
                        )
                    }
                }

                NodeMascotSurface(
                    roleLabel = node.label,
                    hueSeed = subject.identitySeed,
                    state = node.state,
                    motionPhase = { motionPhase() },
                    modifier = Modifier.fillMaxSize(),
                    reach = { tendrilReach(node.id) },
                    rigOverride = rigOverrideFor(node.id, LocalProgrammaticNodes.current),
                )
                H2g2SwarmAdornmentLayer(
                    adornments = adornments[node.id].orEmpty(),
                    hueSeed = subject.identitySeed,
                    modifier = Modifier.fillMaxSize(),
                )

                // Every creature wears its job (docs/Node-Creatures.md: labelled with its task and state),
                // on its state's colour; only a crowded hive keeps names for the one tapped, so packed
                // cells stay legible, unless the keeper asked to always show them (Keeper's room ›
                // Appearance). The full state is always in the semantics.
                // The creature carrying the egg wears it on top, so the egg has its tail tip to itself.
                val carriesEgg = tissue?.egg?.feederIds?.lastOrNull() == node.id
                // Which provider answers for it: Pinned never switches; Auto fails over. Ink and Yellow only.
                LocalProviderBadges.current[node.id]?.let { badge ->
                    val pinned = badge.routing is CreatureProviderRouting.Pinned
                    Text(
                        text = creatureProviderBadgeText(badge.routing, badge.pinnedName),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = AzphaltType.endCap,
                        color = if (pinned) Azphalt.Yellow.contrastingText else Azphalt.Ink.onOpposite(),
                        modifier = Modifier
                            .align(if (carriesEgg) Alignment.TopEnd else Alignment.TopCenter)
                            .widthIn(max = creatureSize)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (pinned) Azphalt.Yellow else Azphalt.Ink.copy(alpha = 0.82f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (creatureWearsLabel(isSelected, subjects.size, CreatureLabels.alwaysShow)) Text(
                    text = taskLabel.ifEmpty { node.label },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AzphaltType.endCap,
                    color = stateColor.contrastingText,
                    modifier = Modifier
                        .align(if (carriesEgg) Alignment.TopCenter else Alignment.BottomCenter)
                        .widthIn(max = creatureSize)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSelected) stateColor else stateColor.copy(alpha = 0.82f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // Each capillary is named where its name is written, for those who hear the field: the
        // provider and the creatures it feeds. It only speaks; touches pass through to the tissue.
        tissue?.capillaryCourses(widthPx, heightPx) { id ->
            (workingPositions[id] ?: subjectById[id]?.position)?.let { screenCenter(it) }
        }?.forEach { course -> key(course.capillary.providerId) {
            val description = stringResource(
                Res.string.semantics_capillary,
                capillaryName(course.capillary.providerId),
                capillaryFeeds(course.fed.map { id -> subjectById[id]?.node?.label ?: id }),
            )
            val half = with(density) { (AzphaltMinTouchTarget / 2).toPx() }
            Box(
                Modifier
                    .offset { IntOffset((course.labelAt.x - half).roundToInt(), (course.labelAt.y - half).roundToInt()) }
                    .size(AzphaltMinTouchTarget)
                    .semantics(mergeDescendants = true) { contentDescription = description },
            )
        } }

        // Each clasp glows by how the upstream work came out: green succeeded, red failed or blocked.
        Canvas(Modifier.fillMaxSize()) {
            tendrilLinks.distinct().forEach { (from, to) ->
                val glow = when (claspOf(from, to).outcome) {
                    ClaspOutcome.Succeeded -> ClaspGreen
                    ClaspOutcome.Failed -> ClaspRed
                    ClaspOutcome.None -> return@forEach
                }
                val a = screenCenter(workingPositions[from] ?: subjectById[from]?.position ?: return@forEach)
                val b = screenCenter(workingPositions[to] ?: subjectById[to]?.position ?: return@forEach)
                val at = (a + b) / 2f
                val r = creatureSizePx * zoom * 0.09f
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(glow.copy(alpha = 0.75f), glow.copy(alpha = 0f)), at, r * 2.4f),
                    r * 2.4f,
                    at,
                )
                drawCircle(glow, r * 0.45f, at)
            }
        }

        // The swarm's egg rides above the bodies, below its feeders, so it is never hidden.
        tissue?.egg?.let { egg ->
            Canvas(Modifier.fillMaxSize()) {
                // The egg hangs at the end of the working creature's tail: nothing else joins them.
                val feeder = egg.feederIds.lastOrNull()?.let { id -> (workingPositions[id] ?: subjectById[id]?.position)?.let { screenCenter(it) } }
                val at = feeder?.let { Offset(it.x, it.y + creatureSizePx * zoom * 0.46f) } ?: screenCenter(egg.position)
                drawTissueEgg(egg, zoom, toScreen = { at })
            }
        }

        TerrariumZoomControls(
            label = if (view.free) stringResource(Res.string.focus_free) else focusStep.level.label(),
            showStepper = !view.driven,
            onZoomIn = view::stepIn,
            // Out from the widest view surfaces, the rim-button equivalent of pinching out.
            onZoomOut = { view.stepOut(onSurface) },
            onRecentre = view::centre,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        )

        if (zoom <= 1.02f) {
            H2g2TerrariumServiceLayer(
                visits = serviceVisits,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun FocusLevel.label(): String = stringResource(
    when (this) {
        FocusLevel.Creature -> Res.string.focus_creature
        FocusLevel.Neighbours -> Res.string.focus_neighbours
        FocusLevel.PathToOrchestrator -> Res.string.focus_path_orchestrator
        FocusLevel.PathToEnd -> Res.string.focus_path_end
        FocusLevel.PathToActive -> Res.string.focus_path_active
        FocusLevel.PathToBlocks -> Res.string.focus_path_blocks
        FocusLevel.WholeChain -> Res.string.focus_chain
        FocusLevel.Overview -> Res.string.focus_overview
    },
)

/**
 * The view's stepper: "+" back in, "−" out, and between them the name of what the view shows
 * (tap it to snap back to that view). With [showStepper] false the hive's controls bar owns the
 * buttons and only the name stays.
 */
@Composable
private fun TerrariumZoomControls(
    label: String,
    showStepper: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRecentre: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Azphalt.Ink.copy(alpha = 0.86f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showStepper) TerrariumZoomButton(label = stringResource(Res.string.terrarium_zoom_in), onClick = onZoomIn) {
            Text(text = "+", style = AzphaltType.section, color = Azphalt.White, textAlign = TextAlign.Center)
        }
        TerrariumZoomButton(label = stringResource(Res.string.focus_recentre, label), onClick = onRecentre) {
            Text(
                text = label,
                style = AzphaltType.endCap,
                color = Azphalt.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.widthIn(max = 112.dp),
            )
        }
        if (showStepper) TerrariumZoomButton(label = stringResource(Res.string.terrarium_zoom_out), onClick = onZoomOut) {
            Text(text = "−", style = AzphaltType.section, color = Azphalt.White, textAlign = TextAlign.Center)
        }
    }
}

/** A zoom control with a 48dp touch target, a button role and a spoken [label]. */
@Composable
private fun TerrariumZoomButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun H2g2WorkflowState.mascotStateColor() = when (this) {
    H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed -> GeministratorColors.error
    H2g2WorkflowState.Complete -> Azphalt.hues[2]
    H2g2WorkflowState.Active -> Azphalt.Ink
    H2g2WorkflowState.Gate -> Azphalt.hues[5]
    H2g2WorkflowState.Ready -> Azphalt.hues[1]
    H2g2WorkflowState.Pending -> Azphalt.hues[10]
}

internal fun mascotActivityLabel(roleLabel: String, state: H2g2WorkflowState): String {
    if (state == H2g2WorkflowState.Blocked) return "BLOCKED"
    if (state == H2g2WorkflowState.Failed) return "FAILED"
    if (state == H2g2WorkflowState.Complete) return "COMPLETE"
    if (state == H2g2WorkflowState.Gate) return "AWAITING GATE"
    if (state == H2g2WorkflowState.Ready) return "READY"
    if (state == H2g2WorkflowState.Pending) return "QUEUED"

    return classifyNodeCreatureRole(roleLabel).activeLabel()
}


/** Whether segment [a]–[b] crosses segment [p]–[q]. */
private fun segmentsCross(a: Offset, b: Offset, p: Offset, q: Offset): Boolean {
    fun side(o: Offset, x: Offset, y: Offset) = (x.x - o.x) * (y.y - o.y) - (x.y - o.y) * (y.x - o.x)
    val d1 = side(p, q, a)
    val d2 = side(p, q, b)
    val d3 = side(a, b, p)
    val d4 = side(a, b, q)
    return (d1 > 0f) != (d2 > 0f) && (d3 > 0f) != (d4 > 0f)
}

/**
 * One axon (the Figma synapse): a dark membrane under a core, a terminal where it leaves the
 * upstream creature and a receptor where it reaches the downstream one. Complete glows green with a
 * vesicle riding it; active runs yellow dashes with two vesicles; waiting is a grey dotted core;
 * failed is a still red core. A [gated] receptor wears the gate ring. [travel] (fast) and
 * [slowTravel] are 0..1 clocks; [reduceMotion] stops the dashes, the firing and the vesicles.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxon(
    a: Offset,
    b: Offset,
    state: AxonState,
    gated: Boolean,
    scale: Float,
    travel: Float,
    slowTravel: Float,
    reduceMotion: Boolean,
) {
    fun w(v: Float) = v.dp.toPx() * scale
    val control = axonControl(a, b)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(a.x, a.y)
        quadraticTo(control.x, control.y, b.x, b.y)
    }
    val core = when (state) {
        AxonState.Complete -> ClaspGreen
        AxonState.Active -> Azphalt.Yellow
        AxonState.Waiting -> AxonWaiting
        AxonState.Failed -> ClaspRed
    }
    val glows = state == AxonState.Complete || state == AxonState.Active
    // Membrane.
    drawPath(path, Azphalt.Ink.copy(alpha = 0.95f), style = Stroke(width = w(13f), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    // Core: a soft halo under a lit line when something has crossed or is crossing.
    when (state) {
        AxonState.Complete, AxonState.Failed -> {
            if (glows) drawPath(path, core.copy(alpha = 0.22f), style = Stroke(width = w(11f), cap = StrokeCap.Round))
            drawPath(path, core.copy(alpha = 0.72f), style = Stroke(width = w(6f), cap = StrokeCap.Round))
        }
        AxonState.Active -> {
            drawPath(path, core.copy(alpha = 0.18f), style = Stroke(width = w(11f), cap = StrokeCap.Round))
            val dash = w(9f)
            val gap = w(7f)
            // The dashes travel toward the receptor: two dash lengths per lap of the clock.
            val offset = if (reduceMotion) 0f else -travel * (dash + gap) * 2f
            drawPath(
                path,
                core,
                style = Stroke(width = w(6f), cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), offset)),
            )
        }
        AxonState.Waiting -> drawPath(
            path,
            core.copy(alpha = 0.72f),
            style = Stroke(width = w(5f), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w(0.01f), w(8f)))),
        )
    }
    // Terminal: the upstream's end. An active one fires (its ring swells) unless motion is reduced.
    val fire = if (state == AxonState.Active && !reduceMotion) kotlin.math.sin(travel * 2f * kotlin.math.PI.toFloat()).let { it * it } else 0f
    if (glows) drawCircle(core.copy(alpha = 0.25f * (1f - fire * 0.5f)), radius = w(13f), center = a)
    drawCircle(Azphalt.Ink, radius = w(8f), center = a)
    drawCircle(core.copy(alpha = if (state == AxonState.Waiting) 0.9f else 1f - fire * 0.35f), radius = w(8f), center = a, style = Stroke(width = w(4f + 3f * fire)))
    // Receptor: the downstream's end; a gate rings it.
    val receptorRadius = if (gated) w(7f) else w(6f)
    if (state == AxonState.Active) drawCircle(core.copy(alpha = 0.25f), radius = w(10f), center = b)
    drawCircle(AzphaltLiftedInk, radius = receptorRadius, center = b)
    drawCircle(if (gated) AzphaltDanger else core, radius = receptorRadius, center = b, style = Stroke(width = if (gated) w(4f) else w(3f)))
    // Vesicles riding the core: complete on the slow clock, active on the fast one.
    val vesicleColour = if (state == AxonState.Complete) lerp(ClaspGreen, Azphalt.White, 0.45f) else lerp(Azphalt.Yellow, Azphalt.White, 0.55f)
    axonVesicles(state, if (state == AxonState.Complete) slowTravel else travel, reduceMotion).forEach { vesicle ->
        val at = axonPoint(a, control, b, vesicle.at)
        val r = w(6f) * vesicle.scale
        val alpha = if (vesicle.scale < 1f && state == AxonState.Active) 0.7f else 1f
        drawCircle(vesicleColour.copy(alpha = 0.3f * alpha), radius = r * 2f, center = at)
        drawCircle(vesicleColour.copy(alpha = alpha), radius = r, center = at)
    }
}

/** A waiting axon's dotted core: the field's grey (the Pending hue). */
private val AxonWaiting = Azphalt.hues[10]

/** One tendril: tapered from the body to the clasp, thinner when stretched; dashed when knotted. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTendril(
    root: Offset,
    tip: Offset,
    color: androidx.compose.ui.graphics.Color,
    zoom: Float,
    stretch: Float,
    knotted: Boolean,
) {
    val segments = 10
    val base = (9f - 4f * stretch).dp.toPx() * zoom
    val end = (3f - 1f * stretch).dp.toPx() * zoom
    for (i in 0 until segments) {
        if (knotted && i % 2 == 1) continue
        val a = root + (tip - root) * (i / segments.toFloat())
        val b = root + (tip - root) * ((i + 1) / segments.toFloat())
        val w = base + (end - base) * (i / (segments - 1).toFloat())
        drawLine(color, a, b, strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** Clasp glows: luminous on the dark tissue. */
internal val ClaspGreen = Color(0xFF6BE04A)
private val ClaspRed = Color(0xFFFF3B30)

/** Up to this many creatures, every one wears its name; more, and only the tapped one does. */
internal const val NAMED_CREATURE_LIMIT = 12

/**
 * Whether a creature wears its label on the field: always when it is selected or the keeper asked
 * to always show labels, otherwise only while the hive has at most [NAMED_CREATURE_LIMIT] creatures.
 */
internal fun creatureWearsLabel(selected: Boolean, creatureCount: Int, alwaysShowLabels: Boolean): Boolean =
    selected || alwaysShowLabels || creatureCount <= NAMED_CREATURE_LIMIT

/** The keeper's "Always show creature labels" switch (Keeper's room › Appearance), stored on this device. */
internal object CreatureLabels {
    private val settings by lazy { Settings() }

    var alwaysShow: Boolean by mutableStateOf(settings.getBoolean(ALWAYS_SHOW_KEY, false))
        private set

    fun updateAlwaysShow(on: Boolean) {
        alwaysShow = on
        settings.putBoolean(ALWAYS_SHOW_KEY, on)
    }

    private const val ALWAYS_SHOW_KEY = "aive.appearance.always-show-creature-labels.v1"
}
