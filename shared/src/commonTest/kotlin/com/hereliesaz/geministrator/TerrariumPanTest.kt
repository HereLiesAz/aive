package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a finger does on the hive's ground: pan on empty ground, cut only from a strand, never lose the hive. */
class TerrariumPanTest {
    private val strand = listOf(Offset(0f, 100f), Offset(200f, 100f))

    @Test
    fun aDragOnEmptyGroundPans() {
        val onStrand = touchesStrand(Offset(100f, 300f), listOf(strand), 20f)
        assertFalse(onStrand)
        assertEquals(CanvasDragIntent.Pan, canvasDragIntent(onStrand, sliceAvailable = true))
    }

    @Test
    fun aDragThatStartsOnAStrandCutsItInBuild() {
        val onStrand = touchesStrand(Offset(100f, 110f), listOf(strand), 20f)
        assertTrue(onStrand)
        assertEquals(CanvasDragIntent.Slice, canvasDragIntent(onStrand, sliceAvailable = true))
    }

    @Test
    fun aStrandCannotBeCutInLiveSoTheDragPans() {
        assertEquals(CanvasDragIntent.Pan, canvasDragIntent(startedOnStrand = true, sliceAvailable = false))
    }

    @Test
    fun strandHitUsesTheDistanceToTheLineNotJustItsPoints() {
        assertEquals(10f, distanceToPolyline(Offset(50f, 110f), strand))
        assertEquals(5f, distanceToPolyline(Offset(205f, 100f), strand))
        assertEquals(Float.POSITIVE_INFINITY, distanceToPolyline(Offset.Zero, emptyList()))
        assertFalse(touchesStrand(Offset(50f, 150f), listOf(strand), 20f))
    }

    private val hive = listOf(H2g2TerrariumPosition(0.2f, 0.3f), H2g2TerrariumPosition(0.8f, 0.7f))

    @Test
    fun draggingTheGroundRightMovesTheFocusLeft() {
        val next = pannedTerrariumFocus(Offset(0.5f, 0.5f), Offset(100f, 0f), 1000f, 1000f, 1f, hive)
        assertEquals(0.4f, next.x, 1e-5f)
        assertEquals(0.5f, next.y, 1e-5f)
    }

    @Test
    fun zoomedInTheSameDragMovesTheFocusLess() {
        val next = pannedTerrariumFocus(Offset(0.5f, 0.5f), Offset(100f, 0f), 1000f, 1000f, 2f, hive)
        assertEquals(0.45f, next.x, 1e-5f)
    }

    @Test
    fun theHiveCannotBeLostOffScreen() {
        val far = pannedTerrariumFocus(Offset(0.5f, 0.5f), Offset(-100_000f, 100_000f), 1000f, 1000f, 1f, hive)
        assertEquals(0.9f, far.x, 1e-5f)
        assertEquals(0.2f, far.y, 1e-5f)
        assertEquals(Offset(0.5f, 0.5f), clampTerrariumFocus(Offset(0.9f, 0.1f), emptyList()))
    }
}
