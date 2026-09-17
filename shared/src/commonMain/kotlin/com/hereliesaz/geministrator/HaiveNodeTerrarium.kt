package com.hereliesaz.geministrator

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val NODE_TAU = (PI * 2.0).toFloat()

internal enum class HaiveNodeArchetype {
    Orchestrator,
    Builder,
    Tester,
    Inspector,
    Reviewer,
    Planner,
    Researcher,
    Generic,
}

private enum class HaiveTerminalKind {
    Node,
    Clamp,
    Probe,
    Coil,
    Fork,
    Loop,
}

private data class HaiveAntennaSpec(
    val angle: Float,
    val length: Float,
    val bend: Float,
    val terminal: HaiveTerminalKind,
    val phase: Float,
)

/**
 * Haive's workflow-native terrarium.
 *
 * Creatures are the graph: each role is a single node-mass with 3–10 functional antennae,
 * relationships attach terminal-to-terminal, role anatomy communicates responsibility, and state
 * animation communicates current work. Drag/drop authoring and external-service visits remain live.
 */
@Composable
internal fun HaiveNodeTerrarium(
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
    if (subjects.isEmpty()) return

    val subjectPositions = subjects.associate { it.node.id to it.position }
    var workingPositions by remember(subjectPositions) { mutableStateOf(subjectPositions) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(subjectPositions) {
        if (draggingId == null) workingPositions = subjectPositions
    }

    val transition = rememberInfiniteTransition(label = "haive-node-terrarium")
    val pulsePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "haive-node-link-pulse",
    )

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val creatureSize = if (compact) 154.dp else 176.dp
        val creatureSizePx = with(density) { creatureSize.toPx() }
        val halfCreaturePx = creatureSizePx / 2f
        val subjectById = remember(subjects) { subjects.associateBy { it.node.id } }
        val onGround = Azphalt.currentGround.onPage

        Canvas(Modifier.fillMaxSize()) {
            drawRect(Azphalt.Ink.copy(alpha = .025f))
            drawHaiveNodeGrid(onGround)

            relationships.forEach { relationship ->
                val fromSubject = subjectById[relationship.from] ?: return@forEach
                val toSubject = subjectById[relationship.to] ?: return@forEach
                val fromPosition = workingPositions[relationship.from] ?: fromSubject.position
                val toPosition = workingPositions[relationship.to] ?: toSubject.position
                val fromCenter = Offset(fromPosition.x * widthPx, fromPosition.y * heightPx)
                val toCenter = Offset(toPosition.x * widthPx, toPosition.y * heightPx)
                val direction = toCenter - fromCenter
                if (direction.getDistance() <= .001f) return@forEach

                val start = fromCenter + haiveNodeTerminalAnchor(
                    label = fromSubject.node.label,
                    seed = fromSubject.identitySeed,
                    toward = direction,
                ) * halfCreaturePx
                val end = toCenter + haiveNodeTerminalAnchor(
                    label = toSubject.node.label,
                    seed = toSubject.identitySeed,
                    toward = -direction,
                ) * halfCreaturePx

                drawHaiveRelationship(
                    relationship = relationship,
                    start = start,
                    end = end,
                    destinationState = toSubject.node.state,
                    pulsePhase = pulsePhase,
                )
            }
        }

        subjects.forEach { subject ->
            val node = subject.node
            val position = workingPositions[node.id] ?: subject.position
            val center = Offset(position.x * widthPx, position.y * heightPx)
            val isSelected = selectedId == node.id
            val isDropTarget = dropTargetId == node.id

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (center.x - halfCreaturePx).roundToInt(),
                            y = (center.y - halfCreaturePx).roundToInt(),
                        )
                    }
                    .size(creatureSize)
                    .pointerInput(editable, node.id, widthPx, heightPx, subjects) {
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
                                val moved = workingPositions[node.id] ?: subject.position
                                onNodeMoved(node.id, moved)
                                val target = dropTargetId
                                if (target != null && target != node.id) {
                                    onNodeDroppedOn(node.id, target)
                                }
                                draggingId = null
                                dropTargetId = null
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val current = workingPositions[node.id] ?: subject.position
                            val next = H2g2TerrariumPosition(
                                x = current.x + dragAmount.x / widthPx,
                                y = current.y + dragAmount.y / heightPx,
                            ).clamped()
                            workingPositions = workingPositions + (node.id to next)

                            val nextPx = Offset(next.x * widthPx, next.y * heightPx)
                            dropTargetId = subjects
                                .asSequence()
                                .filter { it.node.id != node.id }
                                .map { candidate ->
                                    val candidatePosition = workingPositions[candidate.node.id] ?: candidate.position
                                    val candidatePx = Offset(
                                        candidatePosition.x * widthPx,
                                        candidatePosition.y * heightPx,
                                    )
                                    candidate.node.id to hypot(
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
                HaiveNodeCreaturePanel(
                    node = node,
                    identitySeed = subject.identitySeed,
                    selected = isSelected,
                    dropTarget = isDropTarget,
                    adornments = adornments[node.id].orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        H2g2TerrariumServiceLayer(
            visits = serviceVisits,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun HaiveNodeCreaturePanel(
    node: H2g2WorkflowNode,
    identitySeed: String,
    selected: Boolean,
    dropTarget: Boolean,
    adornments: List<H2g2SwarmAdornment>,
    modifier: Modifier = Modifier,
) {
    val archetype = remember(node.label) { haiveNodeArchetype(node.label) }
    val action = remember(archetype, node.state) { haiveNodeActivityVerb(archetype, node.state) }
    val stateColor = haiveNodeStateColor(node.state)
    val taskLabel = node.subtitle.trim()
    val description = buildString {
        append(node.label)
        append(", ")
        append(action.lowercase())
        if (taskLabel.isNotEmpty()) {
            append(", ")
            append(taskLabel)
        }
    }

    Box(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (selected || dropTarget) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = if (dropTarget) stateColor.copy(alpha = .24f) else Azphalt.White.copy(alpha = .14f),
                    radius = min(size.width, size.height) * .45f,
                    center = center,
                    style = Stroke(width = min(size.width, size.height) * .028f),
                )
            }
        }

        HaiveNodeCreature(
            archetype = archetype,
            identitySeed = identitySeed,
            state = node.state,
            modifier = Modifier.fillMaxSize(),
        )

        H2g2SwarmAdornmentLayer(
            adornments = adornments,
            hueSeed = identitySeed,
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = buildString {
                append(action)
                if (taskLabel.isNotEmpty()) {
                    append(" · ")
                    append(taskLabel)
                }
            },
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
                .background(Azphalt.Ink.copy(alpha = .92f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun HaiveNodeCreature(
    archetype: HaiveNodeArchetype,
    identitySeed: String,
    state: H2g2WorkflowState,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "haive-node-$identitySeed")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (archetype) {
                    HaiveNodeArchetype.Orchestrator -> 1450
                    HaiveNodeArchetype.Builder -> 1200
                    HaiveNodeArchetype.Tester -> 900
                    HaiveNodeArchetype.Inspector -> 1750
                    HaiveNodeArchetype.Reviewer -> 1650
                    HaiveNodeArchetype.Planner -> 2100
                    HaiveNodeArchetype.Researcher -> 1950
                    HaiveNodeArchetype.Generic -> 1850
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "haive-node-phase-$identitySeed",
    )
    val breathe by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1350),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haive-node-breathe-$identitySeed",
    )

    val body = haiveNodeBodyColor(archetype, identitySeed)
    val accent = haiveNodeAccentColor(archetype, identitySeed)
    val antennae = remember(archetype, identitySeed) { haiveAntennae(archetype, identitySeed) }

    Canvas(modifier) {
        drawHaiveNodeCreature(
            archetype = archetype,
            body = body,
            accent = accent,
            state = state,
            antennae = antennae,
            phase = phase,
            breathe = breathe,
        )
    }
}

internal fun haiveNodeArchetype(label: String): HaiveNodeArchetype {
    val normalized = label.lowercase()
    return when {
        "orchestrat" in normalized || "queen" in normalized || "swarm lead" in normalized -> HaiveNodeArchetype.Orchestrator
        "qa" in normalized || "quality" in normalized || "verif" in normalized || "inspect" in normalized -> HaiveNodeArchetype.Inspector
        "review" in normalized -> HaiveNodeArchetype.Reviewer
        "crash" in normalized || "dummy" in normalized || "test" in normalized -> HaiveNodeArchetype.Tester
        "implement" in normalized || "build" in normalized || "develop" in normalized || "coder" in normalized || "engineer" in normalized -> HaiveNodeArchetype.Builder
        "plan" in normalized || "coordinat" in normalized || "decompos" in normalized -> HaiveNodeArchetype.Planner
        "research" in normalized || "analyst" in normalized || "investigat" in normalized -> HaiveNodeArchetype.Researcher
        else -> HaiveNodeArchetype.Generic
    }
}

internal fun haiveNodeAntennaCount(label: String, seed: String): Int =
    haiveAntennae(haiveNodeArchetype(label), seed).size

internal fun haiveNodeActivityVerb(
    archetype: HaiveNodeArchetype,
    state: H2g2WorkflowState,
): String = when (state) {
    H2g2WorkflowState.Blocked -> "BLOCKED"
    H2g2WorkflowState.Failed -> "FAILED"
    H2g2WorkflowState.Complete -> "DONE"
    H2g2WorkflowState.Gate -> "WAITING FOR GATE"
    H2g2WorkflowState.Ready -> "READY"
    H2g2WorkflowState.Pending -> "QUEUED"
    H2g2WorkflowState.Active -> when (archetype) {
        HaiveNodeArchetype.Orchestrator -> "ROUTING"
        HaiveNodeArchetype.Builder -> "BUILDING"
        HaiveNodeArchetype.Tester -> "STRESS-TESTING"
        HaiveNodeArchetype.Inspector -> "VERIFYING"
        HaiveNodeArchetype.Reviewer -> "REVIEWING"
        HaiveNodeArchetype.Planner -> "PLANNING"
        HaiveNodeArchetype.Researcher -> "RESEARCHING"
        HaiveNodeArchetype.Generic -> "WORKING"
    }
}

internal fun haiveNodeTerminalAnchor(
    label: String,
    seed: String,
    toward: Offset,
): Offset {
    if (toward.getDistance() <= .001f) return Offset.Zero
    val direction = normalizeNodeVector(toward)
    val antenna = haiveAntennae(haiveNodeArchetype(label), seed).maxByOrNull { candidate ->
        val terminal = candidate.terminalVector()
        val normalized = normalizeNodeVector(terminal)
        normalized.x * direction.x + normalized.y * direction.y
    } ?: return direction * .78f
    return antenna.terminalVector()
}

private fun haiveAntennae(
    archetype: HaiveNodeArchetype,
    seed: String,
): List<HaiveAntennaSpec> {
    val count = when (archetype) {
        HaiveNodeArchetype.Orchestrator -> 10
        HaiveNodeArchetype.Builder -> 6
        HaiveNodeArchetype.Tester -> 5
        HaiveNodeArchetype.Inspector -> 7
        HaiveNodeArchetype.Reviewer -> 8
        HaiveNodeArchetype.Planner -> 7
        HaiveNodeArchetype.Researcher -> 5
        HaiveNodeArchetype.Generic -> 3 + (positiveNodeHash(seed) % 8)
    }
    val rotation = when (archetype) {
        HaiveNodeArchetype.Orchestrator -> -.18f
        HaiveNodeArchetype.Builder -> .16f
        HaiveNodeArchetype.Tester -> -.38f
        HaiveNodeArchetype.Inspector -> .05f
        HaiveNodeArchetype.Reviewer -> -.08f
        HaiveNodeArchetype.Planner -> .28f
        HaiveNodeArchetype.Researcher -> -.25f
        HaiveNodeArchetype.Generic -> ((positiveNodeHash(seed + ":rotation") % 100) / 100f - .5f) * .52f
    }
    return List(count) { index ->
        val jitter = hashUnit("$seed:$index")
        val angle = rotation + (index.toFloat() / count.toFloat()) * NODE_TAU + (jitter - .5f) * .22f
        val length = when (archetype) {
            HaiveNodeArchetype.Orchestrator -> .82f + jitter * .12f
            HaiveNodeArchetype.Builder -> .72f + jitter * .14f
            HaiveNodeArchetype.Tester -> .74f + jitter * .18f
            HaiveNodeArchetype.Inspector -> .80f + jitter * .16f
            HaiveNodeArchetype.Reviewer -> .75f + jitter * .17f
            HaiveNodeArchetype.Planner -> .80f + jitter * .17f
            HaiveNodeArchetype.Researcher -> .78f + jitter * .15f
            HaiveNodeArchetype.Generic -> .72f + jitter * .2f
        }
        HaiveAntennaSpec(
            angle = angle,
            length = length.coerceIn(.68f, .96f),
            bend = (hashUnit("$seed:bend:$index") - .5f) * .18f,
            terminal = terminalKindFor(archetype, index),
            phase = hashUnit("$seed:phase:$index") * NODE_TAU,
        )
    }
}

private fun terminalKindFor(archetype: HaiveNodeArchetype, index: Int): HaiveTerminalKind = when (archetype) {
    HaiveNodeArchetype.Orchestrator -> if (index % 3 == 0) HaiveTerminalKind.Probe else HaiveTerminalKind.Node
    HaiveNodeArchetype.Builder -> if (index % 2 == 0) HaiveTerminalKind.Clamp else HaiveTerminalKind.Node
    HaiveNodeArchetype.Tester -> when (index % 3) {
        0 -> HaiveTerminalKind.Coil
        1 -> HaiveTerminalKind.Probe
        else -> HaiveTerminalKind.Node
    }
    HaiveNodeArchetype.Inspector -> if (index % 2 == 0) HaiveTerminalKind.Probe else HaiveTerminalKind.Node
    HaiveNodeArchetype.Reviewer -> when (index % 3) {
        0 -> HaiveTerminalKind.Loop
        1 -> HaiveTerminalKind.Fork
        else -> HaiveTerminalKind.Node
    }
    HaiveNodeArchetype.Planner -> if (index % 2 == 0) HaiveTerminalKind.Fork else HaiveTerminalKind.Node
    HaiveNodeArchetype.Researcher -> if (index == 0) HaiveTerminalKind.Loop else HaiveTerminalKind.Probe
    HaiveNodeArchetype.Generic -> HaiveTerminalKind.entries[index % HaiveTerminalKind.entries.size]
}

private fun HaiveAntennaSpec.terminalVector(): Offset {
    val direction = Offset(cos(angle), sin(angle))
    val perpendicular = Offset(-direction.y, direction.x)
    return direction * length + perpendicular * bend
}

private fun DrawScope.drawHaiveNodeCreature(
    archetype: HaiveNodeArchetype,
    body: Color,
    accent: Color,
    state: H2g2WorkflowState,
    antennae: List<HaiveAntennaSpec>,
    phase: Float,
    breathe: Float,
) {
    val s = min(size.width, size.height)
    val half = s / 2f
    val active = state == H2g2WorkflowState.Active
    val blocked = state == H2g2WorkflowState.Blocked || state == H2g2WorkflowState.Failed
    val wave = phase * NODE_TAU
    val shake = when {
        archetype == HaiveNodeArchetype.Tester && active -> sin(wave * 5f) * s * .012f
        blocked -> sin(wave * 2f) * s * .004f
        else -> 0f
    }
    val center = Offset(size.width * .5f + shake, size.height * .48f)
    val radiusX = s * when (archetype) {
        HaiveNodeArchetype.Orchestrator -> .235f
        HaiveNodeArchetype.Builder -> .245f
        HaiveNodeArchetype.Tester -> .215f
        HaiveNodeArchetype.Inspector -> .225f
        HaiveNodeArchetype.Reviewer -> .245f
        HaiveNodeArchetype.Planner -> .215f
        HaiveNodeArchetype.Researcher -> .205f
        HaiveNodeArchetype.Generic -> .22f
    }
    val radiusY = s * when (archetype) {
        HaiveNodeArchetype.Orchestrator -> .225f
        HaiveNodeArchetype.Builder -> .205f
        HaiveNodeArchetype.Tester -> .22f
        HaiveNodeArchetype.Inspector -> .235f
        HaiveNodeArchetype.Reviewer -> .20f
        HaiveNodeArchetype.Planner -> .245f
        HaiveNodeArchetype.Researcher -> .215f
        HaiveNodeArchetype.Generic -> .225f
    } * (1f + breathe * .012f)
    val outline = Azphalt.Ink
    val shadow = shadeNode(body, .58f)
    val light = shadeNode(body, 1.18f)

    antennae.forEachIndexed { index, antenna ->
        drawHaiveAntenna(
            spec = antenna,
            index = index,
            archetype = archetype,
            center = center,
            bodyRadius = min(radiusX, radiusY),
            half = half,
            body = body,
            accent = accent,
            outline = outline,
            state = state,
            wave = wave,
        )
    }

    drawHaiveLegs(
        archetype = archetype,
        center = center,
        radiusX = radiusX,
        radiusY = radiusY,
        accent = accent,
        outline = outline,
        state = state,
        wave = wave,
    )

    drawOval(
        color = outline.copy(alpha = .22f),
        topLeft = Offset(center.x - radiusX * .86f, center.y + radiusY * .88f),
        size = Size(radiusX * 1.72f, radiusY * .34f),
    )

    drawNodeBody(
        archetype = archetype,
        center = center,
        radiusX = radiusX,
        radiusY = radiusY,
        color = shadow,
        offset = Offset(s * .018f, s * .024f),
    )
    drawNodeBody(
        archetype = archetype,
        center = center,
        radiusX = radiusX,
        radiusY = radiusY,
        color = body,
    )
    drawNodeBodyOutline(archetype, center, radiusX, radiusY, outline, s * .018f)
    drawNodeFacet(archetype, center, radiusX, radiusY, light)

    drawHaiveArms(
        archetype = archetype,
        center = center,
        radiusX = radiusX,
        radiusY = radiusY,
        accent = accent,
        outline = outline,
        state = state,
        wave = wave,
    )
    drawHaiveFace(
        archetype = archetype,
        center = center,
        radiusX = radiusX,
        radiusY = radiusY,
        accent = accent,
        outline = outline,
        state = state,
        wave = wave,
    )

    if (blocked) {
        val alert = haiveNodeStateColor(state)
        drawCircle(
            color = alert.copy(alpha = .12f + abs(sin(wave)) * .10f),
            radius = maxOf(radiusX, radiusY) * 1.26f,
            center = center,
            style = Stroke(width = s * .018f),
        )
        drawBlockedTangle(center, radiusX, radiusY, alert, outline, wave)
    }
}

private fun DrawScope.drawHaiveAntenna(
    spec: HaiveAntennaSpec,
    index: Int,
    archetype: HaiveNodeArchetype,
    center: Offset,
    bodyRadius: Float,
    half: Float,
    body: Color,
    accent: Color,
    outline: Color,
    state: H2g2WorkflowState,
    wave: Float,
) {
    val baseDirection = Offset(cos(spec.angle), sin(spec.angle))
    val perpendicular = Offset(-baseDirection.y, baseDirection.x)
    val activeAmplitude = if (state == H2g2WorkflowState.Active) .035f else .012f
    val extension = sin(wave + spec.phase) * activeAmplitude
    val blockedSag = if (
        (state == H2g2WorkflowState.Blocked || state == H2g2WorkflowState.Failed) &&
        archetype == HaiveNodeArchetype.Reviewer
    ) half * .12f * (index % 3) / 2f else 0f
    val root = center + baseDirection * bodyRadius * .78f
    val terminalVector = baseDirection * (spec.length + extension) + perpendicular * spec.bend
    val tip = center + terminalVector * half + Offset(0f, blockedSag)
    val control = (root + tip) * .5f + perpendicular * spec.bend * half * .62f
    val shaft = if (index % 2 == 0) accent else shadeNode(body, .72f)
    val stroke = half * when (archetype) {
        HaiveNodeArchetype.Builder -> .065f
        HaiveNodeArchetype.Orchestrator -> .055f
        else -> .046f
    }

    if (spec.terminal == HaiveTerminalKind.Coil) {
        val segments = 7
        var previous = root
        for (segment in 1..segments) {
            val t = segment.toFloat() / segments.toFloat()
            val base = root + (tip - root) * t
            val zig = perpendicular * (if (segment % 2 == 0) 1f else -1f) * half * .045f
            val next = if (segment == segments) tip else base + zig
            drawLine(outline, previous, next, stroke * 1.55f, StrokeCap.Round)
            drawLine(shaft, previous, next, stroke, StrokeCap.Round)
            previous = next
        }
    } else {
        val path = Path().apply {
            moveTo(root.x, root.y)
            quadraticBezierTo(control.x, control.y, tip.x, tip.y)
        }
        drawPath(path, outline, style = Stroke(width = stroke * 1.55f, cap = StrokeCap.Round))
        drawPath(path, shaft, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }

    drawHaiveTerminal(
        kind = spec.terminal,
        center = tip,
        radius = half * when (spec.terminal) {
            HaiveTerminalKind.Clamp -> .105f
            HaiveTerminalKind.Loop -> .095f
            else -> .085f
        },
        accent = accent,
        outline = outline,
        active = state == H2g2WorkflowState.Active,
        wave = wave + spec.phase,
    )
}

private fun DrawScope.drawHaiveTerminal(
    kind: HaiveTerminalKind,
    center: Offset,
    radius: Float,
    accent: Color,
    outline: Color,
    active: Boolean,
    wave: Float,
) {
    val pulse = if (active) 1f + abs(sin(wave)) * .14f else 1f
    when (kind) {
        HaiveTerminalKind.Node -> {
            drawCircle(outline, radius * 1.24f, center)
            drawCircle(accent, radius * pulse, center)
            drawCircle(Azphalt.Yellow, radius * .34f, center)
        }
        HaiveTerminalKind.Probe -> {
            drawCircle(outline, radius * 1.28f, center)
            drawCircle(Azphalt.White, radius, center)
            drawCircle(accent, radius * .48f * pulse, center)
        }
        HaiveTerminalKind.Clamp -> {
            drawCircle(outline, radius * .45f, center)
            val open = radius * (1.05f + if (active) abs(sin(wave)) * .22f else 0f)
            drawLine(outline, center, center + Offset(open, -open * .72f), radius * .35f, StrokeCap.Round)
            drawLine(outline, center, center + Offset(open, open * .72f), radius * .35f, StrokeCap.Round)
            drawCircle(accent, radius * .34f, center)
        }
        HaiveTerminalKind.Coil -> {
            drawCircle(outline, radius * 1.18f, center, style = Stroke(width = radius * .34f))
            drawCircle(accent, radius * .55f * pulse, center)
        }
        HaiveTerminalKind.Fork -> {
            drawCircle(accent, radius * .35f, center)
            drawLine(outline, center, center + Offset(radius, -radius), radius * .28f, StrokeCap.Round)
            drawLine(outline, center, center + Offset(radius, radius), radius * .28f, StrokeCap.Round)
        }
        HaiveTerminalKind.Loop -> {
            drawCircle(outline, radius * 1.08f, center, style = Stroke(width = radius * .42f))
            drawCircle(accent, radius * .72f, center, style = Stroke(width = radius * .24f))
        }
    }
}

private fun DrawScope.drawHaiveLegs(
    archetype: HaiveNodeArchetype,
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    accent: Color,
    outline: Color,
    state: H2g2WorkflowState,
    wave: Float,
) {
    val count = when (archetype) {
        HaiveNodeArchetype.Orchestrator -> 3
        HaiveNodeArchetype.Builder -> 4
        HaiveNodeArchetype.Tester -> 3
        HaiveNodeArchetype.Inspector -> 3
        HaiveNodeArchetype.Reviewer -> 4
        HaiveNodeArchetype.Planner -> 2
        HaiveNodeArchetype.Researcher -> 2
        HaiveNodeArchetype.Generic -> 3
    }
    val active = state == H2g2WorkflowState.Active
    repeat(count) { index ->
        val fraction = if (count == 1) .5f else index.toFloat() / (count - 1).toFloat()
        val x = center.x + (fraction - .5f) * radiusX * 1.55f
        val hip = Offset(x, center.y + radiusY * .7f)
        val step = if (active) sin(wave * 2f + index) * radiusX * .09f else 0f
        val knee = Offset(x + (fraction - .5f) * radiusX * .22f + step, center.y + radiusY * 1.08f)
        val foot = Offset(knee.x + (fraction - .5f) * radiusX * .22f, center.y + radiusY * 1.28f)
        val stroke = radiusX * .13f
        drawLine(outline, hip, knee, stroke * 1.5f, StrokeCap.Round)
        drawLine(accent, hip, knee, stroke, StrokeCap.Round)
        drawLine(outline, knee, foot, stroke * 1.45f, StrokeCap.Round)
        drawLine(accent, knee, foot, stroke * .9f, StrokeCap.Round)
        drawLine(outline, foot - Offset(radiusX * .09f, 0f), foot + Offset(radiusX * .09f, 0f), stroke * .9f, StrokeCap.Round)
    }
}

private fun DrawScope.drawHaiveArms(
    archetype: HaiveNodeArchetype,
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    accent: Color,
    outline: Color,
    state: H2g2WorkflowState,
    wave: Float,
) {
    val active = state == H2g2WorkflowState.Active
    val motion = if (active) sin(wave * 1.6f) * radiusY * .18f else 0f
    val leftRoot = Offset(center.x - radiusX * .82f, center.y + radiusY * .08f)
    val rightRoot = Offset(center.x + radiusX * .82f, center.y + radiusY * .08f)
    val leftHand = Offset(center.x - radiusX * 1.45f, center.y + radiusY * .18f + motion)
    val rightHand = Offset(center.x + radiusX * 1.45f, center.y + radiusY * .18f - motion)
    val stroke = radiusX * .10f

    listOf(leftRoot to leftHand, rightRoot to rightHand).forEach { (root, hand) ->
        drawLine(outline, root, hand, stroke * 1.55f, StrokeCap.Round)
        drawLine(accent, root, hand, stroke, StrokeCap.Round)
    }

    when (archetype) {
        HaiveNodeArchetype.Builder -> {
            drawBuilderHand(leftHand, -1f, radiusX, accent, outline, active, wave)
            drawBuilderHand(rightHand, 1f, radiusX, accent, outline, active, wave)
        }
        HaiveNodeArchetype.Inspector -> {
            drawCircle(outline, radiusX * .20f, leftHand)
            drawCircle(Azphalt.White, radiusX * .14f, leftHand)
            drawCircle(accent, radiusX * .06f, leftHand)
            val sweep = sin(wave) * radiusX * .45f
            drawLine(
                accent.copy(alpha = .55f),
                rightHand,
                rightHand + Offset(radiusX * .72f, sweep),
                radiusX * .035f,
                StrokeCap.Round,
            )
        }
        HaiveNodeArchetype.Reviewer -> {
            drawReviewPincher(leftHand, -1f, radiusX, accent, outline)
            drawReviewPincher(rightHand, 1f, radiusX, accent, outline)
        }
        HaiveNodeArchetype.Tester -> {
            drawCircle(outline, radiusX * .17f, leftHand)
            drawCircle(accent, radiusX * .11f, leftHand)
            drawLine(outline, rightHand - Offset(0f, radiusX * .15f), rightHand + Offset(0f, radiusX * .15f), radiusX * .05f)
            drawLine(outline, rightHand - Offset(radiusX * .15f, 0f), rightHand + Offset(radiusX * .15f, 0f), radiusX * .05f)
        }
        else -> {
            drawCircle(outline, radiusX * .14f, leftHand)
            drawCircle(accent, radiusX * .08f, leftHand)
            drawCircle(outline, radiusX * .14f, rightHand)
            drawCircle(accent, radiusX * .08f, rightHand)
        }
    }
}

private fun DrawScope.drawBuilderHand(
    hand: Offset,
    direction: Float,
    radius: Float,
    accent: Color,
    outline: Color,
    active: Boolean,
    wave: Float,
) {
    val open = radius * (.18f + if (active) abs(sin(wave)) * .08f else 0f)
    drawCircle(accent, radius * .08f, hand)
    drawLine(outline, hand, hand + Offset(direction * radius * .28f, -open), radius * .065f, StrokeCap.Round)
    drawLine(outline, hand, hand + Offset(direction * radius * .28f, open), radius * .065f, StrokeCap.Round)
}

private fun DrawScope.drawReviewPincher(
    hand: Offset,
    direction: Float,
    radius: Float,
    accent: Color,
    outline: Color,
) {
    drawCircle(accent, radius * .07f, hand)
    drawLine(outline, hand, hand + Offset(direction * radius * .24f, -radius * .11f), radius * .055f, StrokeCap.Round)
    drawLine(outline, hand, hand + Offset(direction * radius * .24f, radius * .11f), radius * .055f, StrokeCap.Round)
}

private fun DrawScope.drawHaiveFace(
    archetype: HaiveNodeArchetype,
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    accent: Color,
    outline: Color,
    state: H2g2WorkflowState,
    wave: Float,
) {
    val cream = Color(0xFFFFF1C8)
    val blocked = state == H2g2WorkflowState.Blocked || state == H2g2WorkflowState.Failed
    val pupilShift = when {
        archetype == HaiveNodeArchetype.Orchestrator && state == H2g2WorkflowState.Active -> sin(wave) * radiusX * .14f
        archetype == HaiveNodeArchetype.Inspector && state == H2g2WorkflowState.Active -> sin(wave * .7f) * radiusX * .16f
        archetype == HaiveNodeArchetype.Reviewer && state == H2g2WorkflowState.Active -> sin(wave * 1.25f) * radiusX * .12f
        else -> 0f
    }

    when (archetype) {
        HaiveNodeArchetype.Orchestrator -> {
            drawOval(
                cream,
                topLeft = Offset(center.x - radiusX * .64f, center.y - radiusY * .28f),
                size = Size(radiusX * 1.28f, radiusY * .62f),
            )
            drawOval(
                outline,
                topLeft = Offset(center.x - radiusX * .13f + pupilShift, center.y - radiusY * .26f),
                size = Size(radiusX * .28f, radiusY * .58f),
            )
            drawCircle(accent, radiusX * .10f, Offset(center.x - radiusX * .74f, center.y - radiusY * .03f))
        }
        HaiveNodeArchetype.Builder -> {
            drawOval(cream, Offset(center.x - radiusX * .66f, center.y - radiusY * .32f), Size(radiusX * 1.0f, radiusY * .68f))
            drawOval(outline, Offset(center.x - radiusX * .42f, center.y - radiusY * .29f), Size(radiusX * .28f, radiusY * .61f))
            drawLine(outline, Offset(center.x + radiusX * .32f, center.y - radiusY * .08f), Offset(center.x + radiusX * .68f, center.y - radiusY * .18f), radiusX * .07f, StrokeCap.Round)
        }
        HaiveNodeArchetype.Tester -> {
            val eyeY = center.y - radiusY * .15f
            drawOval(cream, Offset(center.x - radiusX * .62f, eyeY - radiusY * .23f), Size(radiusX * .45f, radiusY * .52f))
            drawOval(cream, Offset(center.x + radiusX * .12f, eyeY - radiusY * .19f), Size(radiusX * .34f, radiusY * .43f))
            drawCircle(outline, radiusX * .07f, Offset(center.x - radiusX * .39f, eyeY))
            drawCircle(outline, radiusX * .06f, Offset(center.x + radiusX * .29f, eyeY))
            val patch = Offset(center.x + radiusX * .55f, center.y + radiusY * .40f)
            drawLine(cream, patch - Offset(radiusX * .13f, radiusX * .13f), patch + Offset(radiusX * .13f, radiusX * .13f), radiusX * .07f, StrokeCap.Round)
            drawLine(cream, patch + Offset(radiusX * .13f, -radiusX * .13f), patch + Offset(-radiusX * .13f, radiusX * .13f), radiusX * .07f, StrokeCap.Round)
        }
        HaiveNodeArchetype.Inspector -> {
            drawOval(cream, Offset(center.x - radiusX * .68f, center.y - radiusY * .26f), Size(radiusX * 1.36f, radiusY * .52f))
            drawOval(outline, Offset(center.x - radiusX * .10f + pupilShift, center.y - radiusY * .24f), Size(radiusX * .24f, radiusY * .48f))
            drawLine(outline, Offset(center.x - radiusX * .65f, center.y - radiusY * .12f), Offset(center.x + radiusX * .62f, center.y - radiusY * .20f), radiusX * .06f, StrokeCap.Round)
        }
        HaiveNodeArchetype.Reviewer -> {
            val eyeColor = if (blocked) Color(0xFFFF6A55) else cream
            drawRoundRect(
                eyeColor,
                topLeft = Offset(center.x - radiusX * .62f, center.y - radiusY * .22f),
                size = Size(radiusX * 1.24f, radiusY * .42f),
                cornerRadius = CornerRadius(radiusY * .2f),
            )
            drawOval(outline, Offset(center.x - radiusX * .06f + pupilShift, center.y - radiusY * .21f), Size(radiusX * .22f, radiusY * .40f))
            drawLine(outline, Offset(center.x - radiusX * .65f, center.y - radiusY * .30f), Offset(center.x + radiusX * .66f, center.y - radiusY * .12f), radiusX * .075f, StrokeCap.Round)
        }
        HaiveNodeArchetype.Planner -> {
            drawCircle(cream, radiusX * .22f, Offset(center.x - radiusX * .28f, center.y - radiusY * .16f))
            drawCircle(cream, radiusX * .16f, Offset(center.x + radiusX * .33f, center.y - radiusY * .12f))
            drawCircle(outline, radiusX * .07f, Offset(center.x - radiusX * .27f, center.y - radiusY * .16f))
            drawCircle(outline, radiusX * .055f, Offset(center.x + radiusX * .32f, center.y - radiusY * .12f))
        }
        HaiveNodeArchetype.Researcher -> {
            drawCircle(cream, radiusX * .30f, Offset(center.x - radiusX * .12f, center.y - radiusY * .12f))
            drawCircle(outline, radiusX * .10f, Offset(center.x - radiusX * .10f, center.y - radiusY * .12f))
            drawCircle(accent, radiusX * .39f, Offset(center.x - radiusX * .12f, center.y - radiusY * .12f), style = Stroke(width = radiusX * .07f))
            drawLine(accent, Offset(center.x + radiusX * .16f, center.y + radiusY * .10f), Offset(center.x + radiusX * .48f, center.y + radiusY * .42f), radiusX * .07f, StrokeCap.Round)
        }
        HaiveNodeArchetype.Generic -> {
            drawOval(cream, Offset(center.x - radiusX * .50f, center.y - radiusY * .23f), Size(radiusX * 1.0f, radiusY * .5f))
            drawCircle(outline, radiusX * .08f, Offset(center.x, center.y - radiusY * .02f))
        }
    }
}

private fun DrawScope.drawBlockedTangle(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    alert: Color,
    outline: Color,
    wave: Float,
) {
    val wobble = sin(wave) * radiusX * .06f
    val path = Path().apply {
        moveTo(center.x - radiusX * .92f, center.y - radiusY * .88f)
        quadraticBezierTo(center.x - radiusX * .18f, center.y - radiusY * 1.55f + wobble, center.x + radiusX * .22f, center.y - radiusY * .85f)
        quadraticBezierTo(center.x + radiusX * .68f, center.y - radiusY * .28f, center.x + radiusX * .88f, center.y - radiusY * 1.18f)
    }
    drawPath(path, outline, style = Stroke(width = radiusX * .11f, cap = StrokeCap.Round))
    drawPath(path, alert, style = Stroke(width = radiusX * .055f, cap = StrokeCap.Round))
    val badge = Offset(center.x + radiusX * 1.02f, center.y - radiusY * .92f)
    drawCircle(outline, radiusX * .20f, badge)
    drawCircle(alert, radiusX * .15f, badge)
    drawLine(Azphalt.White, badge - Offset(0f, radiusX * .08f), badge + Offset(0f, radiusX * .025f), radiusX * .035f, StrokeCap.Round)
    drawCircle(Azphalt.White, radiusX * .022f, badge + Offset(0f, radiusX * .075f))
}

private fun DrawScope.drawNodeBody(
    archetype: HaiveNodeArchetype,
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    color: Color,
    offset: Offset = Offset.Zero,
) {
    val c = center + offset
    when (archetype) {
        HaiveNodeArchetype.Builder -> drawPath(facetedBodyPath(c, radiusX, radiusY, 8), color)
        HaiveNodeArchetype.Reviewer -> drawPath(facetedBodyPath(c, radiusX, radiusY, 10), color)
        HaiveNodeArchetype.Planner -> drawPath(facetedBodyPath(c, radiusX, radiusY, 7), color)
        else -> drawOval(color, Offset(c.x - radiusX, c.y - radiusY), Size(radiusX * 2f, radiusY * 2f))
    }
}

private fun DrawScope.drawNodeBodyOutline(
    archetype: HaiveNodeArchetype,
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    outline: Color,
    width: Float,
) {
    when (archetype) {
        HaiveNodeArchetype.Builder -> drawPath(facetedBodyPath(center, radiusX, radiusY, 8), outline, style = Stroke(width))
        HaiveNodeArchetype.Reviewer -> drawPath(facetedBodyPath(center, radiusX, radiusY, 10), outline, style = Stroke(width))
        HaiveNodeArchetype.Planner -> drawPath(facetedBodyPath(center, radiusX, radiusY, 7), outline, style = Stroke(width))
        else -> drawOval(outline, Offset(center.x - radiusX, center.y - radiusY), Size(radiusX * 2f, radiusY * 2f), style = Stroke(width))
    }
}

private fun DrawScope.drawNodeFacet(
    archetype: HaiveNodeArchetype,
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    light: Color,
) {
    val path = Path().apply {
        moveTo(center.x - radiusX * .72f, center.y - radiusY * .42f)
        quadraticBezierTo(center.x - radiusX * .10f, center.y - radiusY * 1.0f, center.x + radiusX * .42f, center.y - radiusY * .62f)
        quadraticBezierTo(center.x + radiusX * .05f, center.y - radiusY * .12f, center.x - radiusX * .52f, center.y - radiusY * .02f)
        close()
    }
    drawPath(path, light.copy(alpha = if (archetype == HaiveNodeArchetype.Reviewer) .34f else .56f))
}

private fun facetedBodyPath(center: Offset, radiusX: Float, radiusY: Float, sides: Int): Path = Path().apply {
    repeat(sides) { index ->
        val angle = -PI.toFloat() / 2f + NODE_TAU * index.toFloat() / sides.toFloat()
        val point = Offset(
            center.x + cos(angle) * radiusX,
            center.y + sin(angle) * radiusY,
        )
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

private fun DrawScope.drawHaiveNodeGrid(color: Color) {
    val spacing = 48.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(color.copy(alpha = .055f), Offset(x, 0f), Offset(x, size.height), 1f)
        x += spacing
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(color.copy(alpha = .055f), Offset(0f, y), Offset(size.width, y), 1f)
        y += spacing
    }
}

private fun DrawScope.drawHaiveRelationship(
    relationship: H2g2TerrariumRelationship,
    start: Offset,
    end: Offset,
    destinationState: H2g2WorkflowState,
    pulsePhase: Float,
) {
    val blocked = destinationState == H2g2WorkflowState.Blocked || destinationState == H2g2WorkflowState.Failed
    val baseColor = when {
        blocked -> Color(0xFFC6392F)
        relationship.kind == H2g2TerrariumRelationshipKind.Transfer -> Azphalt.Yellow
        relationship.kind == H2g2TerrariumRelationshipKind.Spawn -> Color(0xFFD9762A)
        relationship.kind == H2g2TerrariumRelationshipKind.Confer -> Color(0xFF8E4FA8)
        else -> Azphalt.currentGround.onPage.copy(alpha = .34f)
    }
    val midpoint = (start + end) * .5f
    val bend = Offset(0f, (end.x - start.x) * .10f)
    val control = midpoint + bend
    val path = Path().apply {
        moveTo(start.x, start.y)
        quadraticBezierTo(control.x, control.y, end.x, end.y)
    }
    drawPath(
        path = path,
        color = Azphalt.Ink.copy(alpha = if (blocked) .38f else .16f),
        style = Stroke(
            width = if (blocked) 7f else 5f,
            cap = StrokeCap.Round,
            pathEffect = if (blocked) PathEffect.dashPathEffect(floatArrayOf(13f, 9f)) else null,
        ),
    )
    drawPath(
        path = path,
        color = baseColor.copy(alpha = if (relationship.active || blocked) .95f else .56f),
        style = Stroke(
            width = if (relationship.active || blocked) 3.6f else 2.4f,
            cap = StrokeCap.Round,
            pathEffect = if (blocked) PathEffect.dashPathEffect(floatArrayOf(13f, 9f)) else null,
        ),
    )

    if (relationship.active) {
        val pulse = quadraticPoint(start, control, end, pulsePhase)
        drawCircle(Azphalt.White.copy(alpha = .86f), 6.5f, pulse)
        drawCircle(baseColor, 4f, pulse)
    }

    if (blocked) {
        val mark = quadraticPoint(start, control, end, .58f)
        drawCircle(Azphalt.Ink, 13f, mark)
        drawCircle(baseColor, 10f, mark)
        drawLine(Azphalt.White, mark - Offset(4f, 4f), mark + Offset(4f, 4f), 2.8f, StrokeCap.Round)
        drawLine(Azphalt.White, mark + Offset(4f, -4f), mark + Offset(-4f, 4f), 2.8f, StrokeCap.Round)
    }
}

private fun quadraticPoint(start: Offset, control: Offset, end: Offset, t: Float): Offset {
    val one = 1f - t
    return start * (one * one) + control * (2f * one * t) + end * (t * t)
}

private fun haiveNodeBodyColor(archetype: HaiveNodeArchetype, seed: String): Color = when (archetype) {
    HaiveNodeArchetype.Orchestrator -> Color(0xFF252427)
    HaiveNodeArchetype.Builder -> Color(0xFFD9762A)
    HaiveNodeArchetype.Tester -> Color(0xFF2E6FB7)
    HaiveNodeArchetype.Inspector -> Color(0xFF8E4FA8)
    HaiveNodeArchetype.Reviewer -> Color(0xFF444449)
    HaiveNodeArchetype.Planner -> Color(0xFF1F9E86)
    HaiveNodeArchetype.Researcher -> Color(0xFF2FA9C4)
    HaiveNodeArchetype.Generic -> Azphalt.hue(seed)
}

private fun haiveNodeAccentColor(archetype: HaiveNodeArchetype, seed: String): Color = when (archetype) {
    HaiveNodeArchetype.Orchestrator -> Color(0xFFE85B3C)
    HaiveNodeArchetype.Builder -> Color(0xFF9A3F18)
    HaiveNodeArchetype.Tester -> Color(0xFFF0D42A)
    HaiveNodeArchetype.Inspector -> Color(0xFFD4728F)
    HaiveNodeArchetype.Reviewer -> Color(0xFFC6392F)
    HaiveNodeArchetype.Planner -> Color(0xFF5AAE34)
    HaiveNodeArchetype.Researcher -> Color(0xFF163A63)
    HaiveNodeArchetype.Generic -> Azphalt.cap(seed)
}

private fun haiveNodeStateColor(state: H2g2WorkflowState): Color = when (state) {
    H2g2WorkflowState.Active -> Color(0xFF5AAE34)
    H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed -> Color(0xFFC6392F)
    H2g2WorkflowState.Gate -> Color(0xFFD9762A)
    H2g2WorkflowState.Ready -> Color(0xFF2E6FB7)
    H2g2WorkflowState.Complete -> Color(0xFF1F9E86)
    H2g2WorkflowState.Pending -> Color(0xFF8A9296)
}

private fun shadeNode(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha,
)

private fun positiveNodeHash(value: String): Int = value.hashCode() and 0x7fffffff

private fun hashUnit(value: String): Float = (positiveNodeHash(value) % 10_000) / 9_999f

private fun normalizeNodeVector(value: Offset): Offset {
    val length = value.getDistance()
    return if (length <= .001f) Offset.Zero else value / length
}
