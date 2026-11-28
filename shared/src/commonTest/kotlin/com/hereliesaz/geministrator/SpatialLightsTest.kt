package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hive's light says only what the run's own records say, and nothing else glows. */
class SpatialLightsTest {
    private val queen = SpatialCreature("queen", "Queen", null, SpatialPoint.Origin, 1, H2g2WorkflowState.Pending, SpatialForm.Nucleus)
    private fun scene(vararg links: Pair<String, String>) = SpatialHiveScene("run", "workflow", queen, emptyList(),
        links.map { (a, b) -> SpatialConnection(a, b, H2g2TerrariumRelationshipKind.entries.first(), true) },
        SpatialMemoryProjection(emptyList(), emptyList()))

    private fun body(id: String, x: Float, form: String = "Creature", radius: Float = .5f) =
        SpatialRenderBody(id, SpatialPoint(x, 0f, 0f), 1, form, "Running", 0f, radius)

    private val a = body("a", 0f)
    private val b = body("b", 4f)
    private val spore1 = body("node:1", 0f, "Spore", .1f).copy(position = SpatialPoint(0f, 3f, 0f))
    private val spore2 = body("node:2", 1f, "Spore", .1f).copy(position = SpatialPoint(1f, 3f, 0f))
    private val frame = SpatialRenderFrame(listOf(a, b), listOf(spore1, spore2), emptyList())

    private fun lights(now: Long, uses: List<SporeUse> = emptyList(), pulses: List<TransferPulse> = emptyList(),
        rates: Map<String, Float> = emptyMap(), phases: Map<String, Float> = rates.mapValues { .95f }, scene: SpatialHiveScene = scene(),
        pulseRates: Map<String, Float> = rates) = spatialLights(now, scene, frame, uses, pulses, rates, phases, pulseRates = pulseRates)

    @Test
    fun onlyTheUsedMemoryFlipsBlueAndOnlyForAWhile() {
        val use = SporeUse(TaskDefinitionId("a"), "node:1", 1_000L)
        val lit = lights(1_300L, uses = listOf(use)).single()
        assertEquals(spore1.position, lit.at)
        assertTrue(lit.blue > lit.red && lit.strength > 0f)
        assertEquals(emptyList(), lights(1_000L + BLUE_GLOW_MILLIS, uses = listOf(use))) // back to its resting glow
        assertEquals(emptyList(), lights(900L, uses = listOf(use))) // not yet used
        // A memory that is not in the frame is no light, and its neighbour is not touched.
        assertEquals(emptyList(), lights(1_300L, uses = listOf(SporeUse(TaskDefinitionId("a"), "node:9", 1_000L))))
    }

    @Test
    fun aCreatureBeatsOnlyWhileItWorksAndEasesOutBetweenBeats() {
        val bright = lights(0L, rates = mapOf("a" to 1f), phases = mapOf("a" to 0f)).single()
        assertEquals(a.position, bright.at)
        val faded = lights(0L, rates = mapOf("a" to 1f), phases = mapOf("a" to .6f)).single()
        assertTrue(faded.strength < bright.strength)
        assertEquals(emptyList(), lights(0L, rates = mapOf("a" to 1f), phases = mapOf("a" to .95f))) // dark just before the next
        assertEquals(emptyList(), lights(0L, rates = emptyMap(), phases = mapOf("a" to 0f))) // not beating
    }

    @Test
    fun aPulseTravelsDownARealConnectionTowardWhomItIsSaidTo() {
        val pulse = TransferPulse(TaskDefinitionId("a"), TaskDefinitionId("b"), 1_000L)
        val rates = mapOf("a" to 1f)
        val halfway = lights(1_600L, pulses = listOf(pulse), rates = rates, scene = scene("a" to "b")).single()
        assertTrue(halfway.at.x in .5f..3.5f, "pulse at ${halfway.at}")
        // Nothing is drawn for a delivery with no connection, no sender rate, or outside the pulse's flight.
        assertEquals(emptyList(), lights(1_600L, pulses = listOf(pulse), rates = rates, scene = scene()))
        assertEquals(emptyList(), lights(1_600L, pulses = listOf(pulse), rates = emptyMap(), scene = scene("a" to "b")))
        assertEquals(emptyList(), lights(900L, pulses = listOf(pulse), rates = rates, scene = scene("a" to "b")))
        // A sender that has finished still sends, at the rate given for pulses.
        assertEquals(1, lights(1_600L, pulses = listOf(pulse), rates = emptyMap(), scene = scene("a" to "b"), pulseRates = rates).size)
    }

    @Test
    fun aFinishingCreatureBurstsAndTheFrameIsBounded() {
        val burst = CompletionBurst(TaskDefinitionId("b"), 5_000L, emptySet())
        val flash = spatialLights(5_300L, scene(), frame, emptyList(), emptyList(), emptyMap(), emptyMap(), listOf(burst)).single()
        assertEquals(b.position, flash.at)
        assertEquals(emptyList(), spatialLights(5_000L + BURST_MILLIS, scene(), frame, emptyList(), emptyList(), emptyMap(), emptyMap(), listOf(burst)))
        // However many things glow, a frame carries at most the shader's lights, the strongest first.
        val many = SpatialRenderFrame(listOf(a), (0 until 100).map { body("node:$it", 0f, "Spore", .1f) }, emptyList())
        val uses = (0 until 100).map { SporeUse(TaskDefinitionId("a"), "node:$it", 1_000L) }
        val capped = spatialLights(1_300L, scene(), many, uses, emptyList(), emptyMap(), emptyMap())
        assertEquals(MAX_SPATIAL_LIGHTS, capped.size)
        assertEquals(capped.sortedByDescending { it.strength }, capped)
    }

    @Test
    fun aPointAlongALineIsByLength() {
        val line = listOf(SpatialPoint(0f, 0f, 0f), SpatialPoint(4f, 0f, 0f))
        assertEquals(SpatialPoint(2f, 0f, 0f), pointAlong(line, .5f))
        assertEquals(SpatialPoint(4f, 0f, 0f), pointAlong(line, 9f)) // clamped
        val bent = listOf(SpatialPoint(0f, 0f, 0f), SpatialPoint(2f, 0f, 0f), SpatialPoint(2f, 6f, 0f)) // lengths 2 and 6
        assertEquals(SpatialPoint(2f, 2f, 0f), pointAlong(bent, .5f)) // 4 of 8 along: 2 along the first leg, 2 up the second
        assertEquals(SpatialPoint(1f, 1f, 1f), pointAlong(listOf(SpatialPoint(1f, 1f, 1f)), .5f))
        assertEquals(SpatialPoint.Origin, pointAlong(emptyList(), .5f))
    }
}
