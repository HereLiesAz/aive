package com.hereliesaz.conveyance.demo

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.hereliesaz.conveyance.auditor.ConveyanceAuditor
import com.hereliesaz.conveyance.auditor.JudgeUnreachable
import com.hereliesaz.conveyance.auditor.Judges
import com.hereliesaz.conveyance.compose.ElementRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Grade the Gallery by showing it to something that has never seen it.
 *
 * The rendering half runs anywhere. The judging half needs credentials, so it is skipped without
 * them rather than failing — a check that cannot pass in an environment is not a check there, it is
 * a red light people learn to ignore.
 */
class AuditGallery {

    private val out = File("build/audit").apply { mkdirs() }
    private val json = ObjectMapper().registerKotlinModule().writerWithDefaultPrettyPrinter()

    /**
     * Compose the surface, write the image, and read the truth out while the composition is still
     * standing.
     */
    private fun <T> render(read: (ElementRegistry) -> T): T {
        val registry = ElementRegistry()
        val scene = ImageComposeScene(width = 900, height = 1300, density = Density(2f)) {
            Gallery(registry = registry)
        }
        return try {
            // Two frames: the first lays out, the second is measured once everything has settled.
            scene.render(0L)
            val image = scene.render(2_000_000_000L)
            File(out, "gallery.png").writeBytes(image.encodeToData()!!.bytes)
            read(registry)
        } finally {
            scene.close()
        }
    }

    /**
     * Render the surface and write down what is true about it.
     *
     * These two files are the whole input to an audit, and the split between them is the point:
     * the PNG is all the viewer gets, the JSON is what it is graded against.
     */
    @Test
    fun `emit the audit bundle`() {
        // The frame must be taken while the composition is alive. Closing the scene disposes it,
        // and every element correctly gives up its address on the way out -- so a registry read
        // after close is an empty registry, which is right behaviour and a wrong reading order.
        val frame = render { registry -> registry.auditFrame("gallery") }
        File(out, "gallery.json").writeText(json.writeValueAsString(frame))

        assertTrue(File(out, "gallery.png").length() > 0)
        assertTrue(frame.elements.isNotEmpty(), "A surface with no addressed elements cannot be graded.")
        println("AUDIT BUNDLE: ${frame.elements.size} elements, census ${frame.census}")
        frame.elements.filter { it.staked }.forEach {
            println("  staked: ${it.id} ${it.verb} weight=${it.weight} reversible=${it.reversible}")
        }
    }

    /**
     * The real thing: predict, then grade.
     *
     * Takes whichever judge this machine can reach — a key from any provider if one is configured,
     * otherwise a model running locally with no key at all. If nothing is reachable it says so and
     * returns, because a check that cannot run somewhere is not a check there, and a permanently
     * red one teaches everyone to stop reading CI.
     *
     * It does **not** quietly pass. "Nobody was there to look" and "somebody looked and found
     * nothing wrong" are opposite results, and the output says which one happened.
     */
    @Test
    fun `grade the gallery against a naive viewer`() {
        val png = File(out, "gallery.png")
        val bundle = File(out, "gallery.json")
        if (!png.exists() || !bundle.exists()) {
            println("AUDIT NOT RUN: emit the audit bundle first")
            return
        }

        val frame = render { registry -> registry.auditFrame("gallery") }
        val judge = Judges.detect()
        val report = try {
            ConveyanceAuditor(judge).audit(png.readBytes(), frame)
        } catch (unreachable: JudgeUnreachable) {
            println("AUDIT NOT RUN: ${unreachable.message}")
            println("  Set any provider key, or run a local vision model: ollama pull ${Judges.DEFAULT_LOCAL_MODEL}")
            return
        } catch (offline: java.io.IOException) {
            println("AUDIT NOT RUN: ${judge.name} could not be reached (${offline.message})")
            println("  Set any provider key, or run a local vision model: ollama pull ${Judges.DEFAULT_LOCAL_MODEL}")
            return
        }
        File(out, "gallery-report.json").writeText(json.writeValueAsString(report))

        println("AUDIT: $report")
        report.verdicts.filter { it.grade.name == "Wrong" }.forEach {
            println("  MISLEADING ${it.element}: expected '${it.predicted}', actually '${it.actual}'")
        }
        report.omissions.forEach { println("  OMITTED: $it") }
    }
}
