package com.hereliesaz.geministrator.net

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/** A remote body went past the byte cap the reader was given; the rest of it was not read. */
class ResponseTooLargeException(val limitBytes: Long) : Exception("The response is larger than $limitBytes bytes")

private const val CHUNK_BYTES = 8192

/**
 * Reads this channel to its end, failing with [ResponseTooLargeException] as soon as more than [maxBytes]
 * bytes have arrived. The cap is on bytes on the wire, not characters, and it is enforced while reading, so a
 * server that never stops sending costs at most [maxBytes] of memory.
 */
suspend fun ByteReadChannel.readAllBounded(maxBytes: Int): ByteArray {
    var out = ByteArray(minOf(maxBytes, CHUNK_BYTES).coerceAtLeast(16))
    var size = 0
    val chunk = ByteArray(CHUNK_BYTES)
    while (true) {
        val n = readAvailable(chunk, 0, chunk.size)
        if (n < 0) break
        if (n == 0) continue
        if (size.toLong() + n > maxBytes) throw ResponseTooLargeException(maxBytes.toLong())
        if (size + n > out.size) out = out.copyOf(maxOf(out.size * 2, size + n))
        chunk.copyInto(out, size, 0, n)
        size += n
    }
    return out.copyOf(size)
}

/** The body as UTF-8 text, never more than [maxBytes] bytes of it read; throws [ResponseTooLargeException] past that. */
suspend fun HttpResponse.boundedText(maxBytes: Int): String {
    contentLength()?.let { if (it > maxBytes) throw ResponseTooLargeException(maxBytes.toLong()) }
    return bodyAsChannel().readAllBounded(maxBytes).decodeToString()
}

/** Like [boundedText] for an error body that is only quoted: what fits in [maxBytes] is kept, the rest is ignored. */
suspend fun HttpResponse.boundedTextOrTruncated(maxBytes: Int): String {
    val channel = bodyAsChannel()
    val out = ByteArray(maxBytes)
    var size = 0
    while (size < maxBytes) {
        val n = channel.readAvailable(out, size, maxBytes - size)
        if (n < 0) break
        size += n
    }
    return out.copyOf(size).decodeToString()
}

/**
 * Reads LF or CRLF terminated lines from [channel] while counting bytes: past [maxBytes] in all (a line, or lines
 * together) it throws [ResponseTooLargeException] before the oversized line is assembled. A final line with no
 * terminator is returned; the end of the stream is null.
 */
class BoundedLineReader(private val channel: ByteReadChannel, private val maxBytes: Long) {
    private var buffer = ByteArray(CHUNK_BYTES)
    private var start = 0
    private var end = 0
    private var total = 0L
    private var eof = false
    private val chunk = ByteArray(CHUNK_BYTES)

    suspend fun readLine(): String? {
        var scanned = start
        while (true) {
            var i = scanned
            while (i < end && buffer[i] != NEWLINE) i++
            if (i < end) {
                val lineEnd = if (i > start && buffer[i - 1] == RETURN) i - 1 else i
                val line = buffer.decodeToString(start, lineEnd)
                start = i + 1
                return line
            }
            scanned = end
            if (eof) {
                if (start == end) return null
                val line = buffer.decodeToString(start, end)
                start = end
                return line
            }
            val n = channel.readAvailable(chunk, 0, chunk.size)
            if (n < 0) {
                eof = true
                continue
            }
            if (n == 0) continue
            total += n
            if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
            if (end + n > buffer.size) {
                // Drop what was consumed, then grow if still short.
                if (start > 0) {
                    buffer.copyInto(buffer, 0, start, end)
                    scanned -= start
                    end -= start
                    start = 0
                }
                if (end + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, end + n))
            }
            chunk.copyInto(buffer, end, 0, n)
            end += n
        }
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val RETURN: Byte = '\r'.code.toByte()
    }
}
