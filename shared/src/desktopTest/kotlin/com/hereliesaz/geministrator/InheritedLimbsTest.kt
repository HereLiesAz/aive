package com.hereliesaz.geministrator

import androidx.compose.ui.graphics.ImageBitmap
import com.hereliesaz.geministrator.puppet.CompiledPuppetRig
import com.hereliesaz.geministrator.puppet.LimbMeshScratch
import com.hereliesaz.geministrator.puppet.PuppetAtlas
import com.hereliesaz.geministrator.puppet.PuppetAttachment
import com.hereliesaz.geministrator.puppet.PuppetCanvas
import com.hereliesaz.geministrator.puppet.PuppetFit
import com.hereliesaz.geministrator.puppet.PuppetPart
import com.hereliesaz.geministrator.puppet.PuppetPoint
import com.hereliesaz.geministrator.puppet.PuppetRect
import com.hereliesaz.geministrator.puppet.PuppetRig
import com.hereliesaz.geministrator.puppet.PuppetTransform
import com.hereliesaz.geministrator.puppet.layLimbMesh
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A bred creature's inherited limbs, and limbs that reach, are drawn where the rig reckons them. */
class InheritedLimbsTest {
    private fun rig(vararg parts: PuppetPart, attachments: List<PuppetAttachment> = emptyList()) = PuppetRig(
        canvas = PuppetCanvas(200f, 200f),
        atlas = PuppetAtlas("a.png", 64, 64),
        parts = listOf(PuppetPart("body", rect = PuppetRect(0, 0, 20, 20), rest = PuppetTransform(x = 100f, y = 100f))) + parts,
        attachments = attachments,
    ).also { it.validate() }

    private fun tendril(id: String = "tendril", h: Int = 20) =
        PuppetPart(id, parent = "body", rect = PuppetRect(20, 0, 10, h), kind = "tendril", pivot = PuppetPoint(0.5f, 0f), rest = PuppetTransform(y = 10f))

    @Test
    fun anInheritedTailNeverReplacesTheEggOrItsPods() {
        val host = CompiledPuppetRig(
            rig(
                PuppetPart("tail", parent = "body", rect = PuppetRect(20, 0, 10, 30), pivot = PuppetPoint(0.5f, 0f)),
                PuppetPart("tail.egg", parent = "tail", rect = PuppetRect(30, 0, 10, 10)),
                PuppetPart("tail.pod.0", parent = "tail", rect = PuppetRect(40, 0, 6, 6)),
            ),
        )
        val tail = host.rig.parts.indexOfFirst { it.id == "tail" }
        assertEquals(listOf(tail), host.tailParts.toList())

        val donor = LoadedCreatureRig(
            "donor",
            CompiledPuppetRig(rig(PuppetPart("tail", parent = "body", rect = PuppetRect(20, 0, 8, 40), kind = "tail", pivot = PuppetPoint(0.5f, 0f)))),
            ImageBitmap(1, 1),
        )
        val inherited = inheritedLimbs(host, tendrils = null, tail = donor)!!
        assertEquals(setOf(tail), inherited.keys)
        assertEquals("tail", inherited.getValue(tail).second.id)
    }

    @Test
    fun aChildOfAReachingLimbRidesItsWholeArc() {
        val compiled = CompiledPuppetRig(
            rig(
                tendril(),
                // The bud sits halfway down the tendril, exactly where the attachment is.
                PuppetPart("bud", parent = "tendril", rect = PuppetRect(40, 0, 4, 4), rest = PuppetTransform(y = 10f)),
                attachments = listOf(PuppetAttachment("mid", "tendril", 0.5f, 0.5f)),
            ),
        )
        val pose = compiled.newBuffer()
        val t = compiled.rig.parts.indexOfFirst { it.id == "tendril" }
        val bud = compiled.rig.parts.indexOfFirst { it.id == "bud" }
        pose.clearReach()
        pose.reachX[t] = 150f
        pose.reachY[t] = 150f
        compiled.evaluate(-1, 0f, pose)
        assertTrue(pose.segments[t] > 1, "the reach should need more than one segment")
        assertTrue(abs(pose.world[bud * 6 + 4] - pose.attachX[0]) < 1e-3f, "x ${pose.world[bud * 6 + 4]} vs ${pose.attachX[0]}")
        assertTrue(abs(pose.world[bud * 6 + 5] - pose.attachY[0]) < 1e-3f, "y ${pose.world[bud * 6 + 5]} vs ${pose.attachY[0]}")
    }

    @Test
    fun anInheritedLimbsMeshEndsAtTheHostsTip() {
        val compiled = CompiledPuppetRig(rig(tendril()))
        val t = compiled.rig.parts.indexOfFirst { it.id == "tendril" }
        val host = compiled.rig.parts[t]
        // The donor's tendril is twice as long as the host's.
        val donor = tendril(h = 40)
        val pose = compiled.newBuffer()
        val scratch = LimbMeshScratch()
        listOf(130f to 100f, 160f to 170f).forEach { (x, y) ->
            pose.clearReach()
            pose.reachX[t] = x
            pose.reachY[t] = y
            compiled.evaluate(-1, 0f, pose)
            val tip = compiled.tendrilTip(t, pose)
            listOf(host, donor).forEach { sprite ->
                val last = pose.segments[t] - 1
                assertTrue(layLimbMesh(sprite, host, pose.world, t * 6, PuppetFit(1f, 0f, 0f), pose.bend[t], pose.stretch[t], pose.segments[t], last.toFloat(), scratch))
                // The grid's bottom-centre vertex is the sprite's tip (pivot at its top centre).
                val drawnTip = scratch.positions[10 * 11 + 5]
                assertTrue((drawnTip - tip).getDistance() < 1e-2f, "${sprite.rect} tip $drawnTip vs $tip")
            }
        }
    }
}
