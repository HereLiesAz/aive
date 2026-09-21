package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the approved 2D workflow-mascot family.
 *
 * These are intentionally flat, character-led creatures derived from the supplied mascot sheets.
 * The old projected 3D/native mesh is not involved in this surface.
 */
@Composable
internal fun NodeMascotSurface(
    roleLabel: String,
    hueSeed: String,
    state: H2g2WorkflowState,
    motionPhase: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawNodeMascot(
            role = classifyNodeCreatureRole(roleLabel),
            hueSeed = hueSeed,
            state = state,
            phase = motionPhase,
        )
    }
}

private enum class MascotTerminal {
    Round,
    Square,
    Eye,
    Plug,
    Spike,
    Leaf,
}

private enum class MascotAccessory {
    Baton,
    Clipboard,
    Magnifier,
    Blueprint,
    Wrench,
    Heart,
    Goggles,
    Stop,
    Tablet,
    Bandage,
    Key,
    Flame,
    Dashboard,
    None,
}

private data class MascotSpec(
    val body: Color,
    val dark: Color,
    val antennaCount: Int,
    val terminal: MascotTerminal,
    val accessory: MascotAccessory,
    val angular: Boolean = false,
    val radial: Boolean = false,
)

private fun mascotSpec(role: NodeCreatureRoleKind, hueSeed: String): MascotSpec = when (role) {
    NodeCreatureRoleKind.Orchestrator -> MascotSpec(
        body = Color(0xFFFFB91F),
        dark = Color(0xFF111827),
        antennaCount = 10,
        terminal = MascotTerminal.Round,
        accessory = MascotAccessory.Baton,
        radial = true,
    )
    NodeCreatureRoleKind.ProductManager -> MascotSpec(
        body = Color(0xFFF56C9B),
        dark = Color(0xFF5B2440),
        antennaCount = 8,
        terminal = MascotTerminal.Round,
        accessory = MascotAccessory.Clipboard,
        radial = true,
    )
    NodeCreatureRoleKind.Researcher -> MascotSpec(
        body = Color(0xFF38AAEA),
        dark = Color(0xFF153E67),
        antennaCount = 5,
        terminal = MascotTerminal.Eye,
        accessory = MascotAccessory.Magnifier,
    )
    NodeCreatureRoleKind.Architect -> MascotSpec(
        body = Color(0xFF8D5AE9),
        dark = Color(0xFF4D2A86),
        antennaCount = 7,
        terminal = MascotTerminal.Square,
        accessory = MascotAccessory.Blueprint,
        angular = true,
    )
    NodeCreatureRoleKind.EpaRepresentative -> MascotSpec(
        body = Color(0xFF43B94D),
        dark = Color(0xFF195B2B),
        antennaCount = 7,
        terminal = MascotTerminal.Plug,
        accessory = MascotAccessory.Wrench,
    )
    NodeCreatureRoleKind.UxDesigner -> MascotSpec(
        body = Color(0xFFF66F93),
        dark = Color(0xFF6B2944),
        antennaCount = 8,
        terminal = MascotTerminal.Round,
        accessory = MascotAccessory.Heart,
        radial = true,
    )
    NodeCreatureRoleKind.ImplementationEngineer -> MascotSpec(
        body = Color(0xFFFF7B00),
        dark = Color(0xFF6C370F),
        antennaCount = 8,
        terminal = MascotTerminal.Square,
        accessory = MascotAccessory.Wrench,
        angular = true,
        radial = true,
    )
    NodeCreatureRoleKind.CrashTestDummy -> MascotSpec(
        body = Color(0xFFFFD134),
        dark = Color(0xFF655316),
        antennaCount = 6,
        terminal = MascotTerminal.Round,
        accessory = MascotAccessory.Goggles,
    )
    NodeCreatureRoleKind.QaEngineer -> MascotSpec(
        body = Color(0xFF3CC4DA),
        dark = Color(0xFF173B59),
        antennaCount = 5,
        terminal = MascotTerminal.Eye,
        accessory = MascotAccessory.Tablet,
    )
    NodeCreatureRoleKind.AdversarialReviewer -> MascotSpec(
        body = Color(0xFFF12E3D),
        dark = Color(0xFF711621),
        antennaCount = 8,
        terminal = MascotTerminal.Spike,
        accessory = MascotAccessory.Stop,
        angular = true,
        radial = true,
    )
    NodeCreatureRoleKind.CodeReviewer -> MascotSpec(
        body = Color(0xFF2F70E8),
        dark = Color(0xFF173D7A),
        antennaCount = 7,
        terminal = MascotTerminal.Square,
        accessory = MascotAccessory.Tablet,
        angular = true,
    )
    NodeCreatureRoleKind.RecoveryEngineer -> MascotSpec(
        body = Color(0xFF59C793),
        dark = Color(0xFF1F6847),
        antennaCount = 7,
        terminal = MascotTerminal.Leaf,
        accessory = MascotAccessory.Bandage,
    )
    NodeCreatureRoleKind.ReleaseEngineer -> MascotSpec(
        body = Color(0xFFA66EE8),
        dark = Color(0xFF57337E),
        antennaCount = 7,
        terminal = MascotTerminal.Round,
        accessory = MascotAccessory.Key,
        radial = true,
    )
    NodeCreatureRoleKind.Antagonist -> MascotSpec(
        body = Color(0xFF1A1A1A),
        dark = Color(0xFF050505),
        antennaCount = 9,
        terminal = MascotTerminal.Spike,
        accessory = MascotAccessory.Flame,
        angular = true,
        radial = true,
    )
    NodeCreatureRoleKind.HallMonitor -> MascotSpec(
        body = Color(0xFF78AFE8),
        dark = Color(0xFF244D81),
        antennaCount = 7,
        terminal = MascotTerminal.Eye,
        accessory = MascotAccessory.Dashboard,
        radial = true,
    )
    NodeCreatureRoleKind.Generic -> MascotSpec(
        body = Azphalt.hue(hueSeed),
        dark = Azphalt.cap(hueSeed),
        antennaCount = 6,
        terminal = MascotTerminal.Round,
        accessory = MascotAccessory.None,
        radial = true,
    )
}

private fun DrawScope.drawNodeMascot(
    role: NodeCreatureRoleKind,
    hueSeed: String,
    state: H2g2WorkflowState,
    phase: Float,
) {
    val spec = mascotSpec(role, hueSeed)
    val side = min(size.width, size.height)
    val unit = side / 200f
    val left = (size.width - side) * 0.5f
    val top = (size.height - side) * 0.5f

    fun point(x: Float, y: Float) = Offset(left + x * unit, top + y * unit)

    val wave = sin(phase * 2f * PI.toFloat())
    val puppet = MascotPuppetRig.pose(
        role = role,
        state = state,
        phase = phase,
        antennaCount = spec.antennaCount,
    )
    val bounce = when (state) {
        H2g2WorkflowState.Active -> -3.2f * wave
        H2g2WorkflowState.Complete -> -2.0f * kotlin.math.abs(wave)
        H2g2WorkflowState.Gate -> -1.6f * wave
        H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed -> 2.2f
        else -> -0.8f * wave
    }
    val rotation = when (state) {
        H2g2WorkflowState.Active -> wave * 2.2f
        H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed -> -3.0f
        H2g2WorkflowState.Complete -> wave * 1.2f
        else -> wave * 0.6f
    }
    val scale = when (state) {
        H2g2WorkflowState.Complete -> 1f + 0.018f * kotlin.math.abs(wave)
        H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed -> 0.975f
        else -> 1f
    }

    withTransform({
        translate(0f, bounce * unit)
        rotate(rotation, point(100f, 100f))
        scale(scale, scale, point(100f, 100f))
    }) {
        drawMascotShadow(::point, unit, spec)
        drawMascotAntennae(::point, unit, role, spec, wave, puppet)
        drawMascotBody(::point, unit, role, spec, puppet)
        drawMascotAccessory(::point, unit, role, spec, wave, puppet)
        drawMascotFace(::point, unit, role, spec, puppet)
        drawMascotStateMark(::point, unit, state, wave)
    }
}

private fun DrawScope.drawMascotShadow(
    point: (Float, Float) -> Offset,
    unit: Float,
    spec: MascotSpec,
) {
    drawOval(
        color = spec.dark.copy(alpha = 0.10f),
        topLeft = point(64f, 166f),
        size = Size(72f * unit, 10f * unit),
    )
}

private fun DrawScope.drawMascotAntennae(
    point: (Float, Float) -> Offset,
    unit: Float,
    role: NodeCreatureRoleKind,
    spec: MascotSpec,
    wave: Float,
    puppet: MascotPuppetPose,
) {
    val headCenter = puppet.map(MascotPuppetRig.Head, 100f, 83f)
    val center = point(headCenter.x, headCenter.y)
    val spanStart = if (spec.radial) -168f else -150f
    val spanEnd = if (spec.radial) 168f else -30f
    val count = spec.antennaCount.coerceAtLeast(1)

    repeat(count) { index ->
        val fraction = if (count == 1) 0.5f else index.toFloat() / (count - 1).toFloat()
        var degrees = spanStart + (spanEnd - spanStart) * fraction
        if (!spec.radial && degrees > 10f) degrees -= 80f
        val angle = degrees * PI.toFloat() / 180f
        val wobble = if (role == NodeCreatureRoleKind.CrashTestDummy) {
            sin((fraction * 5f + wave) * PI.toFloat()) * 8f
        } else {
            wave * if (index % 2 == 0) 1.8f else -1.8f
        }
        val length = when (role) {
            NodeCreatureRoleKind.Orchestrator -> 58f + (index % 3) * 5f
            NodeCreatureRoleKind.HallMonitor -> 50f + (index % 2) * 8f
            NodeCreatureRoleKind.AdversarialReviewer, NodeCreatureRoleKind.Antagonist -> 56f + (index % 3) * 4f
            else -> 47f + (index % 3) * 6f
        }
        val antennaBone = MascotPuppetRig.antenna(index)
        fun rigPoint(x: Float, y: Float): Offset {
            val mapped = puppet.map(antennaBone, x, y)
            return point(mapped.x, mapped.y)
        }

        val rootRadius = 31f
        val rootRestX = 100f + cos(angle) * rootRadius
        val rootRestY = 83f + sin(angle) * rootRadius
        val tipRestX = 100f + cos(angle) * (length + wobble)
        val tipRestY = 83f + sin(angle) * (length + wobble)
        val root = rigPoint(rootRestX, rootRestY)
        val tip = rigPoint(tipRestX, tipRestY)
        val animatedAngle = angle +
            puppet.world(antennaBone).rotationDegrees * PI.toFloat() / 180f

        if (spec.angular) {
            val elbowRestX = (rootRestX + tipRestX) * 0.5f + sin(angle) * 8f
            val elbowRestY = (rootRestY + tipRestY) * 0.5f - cos(angle) * 8f
            val elbow = rigPoint(elbowRestX, elbowRestY)
            drawLine(spec.body, root, elbow, 5.2f * unit, StrokeCap.Round)
            drawLine(spec.body, elbow, tip, 5.2f * unit, StrokeCap.Round)
        } else {
            val bend = 11f * if (index % 2 == 0) 1f else -1f
            val c1 = rigPoint(
                rootRestX * 0.72f + tipRestX * 0.28f + sin(angle) * bend,
                rootRestY * 0.72f + tipRestY * 0.28f - cos(angle) * bend,
            )
            val c2 = rigPoint(
                rootRestX * 0.28f + tipRestX * 0.72f - sin(angle) * bend,
                rootRestY * 0.28f + tipRestY * 0.72f + cos(angle) * bend,
            )
            val path = Path().apply {
                moveTo(root.x, root.y)
                cubicTo(c1.x, c1.y, c2.x, c2.y, tip.x, tip.y)
            }
            drawPath(path, spec.body, style = Stroke(5.1f * unit, cap = StrokeCap.Round))
        }

        drawMascotTerminal(
            center = tip,
            angle = animatedAngle,
            unit = unit,
            terminal = terminalFor(role, spec.terminal, index),
            body = spec.body,
            dark = spec.dark,
        )
    }
}

private fun terminalFor(
    role: NodeCreatureRoleKind,
    fallback: MascotTerminal,
    index: Int,
): MascotTerminal = when (role) {
    NodeCreatureRoleKind.EpaRepresentative ->
        if (index % 3 == 0) MascotTerminal.Plug else MascotTerminal.Leaf
    NodeCreatureRoleKind.RecoveryEngineer ->
        if (index % 2 == 0) MascotTerminal.Leaf else MascotTerminal.Round
    NodeCreatureRoleKind.Researcher ->
        if (index < 3) MascotTerminal.Eye else MascotTerminal.Round
    NodeCreatureRoleKind.HallMonitor -> MascotTerminal.Eye
    else -> fallback
}

private fun DrawScope.drawMascotTerminal(
    center: Offset,
    angle: Float,
    unit: Float,
    terminal: MascotTerminal,
    body: Color,
    dark: Color,
) {
    when (terminal) {
        MascotTerminal.Round -> drawCircle(body, 6.0f * unit, center)
        MascotTerminal.Square -> drawRect(
            body,
            topLeft = center - Offset(6f * unit, 6f * unit),
            size = Size(12f * unit, 12f * unit),
        )
        MascotTerminal.Eye -> {
            drawCircle(Color(0xFFF3FAFF), 7.4f * unit, center)
            drawCircle(body, 6.4f * unit, center, style = Stroke(2.0f * unit))
            drawCircle(dark, 2.5f * unit, center)
        }
        MascotTerminal.Plug -> {
            val w = 12f * unit
            val h = 11f * unit
            drawRoundRect(
                body,
                topLeft = center - Offset(w * 0.5f, h * 0.5f),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.2f * unit),
            )
            val outward = Offset(cos(angle), sin(angle))
            val tangent = Offset(-outward.y, outward.x)
            repeat(2) { pin ->
                val side = if (pin == 0) -2.3f else 2.3f
                val start = center + outward * 5f * unit + tangent * side * unit
                drawLine(body, start, start + outward * 5f * unit, 1.8f * unit, StrokeCap.Round)
            }
        }
        MascotTerminal.Spike -> {
            val outward = Offset(cos(angle), sin(angle))
            val tangent = Offset(-outward.y, outward.x)
            val path = Path().apply {
                val a = center + outward * 9f * unit
                val b = center - outward * 5f * unit + tangent * 5f * unit
                val c = center - outward * 5f * unit - tangent * 5f * unit
                moveTo(a.x, a.y)
                lineTo(b.x, b.y)
                lineTo(c.x, c.y)
                close()
            }
            drawPath(path, body)
        }
        MascotTerminal.Leaf -> {
            val outward = Offset(cos(angle), sin(angle))
            val tangent = Offset(-outward.y, outward.x)
            val a = center + outward * 8f * unit
            val b = center - outward * 7f * unit
            val path = Path().apply {
                moveTo(b.x, b.y)
                cubicTo(
                    (center + tangent * 7f * unit).x,
                    (center + tangent * 7f * unit).y,
                    (a + tangent * 4f * unit).x,
                    (a + tangent * 4f * unit).y,
                    a.x,
                    a.y,
                )
                cubicTo(
                    (a - tangent * 4f * unit).x,
                    (a - tangent * 4f * unit).y,
                    (center - tangent * 7f * unit).x,
                    (center - tangent * 7f * unit).y,
                    b.x,
                    b.y,
                )
                close()
            }
            drawPath(path, body)
        }
    }
}

private fun DrawScope.drawMascotBody(
    point: (Float, Float) -> Offset,
    unit: Float,
    role: NodeCreatureRoleKind,
    spec: MascotSpec,
    puppet: MascotPuppetPose,
) {
    fun rigPoint(bone: String, x: Float, y: Float): Offset {
        val mapped = puppet.map(bone, x, y)
        return point(mapped.x, mapped.y)
    }
    val head = rigPoint(MascotPuppetRig.Head, 100f, 83f)

    if (role == NodeCreatureRoleKind.Antagonist) {
        val thornPoints = listOf(
            64f to 84f,
            48f to 72f,
            61f to 69f,
            51f to 53f,
            70f to 61f,
            76f to 47f,
            85f to 61f,
        ).map { (x, y) -> rigPoint(MascotPuppetRig.Head, x, y) }
        val thorn = Path().apply {
            moveTo(thornPoints.first().x, thornPoints.first().y)
            thornPoints.drop(1).forEach { p -> lineTo(p.x, p.y) }
            close()
        }
        drawPath(thorn, spec.body)
    }

    drawCircle(spec.body, 34f * unit, head)
    drawCircle(
        color = Color.White.copy(alpha = 0.12f),
        radius = 26f * unit,
        center = head - Offset(6f * unit, 8f * unit),
    )

    val segmentColor = spec.body
    drawCircle(segmentColor, 11f * unit, rigPoint(MascotPuppetRig.TorsoUpper, 100f, 120f))
    drawCircle(segmentColor.copy(alpha = 0.94f), 9f * unit, rigPoint(MascotPuppetRig.TorsoMid, 100f, 139f))
    drawCircle(segmentColor.copy(alpha = 0.88f), 7f * unit, rigPoint(MascotPuppetRig.Pelvis, 100f, 155f))

    drawMascotLeg(point, unit, spec, left = true, puppet = puppet)
    drawMascotLeg(point, unit, spec, left = false, puppet = puppet)

    if (role == NodeCreatureRoleKind.ReleaseEngineer) {
        val capePoints = listOf(
            82f to 109f,
            58f to 136f,
            78f to 132f,
            86f to 119f,
        ).map { (x, y) -> rigPoint(MascotPuppetRig.TorsoUpper, x, y) }
        val cape = Path().apply {
            moveTo(capePoints.first().x, capePoints.first().y)
            capePoints.drop(1).forEach { p -> lineTo(p.x, p.y) }
            close()
        }
        drawPath(cape, Color(0xFF7C57CC).copy(alpha = 0.88f))
    }
}

private fun DrawScope.drawMascotLeg(
    point: (Float, Float) -> Offset,
    unit: Float,
    spec: MascotSpec,
    left: Boolean,
    puppet: MascotPuppetPose,
) {
    val side = if (left) -1f else 1f
    val bone = if (left) MascotPuppetRig.LeftLeg else MascotPuppetRig.RightLeg
    fun rigPoint(x: Float, y: Float): Offset {
        val mapped = puppet.map(bone, x, y)
        return point(mapped.x, mapped.y)
    }
    val hip = rigPoint(100f + side * 6f, 154f)
    val knee = rigPoint(100f + side * 16f, 166f)
    val foot = rigPoint(100f + side * 25f, 169f)
    drawLine(spec.body, hip, knee, 4f * unit, StrokeCap.Round)
    drawLine(spec.body, knee, foot, 4f * unit, StrokeCap.Round)
    drawCircle(spec.body, 3.4f * unit, foot)
}

private fun DrawScope.drawMascotFace(
    point: (Float, Float) -> Offset,
    unit: Float,
    role: NodeCreatureRoleKind,
    spec: MascotSpec,
    puppet: MascotPuppetPose,
) {
    fun headPoint(x: Float, y: Float): Offset {
        val mapped = puppet.map(MascotPuppetRig.Head, x, y)
        return point(mapped.x, mapped.y)
    }
    fun eyePoint(bone: String, x: Float, y: Float): Offset {
        val mapped = puppet.map(bone, x, y)
        return point(mapped.x, mapped.y)
    }
    val eyeY = 81f
    val leftEye = eyePoint(MascotPuppetRig.LeftEye, 88f, eyeY)
    val rightEye = eyePoint(MascotPuppetRig.RightEye, 112f, eyeY)
    val leftBlink = puppet.world(MascotPuppetRig.LeftEye).scaleY
    val rightBlink = puppet.world(MascotPuppetRig.RightEye).scaleY

    when (role) {
        NodeCreatureRoleKind.Orchestrator,
        NodeCreatureRoleKind.Architect,
        NodeCreatureRoleKind.RecoveryEngineer -> {
            drawArc(
                spec.dark,
                startAngle = 8f,
                sweepAngle = 164f,
                useCenter = false,
                topLeft = leftEye - Offset(6f * unit, 2f * unit),
                size = Size(12f * unit, 8f * unit),
                style = Stroke(2.4f * unit, cap = StrokeCap.Round),
            )
            drawArc(
                spec.dark,
                startAngle = 8f,
                sweepAngle = 164f,
                useCenter = false,
                topLeft = rightEye - Offset(6f * unit, 2f * unit),
                size = Size(12f * unit, 8f * unit),
                style = Stroke(2.4f * unit, cap = StrokeCap.Round),
            )
        }
        NodeCreatureRoleKind.Antagonist -> {
            drawLine(Color.White, headPoint(81f, 77f), headPoint(94f, 82f), 4f * unit, StrokeCap.Round)
            drawLine(Color.White, headPoint(119f, 77f), headPoint(106f, 82f), 4f * unit, StrokeCap.Round)
        }
        NodeCreatureRoleKind.AdversarialReviewer,
        NodeCreatureRoleKind.ImplementationEngineer -> {
            drawLine(spec.dark, headPoint(81f, 76f), headPoint(94f, 82f), 3.2f * unit, StrokeCap.Round)
            drawLine(spec.dark, headPoint(119f, 76f), headPoint(106f, 82f), 3.2f * unit, StrokeCap.Round)
            drawOval(
                Color.White,
                topLeft = leftEye - Offset(5f * unit, 5f * unit * leftBlink),
                size = Size(10f * unit, 10f * unit * leftBlink),
            )
            drawOval(
                Color.White,
                topLeft = rightEye - Offset(5f * unit, 5f * unit * rightBlink),
                size = Size(10f * unit, 10f * unit * rightBlink),
            )
            drawCircle(spec.dark, 2.2f * unit * leftBlink.coerceAtLeast(0.45f), leftEye)
            drawCircle(spec.dark, 2.2f * unit * rightBlink.coerceAtLeast(0.45f), rightEye)
        }
        else -> {
            drawOval(
                Color.White,
                topLeft = leftEye - Offset(6.8f * unit, 6.8f * unit * leftBlink),
                size = Size(13.6f * unit, 13.6f * unit * leftBlink),
            )
            drawOval(
                Color.White,
                topLeft = rightEye - Offset(6.8f * unit, 6.8f * unit * rightBlink),
                size = Size(13.6f * unit, 13.6f * unit * rightBlink),
            )
            drawCircle(spec.dark, 3.2f * unit * leftBlink.coerceAtLeast(0.45f), leftEye)
            drawCircle(spec.dark, 3.2f * unit * rightBlink.coerceAtLeast(0.45f), rightEye)
            drawCircle(Color.White.copy(alpha = 0.78f), 1.2f * unit, leftEye - Offset(1f * unit, 1f * unit))
            drawCircle(Color.White.copy(alpha = 0.78f), 1.2f * unit, rightEye - Offset(1f * unit, 1f * unit))
        }
    }

    if (role == NodeCreatureRoleKind.CodeReviewer) {
        drawRoundRect(
            color = spec.dark,
            topLeft = headPoint(77f, 72f),
            size = Size(21f * unit, 17f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
            style = Stroke(2.6f * unit),
        )
        drawRoundRect(
            color = spec.dark,
            topLeft = headPoint(102f, 72f),
            size = Size(21f * unit, 17f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
            style = Stroke(2.6f * unit),
        )
        drawLine(spec.dark, headPoint(98f, 79f), headPoint(102f, 79f), 2.5f * unit)
    }

    val mouthY = 98f
    when (role) {
        NodeCreatureRoleKind.AdversarialReviewer,
        NodeCreatureRoleKind.ImplementationEngineer,
        NodeCreatureRoleKind.Antagonist -> {
            drawArc(
                spec.dark,
                startAngle = 198f,
                sweepAngle = 144f,
                useCenter = false,
                topLeft = headPoint(91f, mouthY - 3f),
                size = Size(18f * unit, 11f * unit),
                style = Stroke(2.2f * unit, cap = StrokeCap.Round),
            )
        }
        else -> {
            drawArc(
                spec.dark,
                startAngle = 18f,
                sweepAngle = 144f,
                useCenter = false,
                topLeft = headPoint(91f, mouthY - 7f),
                size = Size(18f * unit, 11f * unit),
                style = Stroke(2.2f * unit, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawMascotAccessory(
    point: (Float, Float) -> Offset,
    unit: Float,
    role: NodeCreatureRoleKind,
    spec: MascotSpec,
    wave: Float,
    puppet: MascotPuppetPose,
) {
    fun propPoint(x: Float, y: Float): Offset {
        val mapped = puppet.map(MascotPuppetRig.Prop, x, y)
        return point(mapped.x, mapped.y)
    }
    fun headPoint(x: Float, y: Float): Offset {
        val mapped = puppet.map(MascotPuppetRig.Head, x, y)
        return point(mapped.x, mapped.y)
    }
    when (spec.accessory) {
        MascotAccessory.Baton -> {
            drawLine(Color(0xFF111827), propPoint(65f, 90f), propPoint(58f, 55f), 3.3f * unit, StrokeCap.Round)
            drawCircle(Color(0xFF111827), 3f * unit, propPoint(57f, 53f))
            drawBowTie(headPoint, unit)
        }
        MascotAccessory.Clipboard -> drawClipboard(propPoint, unit, propPoint(139f, 105f))
        MascotAccessory.Magnifier -> drawMagnifier(propPoint, unit, propPoint(132f, 84f), spec.dark)
        MascotAccessory.Blueprint -> {
            drawBlueprint(propPoint, unit)
            drawCompass(propPoint, unit)
        }
        MascotAccessory.Wrench -> drawWrench(propPoint, unit, spec.dark)
        MascotAccessory.Heart -> {
            drawHeart(propPoint, unit, propPoint(140f, 118f), spec.body)
            drawHeart(propPoint, unit, propPoint(58f, 61f), spec.body)
        }
        MascotAccessory.Goggles -> drawGoggles(headPoint, unit, spec.dark)
        MascotAccessory.Stop -> drawStop(propPoint, unit)
        MascotAccessory.Tablet -> drawTablet(propPoint, unit, spec.dark)
        MascotAccessory.Bandage -> {
            drawBandage(headPoint, unit)
            drawPlus(propPoint, unit)
        }
        MascotAccessory.Key -> drawKey(propPoint, unit)
        MascotAccessory.Flame -> drawFlame(propPoint, unit)
        MascotAccessory.Dashboard -> drawDashboard(propPoint, unit, spec.body)
        MascotAccessory.None -> Unit
    }

    if (role == NodeCreatureRoleKind.CrashTestDummy && wave > 0.55f) {
        drawLine(Color(0xFF3AA9EA), propPoint(143f, 68f), propPoint(149f, 61f), 2f * unit, StrokeCap.Round)
        drawLine(Color(0xFF3AA9EA), propPoint(146f, 74f), propPoint(154f, 72f), 2f * unit, StrokeCap.Round)
    }
}

private fun DrawScope.drawBowTie(point: (Float, Float) -> Offset, unit: Float) {
    val center = point(100f, 113f)
    val left = Path().apply {
        moveTo(center.x, center.y)
        lineTo(point(87f, 106f).x, point(87f, 106f).y)
        lineTo(point(87f, 120f).x, point(87f, 120f).y)
        close()
    }
    val right = Path().apply {
        moveTo(center.x, center.y)
        lineTo(point(113f, 106f).x, point(113f, 106f).y)
        lineTo(point(113f, 120f).x, point(113f, 120f).y)
        close()
    }
    drawPath(left, Color(0xFF111827))
    drawPath(right, Color(0xFF111827))
    drawCircle(Color(0xFF111827), 3.6f * unit, center)
}

private fun DrawScope.drawClipboard(
    point: (Float, Float) -> Offset,
    unit: Float,
    center: Offset,
) {
    val purple = Color(0xFF7657E9)
    drawRoundRect(
        Color(0xFFF7F4FF),
        topLeft = center - Offset(16f * unit, 22f * unit),
        size = Size(32f * unit, 44f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
    )
    drawRoundRect(
        purple,
        topLeft = center - Offset(16f * unit, 22f * unit),
        size = Size(32f * unit, 44f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
        style = Stroke(2.5f * unit),
    )
    repeat(3) { row ->
        val y = center.y - 10f * unit + row * 10f * unit
        drawLine(purple, Offset(center.x - 5f * unit, y), Offset(center.x + 10f * unit, y), 1.8f * unit, StrokeCap.Round)
        drawRect(
            purple,
            topLeft = Offset(center.x - 11f * unit, y - 2f * unit),
            size = Size(4f * unit, 4f * unit),
            style = Stroke(1.5f * unit),
        )
    }
}

private fun DrawScope.drawMagnifier(
    point: (Float, Float) -> Offset,
    unit: Float,
    center: Offset,
    dark: Color,
) {
    drawCircle(Color(0xFFE9F8FF), 13f * unit, center)
    drawCircle(dark, 13f * unit, center, style = Stroke(4f * unit))
    drawLine(dark, center + Offset(9f * unit, 9f * unit), center + Offset(22f * unit, 22f * unit), 5f * unit, StrokeCap.Round)
}

private fun DrawScope.drawBlueprint(point: (Float, Float) -> Offset, unit: Float) {
    val blue = Color(0xFF1565C0)
    val tl = point(121f, 110f)
    drawRect(blue, tl, Size(44f * unit, 36f * unit))
    repeat(3) { i ->
        val x = tl.x + (8f + i * 11f) * unit
        drawLine(Color.White.copy(alpha = 0.72f), Offset(x, tl.y + 4f * unit), Offset(x, tl.y + 32f * unit), 1f * unit)
    }
    repeat(2) { i ->
        val y = tl.y + (12f + i * 11f) * unit
        drawLine(Color.White.copy(alpha = 0.72f), Offset(tl.x + 4f * unit, y), Offset(tl.x + 40f * unit, y), 1f * unit)
    }
}

private fun DrawScope.drawCompass(point: (Float, Float) -> Offset, unit: Float) {
    val blue = Color(0xFF1A5AA5)
    drawCircle(blue, 3.5f * unit, point(58f, 110f))
    drawLine(blue, point(58f, 110f), point(48f, 145f), 3f * unit, StrokeCap.Round)
    drawLine(blue, point(58f, 110f), point(69f, 145f), 3f * unit, StrokeCap.Round)
    drawLine(blue, point(51f, 133f), point(66f, 133f), 2f * unit, StrokeCap.Round)
}

private fun DrawScope.drawWrench(point: (Float, Float) -> Offset, unit: Float, dark: Color) {
    drawLine(dark, point(137f, 111f), point(157f, 86f), 8f * unit, StrokeCap.Round)
    drawArc(
        dark,
        startAngle = 34f,
        sweepAngle = 292f,
        useCenter = false,
        topLeft = point(147f, 70f),
        size = Size(22f * unit, 22f * unit),
        style = Stroke(6f * unit, cap = StrokeCap.Round),
    )
    drawCircle(dark, 4f * unit, point(135f, 114f), style = Stroke(2f * unit))
}

private fun DrawScope.drawHeart(
    point: (Float, Float) -> Offset,
    unit: Float,
    center: Offset,
    color: Color,
) {
    val p = Path().apply {
        moveTo(center.x, center.y + 10f * unit)
        cubicTo(
            center.x - 20f * unit, center.y - 2f * unit,
            center.x - 13f * unit, center.y - 18f * unit,
            center.x, center.y - 8f * unit,
        )
        cubicTo(
            center.x + 13f * unit, center.y - 18f * unit,
            center.x + 20f * unit, center.y - 2f * unit,
            center.x, center.y + 10f * unit,
        )
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawGoggles(point: (Float, Float) -> Offset, unit: Float, dark: Color) {
    val left = point(84f, 57f)
    val right = point(108f, 57f)
    drawOval(Color(0xFF9CD7FF), left - Offset(11f * unit, 7f * unit), Size(22f * unit, 14f * unit))
    drawOval(Color(0xFF9CD7FF), right - Offset(11f * unit, 7f * unit), Size(22f * unit, 14f * unit))
    drawOval(dark, left - Offset(11f * unit, 7f * unit), Size(22f * unit, 14f * unit), style = Stroke(3f * unit))
    drawOval(dark, right - Offset(11f * unit, 7f * unit), Size(22f * unit, 14f * unit), style = Stroke(3f * unit))
    drawLine(dark, left + Offset(10f * unit, 0f), right - Offset(10f * unit, 0f), 2.5f * unit)
}

private fun DrawScope.drawStop(point: (Float, Float) -> Offset, unit: Float) {
    val center = point(146f, 105f)
    drawCircle(Color(0xFFAF1E2D), 15f * unit, center)
    drawLine(Color.White, center - Offset(6f * unit, 6f * unit), center + Offset(6f * unit, 6f * unit), 3.2f * unit, StrokeCap.Round)
    drawLine(Color.White, center + Offset(-6f * unit, 6f * unit), center + Offset(6f * unit, -6f * unit), 3.2f * unit, StrokeCap.Round)
}

private fun DrawScope.drawTablet(point: (Float, Float) -> Offset, unit: Float, dark: Color) {
    val tl = point(128f, 101f)
    drawRoundRect(
        dark,
        topLeft = tl,
        size = Size(31f * unit, 39f * unit),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
    )
    drawRect(
        Color(0xFF486C91),
        topLeft = tl + Offset(4f * unit, 4f * unit),
        size = Size(23f * unit, 29f * unit),
    )
}

private fun DrawScope.drawBandage(point: (Float, Float) -> Offset, unit: Float) {
    val center = point(102f, 55f)
    withTransform({ rotate(18f, center) }) {
        drawRoundRect(
            Color(0xFFE9C78E),
            topLeft = center - Offset(8f * unit, 16f * unit),
            size = Size(16f * unit, 32f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * unit),
        )
        drawCircle(Color(0xFFB69059), 1.5f * unit, center - Offset(0f, 7f * unit))
        drawCircle(Color(0xFFB69059), 1.5f * unit, center + Offset(0f, 7f * unit))
    }
}

private fun DrawScope.drawPlus(point: (Float, Float) -> Offset, unit: Float) {
    val green = Color(0xFF45B969)
    val center = point(151f, 110f)
    drawCircle(green, 14f * unit, center)
    drawLine(Color.White, center - Offset(7f * unit, 0f), center + Offset(7f * unit, 0f), 4f * unit, StrokeCap.Round)
    drawLine(Color.White, center - Offset(0f, 7f * unit), center + Offset(0f, 7f * unit), 4f * unit, StrokeCap.Round)
}

private fun DrawScope.drawKey(point: (Float, Float) -> Offset, unit: Float) {
    val gold = Color(0xFFF0A400)
    val center = point(144f, 91f)
    drawCircle(gold, 9f * unit, center, style = Stroke(4f * unit))
    drawLine(gold, center + Offset(0f, 9f * unit), center + Offset(0f, 31f * unit), 4f * unit, StrokeCap.Round)
    drawLine(gold, center + Offset(0f, 21f * unit), center + Offset(10f * unit, 21f * unit), 4f * unit, StrokeCap.Round)
    drawLine(gold, center + Offset(0f, 27f * unit), center + Offset(7f * unit, 27f * unit), 4f * unit, StrokeCap.Round)
}

private fun DrawScope.drawFlame(point: (Float, Float) -> Offset, unit: Float) {
    val color = Color(0xFFA10A5A)
    val path = Path().apply {
        moveTo(point(151f, 121f).x, point(151f, 121f).y)
        cubicTo(
            point(137f, 109f).x, point(137f, 109f).y,
            point(144f, 91f).x, point(144f, 91f).y,
            point(154f, 78f).x, point(154f, 78f).y,
        )
        cubicTo(
            point(153f, 94f).x, point(153f, 94f).y,
            point(169f, 88f).x, point(169f, 88f).y,
            point(168f, 70f).x, point(168f, 70f).y,
        )
        cubicTo(
            point(182f, 92f).x, point(182f, 92f).y,
            point(177f, 114f).x, point(177f, 114f).y,
            point(151f, 121f).x, point(151f, 121f).y,
        )
        close()
    }
    drawPath(path, color)
    drawCircle(Color.White, 5f * unit, point(162f, 105f))
}

private fun DrawScope.drawDashboard(
    point: (Float, Float) -> Offset,
    unit: Float,
    body: Color,
) {
    val panel = Color(0xFF79DCE7)
    val left = point(52f, 116f)
    val right = point(139f, 116f)
    drawRect(panel, left, Size(30f * unit, 26f * unit))
    drawRect(panel, right, Size(30f * unit, 26f * unit))
    repeat(3) { i ->
        drawLine(Color.White, left + Offset(6f * unit, (7f + i * 6f) * unit), left + Offset((12f + i * 5f) * unit, (7f + i * 6f) * unit), 2f * unit)
        drawLine(Color.White, right + Offset(6f * unit, (7f + i * 6f) * unit), right + Offset(24f * unit, (7f + i * 6f) * unit), 1.5f * unit)
    }
}

private fun DrawScope.drawMascotStateMark(
    point: (Float, Float) -> Offset,
    unit: Float,
    state: H2g2WorkflowState,
    wave: Float,
) {
    when (state) {
        H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed -> {
            val center = point(157f, 49f)
            val red = GeministratorColors.error
            val triangle = Path().apply {
                moveTo(center.x, center.y - 13f * unit)
                lineTo(center.x - 12f * unit, center.y + 10f * unit)
                lineTo(center.x + 12f * unit, center.y + 10f * unit)
                close()
            }
            drawPath(triangle, red)
            drawLine(Color.White, center - Offset(0f, 5f * unit), center + Offset(0f, 3f * unit), 2.6f * unit, StrokeCap.Round)
            drawCircle(Color.White, 1.7f * unit, center + Offset(0f, 7f * unit))
        }
        H2g2WorkflowState.Complete -> {
            val center = point(157f, 49f)
            val green = Color(0xFF37B66B)
            drawCircle(green, 12f * unit, center)
            val lift = wave * 0.8f * unit
            drawLine(Color.White, center + Offset(-6f * unit, lift), center + Offset(-1f * unit, 5f * unit + lift), 2.8f * unit, StrokeCap.Round)
            drawLine(Color.White, center + Offset(-1f * unit, 5f * unit + lift), center + Offset(7f * unit, -5f * unit + lift), 2.8f * unit, StrokeCap.Round)
        }
        H2g2WorkflowState.Gate -> {
            drawCircle(Color(0xFFF0A400), 9f * unit, point(157f, 49f), style = Stroke(3f * unit))
        }
        H2g2WorkflowState.Active -> {
            val blue = Color(0xFF3AA9EA)
            drawLine(blue, point(156f, 42f), point(165f, 35f), 2.2f * unit, StrokeCap.Round)
            drawLine(blue, point(160f, 49f), point(171f, 49f), 2.2f * unit, StrokeCap.Round)
        }
        H2g2WorkflowState.Pending,
        H2g2WorkflowState.Ready -> Unit
    }
}
