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
            DesktopLiteRtLmEngineModel(engine)
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

private class DesktopLiteRtLmEngineModel(private val engine: Engine) : LiteRtLmModel {
    override fun complete(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
        // Greedy decoding (top-k 1), like the ONNX generators.
        val session = engine.createSession(SessionConfig(SamplerConfig(1, 1.0, 1.0, 0)))
        val completion = DesktopLiteRtLmSessionCompletion(session)
        try {
            session.generateContentStream(
                listOf(InputData.Text(prompt)),
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

    override fun close() {
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
