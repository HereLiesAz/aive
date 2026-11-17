package com.hereliesaz.geministrator

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import com.hereliesaz.geministrator.inference.LiteRtLmModelArtifact
import com.hereliesaz.geministrator.providers.LiteRtLmCompletion
import com.hereliesaz.geministrator.providers.LiteRtLmCompletionListener
import com.hereliesaz.geministrator.providers.LiteRtLmEngineLoader
import com.hereliesaz.geministrator.providers.LiteRtLmModel
import com.hereliesaz.geministrator.providers.LiteRtLmPrefixBackend
import com.hereliesaz.geministrator.providers.LiteRtLmPrefixReuse
import com.hereliesaz.geministrator.providers.PromptPrefixStats
import com.hereliesaz.geministrator.providers.PromptPrefixSwitch
import com.hereliesaz.geministrator.providers.LiteRtLmPrimedPrefix
import com.hereliesaz.geministrator.providers.OnDeviceResourceProbe
import com.hereliesaz.geministrator.providers.OnDeviceResourceSnapshot
import com.hereliesaz.geministrator.providers.OnDeviceRuntimeUnavailableException
import io.ktor.client.HttpClient
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The LiteRT-LM model file under ~/.aive/models/litertlm, pinned by the catalog's SHA-256 and size.
 * A `.sha256` marker beside the file records the digest it was verified against.
 */
internal class DesktopLiteRtLmModelInstaller(
    httpClient: HttpClient,
    val artifact: LiteRtLmModelArtifact,
    private val root: File = File(System.getProperty("user.home"), ".aive/models/litertlm"),
) {
    private val downloader = DesktopResumableFileDownloader(httpClient)
    private val mutex = Mutex()

    private fun file() = File(root, artifact.fileName)

    private fun marker() = File(root, artifact.fileName + ".sha256")

    /** The verified model file, or null when it is not installed. */
    fun modelFile(): File? {
        val file = file()
        val marker = marker()
        return file.takeIf {
            it.isFile && it.length() == artifact.sizeBytes && marker.isFile && marker.readText().trim() == artifact.sha256
        }
    }

    suspend fun install(): File = mutex.withLock {
        modelFile()?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            root.mkdirs()
            // Partials stay beside the file, so a retry resumes.
            downloader.downloadVerified(
                url = artifact.downloadUrl,
                output = file(),
                expectedSha256 = artifact.sha256,
                expectedSize = artifact.sizeBytes,
            )
            marker().writeText(artifact.sha256 + "\n")
            modelFile() ?: error("${artifact.fileName} did not install")
        }
    }

    suspend fun remove() = mutex.withLock {
        withContext(Dispatchers.IO) {
            marker().delete()
            file().delete()
            File(root, artifact.fileName + ".download").delete()
        }
    }
}

/** MemAvailable from /proc/meminfo text, in bytes; null when absent. */
internal fun parseMemAvailableBytes(meminfo: String): Long? =
    meminfo.lineSequence()
        .firstOrNull { it.startsWith("MemAvailable:") }
        ?.let { Regex("(\\d+)").find(it)?.value?.toLongOrNull() }
        ?.let { it * 1024L }

/** Free memory (Linux MemAvailable, else the JVM's view of free physical memory). No thermal signal exists here. */
internal class DesktopOnDeviceResourceProbe : OnDeviceResourceProbe {
    override fun snapshot(): OnDeviceResourceSnapshot {
        val fromProc = runCatching {
            File("/proc/meminfo").takeIf(File::isFile)?.readText()?.let(::parseMemAvailableBytes)
        }.getOrNull()
        val available = fromProc ?: runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                ?.freeMemorySize
        }.getOrNull()
        return OnDeviceResourceSnapshot(availableMemoryBytes = available)
    }
}

/**
 * The only code that touches the LiteRT-LM JVM classes. CPU backend. The `litertlm-jvm` jar carries
 * natives for Windows x64, macOS arm64, Linux x64 and Linux arm64; the library extracts the one for
 * this machine to a temporary directory when it first loads. Any other platform fails to load, which
 * [OnDeviceRuntimeUnavailableException] turns into a fall back to the ONNX runtime.
 */
internal class DesktopLiteRtLmEngineLoader(private val cacheDirectory: File) : LiteRtLmEngineLoader {
    /** Shared by every engine this loader builds, so a failing prefix cache is given up once per process. */
    private val prefixSwitch = PromptPrefixSwitch()

    override suspend fun load(modelPath: String): LiteRtLmModel = withContext(Dispatchers.IO) {
        try {
            cacheDirectory.mkdirs()
            val engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDirectory.absolutePath,
                ),
            )
            try {
                engine.initialize()
            } catch (failure: Throwable) {
                runCatching { engine.close() }
                throw failure
            }
            DesktopLiteRtLmEngineModel(engine, prefixSwitch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw OnDeviceRuntimeUnavailableException(
                "LiteRT-LM could not load the model: ${failure.message ?: failure::class.simpleName}",
                failure,
            )
        }
    }
}

private class DesktopLiteRtLmEngineModel(
    private val engine: Engine,
    prefixSwitch: PromptPrefixSwitch,
) : LiteRtLmModel {
    // Greedy decoding (top-k 1), like the ONNX generators.
    private fun sessionConfig() = SessionConfig(SamplerConfig(1, 1.0, 1.0, 0))

    /** Runs [input] on [session] and hands the session to a completion that frees it; closes it if the stream cannot start. */
    private fun stream(session: Session, input: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
        val completion = DesktopLiteRtLmSessionCompletion(session)
        try {
            session.generateContentStream(
                listOf(InputData.Text(input)),
                object : ResponseCallback {
                    override fun onNext(response: String) {
                        listener.onText(response)
                    }

                    override fun onDone() {
                        completion.markFinished()
                        listener.onFinished()
                    }

                    override fun onError(throwable: Throwable) {
                        completion.markFinished()
                        listener.onFailed(throwable.message ?: throwable::class.simpleName ?: "error")
                    }
                },
            )
        } catch (failure: Throwable) {
            runCatching { session.close() }
            throw failure
        }
        return completion
    }

    /**
     * One primed base session per distinct (adapter, head). Each call clones it (the clone shares the
     * base's key/value state copy-on-write) and streams only the tail on the clone, so the completion
     * frees the clone and never the base. The base is closed with the model.
     */
    private val prefixReuse = LiteRtLmPrefixReuse(
        backend = object : LiteRtLmPrefixBackend {
            override fun prime(head: String): LiteRtLmPrimedPrefix {
                val base = engine.createSession(sessionConfig())
                try {
                    base.runPrefill(listOf(InputData.Text(head)))
                } catch (failure: Throwable) {
                    runCatching { base.close() }
                    throw failure
                }
                return object : LiteRtLmPrimedPrefix {
                    override fun start(tail: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion =
                        stream(base.clone(), tail, listener)

                    override fun close() {
                        runCatching { base.close() }
                    }
                }
            }

            override fun completeWhole(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion =
                stream(engine.createSession(sessionConfig()), prompt, listener)
        },
        switch = prefixSwitch,
    )

    override fun complete(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion =
        stream(engine.createSession(sessionConfig()), prompt, listener)

    override fun complete(prefix: String, rest: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion =
        prefixReuse.complete(prefix, rest, listener)

    override fun prefixStats(): PromptPrefixStats = prefixReuse.stats()

    override fun close() {
        prefixReuse.close()
        engine.close()
    }
}

/** Closes the native session off the engine's callback thread, and only after the engine has finished with it. */
private class DesktopLiteRtLmSessionCompletion(private val session: Session) : LiteRtLmCompletion {
    private val finished = AtomicBoolean(false)
    private val releaseRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun cancel() {
        runCatching { session.cancelProcess() }
    }

    fun markFinished() {
        finished.set(true)
        if (releaseRequested.get()) closeAsync()
    }

    override fun release() {
        releaseRequested.set(true)
        if (finished.get()) closeAsync()
    }

    private fun closeAsync() {
        if (closed.compareAndSet(false, true)) {
            thread(name = "litertlm-session-close", isDaemon = true) { runCatching { session.close() } }
        }
    }
}
