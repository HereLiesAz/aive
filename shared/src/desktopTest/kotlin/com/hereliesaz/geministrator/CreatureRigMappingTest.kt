package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.geministrator.puppet.CompiledPuppetRig
import com.hereliesaz.geministrator.puppet.PuppetRig
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreatureRigMappingTest {
    private val dir = File("src/commonMain/composeResources/files/rigs")

    private fun rig(slug: String) = PuppetRig.parse(File(dir, "$slug.rig.json").readText())

    @Test
    fun everyMappedSlugHasARigAndAtlas() {
        CreatureRigMapping.allSlugs.forEach { slug ->
            val file = File(dir, "$slug.rig.json")
            assertTrue(file.isFile, "missing rig for $slug")
            assertTrue(File(dir, rig(slug).atlas.image).isFile, "missing atlas for $slug")
        }
    }

    @Test
    fun builtInRoleNamesResolveToTheirRig() {
        CreatureRigMapping.bySlugKind.forEach { (kind, slug) ->
            val label = CreatureRigMapping.displayName(kind)
            assertEquals(slug, CreatureRigMapping.slugFor(label), "label '$label' ($kind)")
        }
        assertEquals("simulator", CreatureRigMapping.slugFor("Simulator"))
        assertNull(CreatureRigMapping.slugFor("UX Designer")) // hand-built sample rig is tiny; bespoke surface stays
        assertEquals("data-miner-sheet-3-variant", CreatureRigMapping.slugFor("Data Miner"))
        assertEquals("idea-catalyst-2", CreatureRigMapping.slugFor("Idea Catalyst"))
    }

    @Test
    fun galleryCoversEverySlug() {
        assertEquals(CreatureRigMapping.allSlugs, creatureRigGalleryEntries.mapNotNull(CreatureRigMapping::slugFor).toSet())
    }

    @Test
    fun rolesWithoutASlicedSheetKeepTheOldRendering() {
        listOf("Orchestrator", "Researcher", "QA Engineer", "Release Engineer", "Python").forEach {
            assertNull(CreatureRigMapping.slugFor(it), it)
        }
        // Distinct Store personas are not collapsed onto a shared rig body.
        assertNull(CreatureRigMapping.slugFor("Brand Strategist"))
        assertNull(CreatureRigMapping.slugFor(""))
    }

    @Test
    fun unknownRolesGetAStableGenericRig() {
        val labels = (0 until 200).map { "Zorblax Wrangler $it" }
        val slugs = labels.map { CreatureRigMapping.slugFor(it) }
        slugs.forEach { assertTrue(it in CreatureRigMapping.GENERIC_SLUGS, "$it") }
        assertEquals(slugs, labels.map { CreatureRigMapping.slugFor(it) })
        assertEquals(CreatureRigMapping.slugFor("Weather Oracle"), CreatureRigMapping.slugFor("  weather oracle "))
        assertEquals(CreatureRigMapping.GENERIC_SLUGS.toSet(), slugs.toSet(), "all six generic bodies get used")
    }

    @Test
    fun socketTransformMapsArtboardIntoTheZoomedNodeBox() {
        val r = rig("simulator") // 374.88 x 510.79 artboard
        val box = 200f
        val center = Offset(500f, 300f)
        // Artboard centre lands on the node centre at any zoom.
        listOf(0.7f, 1f, 2f).forEach { zoom ->
            val c = creatureRigPointToScreen(r.canvas.width / 2, r.canvas.height / 2, r, box, center, zoom)
            assertTrue(abs(c.x - center.x) < 1e-3f && abs(c.y - center.y) < 1e-3f, "$c at $zoom")
        }
        // Top-left corner: height-limited fit, scaled by content scale and zoom.
        val s = box / r.canvas.height * CREATURE_RIG_CONTENT_SCALE
        val tl = creatureRigPointToScreen(0f, 0f, r, box, center, zoom = 2f)
        assertEquals(center.x - r.canvas.width / 2 * s * 2f, tl.x, 1e-3f)
        assertEquals(center.y - r.canvas.height / 2 * s * 2f, tl.y, 1e-3f)
    }

    @Test
    fun armSocketFacesThePartner() {
        val compiled = CompiledPuppetRig(rig("simulator"))
        val pose = compiled.newBuffer()
        compiled.evaluate(compiled.stateIndex("Active"), 0.3f, pose)
        val right = pickArmSocket(compiled, pose, 1f, 0f)
        val left = pickArmSocket(compiled, pose, -1f, 0f)
        assertEquals("arm.socket.right", compiled.attachmentIds[right])
        assertEquals("arm.socket.left", compiled.attachmentIds[left])
        // Sockets sit low on the soma: nothing faces straight up, so the caller falls back.
        assertEquals(-1, pickArmSocket(compiled, pose, 0f, -1f))
    }
}
