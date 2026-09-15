package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryEpoch8ModelCatalogTest {
    @Test
    fun coversEveryMemoryRoleExactlyOnce() {
        val bundles = MemoryEpoch8ModelCatalog.all

        assertEquals(MemoryMicroAgentRole.entries.size, bundles.size)
        assertEquals(MemoryMicroAgentRole.entries.toSet(), bundles.mapTo(linkedSetOf(), MemoryModelReleaseBundle::role))
        assertEquals(bundles.size, bundles.map(MemoryModelReleaseBundle::releaseAssetName).distinct().size)
        assertEquals(bundles.size, bundles.map(MemoryModelReleaseBundle::runtimeArtifactId).distinct().size)
        assertTrue(bundles.all { it.releaseTag == MemoryEpoch8ModelCatalog.RELEASE_TAG })
        assertTrue(bundles.all { it.releaseAssetSha256.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun associationSpecialistUsesEmbeddingWorkloadAndOthersGenerate() {
        MemoryMicroAgentRole.entries.forEach { role ->
            val spec = MemoryEpoch8ModelCatalog.modelSpec(role)
            val expected = if (role == MemoryMicroAgentRole.AssociationLinker) {
                MemoryInferenceWorkload.Embedding
            } else {
                MemoryInferenceWorkload.AutoregressiveGeneration
            }
            assertEquals(expected, spec.requirements.workload)
            assertTrue(spec.deployment.supportsAllHaivePlatforms())
        }
    }

    @Test
    fun catalogPointsAtPublishedEpoch8Assets() {
        assertEquals(
            "haive-specialist_03_noun_indexer-int8-epoch8.tar.gz",
            MemoryEpoch8ModelCatalog.nounIndexer.releaseAssetName,
        )
        assertEquals(
            "haive-specialist_04_verb_indexer-int8-epoch8.tar.gz",
            MemoryEpoch8ModelCatalog.verbIndexer.releaseAssetName,
        )
        assertEquals(
            "haive-specialist_08_association_linker-onnx-epoch8.tar.gz",
            MemoryEpoch8ModelCatalog.associationLinker.releaseAssetName,
        )
    }
}
