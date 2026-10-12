package com.hereliesaz.conveyance.demo

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import java.awt.image.BufferedImage
import kotlin.test.Test
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.SubjectId
import com.hereliesaz.conveyance.compose.ElementRegistry
import com.hereliesaz.conveyance.compose.subjectElement
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Renders the framework's verbs as motion.
 *
 * Stills were the wrong medium and produced the wrong impression: a grammar's whole argument is what
 * happens between two states, so a photograph of the endpoints shows the one thing conveyance is not
 * about. These are driven by real pointer events against the real application, and every frame comes
 * from the framework's own animation rather than from anything staged here.
 */
class RenderMotion {

    private val out = File("build/motion").apply { mkdirs() }
    private val width = 900
    private val height = 1300
    private val density = 2f

    /**
     * Drive the app and capture every frame.
     *
     * [script] is given the frame index and may send pointer events; the scene advances by one
     * display frame each step, so what is recorded is exactly what a person would have seen.
     */
    private fun film(
        name: String,
        frames: Int = 40,
        initial: Int = 4,
        script: Camera.(Int) -> Unit,
    ) {
        // The films tap named elements rather than coordinates, so they are driven by the same
        // registry the framework navigates by. A hard-coded pixel is a second, silent description
        // of the layout, and the two descriptions drift the first time a margin changes.
        val registry = ElementRegistry()
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            Gallery(initial = initial, registry = registry)
        }
        val camera = Camera(scene, registry)
        val captured = mutableListOf<BufferedImage>()
        try {
            var nanos = 0L
            repeat(frames) { frame ->
                camera.script(frame)
                nanos += 33_000_000L
                val skia = scene.render(nanos)
                captured += ImageIO.read(skia.encodeToData()!!.bytes.inputStream())
            }
        } finally {
            scene.close()
        }
        writeGif(File(out, "$name.gif"), captured, delayMs = 33)
        assertTrue(File(out, "$name.gif").length() > 0, "$name produced no film")
    }

    /** A hand over the running application, which can only touch things that are actually there. */
    private class Camera(val scene: ImageComposeScene, val registry: ElementRegistry) {

        fun tap(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
        }

        /** Touch a named element where it currently is, and say so if it is not on screen. */
        fun touch(id: ElementId) {
            val where = assertNotNull(registry.bounds(id), "Nothing to touch at $id.")
            tap(where.center.x, where.center.y)
        }

        fun touch(subject: SubjectId) = touch(subjectElement(subject))
    }

    /** The Migration: an empty tray is its own invitation, and the shutter moves in once used. */
    @Test
    fun `the migration`() = film("01-migration", frames = 55, initial = 0) { frame ->
        // An empty tray is the only state in which the Migration exists. The shutter sits in the
        // middle of the space it is inviting you to fill; taking one photograph sends it home.
        if (frame == 8) touch(ElementId("act:photo.new"))
        if (frame == 26) touch(ElementId("act:photo.new"))
    }

    /**
     * Enter and Return: the thing you touched becomes the place you are in, and gives it back.
     *
     * This is the law the manifesto opens with, rendered. The photograph does not disappear and get
     * replaced by a screen; it grows into one, and on the way out it shrinks into the slot it holds
     * in the tray — the slot it holds *now*, which is why nobody ever loses their place.
     */
    @Test
    fun `entering and returning`() = film("02-enter", frames = 70) { frame ->
        if (frame == 6) touch(SubjectId("photo.2"))
        // The surrounding ground is the way back out.
        if (frame == 38) tap(40f, height - 40f)
    }

    /**
     * The Escort: reaching to send before choosing anyone does not refuse you in place.
     *
     * The photograph leans toward the people — that is the Refuse signature, resistance at the point
     * of contact — and then the person you were missing settles under your attention. No message,
     * no greyed-out control, no explanation.
     */
    @Test
    fun `the escort`() = film("03-escort", frames = 60) { frame ->
        if (frame == 5) touch(SubjectId("photo.2"))
        if (frame == 30) touch(SubjectId("photo.2"))
    }

    /**
     * Send: the photograph leaves your hands and arrives somewhere you can point at, and the person
     * it went to warms. This is the entire argument for a visible destination.
     */
    @Test
    fun `sending`() = film("04-send", frames = 75) { frame ->
        if (frame == 5) touch(SubjectId("photo.2"))
        if (frame == 30) touch(ElementId("person:ines"))
        if (frame == 42) touch(SubjectId("photo.2"))
    }

    /**
     * Destroy and the Ghost: giving something up leaves its shape behind, pressed flat, exactly
     * where it was.
     *
     * No confirmation dialog interrupts the discard, and none is needed — the residue left in the
     * tray is the safety mechanism, not a question asked before the fact. Pulling the flattened
     * slot back is what recovery looks like: not a snackbar in a different postcode, but the same
     * gap, filled back in.
     */
    @Test
    fun `discarding and recovering`() = film("05-discard", frames = 100) { frame ->
        if (frame == 5) touch(SubjectId("photo.2"))
        if (frame == 25) touch(ElementId("discard:photo.2"))
        if (frame == 45) tap(40f, height - 40f)
        if (frame == 65) touch(ElementId("ghost:photo.2"))
    }

    /**
     * Reveal: a count that was already true becomes visible, in the exact spot it was true in.
     *
     * Nothing travels and no place changes — the whole distinction between Reveal and every other
     * verb here is that this is the one where the person does not go anywhere.
     */
    @Test
    fun `revealing the tally`() = film("06-reveal", frames = 45) { frame ->
        if (frame == 10) touch(ElementId("tally"))
    }

    private fun writeGif(target: File, frames: List<BufferedImage>, delayMs: Int) {
        require(frames.isNotEmpty()) { "nothing to write" }
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        FileImageOutputStream(target).use { output ->
            writer.output = output
            val params = writer.defaultWriteParam
            val type = javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)
            val metadata = writer.getDefaultImageMetadata(type, params)
            val format = metadata.nativeMetadataFormatName

            val root = metadata.getAsTree(format) as IIOMetadataNode
            val control = IIOMetadataNode("GraphicControlExtension").apply {
                setAttribute("disposalMethod", "none")
                setAttribute("userInputFlag", "FALSE")
                setAttribute("transparentColorFlag", "FALSE")
                setAttribute("delayTime", (delayMs / 10).toString())
                setAttribute("transparentColorIndex", "0")
            }
            root.appendChild(control)
            val extensions = IIOMetadataNode("ApplicationExtensions")
            extensions.appendChild(
                IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    // Loop forever: a grammar is learned by seeing the same motion more than once.
                    userObject = byteArrayOf(0x1, 0x0, 0x0)
                },
            )
            root.appendChild(extensions)
            metadata.setFromTree(format, root)

            writer.prepareWriteSequence(null)
            frames.forEach { frame ->
                val rgb = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                rgb.createGraphics().apply { drawImage(frame, 0, 0, null); dispose() }
                writer.writeToSequence(IIOImage(rgb, null, metadata), params)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }
}
