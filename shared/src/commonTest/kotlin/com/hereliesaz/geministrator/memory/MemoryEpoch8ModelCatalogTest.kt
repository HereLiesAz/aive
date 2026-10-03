package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryEpoch8ModelCatalogTest {
    @Test
    fun installsOnlyTheEmbeddingLinkerAndReleasedClerks() {
        val released = MemoryClerkCatalog.parse(MemoryClerkCatalog.RELEASED).keys
        val bundles = MemoryEpoch8ModelCatalog.all
        assertEquals(MemoryMicroAgentRole.AssociationLinker, bundles.first().role)
        assertEquals(
            released,
            bundles.filter { it.role != MemoryMicroAgentRole.AssociationLinker }
                .mapTo(hashSetOf()) { MemoryClerkCatalog.specialistId(it.role) },
        )
        assertTrue(bundles.all { it.releaseAssetSha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(
            "haive-specialist_08_association_linker-onnx-epoch8.tar.gz",
            MemoryEpoch8ModelCatalog.bundleFor(MemoryMicroAgentRole.AssociationLinker)?.releaseAssetName,
        )
    }

    @Test
    fun unreleasedClerksHaveNoBundleButStillAModelSpec() {
        MemoryMicroAgentRole.entries.forEach { role ->
            val spec = MemoryEpoch8ModelCatalog.modelSpec(role)
            val expected = if (role == MemoryMicroAgentRole.AssociationLinker) {
                MemoryInferenceWorkload.Embedding
            } else {
                MemoryInferenceWorkload.AutoregressiveGeneration
            }
            assertEquals(expected, spec.requirements.workload)
            assertTrue(spec.deployment.supportsAllHaivePlatforms())
            assertEquals(MemoryEpoch8ModelCatalog.LOCAL_MAX_INPUT_ITEMS, spec.maxInputItems)
        }
    }

    @Test
    fun clerkCatalogReadsMergedInt8Models() {
        val catalog = """{"specialists":[{"specialistId":"memory:salience-filter","mergedVariants":[{"logicalArtifactId":"memory:clerks:v1:int8","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct","releaseRepository":"HereLiesAz/aive","releaseTag":"memory-clerks-v1","assetName":"aive-memory-clerks-int8.tar.gz","sha256":"${"c".repeat(64)}","format":"onnx","precision":"int8","kind":"MergedModel"}]}]}"""
        val artifact = assertNotNull(MemoryClerkCatalog.released(MemoryMicroAgentRole.SalienceFilter, catalog))
        assertEquals("memory-clerks-v1", artifact.releaseTag)
        assertNull(MemoryClerkCatalog.released(MemoryMicroAgentRole.Sectioner, catalog))
    }
}
