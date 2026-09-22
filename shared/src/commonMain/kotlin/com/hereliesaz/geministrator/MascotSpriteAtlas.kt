package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.drawable.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * Sprite atlas definitions for the 9 named mascot characters.
 *
 * All sizing is expressed in the 200×200 artboard coordinate space used by [MascotPuppetRig].
 * [pivotNormX]/[pivotNormY] locate the bone's attachment point within the sprite bounds (0=near
 * side, 1=far side), so the renderer can align each part to its bone pivot correctly.
 */
internal data class MascotSpritePart(
    val resource: DrawableResource,
    /** Width of the sprite when drawn in artboard units. Height is computed from aspect ratio. */
    val designWidth: Float,
    val pivotNormX: Float = 0.5f,
    val pivotNormY: Float = 0.5f,
    /** Pixel dimensions of the source asset — needed for aspect-ratio computation. */
    val srcW: Int,
    val srcH: Int,
)

internal data class MascotSpriteAtlasSpec(
    val antennaCount: Int,
    val body: MascotSpritePart,
    val prop: MascotSpritePart,
    val leg: MascotSpritePart,
    val antenna: MascotSpritePart,
    /** True for left-side bones: the leg sprite is drawn mirrored horizontally. */
    val mirrorLeftLeg: Boolean = true,
)

internal object MascotSpriteAtlas {

    fun forRole(role: NodeCreatureRoleKind): MascotSpriteAtlasSpec? = specs[role]

    private val specs: Map<NodeCreatureRoleKind, MascotSpriteAtlasSpec> by lazy {
        mapOf(
            NodeCreatureRoleKind.ReleaseEngineer to MascotSpriteAtlasSpec(
                antennaCount = 6,
                body = MascotSpritePart(
                    Res.drawable.mascot_release_engineer_body,
                    designWidth = 88f, pivotNormX = 0.5f, pivotNormY = 0.55f,
                    srcW = 443, srcH = 654,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_release_engineer_prop,
                    designWidth = 30f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 146, srcH = 342,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_release_engineer_leg,
                    designWidth = 20f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 177, srcH = 515,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_release_engineer_antenna,
                    designWidth = 22f, pivotNormX = 0.5f, pivotNormY = 0.95f,
                    srcW = 189, srcH = 353,
                ),
            ),
            NodeCreatureRoleKind.QaEngineer to MascotSpriteAtlasSpec(
                antennaCount = 6,
                body = MascotSpritePart(
                    Res.drawable.mascot_qa_engineer_body,
                    designWidth = 90f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 568, srcH = 565,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_qa_engineer_prop,
                    designWidth = 34f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 248, srcH = 286,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_qa_engineer_leg,
                    designWidth = 20f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 173, srcH = 252,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_qa_engineer_antenna,
                    designWidth = 18f, pivotNormX = 0.5f, pivotNormY = 0.95f,
                    srcW = 148, srcH = 257,
                ),
            ),
            NodeCreatureRoleKind.AdversarialReviewer to MascotSpriteAtlasSpec(
                antennaCount = 6,
                body = MascotSpritePart(
                    Res.drawable.mascot_adversarial_reviewer_body,
                    designWidth = 92f, pivotNormX = 0.5f, pivotNormY = 0.55f,
                    srcW = 650, srcH = 596,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_adversarial_reviewer_prop,
                    designWidth = 30f, pivotNormX = 0.5f, pivotNormY = 0.15f,
                    srcW = 249, srcH = 489,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_adversarial_reviewer_leg,
                    designWidth = 22f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 303, srcH = 303,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_adversarial_reviewer_antenna,
                    designWidth = 22f, pivotNormX = 0.5f, pivotNormY = 0.95f,
                    srcW = 197, srcH = 392,
                ),
            ),
            NodeCreatureRoleKind.Architect to MascotSpriteAtlasSpec(
                antennaCount = 4,
                body = MascotSpritePart(
                    Res.drawable.mascot_architect_body,
                    designWidth = 92f, pivotNormX = 0.45f, pivotNormY = 0.5f,
                    srcW = 702, srcH = 544,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_architect_prop,
                    designWidth = 38f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 301, srcH = 268,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_architect_leg,
                    designWidth = 16f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 135, srcH = 366,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_architect_antenna,
                    designWidth = 16f, pivotNormX = 0.5f, pivotNormY = 0.95f,
                    srcW = 126, srcH = 248,
                ),
            ),
            NodeCreatureRoleKind.EpaRepresentative to MascotSpriteAtlasSpec(
                antennaCount = 8,
                body = MascotSpritePart(
                    Res.drawable.mascot_epa_representative_body,
                    designWidth = 82f, pivotNormX = 0.5f, pivotNormY = 0.55f,
                    srcW = 415, srcH = 401,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_epa_representative_prop,
                    designWidth = 30f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 232, srcH = 403,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_epa_representative_leg,
                    designWidth = 24f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 290, srcH = 314,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_epa_representative_antenna,
                    designWidth = 20f, pivotNormX = 0.5f, pivotNormY = 0.95f,
                    srcW = 148, srcH = 422,
                ),
            ),
            NodeCreatureRoleKind.Orchestrator to MascotSpriteAtlasSpec(
                antennaCount = 7,
                body = MascotSpritePart(
                    Res.drawable.mascot_orchestrator_body,
                    designWidth = 84f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 409, srcH = 383,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_orchestrator_prop,
                    designWidth = 22f, pivotNormX = 0.5f, pivotNormY = 0.15f,
                    srcW = 184, srcH = 386,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_orchestrator_leg,
                    designWidth = 26f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 329, srcH = 378,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_orchestrator_antenna,
                    designWidth = 16f, pivotNormX = 0.5f, pivotNormY = 0.9f,
                    srcW = 164, srcH = 156,
                ),
            ),
            NodeCreatureRoleKind.UxDesigner to MascotSpriteAtlasSpec(
                antennaCount = 6,
                body = MascotSpritePart(
                    Res.drawable.mascot_ux_designer_body,
                    designWidth = 82f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 416, srcH = 334,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_ux_designer_prop,
                    designWidth = 28f, pivotNormX = 0.5f, pivotNormY = 0.5f,
                    srcW = 150, srcH = 147,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_ux_designer_leg,
                    designWidth = 22f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 234, srcH = 431,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_ux_designer_antenna,
                    designWidth = 16f, pivotNormX = 0.5f, pivotNormY = 0.95f,
                    srcW = 115, srcH = 295,
                ),
            ),
            NodeCreatureRoleKind.Researcher to MascotSpriteAtlasSpec(
                antennaCount = 6,
                body = MascotSpritePart(
                    Res.drawable.mascot_researcher_body,
                    designWidth = 88f, pivotNormX = 0.5f, pivotNormY = 0.55f,
                    srcW = 477, srcH = 481,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_researcher_prop,
                    designWidth = 32f, pivotNormX = 0.35f, pivotNormY = 0.85f,
                    srcW = 223, srcH = 393,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_researcher_leg,
                    designWidth = 16f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 153, srcH = 397,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_researcher_antenna,
                    designWidth = 14f, pivotNormX = 0.5f, pivotNormY = 0.9f,
                    srcW = 119, srcH = 119,
                ),
            ),
            NodeCreatureRoleKind.HallMonitor to MascotSpriteAtlasSpec(
                antennaCount = 6,
                body = MascotSpritePart(
                    Res.drawable.mascot_hall_monitor_body,
                    designWidth = 86f, pivotNormX = 0.45f, pivotNormY = 0.6f,
                    srcW = 422, srcH = 348,
                ),
                prop = MascotSpritePart(
                    Res.drawable.mascot_hall_monitor_prop,
                    designWidth = 32f, pivotNormX = 0.25f, pivotNormY = 0.9f,
                    srcW = 279, srcH = 404,
                ),
                leg = MascotSpritePart(
                    Res.drawable.mascot_hall_monitor_leg,
                    designWidth = 18f, pivotNormX = 0.5f, pivotNormY = 0.05f,
                    srcW = 171, srcH = 354,
                ),
                antenna = MascotSpritePart(
                    Res.drawable.mascot_hall_monitor_antenna,
                    designWidth = 20f, pivotNormX = 0.5f, pivotNormY = 0.95f,
                    srcW = 182, srcH = 350,
                ),
            ),
        )
    }
}
