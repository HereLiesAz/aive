package com.hereliesaz.geministrator.mcp

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StdioLineReaderTest {
    private fun reader(text: String, max: Int) = StdioLineReader(ByteArrayInputStream(text.encodeToByteArray()), max)

    @Test
    fun readsLinesAndStripsReturns() {
        val r = reader("one\r\ntwo\nthree", 100)
        assertEquals("one", r.readLine())
        assertEquals("two", r.readLine())
        assertEquals("three", r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun aLineAtTheCapIsAccepted() {
        assertEquals("abcd", reader("abcd\n", 4).readLine())
    }

    @Test
    fun aLineOverTheCapFailsBeforeItIsHeldInFull() {
        assertFailsWith<StdioLineTooLongException> { reader("abcde\n", 4).readLine() }
        assertFailsWith<StdioLineTooLongException> { reader("abcde", 4).readLine() }
    }
}
