package com.hereliesaz.geministrator.inference

/** Kind of release artifact managed by the reusable local-model library. */
enum class LocalModelArtifactKind {
    SharedBase,
    MergedModel,
    Adapter,
    Standalone,
}

/**
 * Cryptographically identified local model artifact published as a release asset.
 *
 * [logicalArtifactId] is the stable runtime identity. [foundationModelId] names the compatible
 * upstream/base family, while [adapterId] versions an adapter independently from its display role.
 */
data class LocalModelArtifactDescriptor(
    val logicalArtifactId: String,
    val foundationModelId: String,
    val releaseRepository: String,
    val releaseTag: String,
    val assetName: String,
    val sha256: String,
    val format: String,
    val precision: String? = null,
    val kind: LocalModelArtifactKind,
    val adapterId: String? = null,
    val capabilities: Set<String> = emptySet(),
) {
    init {
        require(logicalArtifactId.isNotBlank()) { "logicalArtifactId must not be blank" }
        require(foundationModelId.isNotBlank()) { "foundationModelId must not be blank" }
        require(releaseRepository.isNotBlank()) { "releaseRepository must not be blank" }
        require(releaseTag.isNotBlank()) { "releaseTag must not be blank" }
        require(assetName.isNotBlank()) { "assetName must not be blank" }
        require(format.isNotBlank()) { "format must not be blank" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be a lowercase SHA-256 digest" }
        require(kind == LocalModelArtifactKind.Adapter || adapterId == null) {
            "adapterId is only valid for adapter artifacts"
        }
        if (kind == LocalModelArtifactKind.Adapter) {
            require(!adapterId.isNullOrBlank()) { "adapter artifacts require a versioned adapterId" }
        }
    }

    val downloadUrl: String
        get() = "https://github.com/$releaseRepository/releases/download/$releaseTag/$assetName"

    fun toInferenceModelDescriptor(): InferenceModelDescriptor = InferenceModelDescriptor(
        logicalModelId = logicalArtifactId,
        baseModelId = foundationModelId,
        adapterId = adapterId,
        precision = precision,
        backend = format,
        capabilities = buildSet {
            add("local-model")
            add("artifact:${kind.name.lowercase()}")
            add("release:$releaseTag")
            addAll(capabilities)
        },
        releaseDigest = sha256,
    )
}

/** One specialist role with every locally published execution form that can satisfy it. */
data class LocalModelSpecialistDescriptor(
    val specialistId: String,
    val sharedBaseVariants: List<LocalModelArtifactDescriptor> = emptyList(),
    val mergedVariants: List<LocalModelArtifactDescriptor> = emptyList(),
    val adapter: LocalModelArtifactDescriptor? = null,
    val standalone: LocalModelArtifactDescriptor? = null,
) {
    init {
        require(specialistId.isNotBlank()) { "specialistId must not be blank" }
        require(mergedVariants.isNotEmpty() || adapter != null || standalone != null) {
            "specialist $specialistId has no executable artifact"
        }
        require(sharedBaseVariants.all { it.kind == LocalModelArtifactKind.SharedBase }) {
            "sharedBaseVariants must contain only shared-base artifacts"
        }
        require(mergedVariants.all { it.kind == LocalModelArtifactKind.MergedModel }) {
            "mergedVariants must contain only merged-model artifacts"
        }
        require(adapter == null || adapter.kind == LocalModelArtifactKind.Adapter) {
            "adapter must be an adapter artifact"
        }
        require(standalone == null || standalone.kind == LocalModelArtifactKind.Standalone) {
            "standalone must be a standalone artifact"
        }
        if (adapter != null) {
            require(sharedBaseVariants.isNotEmpty()) {
                "adapter specialist $specialistId requires at least one compatible shared base"
            }
            require(sharedBaseVariants.all { it.foundationModelId == adapter.foundationModelId }) {
                "adapter and shared bases must name the same foundation model"
            }
        }
    }
}

/** Capabilities reported by a concrete local inference runtime. */
data class LocalModelRuntimeCapabilities(
    val runtimeId: String,
    val supportedFormats: Set<String> = emptySet(),
    val supportedPrecisions: Set<String> = emptySet(),
    val supportsSharedBaseAdapters: Boolean = false,
) {
    init {
        require(runtimeId.isNotBlank()) { "runtimeId must not be blank" }
    }

    fun supports(artifact: LocalModelArtifactDescriptor): Boolean =
        (supportedFormats.isEmpty() || artifact.format in supportedFormats) &&
            (artifact.precision == null || supportedPrecisions.isEmpty() || artifact.precision in supportedPrecisions)
}

/** Concrete load shape selected for a specialist on a particular runtime. */
sealed interface LocalModelLoadPlan {
    val specialistId: String

    data class SharedBaseAdapter(
        override val specialistId: String,
        val base: LocalModelArtifactDescriptor,
        val adapter: LocalModelArtifactDescriptor,
    ) : LocalModelLoadPlan

    data class MergedModel(
        override val specialistId: String,
        val model: LocalModelArtifactDescriptor,
    ) : LocalModelLoadPlan

    data class Standalone(
        override val specialistId: String,
        val model: LocalModelArtifactDescriptor,
    ) : LocalModelLoadPlan
}

/**
 * Provider-neutral catalog and load planner for local specialist models.
 *
 * Adapter execution is opt-in: a runtime must explicitly advertise safe shared-base adapter
 * switching. Otherwise planning falls back to a cryptographically identified merged artifact.
 */
class LocalModelLibrary(
    specialists: Collection<LocalModelSpecialistDescriptor>,
) {
    private val specialistsById = specialists.associateBy(LocalModelSpecialistDescriptor::specialistId)

    init {
        require(specialistsById.size == specialists.size) { "specialist IDs must be unique" }
        val artifactIds = allArtifacts().map(LocalModelArtifactDescriptor::logicalArtifactId)
        require(artifactIds.distinct().size == artifactIds.size) { "local model artifact IDs must be unique" }
    }

    fun specialist(specialistId: String): LocalModelSpecialistDescriptor =
        specialistsById[specialistId] ?: error("Unknown local model specialist $specialistId")

    fun allSpecialists(): List<LocalModelSpecialistDescriptor> = specialistsById.values.toList()

    fun allArtifacts(): List<LocalModelArtifactDescriptor> = buildList {
        specialistsById.values.forEach { specialist ->
            addAll(specialist.sharedBaseVariants)
            addAll(specialist.mergedVariants)
            specialist.adapter?.let(::add)
            specialist.standalone?.let(::add)
        }
    }.distinctBy(LocalModelArtifactDescriptor::logicalArtifactId)

    suspend fun registerInto(registry: InferenceModelRegistry) {
        allArtifacts().forEach { artifact -> registry.register(artifact.toInferenceModelDescriptor()) }
    }

    fun plan(
        specialistId: String,
        runtime: LocalModelRuntimeCapabilities,
        preferredPrecisions: List<String> = listOf("int8", "fp16"),
    ): LocalModelLoadPlan {
        val specialist = specialist(specialistId)

        if (runtime.supportsSharedBaseAdapters) {
            val adapter = specialist.adapter?.takeIf(runtime::supports)
            if (adapter != null) {
                val base = chooseVariant(specialist.sharedBaseVariants, runtime, preferredPrecisions)
                if (base != null) {
                    return LocalModelLoadPlan.SharedBaseAdapter(specialistId, base, adapter)
                }
            }
        }

        chooseVariant(specialist.mergedVariants, runtime, preferredPrecisions)?.let { merged ->
            return LocalModelLoadPlan.MergedModel(specialistId, merged)
        }

        specialist.standalone?.takeIf(runtime::supports)?.let { standalone ->
            return LocalModelLoadPlan.Standalone(specialistId, standalone)
        }

        error("No compatible local model artifact for $specialistId on runtime ${runtime.runtimeId}")
    }

    private fun chooseVariant(
        variants: List<LocalModelArtifactDescriptor>,
        runtime: LocalModelRuntimeCapabilities,
        preferredPrecisions: List<String>,
    ): LocalModelArtifactDescriptor? {
        val compatible = variants.filter(runtime::supports)
        for (precision in preferredPrecisions) {
            compatible.firstOrNull { it.precision == precision }?.let { return it }
        }
        return compatible.firstOrNull()
    }
}
