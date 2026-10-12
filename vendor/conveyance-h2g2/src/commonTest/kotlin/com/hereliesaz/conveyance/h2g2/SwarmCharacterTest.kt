package com.hereliesaz.conveyance.h2g2

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SwarmCharacterTest {
    @Test
    fun `same identity reconstructs the same character`() {
        val first = h2g2SwarmGenome("agent/researcher/42")
        val second = h2g2SwarmGenome("agent/researcher/42")

        assertEquals(first, second)
        assertEquals(first.structuralSignature(), second.structuralSignature())
    }

    @Test
    fun `different identities differ structurally not merely by colour`() {
        val signatures = (0 until 64)
            .map { h2g2SwarmGenome("agent-$it").structuralSignature() }

        assertTrue(signatures.toSet().size >= 60, "Procedural identities should be overwhelmingly structurally unique")
        assertNotEquals(signatures.first(), signatures.last())
    }

    @Test
    fun `terminal anchor selects antenna facing interaction target`() {
        val genome = h2g2SwarmGenome("agent-transfer-test")
        val right = genome.terminalAnchorToward(Offset(1f, 0f))
        val left = genome.terminalAnchorToward(Offset(-1f, 0f))

        assertTrue(right.x >= left.x)
        assertTrue(right.getDistance() > 0f)
        assertTrue(left.getDistance() > 0f)
    }
}
