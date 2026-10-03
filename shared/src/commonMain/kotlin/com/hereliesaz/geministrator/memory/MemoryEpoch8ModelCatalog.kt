package com.hereliesaz.geministrator.memory

/**
 * Deployable memory-model release currently published by Aive.
 *
 * Release bundles are archives; ONNX Runtime never consumes these tarballs directly. Installers
 * download + verify a bundle, extract its ONNX payload, and bind [runtimeArtifactId] to the local
 * extracted file through the platform artifact resolver. Keeping a logical artifact id here avoids
 * coupling runtime code to archive layout.
 *
 * With [adapter], the archive is a shared base ([modelArtifactId]) that every adapter clerk of its
 * family runs on: it is installed once, and [runtimeArtifactId] is the adapter's, so each role still
 * resolves to its own weights.
 */
data class MemoryModelReleaseBundle(
    val role: MemoryMicroAgentRole,
    val releaseTag: String,
    val releaseAssetName: String,
    val releaseAssetSha256: String,
    val runtimeArtifactId: String,
    val quantization: String,
    val modelArtifactId: String = runtimeArtifactId,
    val adapter: MemoryAdapterReleaseAsset? = null,
) {
    val downloadUrl: String
        get() = "https://github.com/HereLiesAz/aive/releases/download/$releaseTag/$releaseAssetName"
}

/** A clerk's LoRA weights: one verified `aive-lora-inputs` safetensors file for its shared base. */
data class MemoryAdapterReleaseAsset(
    val logicalArtifactId: String,
    val releaseTag: String,
    val assetName: String,
    val sha256: String,
) {
    val downloadUrl: String
        get() = "https://github.com/HereLiesAz/aive/releases/download/$releaseTag/$assetName"
}

/**
 * The memory models the runtime installs and runs: the epoch-8 embedding model for the Association
 * Linker, and the clerks released in [MemoryClerkCatalog] for the eight generative stages.
 *
 * The epoch-8 generative releases are deliberately absent. Their INT8 models do not load in ONNX
 * Runtime, and every variant answers in the epoch-8 training schema instead of the sections/nodes/links
 * contract [StructuredMemoryMicroAgent] validates (`tools/memory_models`). [MemoryEpoch8LocalModelLibrary]
 * still describes them as release metadata.
 */
object MemoryEpoch8ModelCatalog {
    const val RELEASE_TAG: String = MemoryEpoch8LocalModelLibrary.RELEASE_TAG

    /** Every installable bundle; a generative stage appears only once its clerk is released. */
    val all: List<MemoryModelReleaseBundle>
        get() = MemoryMicroAgentRole.entries.mapNotNull(::bundleFor)

    fun bundleFor(role: MemoryMicroAgentRole): MemoryModelReleaseBundle? {
        val release = if (role == MemoryMicroAgentRole.AssociationLinker) {
            MemoryClerkRelease(MemoryEpoch8LocalModelLibrary.productionArtifactFor(role))
        } else {
            MemoryClerkCatalog.released(role) ?: return null
        }
        val model = release.model
        return MemoryModelReleaseBundle(
            role = role,
            releaseTag = model.releaseTag,
            releaseAssetName = model.assetName,
            releaseAssetSha256 = model.sha256,
            runtimeArtifactId = release.adapter?.logicalArtifactId ?: model.logicalArtifactId,
            quantization = model.precision ?: "int8",
            modelArtifactId = model.logicalArtifactId,
            adapter = release.adapter?.let {
                MemoryAdapterReleaseAsset(it.logicalArtifactId, it.releaseTag, it.assetName, it.sha256)
            },
        )
    }

    /**
     * Runtime spec for [role]'s local model. The installer/resolver maps the logical artifact id to
     * the extracted ONNX file; archive hashes stay on [MemoryModelReleaseBundle] because they verify
     * the download, not the extracted model payload. An unreleased role still gets a spec (with a
     * placeholder artifact) so training data is fitted to the same limits.
     */
    fun modelSpec(role: MemoryMicroAgentRole): MemoryMicroAgentModelSpec {
        val bundle = bundleFor(role)
        val requirements = if (role == MemoryMicroAgentRole.AssociationLinker) {
            MemoryModelRequirements.embeddings()
        } else {
            MemoryModelRequirements.generation()
        }
        val quantization = bundle?.quantization ?: "int8"
        return MemoryMicroAgentModelSpec(
            // Adapter clerks share one model id, so the runtimes open their shared base once.
            modelId = if (bundle?.adapter != null) bundle.modelArtifactId else "${bundle?.releaseTag ?: "unreleased"}/${role.name}",
            quantization = quantization,
            // Small packets keep prompt + answer within ~3k tokens, the length the local clerks are
            // trained at (tools/memory_training); the router fits packets to these limits.
            maxInputItems = LOCAL_MAX_INPUT_ITEMS,
            maxInputChars = LOCAL_MAX_INPUT_CHARS,
            maxOutputChars = LOCAL_MAX_OUTPUT_CHARS,
            maxMutations = LOCAL_MAX_MUTATIONS,
            requirements = requirements,
            deployment = MemoryMicroAgentDeploymentManifest.portableOnnx(
                artifactId = bundle?.runtimeArtifactId ?: "${MemoryClerkCatalog.specialistId(role)}:unreleased",
                quantization = quantization,
            ),
        )
    }

    const val LOCAL_MAX_INPUT_ITEMS: Int = 8
    const val LOCAL_MAX_INPUT_CHARS: Int = 6_000
    const val LOCAL_MAX_OUTPUT_CHARS: Int = 4_000
    const val LOCAL_MAX_MUTATIONS: Int = 48
}
