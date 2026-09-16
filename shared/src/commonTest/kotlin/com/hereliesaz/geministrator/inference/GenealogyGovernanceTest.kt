package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenealogyGovernanceTest {
    @Test
    fun sharedEvidencePreventsCandidatesFromCountingAsIndependentConsensus(): Unit = runBlocking {
        val graph = InMemoryInferenceGenealogyGraph()
        graph.register(
            InferenceGenealogyNode(
                invocationId = "candidate-a",
                upstreamArtifactIds = setOf(ArtifactId("same-source")),
            ),
        )
        graph.register(
            InferenceGenealogyNode(
                invocationId = "candidate-b",
                upstreamArtifactIds = setOf(ArtifactId("same-source")),
            ),
        )
        val evaluator = DefaultGenealogyGovernanceEvaluator(graph)

        val report = evaluator.evaluate(
            GenealogyGovernanceRequest(
                invocationIds = setOf("candidate-a", "candidate-b"),
                consensusGroups = listOf(
                    GenealogyConsensusGroup(
                        groupId = "agreement",
                        invocationIds = setOf("candidate-a", "candidate-b"),
                    ),
                ),
            ),
        )

        assertFalse(report.structurallyClear)
        assertFalse(report.pairwiseIndependence.single().independent)
        assertTrue(report.findings.any { it.kind == GenealogyGovernanceFindingKind.CommonAncestry })
        assertTrue(report.findings.any { it.kind == GenealogyGovernanceFindingKind.UnsupportedConsensus })
        assertTrue(report.findings.any { it.kind == GenealogyGovernanceFindingKind.InsufficientIndependence })
    }

    @Test
    fun independentEvidenceCanSatisfyConsensusIndependenceThreshold(): Unit = runBlocking {
        val graph = InMemoryInferenceGenealogyGraph()
        graph.register(
            InferenceGenealogyNode(
                invocationId = "candidate-a",
                upstreamArtifactIds = setOf(ArtifactId("source-a")),
            ),
        )
        graph.register(
            InferenceGenealogyNode(
                invocationId = "candidate-b",
                upstreamArtifactIds = setOf(ArtifactId("source-b")),
            ),
        )
        val evaluator = DefaultGenealogyGovernanceEvaluator(graph)

        val report = evaluator.evaluate(
            GenealogyGovernanceRequest(
                invocationIds = setOf("candidate-a", "candidate-b"),
                consensusGroups = listOf(
                    GenealogyConsensusGroup(
                        groupId = "agreement",
                        invocationIds = setOf("candidate-a", "candidate-b"),
                    ),
                ),
            ),
        )

        assertTrue(report.structurallyClear)
        assertTrue(report.pairwiseIndependence.single().independent)
    }

    @Test
    fun transitiveSharedAncestorIsDetectedEvenWhenCandidatesHaveNoDirectSharedEvidence(): Unit = runBlocking {
        val graph = InMemoryInferenceGenealogyGraph()
        graph.register(
            InferenceGenealogyNode(
                invocationId = "root",
                upstreamArtifactIds = setOf(ArtifactId("root-source")),
            ),
        )
        graph.register(
            InferenceGenealogyNode(
                invocationId = "candidate-a",
                upstreamInvocationIds = setOf("root"),
            ),
        )
        graph.register(
            InferenceGenealogyNode(
                invocationId = "candidate-b",
                upstreamInvocationIds = setOf("root"),
            ),
        )
        val evaluator = DefaultGenealogyGovernanceEvaluator(graph)

        val report = evaluator.evaluate(
            GenealogyGovernanceRequest(
                invocationIds = setOf("candidate-a", "candidate-b"),
            ),
        )

        val independence = report.pairwiseIndependence.single()
        assertFalse(independence.independent)
        assertEquals(setOf("root"), independence.sharedAncestorInvocationIds)
        assertTrue(
            GenealogyEvidenceKey(GenealogyEvidenceKind.Artifact, "root-source") in independence.sharedEvidence,
        )
    }

    @Test
    fun circularDerivationIsFlaggedWithoutChoosingAWinningClaim(): Unit = runBlocking {
        val graph = InMemoryInferenceGenealogyGraph()
        graph.register(
            InferenceGenealogyNode(
                invocationId = "a",
                upstreamInvocationIds = setOf("b"),
            ),
        )
        graph.register(
            InferenceGenealogyNode(
                invocationId = "b",
                upstreamInvocationIds = setOf("a"),
            ),
        )
        val evaluator = DefaultGenealogyGovernanceEvaluator(graph)

        val report = evaluator.evaluate(
            GenealogyGovernanceRequest(
                invocationIds = setOf("a", "b"),
            ),
        )

        assertTrue(report.findings.any { finding ->
            finding.kind == GenealogyGovernanceFindingKind.CircularDerivation &&
                finding.invocationIds.containsAll(setOf("a", "b"))
        })
    }

    @Test
    fun strictPolicyFlagsInvocationWithNoRegisteredEvidence(): Unit = runBlocking {
        val graph = InMemoryInferenceGenealogyGraph()
        graph.register(InferenceGenealogyNode(invocationId = "unsupported"))
        val evaluator = DefaultGenealogyGovernanceEvaluator(graph)

        val report = evaluator.evaluate(
            GenealogyGovernanceRequest(
                invocationIds = setOf("unsupported"),
                policy = GenealogyGovernancePolicy(requireEvidence = true),
            ),
        )

        assertTrue(report.findings.any { it.kind == GenealogyGovernanceFindingKind.MissingEvidence })
    }

    @Test
    fun providerRegistryRegistersEveryPreparedConcreteInvocationWithGovernance(): Unit = runBlocking {
        val underlyingFabric = BlueprintCompoundInferenceFabric()
        val registry = AgentProviderRegistry(
            providers = emptyList(),
            inferenceFabric = underlyingFabric,
        )
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("governed-task"),
            objective = "Perform governed work",
            roleInstructions = "Stay inside the task contract.",
            acceptanceCriteria = listOf(AcceptanceCriterion("Produces a bounded result")),
        )

        val prepared = registry.inferenceFabric.prepareDispatch(
            request = request,
            providerId = AgentProviderId("provider"),
            capabilities = AgentCapabilities(supported = emptySet()),
        )

        val node = assertNotNull(
            registry.genealogyGovernance.graph.get(prepared.plan.invocationId),
        )
        assertEquals(prepared.plan.invocationId, node.invocationId)
        assertNotNull(registry.genealogyGovernance.latestReport(prepared.plan.invocationId))
    }
}
