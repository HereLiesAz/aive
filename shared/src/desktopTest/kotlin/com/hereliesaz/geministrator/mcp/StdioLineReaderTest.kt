package com.hereliesaz.geministrator.mcp

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun anEndlessLineIsAbandonedAfterTheFirstByteBeyondTheCap() {
        var consumed = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                consumed++
                return 'a'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                consumed += len
                java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
                return len
            }
        }
        assertFailsWith<StdioLineTooLongException> { StdioLineReader(endless, 4).readLine() }
        // Only what the buffered stream took in one fill was read; a reader that held the whole line would never have stopped.
        assertTrue(consumed <= 64 * 1024, "read $consumed bytes")
    }
}
