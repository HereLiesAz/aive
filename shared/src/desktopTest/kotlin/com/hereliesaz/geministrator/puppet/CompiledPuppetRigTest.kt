package com.hereliesaz.geministrator.puppet

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The allocation-free evaluator must match the reference [PuppetRigEvaluator] on every shipped rig. */
class CompiledPuppetRigTest {
    @Test
    fun matchesReferenceEvaluator() {
        val dir = File("src/commonMain/composeResources/files/rigs")
        val files = dir.listFiles { f -> f.name.endsWith(".rig.json") }.orEmpty()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val rig = PuppetRig.parse(file.readText())
            val compiled = CompiledPuppetRig(rig)
            val buf = compiled.newBuffer()
            (PuppetRig.STATE_NAMES + "Nope").forEach { state ->
                listOf(0f, 0.13f, 0.5f, 0.999f, 1.37f).forEach { phase ->
                    val ref = PuppetRigEvaluator.evaluate(rig, state, phase)
                    compiled.evaluate(compiled.stateIndex(state), phase, buf)
                    assertTrue(compiled.stateName(compiled.stateIndex(state)) == ref.resolvedState)
                    rig.parts.forEachIndexed { i, part ->
                        val t = ref.part(part.id)!!.transform
                        val o = i * 6
                        val expected = floatArrayOf(t.a, t.b, t.c, t.d, t.tx, t.ty)
                        for (k in 0 until 6) {
                            assertTrue(abs(buf.world[o + k] - expected[k]) < 1e-2f, "${file.name} $state $phase ${part.id}[$k]")
                        }
                        assertTrue(abs(buf.alpha[i] - ref.part(part.id)!!.alpha) < 1e-4f)
                    }
                    rig.attachments.forEachIndexed { k, a ->
                        val p = ref.attachments.getValue(a.id)
                        assertTrue(abs(buf.attachX[k] - p.x) < 1e-2f && abs(buf.attachY[k] - p.y) < 1e-2f, "${file.name} ${a.id}")
                    }
                    val order = compiled.drawOrder.map { rig.parts[it].id }
                    assertTrue(order == ref.parts.map { it.part.id }, "${file.name} draw order")
                }
            }
        }
    }
}
