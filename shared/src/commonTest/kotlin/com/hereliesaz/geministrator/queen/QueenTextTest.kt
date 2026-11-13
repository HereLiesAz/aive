package com.hereliesaz.geministrator.queen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueenTextTest {
    @Test
    fun controlCharsAndMarkdownStripped() {
        val text = queenQuoteText("# Heading\n**bold** `code` [x](http://evil.example/a)\u0000‮ tail", 200)
        assertFalse(text.any { it in "#*`[]<>|\\" || it.code < 0x20 || it == '‮' })
        assertFalse(text.contains("http"))
        assertTrue(text.contains("Heading") && text.contains("tail"))
    }

    @Test
    fun quotesAreCappedWithAnEllipsis() {
        val text = queenQuoteText("x".repeat(500), 160)
        assertEquals(160, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun idsAreStableAndDependOnState() {
        assertEquals(queenNoteId("r1", "a"), queenNoteId("r1", "a"))
        assertTrue(queenNoteId("r1", "a") != queenNoteId("r1", "b"))
    }
}
