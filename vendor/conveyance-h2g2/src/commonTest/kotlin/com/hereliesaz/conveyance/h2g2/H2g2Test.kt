package com.hereliesaz.conveyance.h2g2

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class H2g2Test {

    @Test
    fun `indexOf resolves a real hue name exactly, not through the hash`() {
        H2g2.hueNames.forEachIndexed { index, name ->
            assertEquals(index, H2g2.indexOf(name), "\"$name\" should resolve to its own hues entry.")
        }
    }

    @Test
    fun `indexOf falls back to the hash for anything not a real hue name`() {
        assertEquals(H2g2.hueOf("confirm-record"), H2g2.indexOf("confirm-record"))
        assertEquals(H2g2.hueOf(""), H2g2.indexOf(""))
    }

    @Test
    fun `hueOf is deterministic -- the same id always resolves to the same hue`() {
        val ids = listOf("photo.send", "invoice.41", "", "a", "the quick brown fox", "🦊")
        ids.forEach { id ->
            assertEquals(H2g2.hueOf(id), H2g2.hueOf(id))
        }
    }

    @Test
    fun `hueOf always lands inside hues, never throws, for a wide spread of ids`() {
        // Including the exact string this session's audit worried about: one hash overflow class
        // (negate-then-modulo on Int.MIN_VALUE) was found and fixed in three sibling libraries
        // this session. This doesn't prove no string can ever hit Int.MIN_VALUE, but it does prove
        // hueOf/indexOf never produces an out-of-range index for anything actually thrown at them.
        val ids = (0..500).map { "subject.$it" } + listOf("", " ", "🦊", "a".repeat(500))
        ids.forEach { id ->
            val index = H2g2.hueOf(id)
            assertTrue(index in H2g2.hues.indices, "hueOf(\"$id\") = $index, out of range.")
            assertTrue(index in H2g2.caps.indices, "caps and hues must stay index-aligned.")
        }
    }

    @Test
    fun `hues and caps are index-aligned and the same size as hueNames`() {
        assertEquals(H2g2.hues.size, H2g2.caps.size)
        assertEquals(H2g2.hues.size, H2g2.hueNames.size)
    }

    @Test
    fun `contrastingText reads legibly against a known-light and known-dark color`() {
        with(H2g2) {
            assertEquals(ink, Color.White.contrastingText(), "White should read with dark text.")
            assertEquals(white, Color.Black.contrastingText(), "Black should read with light text.")
        }
    }
}
