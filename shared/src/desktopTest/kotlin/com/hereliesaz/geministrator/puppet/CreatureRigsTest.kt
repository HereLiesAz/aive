package com.hereliesaz.geministrator.puppet

import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every rig shipped in compose resources (incl. the sliced creature rigs) must parse and evaluate in every state. */
class CreatureRigsTest {
    @Test
    fun everyRigParsesAndEvaluatesEveryState() {
        val dir = File("src/commonMain/composeResources/files/rigs")
        val files = dir.listFiles { f -> f.name.endsWith(".rig.json") }.orEmpty().sortedBy { it.name }
        assertTrue(files.size > 1, "expected creature rigs in $dir")
        files.forEach { file ->
            val rig = PuppetRig.parse(file.readText())
            assertEquals(file.name.removeSuffix(".rig.json"), rig.role, "role must match the file name")
            assertTrue(File(dir, rig.atlas.image).isFile, "${file.name}: atlas image missing")
            assertEquals(PuppetRig.STATE_NAMES.toSet(), rig.states.keys, "${file.name}: states")
            rig.parts.forEach { p ->
                p.rect?.let { assertTrue(it.x + it.w <= rig.atlas.width && it.y + it.h <= rig.atlas.height, "${file.name}/${p.id}: rect outside atlas") }
            }
            PuppetRig.STATE_NAMES.forEach { state ->
                listOf(0f, 0.37f, 0.91f).forEach { phase ->
                    val pose = PuppetRigEvaluator.evaluate(rig, state, phase)
                    assertEquals(state, pose.resolvedState)
                    assertEquals(rig.parts.size, pose.parts.size)
                    assertEquals(rig.attachments.map { it.id }.toSet(), pose.attachments.keys)
                    pose.attachments.values.forEach { a -> assertTrue(a.x.isFinite() && a.y.isFinite(), "${file.name}: non-finite attachment") }
                }
            }
        }
    }

    /**
     * Catches truncated or corrupt atlases: the PNG signature, every chunk's length and CRC, the
     * closing IEND chunk, the IHDR size against the rig's declared atlas size, and a full decode.
     */
    @Test
    fun everyAtlasIsACompletePngOfTheDeclaredSize() {
        val dir = File("src/commonMain/composeResources/files/rigs")
        val files = dir.listFiles { f -> f.name.endsWith(".rig.json") }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val rig = PuppetRig.parse(file.readText())
            val atlas = File(dir, rig.atlas.image)
            assertTrue(atlas.isFile, "${file.name}: atlas ${rig.atlas.image} missing")
            val bytes = atlas.readBytes()
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue(bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(signature), "${atlas.name}: not a PNG")
            val buffer = ByteBuffer.wrap(bytes)
            buffer.position(8)
            var width = -1
            var height = -1
            var sawIend = false
            while (buffer.remaining() >= 12 && !sawIend) {
                val length = buffer.int
                assertTrue(length >= 0 && buffer.remaining() >= length + 8, "${atlas.name}: truncated chunk")
                val typeBytes = ByteArray(4).also { buffer.get(it) }
                val data = ByteArray(length).also { buffer.get(it) }
                val crc = buffer.int.toLong() and 0xFFFFFFFFL
                val check = CRC32().apply { update(typeBytes); update(data) }.value
                assertEquals(check, crc, "${atlas.name}: bad CRC in ${typeBytes.decodeToString()}")
                when (typeBytes.decodeToString()) {
                    "IHDR" -> {
                        width = ByteBuffer.wrap(data).int
                        height = ByteBuffer.wrap(data, 4, 4).int
                    }
                    "IEND" -> sawIend = true
                }
            }
            assertTrue(sawIend, "${atlas.name}: no IEND chunk (truncated)")
            assertEquals(0, buffer.remaining(), "${atlas.name}: data after IEND")
            assertEquals(rig.atlas.width, width, "${atlas.name}: width")
            assertEquals(rig.atlas.height, height, "${atlas.name}: height")
            val image = assertNotNull(ImageIO.read(atlas), "${atlas.name}: cannot be decoded")
            assertEquals(width, image.width)
            assertEquals(height, image.height)
        }
    }

    /** The retired sprite, procedural and detached-arm art and renderers must stay gone. */
    @Test
    fun noRetiredCreatureArtOrRendererRemains() {
        val resources = File("src/commonMain/composeResources")
        val drawable = File(resources, "drawable")
        val leftovers = drawable.walkTopDown().filter { f ->
            f.isFile && (f.name.startsWith("mascot_") || f.name.startsWith("node_arm_") || f.name.endsWith("_rig_atlas.png"))
        }.map { it.name }.toList()
        assertEquals(emptyList(), leftovers, "retired art in composeResources/drawable")

        // Symbol names are assembled so that this file does not contain them verbatim.
        val retired = listOf("drawNode" + "Mascot", "Mascot" + "PuppetRig", "Mascot" + "SpriteAtlas", "Sprite" + "MascotSurface",
            "Node" + "ArmAssets", "drawDetached" + "NodeArm", "Res.drawable." + "mascot_")
        val sources = File("src/commonMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        sources.forEach { source ->
            val text = source.readText()
            retired.forEach { symbol -> assertTrue(symbol !in text, "${source.name} still mentions $symbol") }
        }
    }
}
