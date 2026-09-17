package com.hereliesaz.geministrator.memory

/**
 * Deployable memory-model release currently published by Haive.
 *
 * Release bundles are archives; ONNX Runtime never consumes these tarballs directly. Installers
 * download + verify a bundle, extract its ONNX payload, and bind [runtimeArtifactId] to the local
 * extracted file through the platform artifact resolver. Keeping a logical artifact id here avoids
 * coupling runtime code to archive layout.
 */
data class MemoryModelReleaseBundle(
    val role: MemoryMicroAgentRole,
    val releaseTag: String,
    val releaseAssetName: String,
    val releaseAssetSha256: String,
    val runtimeArtifactId: String,
    val quantization: String,
) {
    val downloadUrl: String
        get() = "https://github.com/HereLiesAz/haive/releases/download/$releaseTag/$releaseAssetName"
}

/**
 * Compatibility view used by the existing memory runtime.
 *
 * The reusable [MemoryEpoch8LocalModelLibrary] is authoritative for release metadata and variant
 * selection. This view deliberately exposes the current production-safe merged INT8 artifacts so
 * existing ONNX installers keep their stable runtime IDs while adapter-capable backends can use the
 * richer library directly.
 */
object MemoryEpoch8ModelCatalog {
    const val RELEASE_TAG: String = MemoryEpoch8LocalModelLibrary.RELEASE_TAG

    val sectioner = bundle(MemoryMicroAgentRole.Sectioner)
    val salience = bundle(MemoryMicroAgentRole.SalienceFilter)
    val nounIndexer = bundle(MemoryMicroAgentRole.NounTagger)
    val verbIndexer = bundle(MemoryMicroAgentRole.VerbTagger)
    val phraseSynthesizer = bundle(MemoryMicroAgentRole.PhraseSynthesizer)
    val summarySynthesizer = bundle(MemoryMicroAgentRole.SummarySynthesizer)
    val categoryClassifier = bundle(MemoryMicroAgentRole.CategoryClassifier)
    val associationLinker = bundle(MemoryMicroAgentRole.AssociationLinker)
    val condensationRewriter = bundle(MemoryMicroAgentRole.CondensationRewriter)

    val all: List<MemoryModelReleaseBundle> = listOf(
        sectioner,
        salience,
        nounIndexer,
        verbIndexer,
        phraseSynthesizer,
        summarySynthesizer,
        categoryClassifier,
        associationLinker,
        condensationRewriter,
    )

    fun bundleFor(role: MemoryMicroAgentRole): MemoryModelReleaseBundle =
        all.single { it.role == role }

    /**
     * Runtime spec for an installed epoch-8 bundle. The installer/resolver maps the logical
     * artifact id to the extracted ONNX file; archive hashes stay on [MemoryModelReleaseBundle]
     * because they verify the download, not the extracted model payload.
     */
    fun modelSpec(role: MemoryMicroAgentRole): MemoryMicroAgentModelSpec {
        val bundle = bundleFor(role)
        val requirements = if (role == MemoryMicroAgentRole.AssociationLinker) {
            MemoryModelRequirements.embeddings()
        } else {
            MemoryModelRequirements.generation()
        }
        return MemoryMicroAgentModelSpec(
            modelId = "${RELEASE_TAG}/${role.name}",
            quantization = bundle.quantization,
            requirements = requirements,
            deployment = MemoryMicroAgentDeploymentManifest.portableOnnx(
                artifactId = bundle.runtimeArtifactId,
                quantization = bundle.quantization,
            ),
        )
    }

    private fun bundle(role: MemoryMicroAgentRole): MemoryModelReleaseBundle {
        val artifact = MemoryEpoch8LocalModelLibrary.productionArtifactFor(role)
        return MemoryModelReleaseBundle(
            role = role,
            releaseTag = artifact.releaseTag,
            releaseAssetName = artifact.assetName,
            releaseAssetSha256 = artifact.sha256,
            runtimeArtifactId = artifact.logicalArtifactId,
            quantization = artifact.precision ?: "int8",
        )
    }
}
