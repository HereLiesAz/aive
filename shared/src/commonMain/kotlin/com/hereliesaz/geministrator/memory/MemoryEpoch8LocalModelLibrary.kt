package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.LocalModelSpecialistDescriptor

/** Reusable, cryptographically identified local-model catalog for the published Epoch-8 memory family. */
object MemoryEpoch8LocalModelLibrary {
    const val RELEASE_TAG: String = "memory-layer-epoch8"
    private const val RELEASE_REPOSITORY: String = "HereLiesAz/aive"
    private const val FOUNDATION_MODEL_ID: String = "Qwen/Qwen2.5-0.5B-Instruct"

    val sharedBaseFp16 = LocalModelArtifactDescriptor(
        logicalArtifactId = "epoch8:shared-base:fp16",
        foundationModelId = FOUNDATION_MODEL_ID,
        releaseRepository = RELEASE_REPOSITORY,
        releaseTag = RELEASE_TAG,
        assetName = "haive-memory-layer-fp16-epoch8.tar.gz",
        sha256 = "98f548ea4ce3881880efeeaad03100426c2bb938e1c87f69534dfc33fa48e783",
        format = "onnx",
        precision = "fp16",
        kind = LocalModelArtifactKind.SharedBase,
        capabilities = setOf("autoregressive-generation"),
    )

    val sectioner = generativeSpecialist(
        role = MemoryMicroAgentRole.Sectioner,
        slug = "01_sectioner",
        fp16Sha = "9f823d272372f49ece89cbc465269d0c7f70f5beb3940f91878ea7b045c6f770",
        int8Sha = "96fcedd5c872a92298090aceae47869b05154a1a4e439197b7008199fd8e4e20",
        loraSha = "1adcce1036919ff97f17ca6299814e02306d8dbdf261427b2d12b0845c8fda3f",
    )
    val salience = generativeSpecialist(
        role = MemoryMicroAgentRole.SalienceFilter,
        slug = "02_salience",
        fp16Sha = "99513b2d63cc01054e113c3a3f9b7f05db7f3f7f129c6dc13c78510104a48ea4",
        int8Sha = "1edbab26ec052940b6043e593beb5662bab3e0478ff52a74cfd8052e5b052d8e",
        loraSha = "99b4def935ee5c817881534ad73691affa4c316385dcbdc2640d598ced787e34",
    )
    val nounIndexer = generativeSpecialist(
        role = MemoryMicroAgentRole.NounTagger,
        slug = "03_noun_indexer",
        fp16Sha = "bf09a94ff90e1875130b1e6adfa1586a3f1996c9cd14965bba0d0ec4dbb6be3b",
        int8Sha = "4bf6e46a48220979bde2742e686d6cd2ceb4ca91c063b7bda289d1ad752a31c2",
        loraSha = "f98f37a7b5cb0af92dcbf05049476183e0ecfaa45ae3079d7cc8eb99af8c035b",
    )
    val verbIndexer = generativeSpecialist(
        role = MemoryMicroAgentRole.VerbTagger,
        slug = "04_verb_indexer",
        fp16Sha = "60cc9cd3e6c9c0635332a514532ccfb3463864fc140c9f30c490c3d367fdbcd9",
        int8Sha = "2a47b737abe35dca6e2ba381e293be1c310d825d3eb8d012800f9894b550f4d4",
        loraSha = "919f727f0bf8e08e67283b2980d8c461e6a746a77596e301341eed4517bc0691",
    )
    val phraseSynthesizer = generativeSpecialist(
        role = MemoryMicroAgentRole.PhraseSynthesizer,
        slug = "05_phrase_synthesizer",
        fp16Sha = "b635d1dca298634f94a2771d46c99db3b874d4f41ced544e4a487898ece5cb52",
        int8Sha = "3d9d7397c0c92b4369cfdf611c6dcacf8e21d2ff0833fbad8c6e9b9a33e8ccaa",
        loraSha = "e5ebf93d868062223e584399ace3ce437420eeb8f620cc073c9f3976cb6cf2d4",
    )
    val summarySynthesizer = generativeSpecialist(
        role = MemoryMicroAgentRole.SummarySynthesizer,
        slug = "06_summary_synthesizer",
        fp16Sha = "a2e42c5320462b38f719aef430902ff5d55ff0a4393fca48c2fcde757b7e76ca",
        int8Sha = "a07b4adf7095e8078e7566146c68da8e997d11195c69f01f2a6ea426c26fc336",
        loraSha = "ea9c03e29e98aab90c6c6a9011a46c4a3451cf54c9c8cd3091e2f10924522067",
    )
    val categoryClassifier = generativeSpecialist(
        role = MemoryMicroAgentRole.CategoryClassifier,
        slug = "07_category_classifier",
        fp16Sha = "7fa5dbb38e8cbb5b150ad3ff0e09bd8edb5db8868d4ca708a14fa9f5a464068a",
        int8Sha = "73e0066f4062b8625cb80c8e4e919f502d790e39506079a42428f0afe1e3ad61",
        loraSha = "072aa73a9664691f94c17458aea7009896c4d52433029d6ce75d5af239fbb2aa",
    )
    val associationLinker = LocalModelSpecialistDescriptor(
        specialistId = specialistId(MemoryMicroAgentRole.AssociationLinker),
        standalone = LocalModelArtifactDescriptor(
            logicalArtifactId = "epoch8:08-association-linker:int8",
            foundationModelId = "memory-layer-epoch8/association-embedding",
            releaseRepository = RELEASE_REPOSITORY,
            releaseTag = RELEASE_TAG,
            assetName = "haive-specialist_08_association_linker-onnx-epoch8.tar.gz",
            sha256 = "8b348793dcee8201c4519cd846944b460c47aa85cadb5d434bb84bb088e11273",
            format = "onnx",
            precision = "int8",
            kind = LocalModelArtifactKind.Standalone,
            capabilities = setOf("embedding"),
        ),
    )
    val condensationRewriter = generativeSpecialist(
        role = MemoryMicroAgentRole.CondensationRewriter,
        slug = "09_condensation_rewriter",
        fp16Sha = "161387e4cb6a5e7655c0b263208e8fbe469c4e09c5d3e9c079c56f64af0abc7e",
        int8Sha = "fe7c84731be909bbaa20f6297febc31eb78b8a9c9d5c8801d55e606526e8cec9",
        loraSha = "8b273205b3904fbbe717ee1bdd6f3a4dd089773b45e54f78c9f095fa6456ebf7",
    )

    val allSpecialists: List<LocalModelSpecialistDescriptor> = listOf(
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

    val library: LocalModelLibrary = LocalModelLibrary(allSpecialists)

    fun specialistFor(role: MemoryMicroAgentRole): LocalModelSpecialistDescriptor =
        library.specialist(specialistId(role))

    fun productionArtifactFor(role: MemoryMicroAgentRole): LocalModelArtifactDescriptor {
        val specialist = specialistFor(role)
        return specialist.mergedVariants.firstOrNull { it.precision == "int8" }
            ?: specialist.standalone
            ?: error("Epoch-8 specialist ${role.name} has no production artifact")
    }

    fun specialistId(role: MemoryMicroAgentRole): String = "memory-epoch8:${role.name}"

    private fun generativeSpecialist(
        role: MemoryMicroAgentRole,
        slug: String,
        fp16Sha: String,
        int8Sha: String,
        loraSha: String,
    ): LocalModelSpecialistDescriptor {
        val capabilities = setOf("autoregressive-generation", "memory-role:${role.name}")
        return LocalModelSpecialistDescriptor(
            specialistId = specialistId(role),
            sharedBaseVariants = listOf(sharedBaseFp16),
            mergedVariants = listOf(
                LocalModelArtifactDescriptor(
                    logicalArtifactId = "epoch8:${slug.replace('_', '-')}:fp16",
                    foundationModelId = FOUNDATION_MODEL_ID,
                    releaseRepository = RELEASE_REPOSITORY,
                    releaseTag = RELEASE_TAG,
                    assetName = "haive-specialist_$slug-fp16-epoch8.tar.gz",
                    sha256 = fp16Sha,
                    format = "onnx",
                    precision = "fp16",
                    kind = LocalModelArtifactKind.MergedModel,
                    capabilities = capabilities,
                ),
                LocalModelArtifactDescriptor(
                    logicalArtifactId = "epoch8:${slug.replace('_', '-')}:int8",
                    foundationModelId = FOUNDATION_MODEL_ID,
                    releaseRepository = RELEASE_REPOSITORY,
                    releaseTag = RELEASE_TAG,
                    assetName = "haive-specialist_$slug-int8-epoch8.tar.gz",
                    sha256 = int8Sha,
                    format = "onnx",
                    precision = "int8",
                    kind = LocalModelArtifactKind.MergedModel,
                    capabilities = capabilities,
                ),
            ),
            adapter = LocalModelArtifactDescriptor(
                logicalArtifactId = "epoch8:${slug.replace('_', '-')}:lora",
                foundationModelId = FOUNDATION_MODEL_ID,
                releaseRepository = RELEASE_REPOSITORY,
                releaseTag = RELEASE_TAG,
                assetName = "haive-specialist_$slug-lora-epoch8.tar.gz",
                sha256 = loraSha,
                format = "peft",
                precision = "fp16",
                kind = LocalModelArtifactKind.Adapter,
                adapterId = "$RELEASE_TAG/$slug/lora:${loraSha.take(12)}",
                capabilities = capabilities,
            ),
        )
    }
}
