package com.hereliesaz.geministrator.puppet

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The allocation-free evaluator must match the reference [PuppetRigEvaluator] on every shipped rig. */
class CompiledPuppetRigTest {
    @Test
    fun compiledRigHonorsSocketStretchAndRigidity() {
        val rig = PuppetRig(
            canvas = PuppetCanvas(100f, 100f),
            atlas = PuppetAtlas("a.png", 40, 40),
            parts = listOf(
                PuppetPart("body", rect = PuppetRect(0, 0, 20, 20), rest = PuppetTransform(x = 50f, y = 50f)),
                PuppetPart(
                    id = "tendril", parent = "body", rect = PuppetRect(20, 0, 10, 20),
                    kind = "tendril", pivot = PuppetPoint(0.5f, 0f), rigidity = 0.5f,
                    stretch = PuppetStretch("y", 80f), connectTo = "base",
                ),
            ),
            attachments = listOf(
                PuppetAttachment("base", "body", 1f, 0.5f, "tendril-base"),
                PuppetAttachment("tip", "tendril", 0.5f, 1f, "tendril-tip"),
            ),
            states = mapOf("Active" to PuppetStateMotion(
                mapOf("tendril" to PuppetPartMotion(keys = mapOf(
                    "x" to listOf(PuppetKeyframe(0f, 20f)),
                    "scaleY" to listOf(PuppetKeyframe(0f, 3f)),
                ))),
            )),
        )
        rig.validate()
        val compiled = CompiledPuppetRig(rig)
        val buffer = compiled.newBuffer()
        compiled.evaluate(compiled.stateIndex("Active"), 0f, buffer)
        // Body 50 + right-edge socket 10 + half of 20px translation.
        assertTrue(abs(buffer.world[10] - 70f) < 0.001f)
        assertTrue(abs(buffer.world[11] - 50f) < 0.001f)
        // Body socket (60,50), then tail pivot (70,50), scaled 80px tip at y=210.
        assertTrue(abs(buffer.attachX[1] - 70f) < 0.001f)
        assertTrue(abs(buffer.attachY[1] - 210f) < 0.001f)
    }

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
