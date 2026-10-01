package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.LocalModelSpecialistDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Released local orchestration specialists.
 *
 * [RELEASED] is the `catalog.json` written by `tools/orchestration_training/aive_orchestration_specialists.ipynb`
 * for the roles that passed both gates. A role may list a merged multi-task model, a shared base plus its
 * own adapter, or both; [LocalModelLibrary.plan] picks per runtime (shared base + adapter only when the
 * runtime advertises adapter support). Register a new release with
 * `python3 tools/orchestration_training/register_catalog.py <catalog.json>`, which rewrites the constant.
 * An empty catalog is valid: every utility then uses its deterministic baseline.
 */
object OrchestrationSpecialistCatalog {
    // register_catalog.py:begin
    const val RELEASED: String = """{"specialists":[{"specialistId":"orchestration:agent-router","mergedVariants":[{"logicalArtifactId":"orchestration:utilities:int8","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct","releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-utilities-v1","assetName":"aive-orchestration-utilities-int8.tar.gz","sha256":"b8b47322f685f777f36b514591b985a2d3820644174f7ad09d993f66645c2437","format":"onnx","precision":"int8","kind":"MergedModel","capabilities":["agent-router","handoff-composer","orchestration-utility"]}]},{"specialistId":"orchestration:handoff-composer","mergedVariants":[{"logicalArtifactId":"orchestration:utilities:int8","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct","releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-utilities-v1","assetName":"aive-orchestration-utilities-int8.tar.gz","sha256":"b8b47322f685f777f36b514591b985a2d3820644174f7ad09d993f66645c2437","format":"onnx","precision":"int8","kind":"MergedModel","capabilities":["agent-router","handoff-composer","orchestration-utility"]}]}]}"""
    // register_catalog.py:end

    private val json = Json { ignoreUnknownKeys = true }

    fun released(): LocalModelLibrary = parse(RELEASED)

    fun parse(catalogJson: String): LocalModelLibrary {
        val catalog = json.decodeFromString<CatalogFile>(catalogJson)
        return LocalModelLibrary(
            catalog.specialists.map { specialist ->
                require(specialist.specialistId in RELEASABLE_IDS) {
                    "${specialist.specialistId} is not an orchestration specialist ID"
                }
                LocalModelSpecialistDescriptor(
                    specialistId = specialist.specialistId,
                    mergedVariants = specialist.mergedVariants.map { it.toDescriptor(LocalModelArtifactKind.MergedModel) },
                    sharedBaseVariants = specialist.sharedBaseVariants.map { it.toDescriptor(LocalModelArtifactKind.SharedBase) },
                    adapter = specialist.adapter?.toDescriptor(LocalModelArtifactKind.Adapter),
                )
            },
        )
    }

    private val RELEASABLE_IDS = OrchestrationUtilityRole.entries.map(OrchestrationSpecialistIds::specialistId).toSet()

    @Serializable
    private data class CatalogFile(val specialists: List<CatalogSpecialist> = emptyList())

    @Serializable
    private data class CatalogSpecialist(
        val specialistId: String,
        val mergedVariants: List<CatalogArtifact> = emptyList(),
        val sharedBaseVariants: List<CatalogArtifact> = emptyList(),
        val adapter: CatalogArtifact? = null,
    )

    @Serializable
    private data class CatalogArtifact(
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
        fun toDescriptor(expected: LocalModelArtifactKind): LocalModelArtifactDescriptor {
            require(kind == expected) { "$logicalArtifactId: expected a $expected artifact, got $kind" }
            return LocalModelArtifactDescriptor(
                logicalArtifactId = logicalArtifactId,
                foundationModelId = foundationModelId,
                releaseRepository = releaseRepository,
                releaseTag = releaseTag,
                assetName = assetName,
                sha256 = sha256,
                format = format,
                precision = precision,
                kind = kind,
                adapterId = adapterId,
                capabilities = capabilities,
            )
        }
    }
}
