package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TendrilTethersTest {
    private fun px(p: H2g2TerrariumPosition) = p.x * 1000f to p.y * 1000f
    private fun dist(a: H2g2TerrariumPosition, b: H2g2TerrariumPosition): Float {
        val (ax, ay) = px(a); val (bx, by) = px(b)
        return hypot(bx - ax, by - ay)
    }

    @Test
    fun aDraggedCreaturePullsItsPartnerToWithinReach() {
        val out = constrainTendrils(
            positions = mapOf("a" to H2g2TerrariumPosition(0.2f, 0.5f), "b" to H2g2TerrariumPosition(0.8f, 0.5f)),
            links = listOf("a" to "b"),
            widthPx = 1000f, heightPx = 1000f, maxPx = 200f, pinned = "a",
        )
        assertEquals(0.2f, out.getValue("a").x)
        assertTrue(dist(out.getValue("a"), out.getValue("b")) <= 201f)
    }

    @Test
    fun withinReachNothingMoves() {
        val start = mapOf("a" to H2g2TerrariumPosition(0.4f, 0.5f), "b" to H2g2TerrariumPosition(0.5f, 0.5f))
        assertEquals(start, constrainTendrils(start, listOf("a" to "b"), 1000f, 1000f, maxPx = 200f))
    }

    @Test
    fun stretchRunsFromSlackToTheLimit() {
        assertEquals(0f, tendrilStretch(100f, 150f, 200f))
        assertEquals(0.5f, tendrilStretch(175f, 150f, 200f))
        assertEquals(1f, tendrilStretch(260f, 150f, 200f))
    }
}
