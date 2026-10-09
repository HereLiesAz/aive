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
 * [RELEASED] merges the `catalog.json` files written by `tools/orchestration_training/aive_orchestration_specialists.ipynb`
 * and the per-role notebooks in `tools/orchestration_training/notebooks`, for the roles that passed both gates. A role may list a merged multi-task model, a shared base plus its
 * own adapter, or both; [LocalModelLibrary.plan] picks per runtime (shared base + adapter only when the
 * runtime advertises adapter support). Register a new release with
 * `python3 tools/orchestration_training/register_catalog.py <catalog.json>`, which rewrites the constant.
 * An empty catalog is valid: every utility then uses its deterministic baseline.
 *
 * The generative role models (Qwen2.5-0.5B writing each role's JSON) are retired: their labels were the
 * deterministic baseline itself and the runtime guards only accepted answers equal to or stricter than
 * it, so they cost a download and added nothing. What needs judgement is now asked of the small
 * decision model ([DECISIONS_SPECIALIST_ID], [DecisionInformedOrchestrationUtilities]).
 */
object OrchestrationSpecialistCatalog {
    // register_catalog.py:begin
    const val RELEASED: String = """{"specialists":[{"specialistId":"orchestration:decisions","mergedVariants":[{"logicalArtifactId":"orchestration:decisions:int8","foundationModelId":"google/bert_uncased_L-4_H-256_A-4","releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-decisions-v1","assetName":"aive-orchestration-decisions-int8.tar.gz","sha256":"8b6c7014691bb14793faafe465c1f76e5fe099b737b55bbad8e02d81cc058fe3","format":"onnx","precision":"int8","kind":"MergedModel","capabilities":["orchestration-decisions"]}]}]}"""
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

    private val RELEASABLE_IDS =
        OrchestrationUtilityRole.entries.map(OrchestrationSpecialistIds::specialistId).toSet() + DECISIONS_SPECIALIST_ID

    /** The orchestration decision model (`tools/decision_training`), answering every [OrchestrationQuestion]. */
    const val DECISIONS_SPECIALIST_ID: String = "orchestration:decisions"

    /** Whether the released catalog carries the decision model. */
    fun hasDecisionModel(): Boolean = released().hasSpecialist(DECISIONS_SPECIALIST_ID)

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
