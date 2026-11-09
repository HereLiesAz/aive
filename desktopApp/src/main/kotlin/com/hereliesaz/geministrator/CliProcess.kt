package com.hereliesaz.geministrator

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** What a CLI process did: its exit code, whether it was stopped at the timeout, and the tail of its stderr. */
internal data class CliProcessResult(
    val exitCode: Int,
    val timedOut: Boolean = false,
    val startFailure: String? = null,
    val stderrTail: String = "",
)

/** Runs a CLI and hands its output over a line at a time. A seam so tests need no real CLI. */
internal fun interface CliProcessRunner {
    suspend fun run(
        command: List<String>,
        directory: File,
        environment: Map<String, String>,
        stdin: String?,
        timeoutMillis: Long,
        onLine: suspend (String) -> Unit,
    ): CliProcessResult
}

/**
 * The real runner: an argument list (never a shell string) in [directory] with exactly [environment],
 * the prompt written to stdin and then closed, stdout read line by line (a line longer than
 * [MAX_LINE_CHARS] is cut), stderr kept to its last [MAX_STDERR_CHARS], and the whole process tree ended
 * on timeout or when the caller is cancelled.
 */
internal object ProcessCliRunner : CliProcessRunner {
    const val MAX_LINE_CHARS = 64_000
    const val MAX_STDERR_CHARS = 8_000

    /** Lines read from stdout and not yet handed on before the reader waits for the consumer. */
    const val LINE_BACKLOG = 256

    private class StopReading : RuntimeException("The output is no longer wanted")

    override suspend fun run(
        command: List<String>,
        directory: File,
        environment: Map<String, String>,
        stdin: String?,
        timeoutMillis: Long,
        onLine: suspend (String) -> Unit,
    ): CliProcessResult {
        val started = AtomicReference<Process?>()
        val process = try {
            withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(command).directory(directory)
                builder.environment().clear()
                builder.environment().putAll(environment)
                builder.start().also { started.set(it) }
            }
        } catch (failure: CancellationException) {
            started.get()?.let(::destroyTree)
            throw failure
        } catch (failure: Exception) {
            return CliProcessResult(-1, startFailure = failure.message ?: "Unable to start ${command.first()}")
        }
        val stderr = StringBuilder()
        val errThread = Thread({
            runCatching {
                process.errorStream.bufferedReader().use { reader ->
                    val chunk = CharArray(4_096)
                    while (true) {
                        val read = reader.read(chunk)
                        if (read < 0) break
                        synchronized(stderr) {
                            stderr.append(chunk, 0, read)
                            if (stderr.length > MAX_STDERR_CHARS * 2) stderr.delete(0, stderr.length - MAX_STDERR_CHARS)
                        }
                    }
                }
            }
        }, "aive-cli-stderr").apply { isDaemon = true; start() }
        // Bounded: a consumer slower than the CLI holds the reader, the pipe then fills and the CLI waits, instead of
        // every line (up to MAX_LINE_CHARS each) piling up in memory without limit.
        val lines = Channel<String>(LINE_BACKLOG)
        val outThread = Thread({
            runCatching {
                readLines(process.inputStream) { line ->
                    // A channel closed under the reader (the run ended) stops the reading.
                    if (lines.trySendBlocking(line).isClosed) throw StopReading()
                }
            }
            lines.close()
        }, "aive-cli-stdout").apply { isDaemon = true; start() }
        val inThread = Thread({
            runCatching {
                process.outputStream.use { out -> if (stdin != null) out.write(stdin.toByteArray(Charsets.UTF_8)) }
            }
        }, "aive-cli-stdin").apply { isDaemon = true; start() }
        var timedOut = false
        try {
            coroutineScope {
                val waiter = launch {
                    val finished = runInterruptible(Dispatchers.IO) { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
                    if (!finished) {
                        timedOut = true
                        destroyTree(process)
                    }
                }
                for (line in lines) onLine(line)
                waiter.join()
            }
        } catch (failure: CancellationException) {
            destroyTree(process)
            throw failure
        } finally {
            if (process.isAlive) destroyTree(process)
            outThread.join(TimeUnit.SECONDS.toMillis(5))
            errThread.join(TimeUnit.SECONDS.toMillis(5))
            inThread.join(TimeUnit.SECONDS.toMillis(1))
        }
        return CliProcessResult(
            exitCode = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1),
            timedOut = timedOut,
            stderrTail = synchronized(stderr) { stderr.takeLast(MAX_STDERR_CHARS).toString() },
        )
    }

    private fun readLines(stream: InputStream, emit: (String) -> Unit) {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val line = StringBuilder()
            var dropped = false
            while (true) {
                val c = reader.read()
                if (c < 0) break
                if (c == '\n'.code) {
                    emit(line.toString())
                    line.setLength(0)
                    dropped = false
                } else if (c != '\r'.code) {
                    if (line.length < MAX_LINE_CHARS) line.append(c.toChar()) else dropped = true
                }
            }
            if (line.isNotEmpty() || dropped) emit(line.toString())
        }
    }

    private fun destroyTree(process: Process) {
        runCatching { process.toHandle().descendants().forEach { child -> runCatching { child.destroyForcibly() } } }
        runCatching { process.destroyForcibly() }
    }
}
