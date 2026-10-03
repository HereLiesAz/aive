package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Released local memory clerks (the eight generative stages).
 *
 * [RELEASED] merges the `catalog.json` files written by the per-role notebooks in
 * `tools/memory_training/notebooks`: each clerk is a LoRA adapter on the family's shared INT8 base
 * (LoRA weights as graph inputs). An older merged INT8 model per role is still read. Register a release
 * with `python3 tools/memory_training/register_catalog.py <catalog.json>`, which rewrites the constant
 * and keeps the other roles. The clerks are trained on [MemoryMicroAgentPrompts] and the
 * sections/nodes/links contract, so they run through the same [StructuredMemoryMicroAgent] as hosted
 * engines. A role with no release has no local engine: a stage set to it runs programmatically, with
 * the reason shown.
 */
object MemoryClerkCatalog {
    // register_catalog.py:begin
    const val RELEASED: String = """{"specialists":[]}"""
    // register_catalog.py:end

    private val json = Json { ignoreUnknownKeys = true }

    fun specialistId(role: MemoryMicroAgentRole): String =
        "memory:" + role.name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

    /** The released model for [role], or null when none is released. */
    fun released(role: MemoryMicroAgentRole, catalogJson: String = RELEASED): MemoryClerkRelease? =
        parse(catalogJson)[specialistId(role)]

    /** Per specialist ID: its adapter on a shared INT8 base when released that way, else its merged INT8 model. */
    fun parse(catalogJson: String): Map<String, MemoryClerkRelease> {
        val catalog = json.decodeFromString<CatalogFile>(catalogJson)
        val generative = MemoryMicroAgentRole.entries.filter { it != MemoryMicroAgentRole.AssociationLinker }
            .mapTo(hashSetOf(), ::specialistId)
        return catalog.specialists.mapNotNull { specialist ->
            require(specialist.specialistId in generative) { "${specialist.specialistId} is not a generative memory clerk ID" }
            val base = specialist.sharedBaseVariants.firstOrNull { it.format == "onnx" && it.precision == "int8" }
            val release = if (specialist.adapter != null && base != null) {
                MemoryClerkRelease(
                    model = base.toDescriptor(LocalModelArtifactKind.SharedBase),
                    adapter = specialist.adapter.toDescriptor(LocalModelArtifactKind.Adapter),
                )
            } else {
                specialist.mergedVariants
                    .firstOrNull { it.format == "onnx" && it.precision == "int8" }
                    ?.let { MemoryClerkRelease(model = it.toDescriptor(LocalModelArtifactKind.MergedModel)) }
            }
            release?.let { specialist.specialistId to it }
        }.toMap()
    }

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

/**
 * A released clerk: [model] is a merged INT8 model, or, with [adapter], the shared INT8 base whose
 * LoRA graph inputs take that adapter's weights.
 */
data class MemoryClerkRelease(
    val model: LocalModelArtifactDescriptor,
    val adapter: LocalModelArtifactDescriptor? = null,
) {
    init {
        require(adapter == null || model.kind == LocalModelArtifactKind.SharedBase) { "an adapter runs on a shared base" }
        require(adapter == null || adapter.kind == LocalModelArtifactKind.Adapter) { "adapter must be an adapter artifact" }
    }
}
