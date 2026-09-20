package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import com.hereliesaz.geministrator.inference.LocalModelSpecialistDescriptor
import com.hereliesaz.geministrator.memory.MemoryEpoch8LocalModelLibrary
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelBackedLocalOrchestrationUtilitiesTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun validModelMemoryPlanIsAccepted() {
        val expected = MemoryQueryPlan(
            queries = listOf(
                MemoryQuerySpec("Widget", MemoryResolution.Entity, "MODEL_ENTITY"),
            ),
            enoughEvidence = false,
        )
        val family = GuardedModelBackedOrchestrationUtilities(
            runtime = LocalOrchestrationSpecialistRuntime { role, _ ->
                if (role == OrchestrationUtilityRole.MemoryQueryComposer) json.encodeToString(expected) else null
            },
        )

        assertEquals(
            expected,
            family.composeMemoryQueries(
                MemoryQueryInput(
                    objective = "Find Widget history",
                    knownEntities = listOf("Widget"),
                ),
            ),
        )
    }

    @Test
    fun completionModelCannotEraseEvidenceAndDeclareComplete() {
        val input = CompletionInput(
            objective = "Ship",
            criteria = listOf(
                CriterionEvidence(
                    criterion = "Tests pass",
                    evidenceIds = listOf("test-result"),
                    status = EvidenceStatus.Passed,
                ),
            ),
            taskTerminal = true,
        )
        val unsafe = CompletionResult(
            decision = CompletionDecision.Complete,
            unsatisfiedCriteria = emptyList(),
            evidenceIds = emptyList(),
        )
        val family = GuardedModelBackedOrchestrationUtilities(
            runtime = LocalOrchestrationSpecialistRuntime { role, _ ->
                if (role == OrchestrationUtilityRole.CompletionGate) json.encodeToString(unsafe) else null
            },
        )

        assertEquals(DeterministicLocalOrchestrationUtilities.evaluateCompletion(input), family.evaluateCompletion(input))
    }

    @Test
    fun malformedSpecialistOutputFallsBackPerDecision() {
        val input = CapabilityAssessment(requiredToolAvailable = false)
        val family = GuardedModelBackedOrchestrationUtilities(
            runtime = LocalOrchestrationSpecialistRuntime { _, _ -> "not-json" },
        )

        assertEquals(
            DeterministicLocalOrchestrationUtilities.evaluateEscalation(input),
            family.evaluateEscalation(input),
        )
    }

    @Test
    fun orchestrationCatalogComposesWithExistingMemorySpecialists() {
        val specialistId = OrchestrationSpecialistIds.specialistId(
            OrchestrationUtilityRole.MemoryQueryComposer,
        )
        val artifact = LocalModelArtifactDescriptor(
            logicalArtifactId = "orchestration-memory-query-composer:int8",
            foundationModelId = "Qwen/Qwen2.5-0.5B-Instruct",
            releaseRepository = "HereLiesAz/aive",
            releaseTag = "orchestration-specialists-v1",
            assetName = "memory-query-composer-int8.onnx",
            sha256 = "2".repeat(64),
            format = "onnx",
            precision = "int8",
            kind = LocalModelArtifactKind.MergedModel,
            capabilities = setOf("orchestration-utility"),
        )
        val orchestration = LocalModelLibrary(
            listOf(
                LocalModelSpecialistDescriptor(
                    specialistId = specialistId,
                    mergedVariants = listOf(artifact),
                ),
            ),
        )

        val combined = MemoryEpoch8LocalModelLibrary.library.combinedWith(orchestration)

        assertEquals(
            MemoryEpoch8LocalModelLibrary.library.allSpecialists().size + 1,
            combined.allSpecialists().size,
        )
        assertEquals(specialistId, combined.specialist(specialistId).specialistId)
    }

    @Test
    fun everyOrchestrationUtilityHasStableUniqueSpecialistIdentity() {
        val ids = OrchestrationUtilityRole.entries.map(OrchestrationSpecialistIds::specialistId)

        assertEquals(OrchestrationUtilityRole.entries.size, ids.distinct().size)
        assertEquals(
            setOf(
                "orchestration:memory-query-composer",
                "orchestration:context-packer",
                "orchestration:agent-router",
                "orchestration:tool-router",
                "orchestration:handoff-composer",
                "orchestration:escalation-gate",
                "orchestration:completion-gate",
                "orchestration:execution-state-summarizer",
                "orchestration:verification-planner",
            ),
            ids.toSet(),
        )
    }

    @Test
    fun catalogRuntimePlansImmutableReleasedArtifactBeforeExecution() {
        val specialistId = OrchestrationSpecialistIds.specialistId(OrchestrationUtilityRole.AgentRouter)
        val artifact = LocalModelArtifactDescriptor(
            logicalArtifactId = "orchestration-agent-router:int8",
            foundationModelId = "Qwen/Qwen2.5-0.5B-Instruct",
            releaseRepository = "HereLiesAz/aive",
            releaseTag = "orchestration-specialists-v1",
            assetName = "agent-router-int8.tar.gz",
            sha256 = "0".repeat(64),
            format = "onnx",
            precision = "int8",
            kind = LocalModelArtifactKind.MergedModel,
            capabilities = setOf("orchestration-utility"),
        )
        val library = LocalModelLibrary(
            listOf(
                LocalModelSpecialistDescriptor(
                    specialistId = specialistId,
                    mergedVariants = listOf(artifact),
                ),
            ),
        )
        var observedPlan: LocalModelLoadPlan? = null
        val runtime = CatalogBackedLocalOrchestrationSpecialistRuntime(
            library = library,
            runtimeCapabilities = LocalModelRuntimeCapabilities(
                runtimeId = "test",
                supportedFormats = setOf("onnx"),
                supportedPrecisions = setOf("int8"),
            ),
            executor = LocalOrchestrationModelExecutor { role, plan, input ->
                assertEquals(OrchestrationUtilityRole.AgentRouter, role)
                assertEquals("{}", input)
                observedPlan = plan
                "{\"ok\":true}"
            },
        )

        assertEquals("{\"ok\":true}", runtime.infer(OrchestrationUtilityRole.AgentRouter, "{}"))
        assertIs<LocalModelLoadPlan.MergedModel>(assertNotNull(observedPlan))
    }

    @Test
    fun missingCatalogSpecialistFallsBackAsUnavailable() {
        val runtime = CatalogBackedLocalOrchestrationSpecialistRuntime(
            library = LocalModelLibrary(
                listOf(
                    LocalModelSpecialistDescriptor(
                        specialistId = "orchestration:other",
                        standalone = LocalModelArtifactDescriptor(
                            logicalArtifactId = "other",
                            foundationModelId = "foundation",
                            releaseRepository = "HereLiesAz/aive",
                            releaseTag = "test",
                            assetName = "other.onnx",
                            sha256 = "1".repeat(64),
                            format = "onnx",
                            kind = LocalModelArtifactKind.Standalone,
                        ),
                    ),
                ),
            ),
            runtimeCapabilities = LocalModelRuntimeCapabilities(runtimeId = "test"),
            executor = LocalOrchestrationModelExecutor { _, _, _ -> error("must not run") },
        )

        assertNull(runtime.infer(OrchestrationUtilityRole.MemoryQueryComposer, "{}"))
    }
}
