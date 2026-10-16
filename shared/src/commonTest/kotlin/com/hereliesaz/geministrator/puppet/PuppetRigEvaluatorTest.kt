package com.hereliesaz.geministrator.puppet

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PuppetRigEvaluatorTest {
    private fun near(expected: Float, actual: Float, eps: Float = 1e-3f) =
        assertTrue(abs(expected - actual) < eps, "expected $expected but was $actual")

    private fun rig(
        parts: List<PuppetPart>,
        states: Map<String, PuppetStateMotion> = emptyMap(),
        attachments: List<PuppetAttachment> = emptyList(),
        fallback: String? = null,
    ) = PuppetRig(
        canvas = PuppetCanvas(100f, 100f),
        atlas = PuppetAtlas("a.png", 64, 64),
        parts = parts,
        attachments = attachments,
        states = states,
        fallbackState = fallback,
    )

    private fun motion(part: String, m: PuppetPartMotion) = PuppetStateMotion(mapOf(part to m))

    @Test
    fun hierarchyComposesParentTransforms() {
        val r = rig(listOf(
            PuppetPart("root", rest = PuppetTransform(x = 50f, y = 50f, rotation = 90f)),
            PuppetPart("child", parent = "root", rest = PuppetTransform(x = 10f, y = 0f)),
        ))
        val pose = PuppetRigEvaluator.evaluate(r, "Active", 0f)
        val p = pose.part("child")!!.pivotWorld
        // +90 deg is clockwise on a y-down screen: local +x maps to world +y.
        near(50f, p.x); near(60f, p.y)
    }

    @Test
    fun rotationIsAroundThePivot() {
        val part = PuppetPart(
            "arm", rect = PuppetRect(0, 0, 10, 20), pivot = PuppetPoint(0.5f, 1f),
            rest = PuppetTransform(x = 30f, y = 40f, rotation = 180f),
        )
        val pose = PuppetRigEvaluator.evaluate(rig(listOf(part)), "Ready", 0f).part("arm")!!
        near(30f, pose.pivotWorld.x); near(40f, pose.pivotWorld.y)
        // the top-centre of the box (local 0,-20) swings below the pivot.
        val top = pose.transform.apply(0f, -20f)
        near(30f, top.x); near(60f, top.y)
    }

    @Test
    fun keyframesInterpolateWithEasingAndLoop() {
        val keys = listOf(
            PuppetKeyframe(0f, 0f, PuppetEase.Linear),
            PuppetKeyframe(0.5f, 10f, PuppetEase.EaseIn),
        )
        near(5f, PuppetRigEvaluator.sampleKeys(keys, 0.25f))
        // 0.5 -> 1.0 wraps back to the first key with easeIn: u = 0.5 -> 0.25.
        near(10f - 10f * 0.25f, PuppetRigEvaluator.sampleKeys(keys, 0.75f))
        near(0f, PuppetRigEvaluator.sampleKeys(keys, 0f))
        val step = listOf(PuppetKeyframe(0.2f, 1f, PuppetEase.Step), PuppetKeyframe(0.6f, 3f, PuppetEase.Step))
        near(1f, PuppetRigEvaluator.sampleKeys(step, 0.5f))
        near(3f, PuppetRigEvaluator.sampleKeys(step, 0.1f)) // before first key: held from last
        near(0.5f, PuppetRigEvaluator.ease(PuppetEase.EaseInOut, 0.5f))
        near(0.75f, PuppetRigEvaluator.ease(PuppetEase.EaseOut, 0.5f))
    }

    @Test
    fun phaseLoopsOutsideUnitRange() {
        val r = rig(
            listOf(PuppetPart("p")),
            mapOf("Active" to motion("p", PuppetPartMotion(keys = mapOf("x" to listOf(PuppetKeyframe(0f, 0f), PuppetKeyframe(0.5f, 8f)))))),
        )
        val a = PuppetRigEvaluator.evaluate(r, "Active", 0.25f).part("p")!!.pivotWorld.x
        val b = PuppetRigEvaluator.evaluate(r, "Active", 3.25f).part("p")!!.pivotWorld.x
        val c = PuppetRigEvaluator.evaluate(r, "Active", -0.75f).part("p")!!.pivotWorld.x
        near(4f, a); near(a, b); near(a, c)
    }

    @Test
    fun sineParamsAreAdditiveAndScaleIsMultiplicative() {
        val r = rig(
            listOf(PuppetPart("p", rest = PuppetTransform(rotation = 10f, scaleX = 2f))),
            mapOf("Active" to motion("p", PuppetPartMotion(
                keys = mapOf("scaleX" to listOf(PuppetKeyframe(0f, 1.5f))),
                sine = mapOf("rotation" to PuppetSine(amplitude = 4f, frequency = 2f, phase = 0.25f)),
            ))),
        )
        // sin(2π(2*0 + 0.25)) = 1
        val t = PuppetRigEvaluator.evaluate(r, "Active", 0f).part("p")!!.transform
        val unitX = t.apply(1f, 0f)
        val len = kotlin.math.sqrt(unitX.x * unitX.x + unitX.y * unitX.y)
        near(3f, len)
        near(14f, (kotlin.math.atan2(unitX.y, unitX.x) * 180f / kotlin.math.PI.toFloat()))
    }

    @Test
    fun unknownStateFallsBackThenRests() {
        val states = mapOf("Pending" to motion("p", PuppetPartMotion(keys = mapOf("y" to listOf(PuppetKeyframe(0f, 5f))))))
        val withFallback = rig(listOf(PuppetPart("p")), states, fallback = "Pending")
        val pose = PuppetRigEvaluator.evaluate(withFallback, "Exploded", 0.3f)
        assertEquals("Pending", pose.resolvedState)
        near(5f, pose.part("p")!!.pivotWorld.y)
        val noFallback = PuppetRigEvaluator.evaluate(rig(listOf(PuppetPart("p")), states), "Exploded", 0.3f)
        assertNull(noFallback.resolvedState)
        near(0f, noFallback.part("p")!!.pivotWorld.y)
    }

    @Test
    fun drawOrderAlphaAndAttachments() {
        val r = rig(
            listOf(
                PuppetPart("a", z = 2f, rest = PuppetTransform(alpha = 0.5f)),
                PuppetPart("b", parent = "a", z = 1f, rect = PuppetRect(0, 0, 20, 10),
                    rest = PuppetTransform(x = 10f, y = 10f, alpha = 0.5f)),
            ),
            attachments = listOf(PuppetAttachment("socket", "b", x = 1f, y = 0.5f, kind = "arm-socket")),
        )
        val pose = PuppetRigEvaluator.evaluate(r, "Ready", 0f)
        assertEquals(listOf("b", "a"), pose.parts.map { it.part.id })
        near(0.25f, pose.part("b")!!.alpha)
        val s = pose.attachments.getValue("socket")
        near(20f, s.x); near(10f, s.y)
    }

    @Test
    fun tailLengthSocketAndRigidityAffectEvaluatedGeometry() {
        val body = PuppetPart(
            id = "body",
            rect = PuppetRect(0, 0, 20, 20),
            rest = PuppetTransform(x = 50f, y = 50f),
        )
        val tail = PuppetPart(
            id = "tail",
            parent = "body",
            rect = PuppetRect(20, 0, 10, 20),
            pivot = PuppetPoint(0.5f, 0f),
            kind = "tail",
            rigidity = 0.5f,
            stretch = PuppetStretch(axis = "y", length = 80f),
            connectTo = "body-tail-root",
        )
        val state = motion("tail", PuppetPartMotion(
            keys = mapOf(
                "x" to listOf(PuppetKeyframe(0f, 20f)),
                "scaleY" to listOf(PuppetKeyframe(0f, 3f)),
            ),
        ))
        val r = rig(
            parts = listOf(body, tail),
            states = mapOf("Active" to state),
            attachments = listOf(
                PuppetAttachment("body-tail-root", "body", 1f, 0.5f, "tail-base"),
                PuppetAttachment("tail-tip", "tail", 0.5f, 1f, "tail-tip"),
            ),
        )
        r.validate()
        val pose = PuppetRigEvaluator.evaluate(r, "Active", 0f)
        // Parent pivot (50,50) + right-edge socket (10,0) + half of 20px motion.
        near(70f, pose.part("tail")!!.pivotWorld.x)
        near(50f, pose.part("tail")!!.pivotWorld.y)
        // Half-rigid tail: scaleY=1+(3-1)/2=2; 80px tail stretches to 160px.
        near(70f, pose.attachments.getValue("tail-tip").x)
        near(210f, pose.attachments.getValue("tail-tip").y)

        val rigid = r.copy(parts = listOf(body, tail.copy(rigidity = 1f)))
        val rigidPose = PuppetRigEvaluator.evaluate(rigid, "Active", 0f)
        near(60f, rigidPose.part("tail")!!.pivotWorld.x)
        near(130f, rigidPose.attachments.getValue("tail-tip").y)
    }

    @Test
    fun editorExtensionsRoundTripAndInvalidConnectionsAreRejected() {
        val body = PuppetPart("body")
        val tail = PuppetPart(
            id = "tail", parent = "body", kind = "tail", rigidity = 0.4f,
            stretch = PuppetStretch("x", 90f), connectTo = "socket",
        )
        val base = rig(
            listOf(body, tail),
            attachments = listOf(PuppetAttachment("socket", "body", 1f, 0.5f)),
        )
        assertEquals(base, PuppetRig.parse(PuppetRig.encode(base)))
        assertFailsWith<IllegalArgumentException> {
            base.copy(parts = listOf(body, tail.copy(connectTo = "missing"))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(parts = listOf(body, tail.copy(rigidity = 1.5f))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(parts = listOf(body, tail.copy(stretch = PuppetStretch("y", -1f)))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(attachments = base.attachments + base.attachments.first()).validate()
        }
    }

    @Test
    fun parserRoundTripsAndRejectsBadRigs() {
        val r = rig(listOf(PuppetPart("a"), PuppetPart("b", parent = "a")))
        assertEquals(r, PuppetRig.parse(PuppetRig.encode(r)))
        assertFailsWith<IllegalArgumentException> {
            rig(listOf(PuppetPart("a", parent = "b"), PuppetPart("b", parent = "a"))).validate()
        }
        assertFailsWith<IllegalArgumentException> { rig(listOf(PuppetPart("a", parent = "zzz"))).validate() }
        assertFailsWith<IllegalArgumentException> {
            PuppetRig.parse("""{"format":"other","version":1,"canvas":{"width":1,"height":1},"atlas":{"image":"x","width":1,"height":1},"parts":[]}""")
        }
    }
}
