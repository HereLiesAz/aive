package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.memory.MemoryEpoch8LocalModelLibrary
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LocalModelLibraryTest {
    @Test
    fun epoch8CatalogPublishesEveryRoleAndVerifiedVariant() {
        val catalog = MemoryEpoch8LocalModelLibrary
        val artifacts = catalog.library.allArtifacts()

        assertEquals(MemoryMicroAgentRole.entries.size, catalog.allSpecialists.size)
        assertEquals(
            MemoryMicroAgentRole.entries.map(catalog::specialistId).toSet(),
            catalog.allSpecialists.map { it.specialistId }.toSet(),
        )
        assertTrue(artifacts.isNotEmpty())
        assertTrue(artifacts.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(artifacts.size, artifacts.map { it.logicalArtifactId }.distinct().size)
    }

    @Test
    fun adapterCapableRuntimeUsesOneSharedBaseAndVersionedAdapter() {
        val plan = MemoryEpoch8LocalModelLibrary.library.plan(
            specialistId = MemoryEpoch8LocalModelLibrary.specialistId(MemoryMicroAgentRole.Sectioner),
            runtime = LocalModelRuntimeCapabilities(
                runtimeId = "adapter-runtime",
                supportedFormats = setOf("onnx", "peft"),
                supportedPrecisions = setOf("fp16"),
                supportsSharedBaseAdapters = true,
            ),
            preferredPrecisions = listOf("fp16"),
        )

        val adapterPlan = assertIs<LocalModelLoadPlan.SharedBaseAdapter>(plan)
        assertEquals("epoch8:shared-base:fp16", adapterPlan.base.logicalArtifactId)
        assertEquals("epoch8:01-sectioner:lora", adapterPlan.adapter.logicalArtifactId)
        assertTrue(adapterPlan.adapter.adapterId!!.startsWith("memory-layer-epoch8/01_sectioner/lora:"))
    }

    @Test
    fun currentOnnxRuntimeFallsBackToMergedInt8Specialist() {
        val plan = MemoryEpoch8LocalModelLibrary.library.plan(
            specialistId = MemoryEpoch8LocalModelLibrary.specialistId(MemoryMicroAgentRole.NounTagger),
            runtime = LocalModelRuntimeCapabilities(
                runtimeId = "android-onnx",
                supportedFormats = setOf("onnx"),
                supportedPrecisions = setOf("int8"),
                supportsSharedBaseAdapters = false,
            ),
        )

        val merged = assertIs<LocalModelLoadPlan.MergedModel>(plan)
        assertEquals("epoch8:03-noun-indexer:int8", merged.model.logicalArtifactId)
        assertEquals(
            "haive-specialist_03_noun_indexer-int8-epoch8.tar.gz",
            merged.model.assetName,
        )
    }

    @Test
    fun associationEmbeddingUsesStandaloneArtifact() {
        val plan = MemoryEpoch8LocalModelLibrary.library.plan(
            specialistId = MemoryEpoch8LocalModelLibrary.specialistId(MemoryMicroAgentRole.AssociationLinker),
            runtime = LocalModelRuntimeCapabilities(
                runtimeId = "android-onnx",
                supportedFormats = setOf("onnx"),
                supportedPrecisions = setOf("int8"),
            ),
        )

        val standalone = assertIs<LocalModelLoadPlan.Standalone>(plan)
        assertEquals("epoch8:08-association-linker:int8", standalone.model.logicalArtifactId)
    }

    @Test
    fun bootstrapRegistersLibraryIntoInferenceModelRegistry() = runBlocking {
        val delegate = BlueprintCompoundInferenceFabric()
        val bootstrapped = delegate.withLocalModelLibrary(MemoryEpoch8LocalModelLibrary.library)

        val models = bootstrapped.modelRegistry.all()
        val noun = bootstrapped.modelRegistry.get("epoch8:03-noun-indexer:int8")

        assertEquals(MemoryEpoch8LocalModelLibrary.library.allArtifacts().size, models.size)
        assertNotNull(noun)
        assertEquals("Qwen/Qwen2.5-0.5B-Instruct", noun.baseModelId)
        assertEquals("int8", noun.precision)
        assertEquals("onnx", noun.backend)
        assertTrue("local-model" in noun.capabilities)
    }
    @Test
    fun bitcosArtifactRequiresExplicitRuntimeEncodingSupport() {
        val bitcos = LocalModelArtifactDescriptor(
            logicalArtifactId = "test:ternary:bitcos",
            foundationModelId = "test/ternary",
            releaseRepository = "HereLiesAz/haive",
            releaseTag = "test",
            assetName = "test.bitcos",
            sha256 = "0".repeat(64),
            format = "bitnet",
            precision = TERNARY_PRECISION,
            weightEncoding = BITCOS_WEIGHT_ENCODING,
            kind = LocalModelArtifactKind.MergedModel,
        )
        val fallback = LocalModelArtifactDescriptor(
            logicalArtifactId = "test:int8:onnx",
            foundationModelId = "test/ternary",
            releaseRepository = "HereLiesAz/haive",
            releaseTag = "test",
            assetName = "test.onnx",
            sha256 = "1".repeat(64),
            format = "onnx",
            precision = "int8",
            kind = LocalModelArtifactKind.MergedModel,
        )
        val library = LocalModelLibrary(
            listOf(
                LocalModelSpecialistDescriptor(
                    specialistId = "test-specialist",
                    mergedVariants = listOf(bitcos, fallback),
                ),
            ),
        )

        val unproven = library.plan(
            specialistId = "test-specialist",
            runtime = LocalModelRuntimeCapabilities(
                runtimeId = "runtime-without-bitcos",
                supportedFormats = setOf("bitnet", "onnx"),
                supportedPrecisions = setOf(TERNARY_PRECISION, "int8"),
            ),
            preferredPrecisions = listOf(TERNARY_PRECISION, "int8"),
        )
        assertEquals(
            "test:int8:onnx",
            assertIs<LocalModelLoadPlan.MergedModel>(unproven).model.logicalArtifactId,
        )

        val proven = library.plan(
            specialistId = "test-specialist",
            runtime = LocalModelRuntimeCapabilities(
                runtimeId = "runtime-with-bitcos",
                supportedFormats = setOf("bitnet", "onnx"),
                supportedPrecisions = setOf(TERNARY_PRECISION, "int8"),
                supportedWeightEncodings = setOf(BITCOS_WEIGHT_ENCODING),
            ),
            preferredPrecisions = listOf(TERNARY_PRECISION, "int8"),
        )
        val selected = assertIs<LocalModelLoadPlan.MergedModel>(proven).model
        assertEquals("test:ternary:bitcos", selected.logicalArtifactId)
        assertEquals(BITCOS_WEIGHT_ENCODING, selected.weightEncoding)
        assertTrue(
            "weight-encoding:$BITCOS_WEIGHT_ENCODING" in
                selected.toInferenceModelDescriptor().capabilities,
        )
    }

}
