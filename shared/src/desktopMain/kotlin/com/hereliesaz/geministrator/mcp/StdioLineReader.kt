package com.hereliesaz.geministrator.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A line longer than the cap; the stream cannot be trusted past it. */
class StdioLineTooLongException(val maxLineBytes: Int) :
    RuntimeException("The MCP server sent a line longer than $maxLineBytes bytes")

/**
 * Reads newline-terminated lines from a child process's stdout without ever holding more than
 * [maxLineBytes] of one line (a plain buffered reader grows without limit until a newline arrives).
 * Throws [StdioLineTooLongException] the moment a line passes the cap.
 */
class StdioLineReader(input: InputStream, private val maxLineBytes: Int) {
    private val input = BufferedInputStream(input)
    private val line = ByteArrayOutputStream()

    /** The next line without its terminator, or null at end of stream. */
    fun readLine(): String? {
        line.reset()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (sawAny) finish() else null
            sawAny = true
            if (b == '\n'.code) return finish()
            if (line.size() >= maxLineBytes) throw StdioLineTooLongException(maxLineBytes)
            line.write(b)
        }
    }

    private fun finish(): String {
        val text = line.toString(Charsets.UTF_8.name())
        return text.removeSuffix("\r")
    }
}
