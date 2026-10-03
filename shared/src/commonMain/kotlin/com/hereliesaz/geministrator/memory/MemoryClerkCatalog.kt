package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Released local memory clerks (the eight generative stages).
 *
 * [RELEASED] is the `catalog.json` written by `tools/memory_training/aive_memory_clerks.ipynb` for
 * the roles whose merged INT8 model passed both gates; register a release with
 * `python3 tools/memory_training/register_catalog.py <catalog.json>`, which rewrites the constant.
 * The clerks are trained on [MemoryMicroAgentPrompts] and the sections/nodes/links contract, so they
 * run through the same [StructuredMemoryMicroAgent] as hosted engines. A role with no release has no
 * local engine: a stage set to it runs programmatically, with the reason shown.
 */
object MemoryClerkCatalog {
    // register_catalog.py:begin
    const val RELEASED: String = """{"specialists":[]}"""
    // register_catalog.py:end

    private val json = Json { ignoreUnknownKeys = true }

    fun specialistId(role: MemoryMicroAgentRole): String =
        "memory:" + role.name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

    /** The released merged INT8 model for [role], or null when none is released. */
    fun released(role: MemoryMicroAgentRole, catalogJson: String = RELEASED): LocalModelArtifactDescriptor? =
        parse(catalogJson)[specialistId(role)]

    fun parse(catalogJson: String): Map<String, LocalModelArtifactDescriptor> {
        val catalog = json.decodeFromString<CatalogFile>(catalogJson)
        val generative = MemoryMicroAgentRole.entries.filter { it != MemoryMicroAgentRole.AssociationLinker }
            .mapTo(hashSetOf(), ::specialistId)
        return catalog.specialists.mapNotNull { specialist ->
            require(specialist.specialistId in generative) { "${specialist.specialistId} is not a generative memory clerk ID" }
            specialist.mergedVariants
                .firstOrNull { it.format == "onnx" && it.precision == "int8" }
                ?.let { specialist.specialistId to it.toDescriptor() }
        }.toMap()
    }

    @Serializable
    private data class CatalogFile(val specialists: List<CatalogSpecialist> = emptyList())

    @Serializable
    private data class CatalogSpecialist(
        val specialistId: String,
        val mergedVariants: List<CatalogArtifact> = emptyList(),
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
            require(kind == LocalModelArtifactKind.MergedModel) { "$logicalArtifactId: expected a merged model, got $kind" }
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
