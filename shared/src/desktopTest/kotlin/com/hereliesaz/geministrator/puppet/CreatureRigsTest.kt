package com.hereliesaz.geministrator.puppet

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
