package com.hereliesaz.conveyance.h2g2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Normalized habitat coordinate. Persist this in the host, not pixel coordinates. */
data class H2g2TerrariumPosition(val x: Float, val y: Float) {
    fun clamped(): H2g2TerrariumPosition = H2g2TerrariumPosition(
        x = x.coerceIn(.05f, .95f),
        y = y.coerceIn(.07f, .93f),
    )
}

data class H2g2TerrariumSubject(
    val node: H2g2WorkflowNode,
    val position: H2g2TerrariumPosition,
    /** First appearance visibly emerges from this parent if the parent exists in the same habitat. */
    val birthParentId: String? = null,
    /** Stable non-colour identity input. */
    val identitySeed: String = node.id,
    /** Orchestrator is host-rendered canonical art; all other subjects are procedurally generated. */
    val identityKind: H2g2SwarmIdentityKind = H2g2SwarmIdentityKind.Generated,
)

enum class H2g2TerrariumRelationshipKind {
    Dependency,
    Spawn,
    Transfer,
    Confer,
}

data class H2g2TerrariumRelationship(
    val from: String,
    val to: String,
    val kind: H2g2TerrariumRelationshipKind = H2g2TerrariumRelationshipKind.Dependency,
    /** True only while the host runtime says the interaction is currently occurring. */
    val active: Boolean = false,
)

/**
 * A workflow as a living artificial-life terrarium.
 *
 * Every creature owns an independent decision cadence, behavioral state, personality and RNG stream
 * in [H2g2SwarmWorld]. Compose supplies one display-frame clock and renders snapshots; it does not
 * run an AI/physics engine per frame. This keeps the colony cheap while allowing behavior itself to
 * become recognizable identity.
 *
 * [orchestratorContent] is deliberately host supplied. H2G2 must never procedurally approximate the
 * Haive orchestrator: Haive injects the exact canonical logo character there.
 *
 * [adornments] are persistent visualized dependencies carried or worn by the downstream creature.
 * [serviceVisits] are transient outside actors such as the fixed USPR delivery truck.
 *
 * Dragging is presentation until drop completes. [onNodeDroppedOn] is the semantic boundary: a host
 * may rewrite an editable workflow, stage a revision for a running workflow, or reject the gesture.
 */
@Composable
fun H2g2SwarmTerrarium(
    subjects: List<H2g2TerrariumSubject>,
    relationships: List<H2g2TerrariumRelationship>,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    selectedId: String? = null,
    adornments: Map<String, List<H2g2SwarmAdornment>> = emptyMap(),
    serviceVisits: List<H2g2TerrariumServiceVisit> = emptyList(),
    onNodeSelected: (H2g2WorkflowNode) -> Unit = {},
    onNodeMoved: (String, H2g2TerrariumPosition) -> Unit = { _, _ -> },
    onNodeDroppedOn: (String, String) -> Unit = { _, _ -> },
    orchestratorContent: (@Composable (
        subject: H2g2TerrariumSubject,
        snapshot: H2g2SwarmAgentSnapshot,
        modifier: Modifier,
    ) -> Unit)? = null,
    orchestratorTerminalAnchor: ((toward: Offset) -> Offset)? = null,
) {
    if (subjects.isEmpty()) return
    require(subjects.none { it.identityKind == H2g2SwarmIdentityKind.Orchestrator } || orchestratorContent != null) {
        "H2G2 orchestrators require host-supplied canonical artwork; procedural fallback is forbidden"
    }

    val worldKey = remember(subjects) {
        subjects.map { Triple(it.node.id, it.identitySeed, it.identityKind) }
    }
    val world = remember(worldKey) {
        H2g2SwarmWorld(
            subjects.map { subject ->
                H2g2SwarmAgentSpec(
                    id = subject.node.id,
                    identitySeed = subject.identitySeed,
                    identityKind = subject.identityKind,
                    habitatX = subject.position.clamped().x,
                    habitatY = subject.position.clamped().y,
                    parentId = subject.birthParentId,
                )
            },
        )
    }
    var snapshots by remember(world) { mutableStateOf(world.snapshots()) }
    var renderClockMillis by remember(world) { mutableStateOf(0f) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(world) {
        var previousNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val deltaMillis = ((nowNanos - previousNanos) / 1_000_000f).coerceIn(0f, 100f)
            previousNanos = nowNanos
            world.step(deltaMillis)
            renderClockMillis += deltaMillis
            snapshots = world.snapshots()
        }
    }

    LaunchedEffect(subjects.map { it.node.id to it.position }) {
        subjects.forEach { subject ->
            if (draggingId != subject.node.id && world.snapshot(subject.node.id) != null) {
                world.moveHabitat(subject.node.id, subject.position.x, subject.position.y)
            }
        }
        snapshots = world.snapshots()
    }

    LaunchedEffect(subjects.map { it.node.id to it.node.state }) {
        val activeInteractionIds = relationships
            .filter { it.active && it.kind != H2g2TerrariumRelationshipKind.Dependency }
            .flatMap { listOf(it.from, it.to) }
            .toSet()
        subjects.forEach { subject ->
            if (subject.node.id in activeInteractionIds) return@forEach
            val behavior = when (subject.node.state) {
                H2g2WorkflowState.Active -> H2g2SwarmBehavior.Working
                H2g2WorkflowState.Blocked, H2g2WorkflowState.Gate -> H2g2SwarmBehavior.Blocked
                H2g2WorkflowState.Failed -> H2g2SwarmBehavior.Startled
                H2g2WorkflowState.Complete -> H2g2SwarmBehavior.Rest
                H2g2WorkflowState.Ready -> H2g2SwarmBehavior.Investigate
                H2g2WorkflowState.Pending -> null
            }
            if (behavior != null) world.setBehavior(subject.node.id, behavior)
        }
        snapshots = world.snapshots()
    }

    val interactionKey = remember(relationships) {
        relationships.map { listOf(it.from, it.to, it.kind.name, it.active.toString()) }
    }
    LaunchedEffect(interactionKey) {
        val activePairs = relationships.filter { it.active }
        activePairs.forEach { relationship ->
            if (world.snapshot(relationship.from) == null || world.snapshot(relationship.to) == null) return@forEach
            when (relationship.kind) {
                H2g2TerrariumRelationshipKind.Transfer -> world.beginTransfer(relationship.from, relationship.to)
                H2g2TerrariumRelationshipKind.Confer -> world.beginConference(relationship.from, relationship.to)
                H2g2TerrariumRelationshipKind.Spawn -> {
                    world.setBehavior(relationship.to, H2g2SwarmBehavior.Birth, relationship.from)
                    world.setBehavior(relationship.from, H2g2SwarmBehavior.PrepareSpawn, relationship.to)
                }
                H2g2TerrariumRelationshipKind.Dependency -> Unit
            }
        }
        val activeInteractionIds = activePairs
            .filter { it.kind != H2g2TerrariumRelationshipKind.Dependency }
            .flatMap { listOf(it.from, it.to) }
            .toSet()
        world.snapshots().forEach { snapshot ->
            if (snapshot.id !in activeInteractionIds && snapshot.behavior in setOf(
                    H2g2SwarmBehavior.Confer,
                    H2g2SwarmBehavior.Transfer,
                    H2g2SwarmBehavior.Birth,
                    H2g2SwarmBehavior.PrepareSpawn,
                )
            ) {
                world.setBehavior(snapshot.id, H2g2SwarmBehavior.Recover)
            }
        }
        snapshots = world.snapshots()
    }

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val creatureSize = 124.dp
        val creatureSizePx = with(density) { creatureSize.toPx() }
        val halfCreaturePx = creatureSizePx / 2f
        val subjectById = remember(subjects) { subjects.associateBy { it.node.id } }
        val snapshotById = snapshots.associateBy { it.id }
        val activeContactById = relationships
            .filter { it.active && it.kind != H2g2TerrariumRelationshipKind.Dependency }
            .flatMap { relationship -> listOf(relationship.from to relationship, relationship.to to relationship) }
            .toMap()

        Canvas(Modifier.fillMaxSize()) {
            drawTerrariumBackdrop()
            relationships.forEach { relationship ->
                val fromSubject = subjectById[relationship.from] ?: return@forEach
                val toSubject = subjectById[relationship.to] ?: return@forEach
                val fromSnapshot = snapshotById[relationship.from] ?: return@forEach
                val toSnapshot = snapshotById[relationship.to] ?: return@forEach
                val startCenter = Offset(fromSnapshot.x * widthPx, fromSnapshot.y * heightPx)
                val endCenter = Offset(toSnapshot.x * widthPx, toSnapshot.y * heightPx)
                val direction = endCenter - startCenter
                val startTerminal = terminalAnchor(fromSubject, direction, halfCreaturePx, orchestratorTerminalAnchor)
                val endTerminal = terminalAnchor(toSubject, -direction, halfCreaturePx, orchestratorTerminalAnchor)
                drawTerrariumRelationship(
                    relationship = relationship,
                    start = startCenter + startTerminal,
                    end = endCenter + endTerminal,
                    pulsePhase = (renderClockMillis / 1900f) % 1f,
                )
            }
        }

        subjects.forEach { subject ->
            val node = subject.node
            val snapshot = snapshotById[node.id] ?: return@forEach
            val currentCenter = Offset(snapshot.x * widthPx, snapshot.y * heightPx)
            val parentCenter = subject.birthParentId
                ?.let(snapshotById::get)
                ?.let { Offset(it.x * widthPx, it.y * heightPx) }
            val birth = remember(node.id, subject.birthParentId) { Animatable(if (parentCenter == null) 1f else 0f) }
            LaunchedEffect(node.id, subject.birthParentId) {
                if (parentCenter != null && birth.value < 1f) {
                    delay((stableTerrariumHash(node.id).ushr(1) % 180).toLong())
                    birth.animateTo(1f, tween(620))
                }
            }
            val birthCenter = parentCenter?.let { parent -> parent + (currentCenter - parent) * birth.value } ?: currentCenter
            val contact = activeContactById[node.id]
            val contactOtherId = contact?.let { if (it.from == node.id) it.to else it.from }
            val contactOther = contactOtherId?.let(snapshotById::get)
            val contactDirection = contactOther?.let { other ->
                Offset(other.x * widthPx, other.y * heightPx) - currentCenter
            }
            val isDropTarget = dropTargetId == node.id
            val isSelected = selectedId == node.id

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (birthCenter.x - halfCreaturePx).roundToInt(),
                            y = (birthCenter.y - halfCreaturePx).roundToInt(),
                        )
                    }
                    .size(creatureSize)
                    .pointerInput(editable, node.id, widthPx, heightPx, worldKey) {
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
                                val moved = world.snapshot(node.id)
                                if (moved != null) {
                                    onNodeMoved(node.id, H2g2TerrariumPosition(moved.habitatX, moved.habitatY))
                                }
                                val target = dropTargetId
                                if (target != null && target != node.id) onNodeDroppedOn(node.id, target)
                                world.setBehavior(node.id, H2g2SwarmBehavior.Recover)
                                draggingId = null
                                dropTargetId = null
                                snapshots = world.snapshots()
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val current = world.snapshot(node.id) ?: return@detectDragGestures
                            val next = H2g2TerrariumPosition(
                                x = current.x + dragAmount.x / widthPx,
                                y = current.y + dragAmount.y / heightPx,
                            ).clamped()
                            world.moveHabitat(node.id, next.x, next.y, moveCreature = true)
                            world.setBehavior(node.id, H2g2SwarmBehavior.Follow)
                            snapshots = world.snapshots()

                            val nextPx = Offset(next.x * widthPx, next.y * heightPx)
                            dropTargetId = world.snapshots()
                                .asSequence()
                                .filter { it.id != node.id }
                                .map { candidate ->
                                    val candidatePx = Offset(candidate.x * widthPx, candidate.y * heightPx)
                                    candidate.id to hypot(
                                        (candidatePx.x - nextPx.x).toDouble(),
                                        (candidatePx.y - nextPx.y).toDouble(),
                                    ).toFloat()
                                }
                                .filter { (_, distance) -> distance <= creatureSizePx * .72f }
                                .minByOrNull { it.second }
                                ?.first
                        }
                    }
                    .clickable { onNodeSelected(node) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected || isDropTarget) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isDropTarget) H2g2.hues[4].copy(alpha = .28f)
                                else H2g2.white.copy(alpha = .12f),
                            ),
                    )
                }

                if (subject.identityKind == H2g2SwarmIdentityKind.Orchestrator) {
                    requireNotNull(orchestratorContent).invoke(subject, snapshot, Modifier.fillMaxSize())
                } else {
                    H2g2SwarmCharacter(
                        identitySeed = subject.identitySeed,
                        hueSeed = node.hueSeed,
                        active = node.state == H2g2WorkflowState.Active || draggingId == node.id,
                        contactDirection = contactDirection,
                        contactStrength = if (contact != null) 1f else 0f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                H2g2SwarmAdornmentLayer(
                    adornments = adornments[node.id].orEmpty(),
                    hueSeed = node.hueSeed,
                    modifier = Modifier.fillMaxSize(),
                )

                BasicText(
                    text = node.label.uppercase(),
                    style = h2g2Type().endCap.copy(
                        color = H2g2.white,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(999.dp))
                        .background(H2g2.ink.copy(alpha = .84f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        H2g2TerrariumServiceLayer(
            visits = serviceVisits,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun terminalAnchor(
    subject: H2g2TerrariumSubject,
    toward: Offset,
    halfCreaturePx: Float,
    orchestratorTerminalAnchor: ((Offset) -> Offset)?,
): Offset {
    if (toward.getDistance() <= .001f) return Offset.Zero
    return if (subject.identityKind == H2g2SwarmIdentityKind.Orchestrator) {
        val normalized = normalize(toward)
        val normalizedAnchor = orchestratorTerminalAnchor?.invoke(normalized) ?: normalized * .34f
        normalizedAnchor * halfCreaturePx
    } else {
        h2g2SwarmGenome(subject.identitySeed).terminalAnchorToward(toward) * halfCreaturePx
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTerrariumBackdrop() {
    drawRect(H2g2.ink.copy(alpha = .08f))
    val grid = 48.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(H2g2.white.copy(alpha = .025f), Offset(x, 0f), Offset(x, size.height), 1f)
        x += grid
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(H2g2.white.copy(alpha = .025f), Offset(0f, y), Offset(size.width, y), 1f)
        y += grid
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTerrariumRelationship(
    relationship: H2g2TerrariumRelationship,
    start: Offset,
    end: Offset,
    pulsePhase: Float,
) {
    val color = when (relationship.kind) {
        H2g2TerrariumRelationshipKind.Dependency -> H2g2.white.copy(alpha = .18f)
        H2g2TerrariumRelationshipKind.Spawn -> H2g2.hues[3].copy(alpha = .72f)
        H2g2TerrariumRelationshipKind.Transfer -> H2g2.hues[4].copy(alpha = .88f)
        H2g2TerrariumRelationshipKind.Confer -> H2g2.hues[1].copy(alpha = .88f)
    }
    val active = relationship.active
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = if (active) 7f else 3f,
        cap = StrokeCap.Round,
        pathEffect = if (relationship.kind == H2g2TerrariumRelationshipKind.Dependency) {
            PathEffect.dashPathEffect(floatArrayOf(9f, 12f))
        } else null,
    )
    if (active) {
        drawCircle(H2g2.white.copy(alpha = .82f), radius = 5f, center = start)
        drawCircle(H2g2.white.copy(alpha = .82f), radius = 5f, center = end)
        val vector = end - start
        repeat(if (relationship.kind == H2g2TerrariumRelationshipKind.Confer) 4 else 3) { packetIndex ->
            val directionPhase = if (
                relationship.kind == H2g2TerrariumRelationshipKind.Confer && packetIndex % 2 == 1
            ) {
                1f - ((pulsePhase + packetIndex * .23f) % 1f)
            } else {
                (pulsePhase + packetIndex * .29f) % 1f
            }
            val packet = start + vector * directionPhase
            drawCircle(color = color.copy(alpha = .96f), radius = 6f, center = packet)
            drawCircle(color = H2g2.white.copy(alpha = .86f), radius = 2.4f, center = packet)
        }
    }
}

private fun stableTerrariumHash(value: String): Int {
    var hash = 0x811C9DC5.toInt()
    value.forEach { char ->
        hash = hash xor char.code
        hash *= 0x01000193
    }
    return hash
}

private fun normalize(offset: Offset): Offset {
    val length = sqrt(offset.x * offset.x + offset.y * offset.y)
    return if (length <= .000001f) Offset.Zero else Offset(offset.x / length, offset.y / length)
}
