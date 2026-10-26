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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.hereliesaz.geministrator.resources.semantics_move_node_down
import com.hereliesaz.geministrator.resources.semantics_move_node_left
import com.hereliesaz.geministrator.resources.semantics_move_node_right
import com.hereliesaz.geministrator.resources.semantics_move_node_up
import com.hereliesaz.geministrator.resources.terrarium_reset_zoom
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
    var zoomTarget by remember { mutableStateOf(1f) }
    var focusTarget by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    LaunchedEffect(subjectPositions) {
        if (draggingId == null) workingPositions = subjectPositions
    }
    LaunchedEffect(selectedId, subjectPositions, compact) {
        val selected = selectedId?.let(subjectPositions::get)
        if (selected != null) {
            focusTarget = Offset(selected.x, selected.y)
            zoomTarget = maxOf(zoomTarget, if (compact) 1.55f else 1.35f)
        }
    }

    val zoom by animateFloatAsState(
        targetValue = zoomTarget,
        animationSpec = tween(260),
        label = "terrarium-zoom",
    )
    val focusX by animateFloatAsState(
        targetValue = focusTarget.x,
        animationSpec = tween(320),
        label = "terrarium-focus-x",
    )
    val focusY by animateFloatAsState(
        targetValue = focusTarget.y,
        animationSpec = tween(320),
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
        val creatureSize = if (compact) 154.dp else 176.dp
        val creatureSizePx = with(density) { creatureSize.toPx() }
        val halfCreaturePx = creatureSizePx / 2f
        val subjectById = remember(subjects) { subjects.associateBy { it.node.id } }
        val onGround = azphaltOnSurface()

        fun screenCenter(position: H2g2TerrariumPosition): Offset = Offset(
            x = widthPx * 0.5f + (position.x - focusX) * widthPx * zoom,
            y = heightPx * 0.5f + (position.y - focusY) * heightPx * zoom,
        )

        // Connections start and end at the creatures' `arm-socket` attachments. Each node's pose is
        // evaluated once per frame into a reused buffer; nodes whose rig has not loaded (or has no
        // socket facing the partner) use the silhouette-edge anchor below.
        val rigSlugs = remember(subjects) {
            subjects.associate { it.node.id to CreatureRigMapping.slugFor(it.node.label) }
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

        fun relationshipEndpoints(relationship: H2g2TerrariumRelationship): Pair<Offset, Offset>? {
            val fromSubject = subjectById[relationship.from] ?: return null
            val toSubject = subjectById[relationship.to] ?: return null
            val fromPosition = workingPositions[relationship.from] ?: fromSubject.position
            val toPosition = workingPositions[relationship.to] ?: toSubject.position
            val fromCenter = screenCenter(fromPosition)
            val toCenter = screenCenter(toPosition)
            val direction = toCenter - fromCenter
            val distance = direction.getDistance()
            if (distance <= 0.001f) return null

            // Attach the connection at the creature's arm socket facing the partner, else at the
            // silhouette edge.
            val normalized = Offset(direction.x / distance, direction.y / distance)
            val anchorRadius = creatureSizePx * zoom * 0.29f
            val start = rigSocket(relationship.from, fromCenter, normalized.x, normalized.y)
                ?: (fromCenter + normalized * anchorRadius)
            val end = rigSocket(relationship.to, toCenter, -normalized.x, -normalized.y)
                ?: (toCenter - normalized * anchorRadius)
            return start to end
        }

        fun creatureAt(point: Offset, except: String?): String? = subjects.asSequence()
            .filter { it.node.id != except }
            .map { it.node.id to (screenCenter(workingPositions[it.node.id] ?: it.position) - point).getDistance() }
            .filter { (_, distance) -> distance <= creatureSizePx * zoom * 0.45f }
            .minByOrNull { it.second }
            ?.first

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(onTetherSliced, relationships, zoom, focusX, focusY) {
                    // Slice: a swipe across open tissue cuts every tether it crosses.
                    if (onTetherSliced == null) return@pointerInput
                    detectDragGestures(
                        onDragStart = { sliceTrail = listOf(it) },
                        onDragCancel = { sliceTrail = emptyList() },
                        onDragEnd = {
                            val trail = sliceTrail
                            sliceTrail = emptyList()
                            relationships
                                .filter { it.kind == H2g2TerrariumRelationshipKind.Dependency || it.kind == H2g2TerrariumRelationshipKind.Transfer }
                                .filter { relationship ->
                                    val (a, b) = relationshipEndpoints(relationship) ?: return@filter false
                                    trail.zipWithNext().any { (p, q) -> segmentsCross(a, b, p, q) }
                                }
                                .forEach { onTetherSliced(it.from, it.to) }
                        },
                    ) { change, _ ->
                        change.consume()
                        sliceTrail = sliceTrail + change.position
                    }
                },
        ) {
            if (tissue != null) {
                drawTissueGround(tissue) { screenCenter(it) }
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

            // On the dark tissue, synapses are light; on the plain ground they take the ground's ink.
            val linkInk = if (tissue != null) Azphalt.White else onGround
            relationships.forEach { relationship ->
                val toSubject = subjectById[relationship.to] ?: return@forEach
                val (start, end) = relationshipEndpoints(relationship) ?: return@forEach

                val blocked = toSubject.node.state == H2g2WorkflowState.Blocked ||
                    toSubject.node.state == H2g2WorkflowState.Failed
                val active = relationship.active || relationship.kind == H2g2TerrariumRelationshipKind.Transfer
                val color = when {
                    blocked -> GeministratorColors.error.copy(alpha = 0.40f)
                    active -> linkInk.copy(alpha = 0.42f)
                    else -> linkInk.copy(alpha = 0.28f)
                }
                val stroke = (if (active) 2.dp else 1.5.dp).toPx()

                // A soft shadow line under the link keeps it readable over the grid.
                drawLine(
                    color = Azphalt.Ink.copy(alpha = 0.10f),
                    start = start,
                    end = end,
                    strokeWidth = stroke + 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                    // A knotted (conditional) tether is dashed.
                    pathEffect = if (tissue?.conditional?.contains(relationship.from to relationship.to) == true) {
                        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                    } else {
                        null
                    },
                )
                if (active) {
                    val pulse = start + (end - start) * linkPulse()
                    drawCircle(
                        color = linkInk.copy(alpha = 0.72f),
                        radius = 3.dp.toPx(),
                        center = pulse,
                    )
                }
            }
            tetherFromId?.let { from ->
                val start = screenCenter(workingPositions[from] ?: subjectById[from]?.position ?: return@let)
                drawLine(Azphalt.White.copy(alpha = 0.9f), start, tetherEnd, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Azphalt.White, radius = 6.dp.toPx(), center = tetherEnd, style = Stroke(width = 2.dp.toPx()))
            }
            sliceTrail.zipWithNext().forEach { (p, q) ->
                drawLine(Azphalt.Yellow.copy(alpha = 0.8f), p, q, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            tissue?.egg?.let { egg ->
                drawTissueEgg(egg, zoom, toScreen = { screenCenter(it) }) { id -> (workingPositions[id] ?: subjectById[id]?.position)?.let { screenCenter(it) } }
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
                                onNodeMoved(node.id, workingPositions[node.id] ?: subject.position)
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
                            workingPositions = workingPositions + (node.id to next)
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
                )
                H2g2SwarmAdornmentLayer(
                    adornments = adornments[node.id].orEmpty(),
                    hueSeed = subject.identitySeed,
                    modifier = Modifier.fillMaxSize(),
                )

                Text(
                    text = if (taskLabel.isEmpty()) action else "$action · $taskLabel",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AzphaltType.endCap,
                    color = stateColor.contrastingText,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(stateColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    text = node.label.uppercase(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = AzphaltType.endCap,
                    color = Azphalt.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Azphalt.Ink.copy(alpha = 0.92f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }

        TerrariumZoomControls(
            zoom = zoomTarget,
            onZoomIn = { zoomTarget = (zoomTarget + 0.20f).coerceAtMost(2.4f) },
            onZoomOut = { zoomTarget = (zoomTarget - 0.20f).coerceAtLeast(0.70f) },
            onReset = {
                zoomTarget = 1f
                focusTarget = Offset(0.5f, 0.5f)
            },
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
private fun TerrariumZoomControls(
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Azphalt.Ink.copy(alpha = 0.86f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val zoomPercent = "${(zoom * 100f).roundToInt()}%"
        TerrariumZoomButton(
            label = stringResource(Res.string.terrarium_zoom_in),
            onClick = onZoomIn,
        ) {
            Text(text = "+", style = AzphaltType.section, color = Azphalt.White, textAlign = TextAlign.Center)
        }
        TerrariumZoomButton(
            label = stringResource(Res.string.terrarium_reset_zoom, zoomPercent),
            onClick = onReset,
        ) {
            Text(text = zoomPercent, style = AzphaltType.endCap, color = Azphalt.White, textAlign = TextAlign.Center)
        }
        TerrariumZoomButton(
            label = stringResource(Res.string.terrarium_zoom_out),
            onClick = onZoomOut,
        ) {
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
