package com.hereliesaz.geministrator

import kotlinx.serialization.Serializable

/** Minimal, renderer-only geometry. Memory bodies, task objectives and commands never cross this boundary. */
@Serializable
internal data class SpatialRenderBody(
    val id: String, val position: SpatialPoint, val seed: Int, val form: String,
    val state: String, val growth: Float, val radius: Float,
    val blurScale: Float = 1f,
    val genome: SpatialAgentGenome? = null,
)

@Serializable
internal data class SpatialRenderCord(val points: List<SpatialPoint>, val width: Float, val kind: String,
    val blurScale: Float = 1f)

@Serializable
internal data class SpatialRenderFrame(
    val bodies: List<SpatialRenderBody>, val spores: List<SpatialRenderBody>, val cords: List<SpatialRenderCord>,
)

@Serializable
internal data class SpatialRenderCamera(val eye: SpatialPoint, val gaze: SpatialPoint, val memory: Boolean,
    val motionPhase: Float = 0f, val focusPoint: SpatialPoint = gaze)

/** Input messages are requests for presentation changes only, never engine or memory operations. */
@Serializable
internal data class SpatialRenderInput(val type: String, val id: String = "", val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)

internal fun SpatialHiveScene.renderFrame(journal: SpatialTissueJournal): SpatialRenderFrame {
    require(journal.runId == runId)
    val bodies = (listOf(nucleus) + creatures).map { body ->
        val radius = when (body.form) {
            SpatialForm.Nucleus -> 1.15f
            SpatialForm.Site -> .19f
            SpatialForm.Antagonist -> .5f
            SpatialForm.Egg -> .26f + .15f * body.growth.coerceIn(0f, 1f)
            SpatialForm.WitheredEgg -> .29f
            else -> .43f + (body.morphologySeed and 255) / 255f * .2f
        }
        SpatialRenderBody(body.id, body.position, body.morphologySeed, body.form.name, body.state.name, body.growth, radius, genome = body.genome)
    }
    val spores = memory.spores.map { spore ->
        SpatialRenderBody(spore.id, spore.position, spatialSeed(spore.id), "Spore", spore.group.name, 0f,
            .055f + .09f * kotlin.math.sqrt(spore.weight.coerceIn(0f, 1.5f)))
    }
    val positions = bodies.associate { it.id to it.position }
    val cords = journal.branches.map { SpatialRenderCord(it.points, .065f, it.style.name) } +
        connections.mapNotNull { link ->
            val a = positions[link.from] ?: return@mapNotNull null
            val b = positions[link.to] ?: return@mapNotNull null
            SpatialRenderCord(spatialHyphaPoints("link:" + link.from + ":" + link.to, a, b), .027f,
                if (link.activeTransfer) "Transfer" else "Dependency")
        } + memory.associations.map { SpatialRenderCord(it.points, .015f + .032f * it.weight, "Memory") }
    return SpatialRenderFrame(bodies, spores, cords)
}

/** Select only IDs in the currently mounted world; a stale DOM event cannot select another run. */
internal fun SpatialRenderFrame.accepts(input: SpatialRenderInput): Boolean = when (input.type) {
    "agent" -> bodies.any { it.id == input.id }
    "memory" -> spores.any { it.id == input.id }
    "orbit", "pan", "zoom" -> input.x.isFinite() && input.y.isFinite() && input.z.isFinite()
    "empty", "error" -> true
    else -> false
}

/** A selected moving element can retain optical focus while the Keeper manually aims the camera. */
internal fun SpatialCameraState.renderCamera(motionPhase: Float = 0f, focus: SpatialPoint? = null): SpatialRenderCamera {
    val gaze = if (manual) pose.gaze else spatialMotionPoint(pose.gaze, motionPhase)
    return SpatialRenderCamera(pose.eye(), gaze, mode == SpatialCameraMode.Memory, motionPhase,
        focus?.let { spatialMotionPoint(it, motionPhase) } ?: gaze)
}
