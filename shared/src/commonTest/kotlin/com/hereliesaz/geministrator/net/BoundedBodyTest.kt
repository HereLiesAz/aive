package com.hereliesaz.geministrator.net

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedBodyTest {
    @Test
    fun aBodyThatNeverEndsIsCutOffNearTheCapNotReadToTheEnd() = runTest {
        val channel = ByteChannel()
        var written = 0L
        val writer = launch {
            val chunk = ByteArray(4096) { 'a'.code.toByte() }
            while (true) {
                channel.writeFully(chunk)
                channel.flush()
                written += chunk.size
            }
        }
        assertFailsWith<ResponseTooLargeException> { channel.readAllBounded(100_000) }
        // The channel buffers about 1 MB on its own, so the writer refills once; then nobody reads and it is parked for good.
        repeat(100) { yield() }
        val parked = written
        repeat(100) { yield() }
        assertEquals(parked, written, "nothing more is pulled through after the cap")
        assertTrue(written <= 4 * 1024 * 1024, "the endless body was not drained: $written")
        writer.cancel()
    }

    @Test
    fun bytesAreCountedNotCharacters() = runTest {
        val text = "€".repeat(40) // 120 bytes
        assertFailsWith<ResponseTooLargeException> { ByteReadChannel(text.encodeToByteArray()).readAllBounded(100) }
        assertEquals(text, ByteReadChannel(text.encodeToByteArray()).readAllBounded(120).decodeToString())
    }

    @Test
    fun linesSplitOnLfAndCrLfAndTheLastOneNeedsNoTerminator() = runTest {
        val reader = BoundedLineReader(ByteReadChannel("a\r\nb\n\nc".encodeToByteArray()), 1000)
        assertEquals("a", reader.readLine())
        assertEquals("b", reader.readLine())
        assertEquals("", reader.readLine())
        assertEquals("c", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun aLongLineIsRefusedByBytesBeforeItIsAssembled() = runTest {
        val reader = BoundedLineReader(ByteReadChannel(("x".repeat(500_000) + "\n").encodeToByteArray()), 50_000)
        assertFailsWith<ResponseTooLargeException> { reader.readLine() }
    }

    @Test
    fun manyShortLinesAcrossChunksComeBackWhole() = runTest {
        val lines = (1..5000).map { "line-$it-€" }
        val reader = BoundedLineReader(ByteReadChannel(lines.joinToString("\n").encodeToByteArray()), 10_000_000)
        val back = buildList { while (true) add(reader.readLine() ?: break) }
        assertEquals(lines, back)
    }
}
