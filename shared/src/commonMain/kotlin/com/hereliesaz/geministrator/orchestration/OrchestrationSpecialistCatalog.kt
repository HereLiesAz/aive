package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.LocalModelSpecialistDescriptor
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

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
 * The app-integration adapter (`tools/app_integration_training`) is a field of its own in the same constant,
 * `appIntegration`, read by [appIntegration]. It is not an orchestration specialist: [parse] still refuses any
 * id in `specialists` that is not an orchestration one, so a catalog that lists `app-integration:assistant`
 * there stays unreadable, and `register_catalog.py` moves it to `appIntegration` instead.
 *
 * The generative role models (Qwen2.5-0.5B writing each role's JSON) are retired: their labels were the
 * deterministic baseline itself and the runtime guards only accepted answers equal to or stricter than
 * it, so they cost a download and added nothing. What needs judgement is now asked of the small
 * decision model ([DECISIONS_SPECIALIST_ID], [DecisionInformedOrchestrationUtilities]).
 *
 * The decision model was never published and is abandoned for now (`orchestration-decisions-v1` does not exist), so [RELEASED]
 * lists nothing and every utility uses its deterministic baseline. The decision code and `tools/decision_training` are kept dormant
 * in case the approach is taken up again; re-register a release with `register_catalog.py` to bring it back.
 */
object OrchestrationSpecialistCatalog {
    // register_catalog.py:begin
    const val RELEASED: String = """{"specialists":[]}"""
    // register_catalog.py:end

    private val json = Json { ignoreUnknownKeys = true }

    fun released(): LocalModelLibrary = parse(RELEASED)

    /** The released app-integration adapter and its shared base, or null when none is released. */
    fun appIntegration(): AppIntegrationRelease? = releasedAppIntegration

    private val releasedAppIntegration: AppIntegrationRelease? by lazy { parseAppIntegration(RELEASED) }

    /**
     * The `appIntegration` field of [catalogJson], or null when it has none. Throws when the field names another
     * id, carries no adapter, or its artifacts are not a shared base plus an adapter.
     */
    fun parseAppIntegration(catalogJson: String): AppIntegrationRelease? {
        val entry = json.decodeFromString<CatalogFile>(catalogJson).appIntegration ?: return null
        require(entry.specialistId == APP_INTEGRATION_SPECIALIST_ID) {
            "${entry.specialistId} is not the app-integration specialist ID"
        }
        return AppIntegrationRelease(
            sharedBaseVariants = entry.sharedBaseVariants.map { it.toDescriptor(LocalModelArtifactKind.SharedBase) },
            adapter = entry.adapter.toDescriptor(LocalModelArtifactKind.Adapter),
            adapterSizeBytes = entry.adapter.sizeBytes,
            gateSummary = summarizeScores(entry.scores),
        )
    }

    /** A catalog holding only [release] (the shape `register_catalog.py` writes), so a parse can be round-tripped. */
    fun encodeAppIntegration(release: AppIntegrationRelease): String = json.encodeToString(
        CatalogFile.serializer(),
        CatalogFile(
            appIntegration = CatalogAppIntegration(
                specialistId = APP_INTEGRATION_SPECIALIST_ID,
                sharedBaseVariants = release.sharedBaseVariants.map { CatalogArtifact.of(it, null) },
                adapter = CatalogArtifact.of(release.adapter, release.adapterSizeBytes),
            ),
        ),
    )

    /** The worst family per gate from the notebook's `scores`, in one line, or null when there are none to read. */
    private fun summarizeScores(scores: JsonElement?): String? {
        val gates = scores as? JsonObject ?: return null
        val parts = listOf("gate1" to "gate 1", "gate2" to "gate 2").mapNotNull { (key, label) ->
            val families = gates[key] as? JsonObject ?: return@mapNotNull null
            val values = families.values.mapNotNull { value ->
                ((value as? JsonObject)?.get("score") ?: value).let { (it as? JsonPrimitive)?.doubleOrNull }
            }
            val worst = values.minOrNull() ?: return@mapNotNull null
            "$label worst family ${(worst * 100).roundToInt() / 100.0}"
        }
        return parts.joinToString(", ").ifEmpty { null }
    }

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
    private data class CatalogFile(
        val specialists: List<CatalogSpecialist> = emptyList(),
        val appIntegration: CatalogAppIntegration? = null,
    )

    @Serializable
    private data class CatalogAppIntegration(
        val specialistId: String,
        val sharedBaseVariants: List<CatalogArtifact> = emptyList(),
        val adapter: CatalogArtifact,
        val scores: JsonElement? = null,
    )

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
        val sizeBytes: Long? = null,
    ) {
        companion object {
            fun of(descriptor: LocalModelArtifactDescriptor, sizeBytes: Long?) = CatalogArtifact(
                logicalArtifactId = descriptor.logicalArtifactId,
                foundationModelId = descriptor.foundationModelId,
                releaseRepository = descriptor.releaseRepository,
                releaseTag = descriptor.releaseTag,
                assetName = descriptor.assetName,
                sha256 = descriptor.sha256,
                format = descriptor.format,
                precision = descriptor.precision,
                kind = descriptor.kind,
                adapterId = descriptor.adapterId,
                capabilities = descriptor.capabilities,
                sizeBytes = sizeBytes,
            )
        }

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
