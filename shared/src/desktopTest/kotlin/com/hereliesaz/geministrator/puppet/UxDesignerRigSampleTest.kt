package com.hereliesaz.geministrator.puppet

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The transcribed reference rig shipped in compose resources must parse and evaluate. */
class UxDesignerRigSampleTest {
    @Test
    fun uxDesignerSampleParsesAndEvaluatesEveryState() {
        val dir = File("src/commonMain/composeResources/files/rigs")
        val rig = PuppetRig.parse(File(dir, "ux-designer.rig.json").readText())
        assertEquals("ux-designer", rig.role)
        assertTrue(File(dir, rig.atlas.image).isFile, "atlas image missing")
        assertEquals(PuppetRig.STATE_NAMES.toSet(), rig.states.keys)
        rig.parts.forEach { p -> p.rect?.let { assertTrue(it.x + it.w <= rig.atlas.width && it.y + it.h <= rig.atlas.height) } }
        PuppetRig.STATE_NAMES.forEach { state ->
            val pose = PuppetRigEvaluator.evaluate(rig, state, 0.37f)
            assertEquals(state, pose.resolvedState)
            assertEquals(rig.parts.size, pose.parts.size)
            assertEquals(setOf("arm.socket.a", "arm.socket.b", "prop.slot"), pose.attachments.keys)
        }
    }
}
