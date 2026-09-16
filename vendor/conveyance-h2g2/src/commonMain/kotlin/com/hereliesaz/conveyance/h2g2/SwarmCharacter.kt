package com.hereliesaz.conveyance.h2g2

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val SWARM_TAU = (PI * 2.0).toFloat()
private const val SWARM_LIGHT_AZIMUTH = -0.72f

/** Stable, non-colour identity for one procedural swarm character. */
data class H2g2SwarmGenome(
    val identitySeed: String,
    val bodySides: Int,
    val bodyHeight: Float,
    val bodyWidth: Float,
    val depthScale: Float,
    val shoulderScale: Float,
    val waistScale: Float,
    val hipScale: Float,
    val headScale: Float,
    val headOffsetX: Float,
    val eyeCount: Int,
    val eyeSpread: Float,
    val limbCount: Int,
    val limbLength: Float,
    val antennae: List<H2g2SwarmAntenna>,
    val locomotion: H2g2SwarmLocomotion,
    val baseYaw: Float,
    val yawAmplitude: Float,
    val pitchAmplitude: Float,
    val movementAmplitude: Float,
    val movementPeriodMs: Int,
)

data class H2g2SwarmAntenna(
    val azimuth: Float,
    val elevation: Float,
    val length: Float,
    val bend: Float,
    val terminalScale: Float,
    val terminal: H2g2SwarmTerminal,
    val phase: Float,
)

enum class H2g2SwarmTerminal { Orb, Disc, Diamond, Fork, Clamp }

enum class H2g2SwarmLocomotion { Tripod, Stilt, Scuttle, Float, Hop, Inch, Orbit, Glide }

/**
 * Generates the same body plan from the same identity on every supported target. Colour is not
 * part of the genome, so two agents remain distinguishable even in monochrome.
 */
fun h2g2SwarmGenome(identitySeed: String): H2g2SwarmGenome {
    val random = StableSwarmRandom(stableSwarmSeed(identitySeed))
    val antennaCount = random.int(3, 9)
    return H2g2SwarmGenome(
        identitySeed = identitySeed,
        bodySides = random.int(5, 11),
        bodyHeight = random.float(.74f, 1.18f),
        bodyWidth = random.float(.64f, 1.08f),
        depthScale = random.float(.54f, .94f),
        shoulderScale = random.float(.74f, 1.18f),
        waistScale = random.float(.58f, .96f),
        hipScale = random.float(.68f, 1.16f),
        headScale = random.float(.56f, .92f),
        headOffsetX = random.float(-.18f, .18f),
        eyeCount = random.int(1, 5),
        eyeSpread = random.float(.18f, .42f),
        limbCount = random.int(2, 7),
        limbLength = random.float(.34f, .68f),
        antennae = List(antennaCount) { index ->
            val baseAngle = (index.toFloat() / antennaCount.toFloat()) * SWARM_TAU
            H2g2SwarmAntenna(
                azimuth = baseAngle + random.float(-.34f, .34f),
                elevation = random.float(-.96f, -.18f),
                length = random.float(.44f, .94f),
                bend = random.float(-.42f, .42f),
                terminalScale = random.float(.7f, 1.3f),
                terminal = H2g2SwarmTerminal.entries[random.int(0, H2g2SwarmTerminal.entries.size)],
                phase = random.float(0f, SWARM_TAU),
            )
        },
        locomotion = H2g2SwarmLocomotion.entries[random.int(0, H2g2SwarmLocomotion.entries.size)],
        baseYaw = random.float(-.42f, .42f),
        yawAmplitude = random.float(.06f, .26f),
        pitchAmplitude = random.float(.025f, .14f),
        movementAmplitude = random.float(.8f, 1.4f),
        movementPeriodMs = random.int(1900, 5100),
    )
}

/** Useful for regression tests and caches without persisting generated art. */
fun H2g2SwarmGenome.structuralSignature(): String = buildString {
    append(bodySides).append(':')
    append(quantize(bodyHeight)).append(':')
    append(quantize(bodyWidth)).append(':')
    append(quantize(headScale)).append(':')
    append(eyeCount).append(':')
    append(limbCount).append(':')
    append(locomotion.name).append(':')
    antennae.forEach { antenna ->
        append(antenna.terminal.name.first())
        append(quantize(antenna.azimuth)).append('.')
        append(quantize(antenna.length)).append(';')
    }
}

/**
 * Procedural low-poly 3D projected into a Compose canvas. Faces use discrete light/mid/shadow
 * colours: actual cel shading, with no gradient dependency.
 */
@Composable
fun H2g2SwarmCharacter(
    identitySeed: String,
    hueSeed: String = identitySeed,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    contactDirection: Offset? = null,
    contactStrength: Float = 0f,
) {
    val genome = remember(identitySeed) { h2g2SwarmGenome(identitySeed) }
    val hueIndex = remember(hueSeed) { H2g2.indexOf(hueSeed) }
    val base = H2g2.hues[hueIndex]
    val cap = H2g2.caps[hueIndex]
    val transition = rememberInfiniteTransition(label = "h2g2-swarm-$identitySeed")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(genome.movementPeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "h2g2-swarm-phase-$identitySeed",
    )
    val breathe by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600 + genome.movementPeriodMs / 3, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "h2g2-swarm-breathe-$identitySeed",
    )

    Canvas(modifier) {
        val t = phase * SWARM_TAU
        val gait = swarmGait(genome, t, active)
        val yaw = genome.baseYaw + sin(t) * genome.yawAmplitude + gait.yaw
        val pitch = cos(t * .73f) * genome.pitchAmplitude + gait.pitch
        val contact = contactDirection
            ?.takeIf { contactStrength > 0f && it.getDistance() > .001f }
            ?.let(::normalized)

        drawSwarmCharacter(
            genome = genome,
            base = base,
            cap = cap,
            yaw = yaw,
            pitch = pitch,
            antennaWave = t,
            breathe = breathe,
            gait = gait,
            contactDirection = contact,
            contactStrength = contactStrength.coerceIn(0f, 1f),
        )
    }
}

/** Normalized terminal-node anchor best facing [toward], relative to character centre. */
fun H2g2SwarmGenome.terminalAnchorToward(toward: Offset): Offset {
    if (antennae.isEmpty() || toward.getDistance() <= .001f) return Offset.Zero
    val direction = normalized(toward)
    val antenna = antennae.maxBy { candidate ->
        val planar = normalized(
            Offset(
                x = cos(candidate.azimuth),
                y = sin(candidate.azimuth) * .62f + candidate.elevation * .48f,
            ),
        )
        planar.x * direction.x + planar.y * direction.y
    }
    return normalized(
        Offset(
            x = cos(antenna.azimuth),
            y = sin(antenna.azimuth) * .62f + antenna.elevation * .48f,
        ),
    ) * (.28f + antenna.length * .24f)
}

private data class SwarmGait(
    val translation: Offset = Offset.Zero,
    val rotationDegrees: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

private fun swarmGait(genome: H2g2SwarmGenome, t: Float, active: Boolean): SwarmGait {
    val a = genome.movementAmplitude * if (active) 1.28f else 1f
    return when (genome.locomotion) {
        H2g2SwarmLocomotion.Tripod -> SwarmGait(
            translation = Offset(sin(t) * 3.2f * a, -abs(sin(t * 1.5f)) * 2.4f * a),
            rotationDegrees = sin(t) * 1.6f,
            yaw = sin(t * .5f) * .07f,
        )
        H2g2SwarmLocomotion.Stilt -> SwarmGait(
            translation = Offset(sin(t * .5f) * 1.8f * a, cos(t) * 4.6f * a),
            rotationDegrees = sin(t * .5f) * 2.2f,
            pitch = sin(t) * .045f,
        )
        H2g2SwarmLocomotion.Scuttle -> SwarmGait(
            translation = Offset(sin(t * 2f) * 5.4f * a, cos(t * 3f) * 1.2f * a),
            rotationDegrees = sin(t * 2f) * 1.1f,
            scaleX = 1f + sin(t * 2f) * .018f,
        )
        H2g2SwarmLocomotion.Float -> SwarmGait(
            translation = Offset(cos(t * .7f) * 2.2f * a, sin(t) * 5.2f * a),
            rotationDegrees = sin(t * .7f) * 2.4f,
            yaw = sin(t) * .09f,
        )
        H2g2SwarmLocomotion.Hop -> SwarmGait(
            translation = Offset(sin(t) * 2.4f * a, -abs(sin(t)) * 7f * a),
            scaleX = 1f + abs(sin(t)) * .028f,
            scaleY = 1f - abs(sin(t)) * .025f,
        )
        H2g2SwarmLocomotion.Inch -> SwarmGait(
            translation = Offset(sin(t * .5f) * 4f * a, 0f),
            scaleX = 1f + sin(t) * .055f,
            scaleY = 1f - sin(t) * .04f,
            rotationDegrees = sin(t) * .8f,
        )
        H2g2SwarmLocomotion.Orbit -> SwarmGait(
            translation = Offset(cos(t) * 4.1f * a, sin(t) * 3.2f * a),
            rotationDegrees = sin(t) * 2.6f,
            yaw = cos(t) * .12f,
        )
        H2g2SwarmLocomotion.Glide -> SwarmGait(
            translation = Offset(sin(t * .5f) * 6f * a, cos(t) * 1.7f * a),
            rotationDegrees = sin(t * .5f) * 1.2f,
            pitch = cos(t) * .035f,
        )
    }
}

private fun DrawScope.drawSwarmCharacter(
    genome: H2g2SwarmGenome,
    base: Color,
    cap: Color,
    yaw: Float,
    pitch: Float,
    antennaWave: Float,
    breathe: Float,
    gait: SwarmGait,
    contactDirection: Offset?,
    contactStrength: Float,
) {
    val scale = min(size.width, size.height) * .34f
    val origin = Offset(
        size.width * .5f + gait.translation.x,
        size.height * .57f + gait.translation.y,
    )
    val bodyBase = shade(base, .82f)
    val bodyLight = shade(base, 1.12f)
    val bodyShadow = shade(base, .58f)
    val outline = H2g2.ink
    val breathingScale = 1f + breathe * .012f

    val bodyRings = listOf(
        MeshRing(-.46f * genome.bodyHeight, .54f * genome.bodyWidth * genome.shoulderScale),
        MeshRing(-.08f * genome.bodyHeight, .50f * genome.bodyWidth),
        MeshRing(.28f * genome.bodyHeight, .46f * genome.bodyWidth * genome.waistScale),
        MeshRing(.55f * genome.bodyHeight, .50f * genome.bodyWidth * genome.hipScale),
    )
    drawFacetedMesh(
        rings = bodyRings,
        sides = genome.bodySides,
        depthScale = genome.depthScale,
        origin = origin,
        scale = scale * breathingScale,
        yaw = yaw,
        pitch = pitch,
        base = bodyBase,
        light = bodyLight,
        shadow = bodyShadow,
        outline = outline,
    )

    val headY = -.72f * genome.bodyHeight
    val headRings = listOf(
        MeshRing(headY - .19f * genome.headScale, .32f * genome.headScale),
        MeshRing(headY, .48f * genome.headScale),
        MeshRing(headY + .20f * genome.headScale, .34f * genome.headScale),
    )
    drawFacetedMesh(
        rings = headRings,
        sides = max(5, genome.bodySides - 1),
        depthScale = genome.depthScale * .88f,
        origin = origin,
        scale = scale,
        yaw = yaw,
        pitch = pitch,
        base = cap,
        light = shade(cap, 1.12f),
        shadow = shade(cap, .58f),
        outline = outline,
        xOffset = genome.headOffsetX,
    )

    drawEyes(genome, origin, scale, yaw, pitch)
    drawLimbs(genome, origin, scale, yaw, pitch, antennaWave, bodyShadow, outline)
    drawAntennae(
        genome = genome,
        origin = origin,
        scale = scale,
        yaw = yaw,
        pitch = pitch,
        wave = antennaWave,
        color = cap,
        outline = outline,
        contactDirection = contactDirection,
        contactStrength = contactStrength,
    )
}

private data class MeshRing(val y: Float, val radius: Float)
private data class SwarmPoint3(val x: Float, val y: Float, val z: Float)
private data class ProjectedPoint(val offset: Offset, val depth: Float)
private data class MeshFace(val points: List<ProjectedPoint>, val lightScore: Float)

private fun DrawScope.drawFacetedMesh(
    rings: List<MeshRing>,
    sides: Int,
    depthScale: Float,
    origin: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
    base: Color,
    light: Color,
    shadow: Color,
    outline: Color,
    xOffset: Float = 0f,
) {
    if (rings.size < 2 || sides < 3) return
    val points = rings.map { ring ->
        List(sides) { side ->
            val angle = SWARM_TAU * side / sides.toFloat()
            project(
                SwarmPoint3(
                    x = xOffset + cos(angle) * ring.radius,
                    y = ring.y,
                    z = sin(angle) * ring.radius * depthScale,
                ),
                origin,
                scale,
                yaw,
                pitch,
            )
        }
    }
    val faces = buildList {
        for (ring in 0 until rings.lastIndex) {
            for (side in 0 until sides) {
                val next = (side + 1) % sides
                val angle = SWARM_TAU * (side + .5f) / sides.toFloat()
                add(
                    MeshFace(
                        points = listOf(
                            points[ring][side],
                            points[ring][next],
                            points[ring + 1][next],
                            points[ring + 1][side],
                        ),
                        lightScore = cos(angle - yaw - SWARM_LIGHT_AZIMUTH),
                    ),
                )
            }
        }
    }.sortedBy { face -> face.points.sumOf { it.depth.toDouble() } / face.points.size }

    faces.forEach { face ->
        val fill = when {
            face.lightScore > .42f -> light
            face.lightScore < -.28f -> shadow
            else -> base
        }
        val path = Path().apply {
            moveTo(face.points.first().offset.x, face.points.first().offset.y)
            face.points.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
            close()
        }
        drawPath(path, fill)
        drawPath(path, outline.copy(alpha = .72f), style = Stroke(width = max(1.2f, scale * .024f)))
    }
}

private fun DrawScope.drawEyes(
    genome: H2g2SwarmGenome,
    origin: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
) {
    repeat(genome.eyeCount) { index ->
        val centered = index - (genome.eyeCount - 1) / 2f
        val spreadDivisor = max(1f, genome.eyeCount - 1f)
        val point = project(
            SwarmPoint3(
                x = genome.headOffsetX + centered * genome.eyeSpread / spreadDivisor,
                y = -.72f * genome.bodyHeight,
                z = .39f * genome.headScale * genome.depthScale,
            ),
            origin,
            scale,
            yaw,
            pitch,
        ).offset
        val radius = scale * (.055f + (index % 2) * .008f)
        drawCircle(H2g2.ink, radius = radius * 1.18f, center = point)
        drawCircle(H2g2.white, radius = radius, center = point)
        drawCircle(
            H2g2.ink,
            radius = radius * .42f,
            center = point + Offset(cos(yaw) * radius * .23f, sin(pitch) * radius * .2f),
        )
    }
}

private fun DrawScope.drawLimbs(
    genome: H2g2SwarmGenome,
    origin: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
    wave: Float,
    color: Color,
    outline: Color,
) {
    repeat(genome.limbCount) { index ->
        val side = if (index % 2 == 0) -1f else 1f
        val row = index / 2
        val y = .16f + row * .16f
        val phase = wave + index * .83f
        val root = project(
            SwarmPoint3(side * .38f * genome.bodyWidth, y * genome.bodyHeight, .03f),
            origin, scale, yaw, pitch,
        ).offset
        val knee = project(
            SwarmPoint3(
                side * (.56f + genome.limbLength * .24f) * genome.bodyWidth,
                (y + .13f + sin(phase) * .035f) * genome.bodyHeight,
                cos(phase) * .08f,
            ),
            origin, scale, yaw, pitch,
        ).offset
        val foot = project(
            SwarmPoint3(
                side * (.64f + genome.limbLength * .36f) * genome.bodyWidth,
                (y + .28f + abs(sin(phase)) * .04f) * genome.bodyHeight,
                .02f,
            ),
            origin, scale, yaw, pitch,
        ).offset
        val path = Path().apply {
            moveTo(root.x, root.y)
            lineTo(knee.x, knee.y)
            lineTo(foot.x, foot.y)
        }
        drawPath(path, outline, style = Stroke(width = scale * .07f, cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(width = scale * .038f, cap = StrokeCap.Round))
        drawCircle(outline, radius = scale * .045f, center = foot)
        drawCircle(color, radius = scale * .027f, center = foot)
    }
}

private fun DrawScope.drawAntennae(
    genome: H2g2SwarmGenome,
    origin: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
    wave: Float,
    color: Color,
    outline: Color,
    contactDirection: Offset?,
    contactStrength: Float,
) {
    val contactIndex = contactDirection?.let { direction ->
        genome.antennae.indices.maxByOrNull { index ->
            val antenna = genome.antennae[index]
            val planar = normalized(
                Offset(cos(antenna.azimuth), sin(antenna.azimuth) * .62f + antenna.elevation * .48f),
            )
            planar.x * direction.x + planar.y * direction.y
        }
    }

    genome.antennae.forEachIndexed { index, antenna ->
        val wiggle = sin(wave * (1f + index * .035f) + antenna.phase) * .075f
        val angle = antenna.azimuth + wiggle
        val stretch = if (index == contactIndex) 1f + contactStrength * .72f else 1f
        val headY = -.72f * genome.bodyHeight
        val root = project(
            SwarmPoint3(
                genome.headOffsetX + cos(angle) * .20f,
                headY + sin(angle) * .08f,
                sin(angle) * .16f,
            ),
            origin, scale, yaw, pitch,
        ).offset
        val mid = project(
            SwarmPoint3(
                genome.headOffsetX + cos(angle + antenna.bend * .35f) * antenna.length * .52f * stretch,
                headY + antenna.elevation * antenna.length * .38f,
                sin(angle) * antenna.length * .32f,
            ),
            origin, scale, yaw, pitch,
        ).offset
        var end = project(
            SwarmPoint3(
                genome.headOffsetX + cos(angle + antenna.bend) * antenna.length * stretch,
                headY + antenna.elevation * antenna.length * .72f,
                sin(angle + antenna.bend) * antenna.length * .5f,
            ),
            origin, scale, yaw, pitch,
        ).offset
        if (index == contactIndex && contactDirection != null) {
            end += contactDirection * scale * .32f * contactStrength
        }
        val path = Path().apply {
            moveTo(root.x, root.y)
            quadraticBezierTo(mid.x, mid.y, end.x, end.y)
        }
        drawPath(path, outline, style = Stroke(width = scale * .055f, cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(width = scale * .027f, cap = StrokeCap.Round))
        drawTerminal(antenna.terminal, end, scale * .075f * antenna.terminalScale, color, outline)
    }
}

private fun DrawScope.drawTerminal(
    terminal: H2g2SwarmTerminal,
    center: Offset,
    radius: Float,
    fill: Color,
    outline: Color,
) {
    when (terminal) {
        H2g2SwarmTerminal.Orb -> {
            drawCircle(outline, radius * 1.16f, center)
            drawCircle(fill, radius, center)
            drawCircle(shade(fill, 1.18f), radius * .34f, center - Offset(radius * .24f, radius * .24f))
        }
        H2g2SwarmTerminal.Disc -> {
            drawOval(outline, center - Offset(radius * 1.2f, radius * .7f), androidx.compose.ui.geometry.Size(radius * 2.4f, radius * 1.4f))
            drawOval(fill, center - Offset(radius, radius * .5f), androidx.compose.ui.geometry.Size(radius * 2f, radius))
        }
        H2g2SwarmTerminal.Diamond -> {
            val p = Path().apply {
                moveTo(center.x, center.y - radius * 1.2f)
                lineTo(center.x + radius, center.y)
                lineTo(center.x, center.y + radius * 1.2f)
                lineTo(center.x - radius, center.y)
                close()
            }
            drawPath(p, outline)
            val inner = Path().apply {
                moveTo(center.x, center.y - radius * .88f)
                lineTo(center.x + radius * .72f, center.y)
                lineTo(center.x, center.y + radius * .88f)
                lineTo(center.x - radius * .72f, center.y)
                close()
            }
            drawPath(inner, fill)
        }
        H2g2SwarmTerminal.Fork -> {
            drawCircle(outline, radius * .45f, center)
            drawLine(outline, center, center + Offset(-radius, -radius), strokeWidth = radius * .42f, cap = StrokeCap.Round)
            drawLine(outline, center, center + Offset(radius, -radius), strokeWidth = radius * .42f, cap = StrokeCap.Round)
            drawLine(fill, center, center + Offset(-radius, -radius), strokeWidth = radius * .2f, cap = StrokeCap.Round)
            drawLine(fill, center, center + Offset(radius, -radius), strokeWidth = radius * .2f, cap = StrokeCap.Round)
        }
        H2g2SwarmTerminal.Clamp -> {
            drawArc(outline, -70f, 140f, false, center - Offset(radius, radius), androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f), style = Stroke(radius * .48f, cap = StrokeCap.Round))
            drawArc(fill, -70f, 140f, false, center - Offset(radius, radius), androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f), style = Stroke(radius * .22f, cap = StrokeCap.Round))
        }
    }
}

private fun project(
    point: SwarmPoint3,
    origin: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
): ProjectedPoint {
    val cy = cos(yaw)
    val sy = sin(yaw)
    val x1 = point.x * cy + point.z * sy
    val z1 = -point.x * sy + point.z * cy
    val cp = cos(pitch)
    val sp = sin(pitch)
    val y2 = point.y * cp - z1 * sp
    val z2 = point.y * sp + z1 * cp
    return ProjectedPoint(
        offset = Offset(origin.x + x1 * scale, origin.y + y2 * scale),
        depth = z2,
    )
}

private fun shade(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha,
)

private fun normalized(offset: Offset): Offset {
    val length = offset.getDistance()
    return if (length <= .0001f) Offset.Zero else offset / length
}

private fun quantize(value: Float): Int = (value * 1000f).toInt()

private fun stableSwarmSeed(value: String): Int {
    var hash = 0x811C9DC5u
    value.encodeToByteArray().forEach { byte ->
        hash = hash xor byte.toUByte().toUInt()
        hash *= 0x01000193u
    }
    return hash.toInt().let { if (it == 0) 0x6D2B79F5 else it }
}

private class StableSwarmRandom(seed: Int) {
    private var state: Int = seed

    private fun nextUInt(): UInt {
        var x = state.toUInt()
        x = x xor (x shl 13)
        x = x xor (x shr 17)
        x = x xor (x shl 5)
        state = x.toInt()
        return x
    }

    fun float(min: Float, max: Float): Float {
        val unit = (nextUInt().toDouble() / UInt.MAX_VALUE.toDouble()).toFloat()
        return min + (max - min) * unit
    }

    fun int(minInclusive: Int, maxExclusive: Int): Int {
        require(maxExclusive > minInclusive)
        val span = (maxExclusive - minInclusive).toUInt()
        return minInclusive + (nextUInt() % span).toInt()
    }
}
