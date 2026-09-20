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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
                zoom = zoom,
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
    zoom: Float,
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
        zoom = zoom,
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
        zoom = zoom,
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
    zoom: Float,
) {
    val density = LocalDensity.current
    val baseWidth = 144.dp
    val baseHeight = 72.dp
    val baseWidthPx = with(density) { baseWidth.toPx() }
    val baseHeightPx = with(density) { baseHeight.toPx() }
    val direction = toward - socket
    val distance = direction.getDistance()
    if (distance <= 0.5f) return

    // The source art runs from x=20 to terminal x=188 in a 200-wide viewBox. Use those
    // actual pivots instead of the image edge so paired arms meet exactly at the midpoint.
    val sourceSpanPx = baseWidthPx * (asset.terminalPivotX - asset.socketPivotX)
    val uniformScale = zoom.coerceAtLeast(0.01f)
    val longitudinalStretch = (
        distance / (sourceSpanPx * uniformScale).coerceAtLeast(1f)
        ).coerceIn(0.22f, 6f)
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

    Canvas(
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
                scaleX = uniformScale * longitudinalStretch
                scaleY = uniformScale * breathe
                alpha = if (blocked) 0.72f else 0.96f
            },
    ) {
        drawDetachedNodeArm(asset)
    }
}

private fun DrawScope.drawDetachedNodeArm(asset: NodeArmAsset) {
    val color = nodeArmColor(asset.role)
    val dark = nodeArmDarkColor(asset.role)
    val stroke = size.height * 0.09f

    fun point(x: Float, y: Float): Offset = Offset(
        x = size.width * (x / 200f),
        y = size.height * (y / 100f),
    )
    fun circle(x: Float, y: Float, radius: Float, fill: Color = Color.Transparent) {
        drawCircle(
            color = if (fill == Color.Transparent) color else fill,
            radius = size.height * (radius / 100f),
            center = point(x, y),
            style = if (fill == Color.Transparent) Stroke(width = stroke * 0.72f) else androidx.compose.ui.graphics.drawscope.Fill,
        )
    }
    fun curvedMain(variant: NodeArmVariant) {
        val path = Path().apply {
            moveTo(point(20f, 50f).x, point(20f, 50f).y)
            if (variant == NodeArmVariant.A) {
                cubicTo(
                    point(58f, 46f).x, point(58f, 46f).y,
                    point(72f, 67f).x, point(72f, 67f).y,
                    point(103f, 52f).x, point(103f, 52f).y,
                )
                cubicTo(
                    point(132f, 38f).x, point(132f, 38f).y,
                    point(160f, 48f).x, point(160f, 48f).y,
                    point(188f, 50f).x, point(188f, 50f).y,
                )
            } else {
                cubicTo(
                    point(52f, 68f).x, point(52f, 68f).y,
                    point(78f, 64f).x, point(78f, 64f).y,
                    point(102f, 49f).x, point(102f, 49f).y,
                )
                cubicTo(
                    point(132f, 31f).x, point(132f, 31f).y,
                    point(158f, 43f).x, point(158f, 43f).y,
                    point(188f, 50f).x, point(188f, 50f).y,
                )
            }
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
    fun angularMain(variant: NodeArmVariant) {
        val points = if (variant == NodeArmVariant.A) {
            listOf(
                20f to 50f, 58f to 50f, 82f to 35f, 108f to 53f,
                135f to 31f, 162f to 49f, 188f to 50f,
            )
        } else {
            listOf(
                20f to 50f, 56f to 50f, 76f to 69f, 105f to 50f,
                126f to 32f, 149f to 50f, 188f to 50f,
            )
        }
        val path = Path().apply {
            val first = point(points.first().first, points.first().second)
            moveTo(first.x, first.y)
            points.drop(1).forEach { (x, y) ->
                val p = point(x, y)
                lineTo(p.x, p.y)
            }
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
    fun waveMain(variant: NodeArmVariant) {
        val path = Path().apply {
            val start = point(20f, 50f)
            moveTo(start.x, start.y)
            if (variant == NodeArmVariant.A) {
                cubicTo(
                    point(45f, 25f).x, point(45f, 25f).y,
                    point(62f, 78f).x, point(62f, 78f).y,
                    point(86f, 50f).x, point(86f, 50f).y,
                )
                cubicTo(
                    point(111f, 20f).x, point(111f, 20f).y,
                    point(130f, 79f).x, point(130f, 79f).y,
                    point(151f, 48f).x, point(151f, 48f).y,
                )
                cubicTo(
                    point(163f, 33f).x, point(163f, 33f).y,
                    point(174f, 40f).x, point(174f, 40f).y,
                    point(188f, 50f).x, point(188f, 50f).y,
                )
            } else {
                cubicTo(
                    point(47f, 70f).x, point(47f, 70f).y,
                    point(61f, 26f).x, point(61f, 26f).y,
                    point(88f, 51f).x, point(88f, 51f).y,
                )
                cubicTo(
                    point(112f, 74f).x, point(112f, 74f).y,
                    point(128f, 28f).x, point(128f, 28f).y,
                    point(151f, 49f).x, point(151f, 49f).y,
                )
                cubicTo(
                    point(166f, 63f).x, point(166f, 63f).y,
                    point(177f, 58f).x, point(177f, 58f).y,
                    point(188f, 50f).x, point(188f, 50f).y,
                )
            }
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
    fun branch(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        drawLine(
            color = color,
            start = point(fromX, fromY),
            end = point(toX, toY),
            strokeWidth = stroke * 0.78f,
            cap = StrokeCap.Round,
        )
    }
    fun square(x: Float, y: Float, sizeDesign: Float = 13f) {
        val s = size.height * (sizeDesign / 100f)
        drawRect(
            color = color,
            topLeft = point(x, y) - Offset(s / 2f, s / 2f),
            size = androidx.compose.ui.geometry.Size(s, s),
        )
    }
    fun eye(x: Float, y: Float, radius: Float) {
        circle(x, y, radius, Color(0xFFE8F6FF))
        circle(x, y, radius * 0.36f, dark)
    }
    fun heart(x: Float, y: Float) {
        val p = Path().apply {
            val a = point(x, y + 12f)
            moveTo(a.x, a.y)
            val leftTop = point(x - 20f, y - 4f)
            val rightTop = point(x + 20f, y - 4f)
            cubicTo(
                point(x - 25f, y - 18f).x, point(x - 25f, y - 18f).y,
                leftTop.x, leftTop.y,
                point(x, y + 12f).x, point(x, y + 12f).y,
            )
            cubicTo(
                rightTop.x, rightTop.y,
                point(x + 25f, y - 18f).x, point(x + 25f, y - 18f).y,
                a.x, a.y,
            )
            close()
        }
        drawPath(p, color)
    }

    when (asset.role) {
        NodeArmRole.Architect,
        NodeArmRole.ImplementationEngineer,
        NodeArmRole.CodeReviewer,
        NodeArmRole.AdversarialReviewer,
        NodeArmRole.Antagonist -> angularMain(asset.variant)
        NodeArmRole.CrashTestDummy -> waveMain(asset.variant)
        else -> curvedMain(asset.variant)
    }

    when (asset.role) {
        NodeArmRole.Orchestrator -> {
            if (asset.variant == NodeArmVariant.A) {
                branch(91f, 43f, 130f, 13f)
                branch(112f, 51f, 153f, 82f)
                circle(130f, 13f, 6f)
                circle(153f, 82f, 6f)
            } else {
                branch(102f, 51f, 132f, 10f)
                branch(120f, 43f, 169f, 25f)
                circle(132f, 10f, 6f)
                circle(169f, 25f, 6f)
            }
        }
        NodeArmRole.ProductManager,
        NodeArmRole.UxDesigner -> {
            heart(126f, if (asset.variant == NodeArmVariant.A) 28f else 34f)
            branch(110f, 52f, 154f, 80f)
            circle(154f, 80f, 5f)
        }
        NodeArmRole.Researcher -> {
            branch(100f, 52f, 126f, 22f)
            eye(126f, 22f, if (asset.variant == NodeArmVariant.A) 10f else 7f)
            if (asset.variant == NodeArmVariant.B) {
                branch(126f, 53f, 160f, 82f)
                circle(160f, 82f, 5f)
            }
        }
        NodeArmRole.Architect,
        NodeArmRole.ImplementationEngineer,
        NodeArmRole.CodeReviewer -> {
            square(82f, if (asset.variant == NodeArmVariant.A) 35f else 69f)
            square(135f, if (asset.variant == NodeArmVariant.A) 31f else 17f)
            if (asset.variant == NodeArmVariant.A) {
                branch(108f, 53f, 139f, 82f)
                square(139f, 82f)
            }
        }
        NodeArmRole.EpaRepresentative,
        NodeArmRole.RecoveryEngineer -> {
            branch(102f, 50f, 128f, 17f)
            branch(115f, 52f, 156f, 81f)
            circle(128f, 17f, 6f, color)
            circle(156f, 81f, 6f, color)
            if (asset.role == NodeArmRole.RecoveryEngineer && asset.variant == NodeArmVariant.B) {
                val bandSize = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.16f)
                drawRect(
                    color = Color(0xFFEAC58A),
                    topLeft = point(131f, 33f) - Offset(bandSize.width / 2f, bandSize.height / 2f),
                    size = bandSize,
                )
            }
        }
        NodeArmRole.CrashTestDummy -> {
            circle(113f, if (asset.variant == NodeArmVariant.A) 22f else 66f, 5f)
            if (asset.variant == NodeArmVariant.B) {
                branch(122f, 47f, 146f, 82f)
                circle(146f, 82f, 5f)
            }
        }
        NodeArmRole.QaEngineer -> {
            if (asset.variant == NodeArmVariant.A) {
                branch(100f, 50f, 132f, 20f)
                eye(132f, 20f, 12f)
            } else {
                eye(153f, 28f, 14f)
                branch(165f, 40f, 179f, 54f)
            }
        }
        NodeArmRole.AdversarialReviewer -> {
            val p = Path().apply {
                val a = point(if (asset.variant == NodeArmVariant.A) 135f else 151f, 26f)
                moveTo(a.x, a.y)
                val b = point(184f, 50f)
                lineTo(b.x, b.y)
                val d = point(if (asset.variant == NodeArmVariant.A) 138f else 151f, 54f)
                lineTo(d.x, d.y)
                close()
            }
            drawPath(p, color)
        }
        NodeArmRole.ReleaseEngineer -> {
            if (asset.variant == NodeArmVariant.A) {
                branch(102f, 51f, 132f, 18f)
                circle(132f, 18f, 6f)
            } else {
                circle(145f, 20f, 11f)
                branch(145f, 31f, 145f, 64f)
                branch(145f, 55f, 162f, 55f)
            }
        }
        NodeArmRole.Antagonist -> {
            if (asset.variant == NodeArmVariant.B) {
                val flame = Path().apply {
                    val a = point(152f, 12f)
                    moveTo(a.x, a.y)
                    cubicTo(
                        point(143f, 25f).x, point(143f, 25f).y,
                        point(148f, 34f).x, point(148f, 34f).y,
                        point(157f, 39f).x, point(157f, 39f).y,
                    )
                    cubicTo(
                        point(151f, 26f).x, point(151f, 26f).y,
                        point(165f, 23f).x, point(165f, 23f).y,
                        point(168f, 10f).x, point(168f, 10f).y,
                    )
                    cubicTo(
                        point(180f, 27f).x, point(180f, 27f).y,
                        point(180f, 44f).x, point(180f, 44f).y,
                        point(165f, 52f).x, point(165f, 52f).y,
                    )
                    close()
                }
                drawPath(flame, Color(0xFFA10A5A))
            }
        }
        NodeArmRole.HallMonitor -> {
            if (asset.variant == NodeArmVariant.A) {
                branch(98f, 50f, 125f, 18f)
                branch(116f, 52f, 154f, 80f)
                eye(125f, 18f, 10f)
                eye(154f, 80f, 7f)
            } else {
                branch(101f, 48f, 135f, 16f)
                branch(122f, 53f, 165f, 78f)
                eye(135f, 16f, 11f)
                eye(165f, 78f, 6f)
            }
        }
    }

    // Socket and terminal are rendered last so their circular shape stays legible after stretching.
    circle(20f, 50f, 12f, color)
    circle(20f, 50f, 5.5f, dark)
    circle(188f, 50f, 6f)
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
