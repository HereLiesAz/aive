package com.hereliesaz.geministrator

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/*
 * Runs an external program with bounded output and a timeout (desktop). It lives in `shared` so the desktop-side runners in this
 * module and in `desktopApp` (the test runner, the CLI agent provider) use the one implementation.
 */

/** The last characters a stream produced, and how many it produced in all. */
class BoundedOutput(val text: String, val totalChars: Long) {
    val truncated: Boolean get() = totalChars > text.length
}

data class BoundedProcessResult(
    val exitCode: Int,
    val stdout: BoundedOutput,
    val stderr: BoundedOutput,
    val timedOut: Boolean,
    val startFailure: String? = null,
)

/**
 * Runs [command] (an argument list, never a shell string) in [directory]. [environment] replaces
 * the inherited environment; null inherits it with Git's variables cleared. Both streams are
 * drained on their own threads and keep only their last [maxChars] characters. On timeout or
 * cancellation the whole process tree is ended.
 */
suspend fun runBoundedProcess(
    command: List<String>,
    directory: File,
    environment: Map<String, String>?,
    timeoutMillis: Long,
    maxChars: Int,
): BoundedProcessResult {
    // Held outside the start's suspension: a cancellation as it resumes would otherwise drop a
    // process that is already running, and leave it unsupervised.
    val started = AtomicReference<Process?>()
    val process = try {
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(command).directory(directory)
            if (environment == null) {
                sanitizeGitEnvironment(builder)
            } else {
                builder.environment().clear()
                builder.environment().putAll(environment)
            }
            builder.start().also {
                started.set(it)
                runCatching { it.outputStream.close() }
            }
        }
    } catch (failure: CancellationException) {
        started.get()?.let(::destroyProcessTree)
        throw failure
    } catch (failure: Exception) {
        val empty = BoundedOutput("", 0)
        return BoundedProcessResult(-1, empty, empty, timedOut = false, startFailure = failure.message ?: "Unable to start ${command.first()}")
    }
    val out = BoundedDrain(process.inputStream, maxChars, "aive-test-stdout")
    val err = BoundedDrain(process.errorStream, maxChars, "aive-test-stderr")
    val finished = try {
        runInterruptible(Dispatchers.IO) { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
    } catch (failure: CancellationException) {
        destroyProcessTree(process)
        throw failure
    }
    if (!finished) destroyProcessTree(process)
    return BoundedProcessResult(
        exitCode = if (finished) process.exitValue() else -1,
        stdout = out.await(),
        stderr = err.await(),
        timedOut = !finished,
    )
}

private fun destroyProcessTree(process: Process) {
    runCatching { process.toHandle().descendants().forEach { child -> runCatching { child.destroyForcibly() } } }
    runCatching { process.destroyForcibly() }
}

/** Reads a stream to its end on its own thread, keeping only the last [maxChars] characters. */
private class BoundedDrain(stream: InputStream, private val maxChars: Int, name: String) {
    private val buffer = StringBuilder()
    private var total = 0L
    private val thread = Thread({
        runCatching {
            stream.bufferedReader().use { reader ->
                val chunk = CharArray(8_192)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(buffer) {
                        buffer.append(chunk, 0, read)
                        total += read
                        if (buffer.length.toLong() > maxChars.toLong() * 2) buffer.delete(0, buffer.length - maxChars)
                    }
                }
            }
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    /** What the stream produced; waits briefly for the reader (a surviving grandchild may hold the pipe). */
    fun await(): BoundedOutput {
        thread.join(TimeUnit.SECONDS.toMillis(10))
        return synchronized(buffer) {
            val text = if (buffer.length > maxChars) buffer.substring(buffer.length - maxChars) else buffer.toString()
            BoundedOutput(text, total)
        }
    }
}
