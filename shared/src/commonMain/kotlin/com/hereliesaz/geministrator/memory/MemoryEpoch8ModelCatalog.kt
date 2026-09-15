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

object MemoryEpoch8ModelCatalog {
    const val RELEASE_TAG: String = "memory-layer-epoch8"

    val sectioner = bundle(
        role = MemoryMicroAgentRole.Sectioner,
        slug = "01_sectioner",
        sha256 = "96fcedd5c872a92298090aceae47869b05154a1a4e439197b7008199fd8e4e20",
    )
    val salience = bundle(
        role = MemoryMicroAgentRole.SalienceFilter,
        slug = "02_salience",
        sha256 = "1edbab26ec052940b6043e593beb5662bab3e0478ff52a74cfd8052e5b052d8e",
    )
    val nounIndexer = bundle(
        role = MemoryMicroAgentRole.NounTagger,
        slug = "03_noun_indexer",
        sha256 = "4bf6e46a48220979bde2742e686d6cd2ceb4ca91c063b7bda289d1ad752a31c2",
    )
    val verbIndexer = bundle(
        role = MemoryMicroAgentRole.VerbTagger,
        slug = "04_verb_indexer",
        sha256 = "2a47b737abe35dca6e2ba381e293be1c310d825d3eb8d012800f9894b550f4d4",
    )
    val phraseSynthesizer = bundle(
        role = MemoryMicroAgentRole.PhraseSynthesizer,
        slug = "05_phrase_synthesizer",
        sha256 = "3d9d7397c0c92b4369cfdf611c6dcacf8e21d2ff0833fbad8c6e9b9a33e8ccaa",
    )
    val summarySynthesizer = bundle(
        role = MemoryMicroAgentRole.SummarySynthesizer,
        slug = "06_summary_synthesizer",
        sha256 = "a07b4adf7095e8078e7566146c68da8e997d11195c69f01f2a6ea426c26fc336",
    )
    val categoryClassifier = bundle(
        role = MemoryMicroAgentRole.CategoryClassifier,
        slug = "07_category_classifier",
        sha256 = "73e0066f4062b8625cb80c8e4e919f502d790e39506079a42428f0afe1e3ad61",
    )
    val associationLinker = MemoryModelReleaseBundle(
        role = MemoryMicroAgentRole.AssociationLinker,
        releaseTag = RELEASE_TAG,
        releaseAssetName = "haive-specialist_08_association_linker-onnx-epoch8.tar.gz",
        releaseAssetSha256 = "8b348793dcee8201c4519cd846944b460c47aa85cadb5d434bb84bb088e11273",
        runtimeArtifactId = "epoch8:08-association-linker:int8",
        quantization = "int8",
    )
    val condensationRewriter = bundle(
        role = MemoryMicroAgentRole.CondensationRewriter,
        slug = "09_condensation_rewriter",
        sha256 = "fe7c84731be909bbaa20f6297febc31eb78b8a9c9d5c8801d55e606526e8cec9",
    )

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

    private fun bundle(
        role: MemoryMicroAgentRole,
        slug: String,
        sha256: String,
    ): MemoryModelReleaseBundle = MemoryModelReleaseBundle(
        role = role,
        releaseTag = RELEASE_TAG,
        releaseAssetName = "haive-specialist_$slug-int8-epoch8.tar.gz",
        releaseAssetSha256 = sha256,
        runtimeArtifactId = "epoch8:${slug.replace('_', '-')}:int8",
        quantization = "int8",
    )
}
