package com.hereliesaz.geministrator.providers

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Receives one completion's text as the engine produces it. Called from any thread, one call at a time. */
interface LiteRtLmCompletionListener {
    fun onText(chunk: String)

    fun onFinished()

    fun onFailed(message: String)
}

/** One running completion. */
interface LiteRtLmCompletion {
    /** Asks the engine to stop; the listener still receives a final event. Safe to call after it finished. */
    fun cancel()

    /** The caller is done with it; frees the native session once the engine has finished with it. */
    fun release()
}

/** A loaded LiteRT-LM engine. */
interface LiteRtLmModel {
    /** Starts a completion of the raw (already chat-templated) [prompt]; the listener gets the text. */
    fun complete(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion

    fun close()
}

/**
 * The platform seam over the native LiteRT-LM library (Android `litertlm-android`, desktop
 * `litertlm-jvm`). Everything that touches the native classes lives behind it, so the rest is tested
 * with a fake.
 */
interface LiteRtLmEngineLoader {
    /** Loads the model file; throws [OnDeviceRuntimeUnavailableException] when the library or the model will not load. */
    suspend fun load(modelPath: String): LiteRtLmModel
}

/**
 * Small text tasks on LiteRT-LM (Google's on-device LLM runtime) over the installed Qwen2.5-0.5B-Instruct.
 *
 * Generation is serialised, cancellable, bounded by [timeoutMillis] and by the answer length, and
 * refuses to load the model when [resources] reports too little free memory. The loaded engine is kept
 * between calls and released after [idleReleaseMillis]. Prompts and answers are never logged or put in
 * an exception message.
 */
class LiteRtLmTextGenerator(
    /** Path of the installed model file, or null when it is not installed or this device cannot run it. */
    private val modelPath: () -> String?,
    private val loader: LiteRtLmEngineLoader,
    private val resources: OnDeviceResourceProbe,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val idleReleaseMillis: Long = DEFAULT_IDLE_RELEASE_MILLIS,
    private val minAvailableMemoryBytes: Long = OnDeviceResourceGate.LITERT_LM_MIN_AVAILABLE_MEMORY_BYTES,
) : OnDeviceTextGenerator {
    private val mutex = Mutex()
    private var model: LiteRtLmModel? = null
    private var loadedPath: String? = null
    private var idleRelease: Job? = null

    /** The model file that failed to load in this process; not tried again until [release]. */
    @Volatile
    private var failedPath: String? = null

    override fun isReady(): Boolean {
        val path = modelPath() ?: return false
        return failedPath != path
    }

    /** Closes the loaded engine and forgets a load failure (after the model is installed or removed). */
    suspend fun release() {
        mutex.withLock {
            idleRelease?.cancel()
            idleRelease = null
            closeModel()
            failedPath = null
        }
    }

    override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration {
        val prompt = QwenChatPrompt.format(system, user)
        val limit = maxNewTokens.coerceIn(1, MAX_NEW_TOKENS_CAP)
        if (OnDeviceTextAgentProvider.estimateTokens(prompt.length) + limit > LITERT_LM_CONTEXT_TOKENS) {
            throw OnDeviceRuntimeUnavailableException("The task is too long for the LiteRT-LM model's context")
        }
        return mutex.withLock {
            idleRelease?.cancel()
            idleRelease = null
            try {
                val path = modelPath() ?: throw OnDeviceRuntimeUnavailableException("No LiteRT-LM model is installed")
                val loaded = ensureLoaded(path)
                OnDeviceTextGeneration(text = sanitizeOnDeviceText(complete(loaded, prompt, limit)))
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    private suspend fun ensureLoaded(path: String): LiteRtLmModel {
        val current = model
        if (current != null && loadedPath == path) return current
        closeModel()
        OnDeviceResourceGate.memoryRefusal(resources.snapshot(), minAvailableMemoryBytes)?.let {
            throw OnDeviceGenerationDeclined(it)
        }
        val loaded = try {
            loader.load(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failedPath = path
            if (failure is OnDeviceRuntimeUnavailableException) throw failure
            throw OnDeviceRuntimeUnavailableException(
                "LiteRT-LM could not load the model: ${failure.message ?: failure::class.simpleName}",
                failure,
            )
        }
        model = loaded
        loadedPath = path
        return loaded
    }

    private class StreamFailure(message: String) : RuntimeException(message)

    private suspend fun complete(loaded: LiteRtLmModel, prompt: String, maxNewTokens: Int): String {
        val events = Channel<String>(Channel.UNLIMITED)
        val call = try {
            loaded.complete(
                prompt,
                object : LiteRtLmCompletionListener {
                    override fun onText(chunk: String) {
                        events.trySend(chunk)
                    }

                    override fun onFinished() {
                        events.close()
                    }

                    override fun onFailed(message: String) {
                        events.close(StreamFailure(message))
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw IllegalStateException("LiteRT-LM could not start a generation: ${failure.message ?: failure::class.simpleName}")
        }
        val text = StringBuilder()
        var pieces = 0
        var stoppedByLimit = false
        try {
            withTimeout(timeoutMillis) {
                for (piece in events) {
                    if (stoppedByLimit) continue
                    text.append(piece)
                    pieces++
                    if (pieces >= maxNewTokens || text.length >= maxNewTokens * MAX_CHARS_PER_TOKEN) {
                        stoppedByLimit = true
                        call.cancel()
                    }
                }
            }
        } catch (failure: StreamFailure) {
            // A stop we asked for ends the stream with an error; the text so far is the answer.
            if (!stoppedByLimit) throw IllegalStateException("LiteRT-LM generation failed: ${failure.message}")
        } catch (timeout: TimeoutCancellationException) {
            call.cancel()
            throw OnDeviceGenerationTimedOut("The on-device model took longer than ${timeoutMillis / 1000} s and was stopped")
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        } finally {
            if (!settle(call, events)) {
                // The engine never confirmed the stop: drop it without closing (a leak beats a native crash).
                model = null
                loadedPath = null
            }
        }
        return text.toString()
    }

    /** Waits briefly for the engine's final event, then frees the session. False when it never came. */
    private suspend fun settle(call: LiteRtLmCompletion, events: Channel<String>): Boolean =
        withContext(NonCancellable) {
            val finished = withTimeoutOrNull(CANCEL_GRACE_MILLIS) {
                try {
                    for (ignored in events) {
                        // Drain.
                    }
                } catch (ignored: StreamFailure) {
                    // The stream's own failure was handled by the caller.
                }
                true
            } ?: false
            if (finished) call.release()
            finished
        }

    private fun scheduleIdleRelease() {
        idleRelease = scope.launch {
            delay(idleReleaseMillis)
            mutex.withLock { closeModel() }
        }
    }

    private fun closeModel() {
        val current = model
        model = null
        loadedPath = null
        if (current != null) runCatching { current.close() }
    }

    companion object {
        /** One generation's time limit, in the spirit of the other local call limits. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 120_000L

        /** How long the loaded engine (about 1.4 GB resident) stays in memory after the last generation. */
        const val DEFAULT_IDLE_RELEASE_MILLIS: Long = 120_000L

        private const val CANCEL_GRACE_MILLIS = 5_000L
        private const val MAX_NEW_TOKENS_CAP = 1_024
        private const val MAX_CHARS_PER_TOKEN = 8
    }
}
