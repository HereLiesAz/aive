package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import com.hereliesaz.geministrator.orchestration.AppIntegrationInstallState
import com.hereliesaz.geministrator.orchestration.AppIntegrationRelease
import kotlin.concurrent.Volatile

/** A generator that can say, in one plain line, whether it ran with the app-integration adapter or why not. */
interface OnDeviceAdapterNoteSource {
    /** The line for the status under This device, or null when there is nothing to say (no adapter released or installed). */
    fun adapterNote(): String?
}

/**
 * Chooses, for the ONNX small-task generators, between the released app-integration adapter's real LoRA
 * tensors and zero tensors (the plain base, today's behaviour). Platform-neutral: [F] is the platform's file
 * type and [A] its loaded adapter (the orchestration executors' `AndroidLoraAdapter` / `DesktopLoraAdapter`),
 * reached through [load] and [trainedFor], so there is one adapter loader and this holds only the decision.
 *
 * The adapter is used only when it and the shared base it names are both installed and verified, the file
 * reads, and the file says it was trained for that base. Anything else gives [Selection.Plain]: a missing
 * adapter is silent (nothing was asked for), the rest carry a plain reason. A rejected adapter is remembered
 * by its SHA-256, so it is not parsed again on every call; a new release has a new SHA-256.
 *
 * Calls must be serialised by the caller (the generators hold their call lock); the loaded adapter's tensors
 * are owned here, so a caller must not close them.
 */
class AppIntegrationAdapterSelector<F : Any, A : AutoCloseable>(
    private val release: () -> AppIntegrationRelease?,
    private val runtime: LocalModelRuntimeCapabilities,
    /** The installed, verified adapter file for the descriptor, or null. */
    private val installedAdapter: (LocalModelArtifactDescriptor) -> F?,
    /** The installed, verified shared base (its directory) for the descriptor, or null. */
    private val installedBase: (LocalModelArtifactDescriptor) -> F?,
    /** Reads the adapter file; may throw. */
    private val load: (F) -> A,
    /** The base id the adapter file says it was trained for, or null when it does not say. */
    private val trainedFor: (A) -> String?,
) : OnDeviceAdapterNoteSource, AutoCloseable {
    sealed interface Selection<out F, out A> {
        /** Run on the plain base with zero adapter tensors. [note] says why an installed adapter was not used. */
        class Plain(val note: String?) : Selection<Nothing, Nothing>

        /** Run on [baseRoot] with [adapter]'s tensors. */
        class Adapter<F, A>(
            val plan: LocalModelLoadPlan.SharedBaseAdapter,
            val baseRoot: F,
            val adapter: A,
        ) : Selection<F, A> {
            /** The prefix-cache adapter id for a run on [modelPath]: the adapter's id and SHA-256, so a new release re-primes. */
            fun cacheKey(modelPath: String): String = AppIntegrationAdapterSelector.cacheKey(plan.adapter, modelPath)
        }
    }

    private class Loaded<A>(val sha256: String, val adapter: A)

    // Written by the generation thread, read by the status line: whole values, replaced rather than edited.
    @Volatile
    private var loaded: Loaded<A>? = null

    @Volatile
    private var rejected: Map<String, String> = emptyMap()

    /**
     * What the status line says now, from the installed files and what this has learned: null while nothing is
     * released or installed (nothing to explain), otherwise one plain line.
     */
    override fun adapterNote(): String? {
        val released = release() ?: return null
        val plan = released.plan(runtime) ?: return NOT_FIT
        if (installedAdapter(plan.adapter) == null) return null
        if (installedBase(plan.base) == null) return BASE_MISSING
        rejected[plan.adapter.sha256]?.let { return it }
        return if (loaded?.sha256 == plan.adapter.sha256) IN_USE else INSTALLED
    }

    /** Whether an adapter and its base are installed, without reading the adapter (cheap; for routing decisions). */
    fun available(): Boolean = AppIntegrationInstallState.of(release(), runtime) { artifact ->
        if (artifact.kind == LocalModelArtifactKind.Adapter) {
            installedAdapter(artifact) != null
        } else {
            installedBase(artifact) != null
        }
    } == AppIntegrationInstallState.Installed

    fun select(): Selection<F, A> {
        val released = release() ?: return Selection.Plain(null)
        val plan = released.plan(runtime) ?: return Selection.Plain(NOT_FIT)
        val adapterFile = installedAdapter(plan.adapter)
        if (adapterFile == null) {
            dropLoaded()
            rejected = emptyMap()
            return Selection.Plain(null)
        }
        val baseFile = installedBase(plan.base) ?: return Selection.Plain(BASE_MISSING)
        val sha = plan.adapter.sha256
        rejected[sha]?.let { return Selection.Plain(it) }
        val current = loaded?.takeIf { it.sha256 == sha }?.adapter ?: run {
            dropLoaded()
            try {
                load(adapterFile).also { loaded = Loaded(sha, it) }
            } catch (failure: Exception) {
                return reject(
                    sha,
                    "The app-integration adapter could not be read (${failure.message ?: failure::class.simpleName}), " +
                        "so small tasks run on the plain model.",
                )
            }
        }
        val expected = trainedFor(current)
        if (expected != null && expected != plan.base.logicalArtifactId) {
            return reject(
                sha,
                "The app-integration adapter was trained for $expected, not ${plan.base.logicalArtifactId}, " +
                    "so small tasks run on the plain model.",
            )
        }
        return Selection.Adapter(plan, baseFile, current)
    }

    /** The caller found the adapter unusable on the loaded base (for example it lacks inputs the graph needs). */
    fun reject(adapter: LocalModelArtifactDescriptor, reason: String) {
        reject(adapter.sha256, reason)
    }

    private fun reject(sha: String, reason: String): Selection<F, A> {
        rejected = rejected + (sha to reason)
        dropLoaded()
        return Selection.Plain(reason)
    }

    private fun dropLoaded() {
        val current = loaded
        loaded = null
        if (current != null) runCatching { current.adapter.close() }
    }

    /** Frees the loaded adapter and forgets rejections (the files were removed or reinstalled). */
    override fun close() {
        dropLoaded()
        rejected = emptyMap()
    }

    companion object {
        private const val NOT_FIT = "The app-integration adapter does not fit this runtime, so small tasks run on the plain model."
        private const val BASE_MISSING =
            "The app-integration adapter is installed but its base model is not, so small tasks run on the plain model."
        private const val IN_USE = "Using the app-integration adapter."
        private const val INSTALLED = "The app-integration adapter is installed and is used from the next task."

        /** Adapter id plus SHA-256 plus the model file the run used; changes whenever a different adapter is released. */
        fun cacheKey(adapter: LocalModelArtifactDescriptor, modelPath: String): String =
            "${adapter.adapterId}@${adapter.sha256}|$modelPath"
    }
}
