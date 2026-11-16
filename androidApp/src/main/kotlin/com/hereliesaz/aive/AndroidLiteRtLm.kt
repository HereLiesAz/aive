package com.hereliesaz.aive

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The LiteRT-LM model file under files/aive/litertlm. The download is pinned by the catalog's SHA-256
 * and size; a `.sha256` marker beside the file records the digest it was verified against.
 */
internal class AndroidLiteRtLmModelInstaller(
    context: Context,
    httpClient: HttpClient,
    val artifact: LiteRtLmModelArtifact,
) {
    private val root = File(context.filesDir, "aive/litertlm")
    private val downloader = AndroidResumableFileDownloader(httpClient)
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
        root.mkdirs()
        // Partials stay beside the file, so a retry resumes.
        downloader.downloadVerified(artifact.downloadUrl, file(), artifact.sha256, artifact.sizeBytes)
        marker().writeText(artifact.sha256 + "\n")
        modelFile() ?: error("${artifact.fileName} did not install")
    }

    suspend fun remove() = mutex.withLock {
        withContext(Dispatchers.IO) {
            marker().delete()
            file().delete()
            File(root, artifact.fileName + ".download").delete()
        }
    }
}

/** Free memory from the system and the phone's thermal state (the same reading the relay uses). */
internal class AndroidOnDeviceResourceProbe(private val context: Context) : OnDeviceResourceProbe {
    override fun snapshot(): OnDeviceResourceSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null
        val hot = thermalHoldReason(thermalStatus, battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE))
        return OnDeviceResourceSnapshot(
            availableMemoryBytes = info.availMem,
            memoryLow = info.lowMemory,
            thermalHot = hot != null,
        )
    }
}

/** True where the LiteRT-LM AAR ships a native library (arm64-v8a and x86_64 only). */
internal fun liteRtLmAbiSupported(): Boolean =
    Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

/** The only code that touches the LiteRT-LM Android classes. CPU backend. */
internal class AndroidLiteRtLmEngineLoader(private val cacheDirectory: File) : LiteRtLmEngineLoader {
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
            LiteRtLmEngineModel(engine)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Includes UnsatisfiedLinkError (no native library for this ABI) and a model the engine rejects.
            throw OnDeviceRuntimeUnavailableException(
                "LiteRT-LM could not load the model: ${failure.message ?: failure::class.simpleName}",
                failure,
            )
        }
    }
}

private class LiteRtLmEngineModel(private val engine: Engine) : LiteRtLmModel {
    override fun complete(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
        // Greedy decoding (top-k 1), like the ONNX generators.
        val session = engine.createSession(SessionConfig(SamplerConfig(1, 1.0, 1.0, 0)))
        val completion = LiteRtLmSessionCompletion(session)
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
private class LiteRtLmSessionCompletion(private val session: Session) : LiteRtLmCompletion {
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
