package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.providers.LiteRtLmTextGenerator
import com.hereliesaz.geministrator.providers.lfm.InstalledLocalLlm
import com.hereliesaz.geministrator.providers.lfm.LocalLiteRtLmHost
import com.hereliesaz.geministrator.providers.lfm.LocalLiteRtLmRuntime
import kotlinx.coroutines.sync.Mutex

/**
 * The engines behind the installed on-device LFM3 models: one [LiteRtLmTextGenerator] per model file, kept so that every
 * conversation with the same model shares one loaded engine (about the model's size in memory, released after a while idle)
 * and generations take turns on it.
 */
object LocalLfmModels {
    private val generators = HashMap<String, LiteRtLmTextGenerator>()
    private val lock = Mutex()

    /** Common code has no blocking lock; the map is touched for a moment only, so waiting for it by retrying is cheap. */
    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) Unit
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** The text API for [model]; throws a plain message when this device has no LiteRT-LM runtime. */
    fun api(model: InstalledLocalLlm): LfmLocalChatApi {
        val runtime = LocalLiteRtLmHost.runtime ?: error("This device cannot run on-device models: it has no LiteRT-LM runtime")
        val generator = generatorFor(model, runtime)
        return LfmLocalChatApi(model.profile, { prompt, maxNew, context, stops -> generator.generateRaw(prompt, maxNew, context, stops) })
    }

    /** Frees the engine of a model that was removed. */
    suspend fun release(packageId: String) {
        val removed = locked { generators.filterKeys { it.startsWith("$packageId\n") }.also { found -> found.keys.forEach(generators::remove) } }
        removed.values.forEach { it.release() }
    }

    private fun generatorFor(model: InstalledLocalLlm, runtime: LocalLiteRtLmRuntime): LiteRtLmTextGenerator = locked {
        generators.getOrPut(model.packageId + "\n" + model.artifactPath) {
            LiteRtLmTextGenerator(
                modelPath = { model.artifactPath },
                loader = runtime.loader,
                resources = runtime.resources,
                scope = runtime.scope,
                minAvailableMemoryBytes = model.profile.minRamMegabytes * BYTES_PER_MEGABYTE,
            )
        }
    }

    private const val BYTES_PER_MEGABYTE = 1_048_576L
}
