package com.hereliesaz.geministrator

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.jetbrains.compose.resources.painterResource

/**
 * Production node-creature host.
 *
 * Creature geometry is always supplied by the Rust native/WASM engine. Compose owns only workflow
 * placement, interaction, labels and rasterization of the projected render packets.
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
    onNodeDroppedOn: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    compact: Boolean = false,
) {
    val engine = remember { platformNodeCreatureRenderEngine() }
    if (engine == null) {
        NodeCreatureRendererUnavailable(modifier)
        return
    }

    RustNodeTerrarium(
        engine = engine,
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
    )
}

@Composable
private fun NodeCreatureRendererUnavailable(modifier: Modifier) {
    Box(
        modifier = modifier.background(Azphalt.currentGround.page),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "NODE CREATURE RENDERER UNAVAILABLE",
            style = AzphaltType.eyebrow,
            color = Azphalt.currentGround.onPage,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun RustNodeTerrarium(
    engine: NodeCreatureRenderEngine,
    subjects: List<H2g2TerrariumSubject>,
    relationships: List<H2g2TerrariumRelationship>,
    adornments: Map<String, List<H2g2SwarmAdornment>>,
    serviceVisits: List<H2g2TerrariumServiceVisit>,
    selectedId: String?,
    onNodeSelected: (H2g2WorkflowNode) -> Unit,
    onNodeMoved: (String, H2g2TerrariumPosition) -> Unit,
    onNodeDroppedOn: (String, String) -> Unit,
    modifier: Modifier,
    editable: Boolean,
    compact: Boolean,
) {
    if (subjects.isEmpty()) return

    val subjectPositions = subjects.associate { it.node.id to it.position }
    var workingPositions by remember(subjectPositions) { mutableStateOf(subjectPositions) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
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

    val clock = rememberInfiniteTransition(label = "rust-node-creature-clock")
    val timeSeconds by clock.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rust-node-creature-time",
    )
    val linkPulse by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rust-node-link-pulse",
    )

    // Rust packet generation includes topology construction. Quantize creature animation to 20 Hz
    // so link/UI animation can remain fluid without rebuilding every creature mesh on every frame.
    val creatureFrameTime = (timeSeconds * 20f).roundToInt() / 20f
    val packetInputs = subjects.map { subject ->
        listOf(
            subject.node.id,
            subject.node.label,
            subject.identitySeed,
            subject.node.state.name,
        )
    }
    val packets = remember(packetInputs, creatureFrameTime) {
        subjects.associateNotNull { subject ->
            runCatching {
                subject.node.id to NodeCreatureRenderPacketDecoder.decode(
                    engine.render(
                        NodeCreatureRenderRequest(
                            roleLabel = subject.node.label,
                            identitySeed = subject.identitySeed,
                            activity = subject.node.state.toNodeCreatureActivity(),
                            timeSeconds = creatureFrameTime,
                        ),
                    ),
                )
            }.getOrNull()
        }
    }

    if (packets.size != subjects.size) {
        NodeCreatureRendererUnavailable(modifier)
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val creatureSize = if (compact) 154.dp else 176.dp
        val creatureSizePx = with(density) { creatureSize.toPx() }
        val halfCreaturePx = creatureSizePx / 2f
        val vectorScalePx = creatureSizePx * 0.92f
        val subjectById = remember(subjects) { subjects.associateBy { it.node.id } }
        val onGround = Azphalt.currentGround.onPage

        fun screenCenter(position: H2g2TerrariumPosition): Offset = Offset(
            x = widthPx * 0.5f + (position.x - focusX) * widthPx * zoom,
            y = heightPx * 0.5f + (position.y - focusY) * heightPx * zoom,
        )

        fun relationshipEndpoints(relationship: H2g2TerrariumRelationship): Pair<Offset, Offset>? {
            val fromSubject = subjectById[relationship.from] ?: return null
            val toSubject = subjectById[relationship.to] ?: return null
            val fromPacket = packets[relationship.from] ?: return null
            val toPacket = packets[relationship.to] ?: return null
            val fromPosition = workingPositions[relationship.from] ?: fromSubject.position
            val toPosition = workingPositions[relationship.to] ?: toSubject.position
            val fromCenter = screenCenter(fromPosition)
            val toCenter = screenCenter(toPosition)
            val direction = toCenter - fromCenter
            if (direction.getDistance() <= 0.001f) return null

            val startTerminal = fromPacket.terminalAnchorToward(
                NodeCreaturePoint(direction.x, direction.y),
            )
            val endTerminal = toPacket.terminalAnchorToward(
                NodeCreaturePoint(-direction.x, -direction.y),
            )
            val terminalScale = vectorScalePx * zoom
            val start = fromCenter + Offset(
                startTerminal.x * terminalScale,
                startTerminal.y * terminalScale,
            )
            val end = toCenter + Offset(
                endTerminal.x * terminalScale,
                endTerminal.y * terminalScale,
            )
            return start to end
        }

        Canvas(Modifier.fillMaxSize()) {
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

            relationships.forEach { relationship ->
                val toSubject = subjectById[relationship.to] ?: return@forEach
                val (start, end) = relationshipEndpoints(relationship) ?: return@forEach

                val blocked = toSubject.node.state == H2g2WorkflowState.Blocked ||
                    toSubject.node.state == H2g2WorkflowState.Failed
                val active = relationship.active || relationship.kind == H2g2TerrariumRelationshipKind.Transfer
                val color = when {
                    blocked -> GeministratorColors.error.copy(alpha = 0.28f)
                    active -> onGround.copy(alpha = 0.24f)
                    else -> onGround.copy(alpha = 0.16f)
                }
                val stroke = (if (active) 2.dp else 1.5.dp).toPx()

                // Keep a faint topology backbone behind the detached arm pair. It preserves
                // readability at extreme distances without competing with the role-specific arms.
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
                )
                if (active) {
                    val pulse = start + (end - start) * linkPulse
                    drawCircle(
                        color = onGround.copy(alpha = 0.72f),
                        radius = 3.dp.toPx(),
                        center = pulse,
                    )
                }
            }
        }

        // The connection pair is a transformable layer of its own. Dragging either creature
        // recomputes these two sprites from the Rust terminal anchors; the creature body is never
        // stretched or rotated with the relationship.
        relationships.forEach { relationship ->
            val fromSubject = subjectById[relationship.from] ?: return@forEach
            val toSubject = subjectById[relationship.to] ?: return@forEach
            val (start, end) = relationshipEndpoints(relationship) ?: return@forEach
            val blocked = toSubject.node.state == H2g2WorkflowState.Blocked ||
                toSubject.node.state == H2g2WorkflowState.Failed
            val active = relationship.active ||
                relationship.kind == H2g2TerrariumRelationshipKind.Transfer
            DetachedNodeArmPair(
                relationshipKey = "${relationship.from}->${relationship.to}:${relationship.kind.name}",
                fromRoleLabel = fromSubject.node.label,
                toRoleLabel = toSubject.node.label,
                start = start,
                end = end,
                pulse = linkPulse,
                active = active,
                blocked = blocked,
            )
        }

        subjects.forEach { subject ->
            val node = subject.node
            val packet = packets[node.id] ?: return@forEach
            val position = workingPositions[node.id] ?: subject.position
            val center = screenCenter(position)
            val isSelected = selectedId == node.id
            val isDropTarget = dropTargetId == node.id
            val stateColor = node.state.nodeCreatureStateColor()
            val action = nodeCreatureActivityLabel(node.label, node.state)
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
                    .semantics {
                        contentDescription = buildString {
                            append(node.label)
                            append(", ")
                            append(action.lowercase())
                            if (taskLabel.isNotEmpty()) append(", $taskLabel")
                        }
                    }
                    .pointerInput(editable, node.id, widthPx, heightPx, subjects, zoom) {
                        if (!editable) return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                draggingId = node.id
                                dropTargetId = null
                            },
                            onDragCancel = {
                                draggingId = null
                                dropTargetId = null
                            },
                            onDragEnd = {
                                onNodeMoved(node.id, workingPositions[node.id] ?: subject.position)
                                val target = dropTargetId
                                if (target != null && target != node.id) onNodeDroppedOn(node.id, target)
                                draggingId = null
                                dropTargetId = null
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val current = workingPositions[node.id] ?: subject.position
                            val next = H2g2TerrariumPosition(
                                x = current.x + dragAmount.x / (widthPx * zoom),
                                y = current.y + dragAmount.y / (heightPx * zoom),
                            ).clamped()
                            workingPositions = workingPositions + (node.id to next)
                            val nextPx = screenCenter(next)
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
                    .clickable { onNodeSelected(node) },
                contentAlignment = Alignment.Center,
            ) {
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

                NodeCreatureVectorSurface(
                    packet = packet,
                    hueSeed = subject.identitySeed,
                    roleLabel = node.label,
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
private fun DetachedNodeArmPair(
    relationshipKey: String,
    fromRoleLabel: String,
    toRoleLabel: String,
    start: Offset,
    end: Offset,
    pulse: Float,
    active: Boolean,
    blocked: Boolean,
) {
    val midpoint = Offset(
        x = (start.x + end.x) * 0.5f,
        y = (start.y + end.y) * 0.5f,
    )
    DetachedNodeArmSprite(
        asset = NodeArmAssets.forRole(
            fromRoleLabel,
            NodeArmAssets.variantFor(relationshipKey, endpointIndex = 0),
        ),
        socket = start,
        toward = midpoint,
        pulse = pulse,
        active = active,
        blocked = blocked,
    )
    DetachedNodeArmSprite(
        asset = NodeArmAssets.forRole(
            toRoleLabel,
            NodeArmAssets.variantFor(relationshipKey, endpointIndex = 1),
        ),
        socket = end,
        toward = midpoint,
        pulse = pulse,
        active = active,
        blocked = blocked,
    )
}

@Composable
private fun DetachedNodeArmSprite(
    asset: NodeArmAsset,
    socket: Offset,
    toward: Offset,
    pulse: Float,
    active: Boolean,
    blocked: Boolean,
) {
    val density = LocalDensity.current
    val baseWidth = 144.dp
    val baseHeight = 72.dp
    val baseWidthPx = with(density) { baseWidth.toPx() }
    val baseHeightPx = with(density) { baseHeight.toPx() }
    val direction = toward - socket
    val distance = direction.getDistance()
    if (distance <= 0.5f) return

    val usableWidth = baseWidthPx * (1f - asset.socketPivotX)
    val stretch = (distance / usableWidth.coerceAtLeast(1f)).coerceIn(0.22f, 6f)
    val angle = (
        atan2(direction.y.toDouble(), direction.x.toDouble()) * 180.0 / PI
        ).toFloat()
    val wave = sin((pulse * (2f * PI.toFloat())).toDouble()).toFloat()
    val wobble = when {
        blocked -> wave * 2.4f
        active -> wave * 1.25f
        else -> 0f
    }
    val breathe = if (active) 1f + wave * 0.035f else 1f

    Image(
        painter = painterResource(asset.resource),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (socket.x - baseWidthPx * asset.socketPivotX).roundToInt(),
                    y = (socket.y - baseHeightPx * asset.socketPivotY).roundToInt(),
                )
            }
            .size(baseWidth, baseHeight)
            .graphicsLayer {
                transformOrigin = TransformOrigin(asset.socketPivotX, asset.socketPivotY)
                rotationZ = angle + wobble
                scaleX = stretch
                scaleY = breathe
                alpha = if (blocked) 0.72f else 0.96f
            },
    )
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
        Text(
            text = "+",
            style = AzphaltType.section,
            color = Azphalt.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(onClick = onZoomIn).padding(horizontal = 15.dp, vertical = 7.dp),
        )
        Text(
            text = "${(zoom * 100f).roundToInt()}%",
            style = AzphaltType.endCap,
            color = Azphalt.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(onClick = onReset).padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            text = "−",
            style = AzphaltType.section,
            color = Azphalt.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(onClick = onZoomOut).padding(horizontal = 15.dp, vertical = 7.dp),
        )
    }
}

private inline fun <K, V> Iterable<V>.associateNotNull(transform: (V) -> Pair<K, V>?) =
    buildMap<K, V> {
        this@associateNotNull.forEach { item -> transform(item)?.let { (key, value) -> put(key, value) } }
    }

private fun H2g2WorkflowState.toNodeCreatureActivity(): NodeCreatureActivity = when (this) {
    H2g2WorkflowState.Pending -> NodeCreatureActivity.Queued
    H2g2WorkflowState.Ready -> NodeCreatureActivity.Ready
    H2g2WorkflowState.Active -> NodeCreatureActivity.Active
    H2g2WorkflowState.Blocked -> NodeCreatureActivity.Blocked
    H2g2WorkflowState.Failed -> NodeCreatureActivity.Failed
    H2g2WorkflowState.Complete -> NodeCreatureActivity.Complete
    H2g2WorkflowState.Gate -> NodeCreatureActivity.Gate
}

private fun H2g2WorkflowState.nodeCreatureStateColor() = when (this) {
    H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed -> GeministratorColors.error
    H2g2WorkflowState.Complete -> Azphalt.hues[2]
    H2g2WorkflowState.Active -> Azphalt.Ink
    H2g2WorkflowState.Gate -> Azphalt.hues[5]
    H2g2WorkflowState.Ready -> Azphalt.hues[1]
    H2g2WorkflowState.Pending -> Azphalt.hues[10]
}

private fun nodeCreatureActivityLabel(roleLabel: String, state: H2g2WorkflowState): String {
    if (state == H2g2WorkflowState.Blocked) return "BLOCKED"
    if (state == H2g2WorkflowState.Failed) return "FAILED"
    if (state == H2g2WorkflowState.Complete) return "COMPLETE"
    if (state == H2g2WorkflowState.Gate) return "AWAITING GATE"
    if (state == H2g2WorkflowState.Ready) return "READY"
    if (state == H2g2WorkflowState.Pending) return "QUEUED"

    val role = roleLabel.lowercase()
    return when {
        "orchestrat" in role -> "ROUTING"
        "review" in role -> "REVIEWING"
        "qa" in role || "quality" in role || "verif" in role || "inspect" in role -> "VERIFYING"
        "crash" in role || "dummy" in role || "test" in role -> "STRESS-TESTING"
        "implement" in role || "build" in role || "develop" in role || "engineer" in role -> "BUILDING"
        "plan" in role || "coordinat" in role || "decompos" in role -> "PLANNING"
        "research" in role || "analyst" in role || "investigat" in role -> "INVESTIGATING"
        else -> "WORKING"
    }
}
