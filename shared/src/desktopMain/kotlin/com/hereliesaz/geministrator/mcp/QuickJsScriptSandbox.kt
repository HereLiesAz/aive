package com.hereliesaz.geministrator.mcp

import io.roastedroot.quickjs4j.core.Builtins
import io.roastedroot.quickjs4j.core.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import run.endive.runtime.ByteArrayMemory
import run.endive.wasm.types.MemoryLimits
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The desktop script sandbox (docs/architecture/MCP_HOST.md, "Code execution"): QuickJS compiled
 * to WebAssembly (quickjs4j), run by Endive, the pure-JVM WebAssembly runtime formerly called
 * Chicory. No native code and no JNI.
 *
 * The guest gets WASI with stdout and stderr only (into a bounded buffer): no preopened
 * directories, no environment, no arguments, and WASI preview 1 has no sockets. Its one host
 * module with an effect is `aive`: `tool_names()`, `call(request)` (the [ScriptBridge]) and
 * `finish(value)`.
 *
 * Limits: linear memory is capped through the memory factory; guest time (excluding time waiting
 * in the bridge) and total time are watched, and the guest thread is interrupted when either
 * runs out — Endive's compiled code checks for interruption. Cancelling the caller interrupts it
 * too. Instruction metering (fuel) is not offered by Endive's compiled mode, so guest time stands
 * in for it.
 */
class QuickJsScriptSandbox : ScriptSandboxRuntime {
    override val description: String = "QuickJS (WebAssembly) on Endive"

    override suspend fun run(script: String, limits: ScriptLimits, bridge: ScriptBridge): ScriptRunResult {
        val clock = GuestClock()
        val stopped = AtomicReference<String?>(null)
        val done = CompletableDeferred<ScriptRunResult>()
        val guest = Thread({
            val result = try {
                execute(script, limits, bridge, clock)
            } catch (failure: Throwable) {
                ScriptRunResult.Failed(failure.message ?: failure::class.java.simpleName)
            }
            done.complete(result)
        }, "aive-script-sandbox").apply { isDaemon = true }
        clock.start()
        guest.start()
        try {
            while (!done.isCompleted) {
                val reason = when {
                    clock.guestMillis() > limits.guestTimeMillis -> "it used more than ${limits.guestTimeMillis} ms of computing time"
                    clock.totalMillis() > limits.totalTimeMillis -> "it ran longer than ${limits.totalTimeMillis / 1000} s in all"
                    else -> null
                }
                if (reason != null) {
                    stopped.compareAndSet(null, reason)
                    guest.interrupt()
                    // The guest checks for interruption; if it does not end soon, give up on it.
                    val ended = withTimeoutOrNull(STOP_GRACE_MILLIS) { done.await() }
                    if (ended == null) return ScriptRunResult.Failed(reason, limitExceeded = true)
                    break
                }
                withTimeoutOrNull(WATCH_INTERVAL_MILLIS) { done.await() }
            }
        } catch (cancelled: CancellationException) {
            guest.interrupt()
            throw cancelled
        }
        val result = done.await()
        val stopReason = stopped.get()
        return if (stopReason != null) {
            ScriptRunResult.Failed(stopReason, limitExceeded = true, log = (result as? ScriptRunResult.Failed)?.log.orEmpty())
        } else {
            result
        }
    }

    private fun execute(script: String, limits: ScriptLimits, bridge: ScriptBridge, clock: GuestClock): ScriptRunResult {
        val log = BoundedOutput(limits.maxLogChars)
        val finished = AtomicReference<String?>(null)
        val builtins = Builtins.builder(MODULE)
            .addVoidToString("tool_names") { Json.encodeToString(ListSerializer(String.serializer()), bridge.toolNames) }
            .addStringToString("call") { request -> clock.inBridge { runBlocking { bridgeCall(bridge, request) } } }
            .addStringToVoid("finish") { value -> finished.compareAndSet(null, value) }
            .build()
        val capPages = (limits.memoryBytes / WASM_PAGE_BYTES).toInt().coerceAtLeast(1)
        val failure = try {
            Engine.builder()
                .addBuiltins(builtins)
                .withMemoryFactory { requested ->
                    val maximum = minOf(requested.maximumPages(), capPages).coerceAtLeast(requested.initialPages())
                    ByteArrayMemory(MemoryLimits(requested.initialPages(), maximum))
                }
                .withStdout(log)
                .withStderr(log)
                .build()
                .use { engine ->
                    val compiledAt = engine.compile(wrap(script))
                    val compiled = try {
                        engine.readCompiled(compiledAt)
                    } finally {
                        engine.free(compiledAt)
                    }
                    val loadedAt = engine.writeCompiled(compiled)
                    try {
                        engine.exec(loadedAt)
                    } finally {
                        engine.free(loadedAt)
                    }
                }
            null
        } catch (error: Throwable) {
            error
        }
        val value = finished.get()
        return when {
            value != null && value.length > MAX_FINISH_CHARS ->
                ScriptRunResult.Failed("its result was larger than $MAX_FINISH_CHARS characters", limitExceeded = true)
            failure == null && value != null -> ScriptRunResult.Completed(value)
            failure == null -> ScriptRunResult.Failed("the script ended without returning a value", log = log.text())
            else -> {
                val message = failure.message ?: failure::class.java.simpleName
                ScriptRunResult.Failed(
                    reason = message,
                    limitExceeded = message.contains("out of memory", ignoreCase = true),
                    log = log.text(),
                )
            }
        }
    }

    /** Guest time is wall time minus time spent waiting in the bridge. */
    private class GuestClock {
        private var startedAt = 0L
        private val bridgeNanos = AtomicLong(0)
        private val bridgeSince = AtomicLong(0)

        fun start() {
            startedAt = System.nanoTime()
        }

        fun <T> inBridge(block: () -> T): T {
            val since = System.nanoTime()
            bridgeSince.set(since)
            try {
                return block()
            } finally {
                bridgeNanos.addAndGet(System.nanoTime() - since)
                bridgeSince.set(0)
            }
        }

        fun totalMillis(): Long = (System.nanoTime() - startedAt) / 1_000_000

        fun guestMillis(): Long {
            val now = System.nanoTime()
            val waiting = bridgeSince.get().let { if (it == 0L) 0L else now - it }
            return (now - startedAt - bridgeNanos.get() - waiting) / 1_000_000
        }
    }

    /** Keeps the first [maxChars] characters' worth of console output and drops the rest. */
    private class BoundedOutput(maxChars: Int) : ByteArrayOutputStream() {
        private val maxBytes = maxChars.coerceAtLeast(0) * 4

        @Synchronized
        override fun write(b: Int) {
            if (count < maxBytes) super.write(b)
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = maxBytes - count
            if (room > 0) super.write(b, off, minOf(len, room))
        }

        @Synchronized
        fun text(): String = toString(Charsets.UTF_8)
    }

    companion object {
        private const val MODULE = "aive"
        private const val WASM_PAGE_BYTES = 65_536L
        private const val WATCH_INTERVAL_MILLIS = 25L
        private const val STOP_GRACE_MILLIS = 2_000L
        /** Far above any result the model is shown; bounds what is parsed afterwards. */
        private const val MAX_FINISH_CHARS = 1_000_000

        private suspend fun bridgeCall(bridge: ScriptBridge, request: String): String {
            val parsed = runCatching { Json.parseToJsonElement(request) as? JsonObject }.getOrNull()
            val name = (parsed?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val reply = if (parsed == null || name == null) {
                ScriptBridgeReply("Not run: malformed tool call.", isError = true)
            } else {
                bridge.call(name, parsed["arguments"]?.toString() ?: "{}")
            }
            return buildJsonObject { put(if (reply.isError) "error" else "text", reply.text) }.toString()
        }

        /**
         * The model's script is the body of a function given `tools`; its return value goes to
         * `aive.finish`. Escaping the wrapper changes nothing: the whole program is inside the guest.
         */
        internal fun wrap(script: String): String = buildString {
            append(
                """
                const tools = Object.create(null);
                for (const name of JSON.parse($MODULE.tool_names())) {
                  tools[name] = function (args) {
                    const reply = JSON.parse($MODULE.call(JSON.stringify({ name: name, arguments: args === undefined ? {} : args })));
                    if (reply.error !== undefined) throw new Error(reply.error);
                    return reply.text;
                  };
                }
                Object.freeze(tools);
                const __aiveValue = (function (tools) {
                """.trimIndent(),
            )
            append('\n').append(script).append('\n')
            append(
                """
                })(tools);
                if (__aiveValue !== null && typeof __aiveValue === "object" && typeof __aiveValue.then === "function") {
                  throw new Error("Return a value, not a promise: tool functions are synchronous, so do not use async or await.");
                }
                const __aiveJson = JSON.stringify(__aiveValue === undefined ? null : __aiveValue);
                $MODULE.finish(__aiveJson === undefined ? "null" : __aiveJson);
                """.trimIndent(),
            )
        }
    }
}
