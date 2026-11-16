package com.hereliesaz.geministrator.mcp

import com.hereliesaz.geministrator.net.ChildProcessEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A local MCP server over stdio (desktop): the keeper's command started as a fixed argument list,
 * never through a shell, in the user's home folder. One JSON-RPC message per line each way; its
 * stderr is discarded. The process runs with the keeper's own permissions, which is why adding one
 * is the keeper's explicit act and its tools still need consent per run.
 */
class McpStdioTransport private constructor(
    private val process: Process,
) : McpTransport {
    override val kind: McpTransportKind = McpTransportKind.Stdio
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    /** Why the reader has stopped, once it has: set before the waiting calls are failed, so a call that arrives later is refused at once. */
    @Volatile
    private var stopped: McpProtocolException? = null

    /** The newest stderr text (bounded), so an exit or a timeout can say what the server printed. */
    private val stderrTail = StringBuilder()

    init {
        scope.launch { readLoop() }
        scope.launch { drainStderr() }
    }

    private suspend fun drainStderr() {
        try {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(1024)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    synchronized(stderrTail) {
                        stderrTail.append(chunk, 0, count)
                        if (stderrTail.length > MAX_STDERR_CHARS) stderrTail.delete(0, stderrTail.length - MAX_STDERR_CHARS)
                    }
                }
            }
        } catch (_: Exception) {
            // The stream closed with the process.
        }
    }

    /** What the server last wrote to stderr, trimmed, as a suffix for an error message ("" when it wrote nothing). */
    private fun stderrNote(): String {
        val text = synchronized(stderrTail) { stderrTail.toString() }.trim()
        return if (text.isEmpty()) "" else ". Its last output: ${text.takeLast(MAX_STDERR_NOTE_CHARS)}"
    }

    override suspend fun exchange(outgoing: McpOutgoing): McpIncoming {
        val id = McpJsonRpc.idOf(outgoing.message)?.longOrNull
        if (id == null) {
            write(outgoing.message)
            return McpIncoming(null, null)
        }
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            // Checked after the call is registered: either the reader sees it when it fails the waiting calls, or this sees why it stopped.
            stopped?.let { throw it }
            if (!process.isAlive) throw McpProtocolException("The MCP server process has exited${stderrNote()}")
            write(outgoing.message)
            return McpIncoming(null, answer.await())
        } catch (cancelled: CancellationException) {
            // A timeout or a cancelled run: tell the server to stop working on it (MCP notifications/cancelled), so it does
            // not keep going and answer into a request nobody waits for.
            if (!answer.isCompleted && process.isAlive) {
                val cancel = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/cancelled")
                    put("params", buildJsonObject {
                        put("requestId", id)
                        put("reason", "The Aive stopped waiting for this request")
                    })
                }
                scope.launch { runCatching { write(cancel) } }
            }
            throw cancelled
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun write(message: JsonObject) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            // JSON from kotlinx.serialization never contains a raw newline.
            writer.write(message.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    private suspend fun readLoop() {
        var failure = McpProtocolException("The MCP server process stopped")
        try {
            val reader = StdioLineReader(process.inputStream, MAX_LINE_BYTES)
            while (true) {
                val line = reader.readLine() ?: break
                val message = McpJsonRpc.parse(line) ?: continue
                val id = McpJsonRpc.idOf(message)
                when {
                    McpJsonRpc.isRequest(message) -> answerServerRequest(message)
                    id != null -> id.longOrNull?.let { pending[it]?.complete(message) }
                    else -> Unit // notifications about in-flight requests are not used
                }
            }
        } catch (_: StdioLineTooLongException) {
            // The stream cannot be resynchronised after an oversized line: fail the waiting calls and stop the server.
            failure = McpProtocolException("The MCP server sent a message that is too large; it was stopped")
            runCatching { stopProcess() }
        } catch (_: Exception) {
            // The stream closed; pending callers are failed below.
        } finally {
            stopped = if (failure.message == "The MCP server process stopped") {
                McpProtocolException("The MCP server process stopped${stderrNote()}")
            } else {
                failure
            }
            pending.values.forEach { it.completeExceptionally(stopped ?: failure) }
        }
    }

    /** Handshake-era servers may send requests; only ping is answered, the rest declined. */
    private fun answerServerRequest(request: JsonObject) {
        val id = McpJsonRpc.idOf(request) ?: return
        val reply = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (request.mcpString("method") == "ping") {
                put("result", JsonObject(emptyMap()))
            } else {
                put("error", buildJsonObject {
                    put("code", -32601)
                    put("message", "The Aive does not offer this to MCP servers")
                })
            }
        }
        scope.launch { runCatching { write(reply) } }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { writer.close() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) stopProcess()
        }
        scope.cancel()
    }

    /** Asks the server to end, waits a moment, then ends it for good: a server that ignores the request does not keep running. */
    private fun stopProcess() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        private const val MAX_LINE_BYTES = 8_000_000
        private const val MAX_STDERR_CHARS = 4_000
        private const val MAX_STDERR_NOTE_CHARS = 600

        /** Starts [command] directly (no shell); throws when it cannot be started. */
        fun start(
            command: List<String>,
            workingDirectory: File = File(System.getProperty("user.home")),
            inheritedEnvironment: Map<String, String> = System.getenv(),
        ): McpStdioTransport {
            McpCommandLine.requireUsable(command)
            val builder = ProcessBuilder(command)
                .directory(workingDirectory.takeIf(File::isDirectory))
            // The server gets the same allowlisted environment a CLI agent gets, not everything the app was started with
            // (API keys, repository tokens); a server that needs a secret takes it on its own command line or in its own config.
            builder.environment().apply {
                clear()
                putAll(ChildProcessEnvironment.allowed(inheritedEnvironment))
            }
            return McpStdioTransport(builder.start())
        }
    }
}

/** The desktop's [McpStdioLauncher]. */
object DesktopMcpStdioLauncher : McpStdioLauncher {
    override fun launch(command: List<String>): McpTransport = McpStdioTransport.start(command)
}
