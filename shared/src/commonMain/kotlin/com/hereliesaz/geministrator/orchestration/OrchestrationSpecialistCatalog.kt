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
 * for the roles that passed both gates. Register a new release with
 * `python3 tools/orchestration_training/register_catalog.py <catalog.json>`, which rewrites the constant.
 * An empty catalog is valid: every utility then uses its deterministic baseline.
 */
object OrchestrationSpecialistCatalog {
    // register_catalog.py:begin
    const val RELEASED: String = """{"specialists":[]}"""
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
                    mergedVariants = specialist.mergedVariants.map(CatalogArtifact::toDescriptor),
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
        val mergedVariants: List<CatalogArtifact>,
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
        val capabilities: Set<String> = emptySet(),
    ) {
        fun toDescriptor(): LocalModelArtifactDescriptor {
            require(kind == LocalModelArtifactKind.MergedModel) { "$logicalArtifactId: only merged models are released" }
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
                capabilities = capabilities,
            )
        }
    }
}
